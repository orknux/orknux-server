/*
 * Orknux on one machine: the server on SQLite, in a window of its own.
 *
 * The same jar orknux-one runs, started by this process instead of by a
 * container, with its data in the user's profile instead of a volume. Nothing
 * here knows what the server does; it starts it, shows it, keeps it running
 * and stops it. See ../README.md for what is still to come.
 */
const { app, BrowserWindow, Menu, Notification, Tray, clipboard, dialog, nativeImage, session, shell } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const { readSettings, prepare } = require('./data');
const { Log } = require('./logs');
const { Server } = require('./server');
const { Tabs } = require('./tabs');
const { classify, linkMenu, shortcut, tabKeys, DEFAULT_TAB_KEYS } = require('./links');
const windowState = require('./window-state');
const look = require('./look');
const manifest = require('../package.json');

/*
 * Whether a release the server installs may run instead of the one shipped.
 * Baked into the package at build time - a store or a distribution updates the
 * app itself, and an app that replaces its own code underneath them is one they
 * refuse - and switched off from outside with ORKNUX_SELF_UPDATE=false as the
 * images are.
 */
const selfUpdate = manifest.orknux?.selfUpdate !== false && process.env.ORKNUX_SELF_UPDATE !== 'false';

/** Set to a directory, the app loads the interface, photographs it there and quits: what the smoke test drives. */
const smoke = process.env.ORKNUX_DESKTOP_SMOKE || null;

// Apart from Chromium's own caches, which also live under userData.
const data = process.env.ORKNUX_DATA || path.join(app.getPath('userData'), 'data');
const resources = app.isPackaged ? process.resourcesPath : path.join(__dirname, '..', 'resources');
const java = path.join(resources, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const imageJar = path.join(resources, 'server', 'app.jar');

/**
 * Every window, in the order they were opened, each with its tabs. The first is
 * the one a start opens and the tray brings back; "Open link in new window"
 * adds the others.
 * @type {Map<BrowserWindow, Tabs>}
 */
const windows = new Map();
/** The windows' title, which every window shows: see server.onNetwork. */
let title = 'Orknux';
/** The next and previous tab keys, as the interface last said them; links.js has the defaults. */
let tabKeysChosen = DEFAULT_TAB_KEYS;
/** The interface's address once the server answers: what a new tab opens. */
let home = null;
let tray = null;
let server = null;
let quitting = false;
let log = null;

// One app, one server, one database: a second start brings the first one forward instead.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => show());
  app.whenReady().then(start).catch((failure) => fatal('Orknux could not start', failure.message));
}

/** The window opened first that is still open, or null. */
function first() {
  return windows.keys().next().value ?? null;
}

/** The window somebody is looking at, which is where a dialog belongs; the first one otherwise. */
function current() {
  const focused = BrowserWindow.getFocusedWindow();
  return focused !== null && windows.has(focused) ? focused : first();
}

/** The tab a page is in, wherever it is. */
function tabOf(contents) {
  for (const tabs of windows.values()) {
    const tab = tabs.of(contents);
    if (tab !== null) return tab;
  }
  return null;
}

function show() {
  const window = first();
  if (window === null) return;
  // Closing the last tab hid the window; it comes back with one.
  if (windows.get(window).list.length === 0) open(windows.get(window), home);
  if (window.isMinimized()) window.restore();
  window.show();
  window.focus();
}

async function start() {
  Menu.setApplicationMenu(null);
  fs.mkdirSync(data, { recursive: true });
  log = new Log(path.join(data, 'logs'));
  log.note(`Orknux desktop ${app.getVersion()} starting; data at ${data}; self-update ${selfUpdate ? 'on' : 'off'}`);

  const settings = readSettings(data);
  const prepared = prepare(data, settings);
  prepared.said.forEach((line) => log.note(line));
  prepared.loud.forEach((line) => log.note(`WARNING ${line}`));

  createWindow(null);
  watchDownloads();
  createTray();

  if (prepared.loud.length > 0) {
    await dialog.showMessageBox(current(), { type: 'warning', title: 'Orknux', message: 'Read this before going on', detail: prepared.loud.join('\n\n') });
  }
  if (!fs.existsSync(java) || !fs.existsSync(imageJar)) {
    fatal('Orknux is incomplete', `The bundled runtime or server is missing:\n${java}\n${imageJar}`);
    return;
  }

  server = new Server({ java, imageJar, selfUpdate, log });
  // What a restart the server asked for starts with: desktop.json read again, in case Network access changed it.
  server.reconfigure = () => {
    const next = readSettings(data);
    const again = prepare(data, next);
    again.said.forEach((line) => log.note(line));
    return { prepared: again, network: next.network };
  };
  server.onNetwork = (network) => {
    title = network === 'exposed' ? 'Orknux — open to the network' : 'Orknux';
    windows.forEach((_tabs, window) => window.isDestroyed() || window.setTitle(title));
  };
  server.onExit = (code) => {
    if (quitting) return;
    unexpected(`The server stopped with exit code ${code}.`);
  };

  let url;
  try {
    url = await server.start({ data, prepared, network: settings.network });
  } catch (failure) {
    if (!quitting) unexpected(failure.message);
    return;
  }
  log.note(`the server answers at ${url}`);
  if (prepared.admin?.first) {
    dialog.showMessageBox(current(), {
      type: 'info',
      title: 'Orknux',
      message: 'Orknux is open to the network, and everybody signs in',
      detail:
        `Sign in with\n\n    username   ${prepared.admin.username}\n    password   ${prepared.admin.password}\n\n` +
        `This was generated for this installation and is kept at ${path.join(data, 'admin-password')}. ` +
        'Change it under the account itself, then delete that file.',
    });
  }
  home = url;
  // The splash's tab becomes the interface's - or a new one, if that was closed while the server started.
  const splash = [...windows.values()].flatMap((tabs) => tabs.list).find((tab) => tab.splash);
  if (splash) {
    splash.splash = false;
    await splash.contents.loadURL(url);
  } else if (first() !== null) {
    open(windows.get(first()), url);
  }
  if (smoke !== null) photograph();
}

/**
 * What every page of the interface runs with, in every tab. The preload is
 * page-preload.js: the tab keys, and nothing else.
 */
const PAGE_PREFERENCES = { contextIsolation: true, nodeIntegration: false, sandbox: true, preload: path.join(__dirname, 'page-preload.js') };

/** A tab on the interface, or on the splash while there is no interface yet, among the tabs given. */
function open(tabs, url, more = {}) {
  if (url !== null) return tabs.open({ url, ...more });
  const tab = tabs.open({ file: path.join(__dirname, 'splash.html'), query: look.remembered(data), ...more });
  tab.splash = true;
  return tab;
}

/**
 * Where links from a tab go, the same on Windows and Linux.
 *
 * The app's own pages stay in the app; anything else is the web, and opens
 * where the web is read - a GitHub issue opening inside the app is a page with
 * no address bar and no way back. What links.js refuses is said rather than
 * dropped: a link that does nothing reads as a broken app.
 *
 * A link the page opens "in a new tab" - an open-definition icon, Add a model,
 * Ctrl+click, the middle button - opens in a tab beside it, and behind it for
 * Ctrl+click and the middle button, as a browser does. Loading it in place
 * threw away whatever the page had not saved, which is exactly why the page
 * asked for a new tab. Shift+click asks for a new window, and gets one of this
 * app's.
 */
function wire(tab, tabs) {
  const contents = tab.contents;
  const port = () => (server === null ? null : server.port);
  // A tab that never showed a page and was sent elsewhere at once would stay empty.
  tab.leaveIfEmpty = () => {
    if (!tab.committed) tabs.close(tab);
  };

  contents.setWindowOpenHandler(({ url, disposition }) => {
    const link = classify(url, port());
    if (link.kind === 'ours' && disposition === 'new-window' && !link.url.startsWith('blob:')) openWindow(link.url);
    else if (link.kind === 'ours') open(tabs, link.url, { after: tab, activate: disposition !== 'background-tab' });
    else follow(link);
    return { action: 'deny' };
  });
  /*
   * Ours at its own address loads in place; ours at localhost is loaded again
   * at 127.0.0.1 (links.js says why). Anything else is not loaded here at all -
   * a file: address included, which is what a file dropped where the page did
   * not take it becomes, and which would replace Orknux with the picture.
   */
  const steer = (event, url) => {
    const link = classify(url, port());
    if (link.kind === 'ours' && !link.moved) return;
    event.preventDefault();
    if (link.kind === 'ours') {
      contents.loadURL(link.url);
    } else {
      follow(link);
      tab.leaveIfEmpty();
    }
  };
  // The event's own url and isMainFrame: the positional arguments after it are deprecated.
  contents.on('will-navigate', (event) => steer(event, event.url));
  // The server redirecting a page of ours elsewhere is a link like any other; a frame inside the page is the page's business.
  contents.on('will-redirect', (event) => {
    if (event.isMainFrame) steer(event, event.url);
  });
  /*
   * A page with unsaved work - the workflow editor, a form - asks before it is
   * left. Electron does not ask on its behalf: it quietly cancels, so a link, a
   * tab's close or the window's stayed where it was with nothing said.
   */
  contents.on('will-prevent-unload', (event) => {
    const choice = dialog.showMessageBoxSync(tabs.win, {
      type: 'question',
      title: 'Orknux',
      message: 'Leave this page?',
      detail: 'Changes you made may not be saved.',
      buttons: ['Leave', 'Stay'],
      defaultId: 1,
      cancelId: 1,
    });
    if (choice === 0) event.preventDefault();
  });
  contents.on('before-input-event', (event, input) => {
    // Recording a shortcut on Preferences: every key is the page's, the tab keys included, or they could never be chosen.
    if (!tab.recording) keys(tabs, event, input);
  });
  contents.on('context-menu', (_event, params) => contextMenu(tabs, tab, params, contents));
  /*
   * What page-preload.js passes on. From a page of ours only: a tab never shows
   * anything else (steer), and this says so rather than assumes it.
   */
  const fromUs = () => classify(contents.getURL(), port()).kind === 'ours';
  contents.ipc.on('desktop:tab-shortcuts', (_event, next, previous) => {
    if (fromUs()) tabKeysChosen = tabKeys(next, previous);
  });
  contents.ipc.on('desktop:recording-keys', (_event, on) => {
    tab.recording = fromUs() && on === true;
  });
  // A page that leaves while recording cannot say it stopped.
  contents.on('did-navigate', () => {
    tab.recording = false;
  });
  // The colours this page is drawn in, for the next start's splash; again on moving within it, which is how leaving Preferences after a Save looks.
  const remember = () => {
    if (!tab.splash) look.capture(contents, data);
  };
  contents.on('did-finish-load', remember);
  contents.on('did-navigate-in-page', remember);
}

/** A link that is not a page of ours: to the operating system, or said why not. */
function follow(link) {
  if (link.kind === 'outside') {
    // On Linux this is xdg-open, and a desktop with no browser or mail program set fails here, not in silence.
    shell.openExternal(link.url).catch((failure) => {
      log?.note(`could not open ${link.url}: ${failure.message}`);
      tell('This link could not be opened', link.url, failure.message);
    });
  } else if (link.kind === 'refused') {
    log?.note(`refused to open ${link.url}`);
    tell(link.scheme ? `Orknux does not open ${link.scheme} links` : 'This is not a link Orknux can open', link.url,
      'Only web, mail and phone links open from here.');
  }
}

/** Says a link was not opened, with the link to copy: somebody may still want it elsewhere. */
function tell(message, url, why) {
  dialog.showMessageBox(current(), {
    type: 'warning', title: 'Orknux', message, detail: `${url}\n\n${why}`, buttons: ['Copy link', 'Close'], defaultId: 1, cancelId: 1,
  }).then(({ response }) => {
    if (response === 0) clipboard.writeText(url);
  });
}

/** The tab keys, in a tab and in the strip alike, for the window they were pressed in. */
function keys(tabs, event, input) {
  const asked = shortcut(input, tabKeysChosen);
  if (asked === null) return;
  event.preventDefault();
  if (asked === 'new') tabs.openHome();
  else if (asked === 'close') tabs.close(tabs.active);
  else if (asked === 'next') tabs.step(1);
  else if (asked === 'previous') tabs.step(-1);
}

/*
 * Right-click. Electron draws no menu of its own, so without this a link could
 * not be copied or opened beside the page, a field had no Paste, and a picture -
 * a diagram an agent drew - could not be saved or copied at all. What a link
 * offers is links.js's linkMenu.
 */
function contextMenu(tabs, tab, params, contents) {
  const items = [];
  if (params.mediaType === 'image' && params.srcURL) {
    // Through the same Save dialog as any download; see watchDownloads.
    items.push({ label: 'Save image as…', click: () => contents.downloadURL(params.srcURL) });
    items.push({ label: 'Copy image', click: () => contents.copyImageAt(params.x, params.y) });
    items.push({ label: 'Copy image address', click: () => clipboard.writeText(params.srcURL) });
  }
  if (params.linkURL) {
    if (items.length > 0) items.push({ type: 'separator' });
    const link = classify(params.linkURL, server === null ? null : server.port);
    const actions = {
      tab: () => open(tabs, link.url, { after: tab }),
      window: () => openWindow(link.url),
      outside: () => follow(link),
      // A mail link copies as the address, which is what gets pasted into a mail.
      copy: () => clipboard.writeText(params.linkURL.replace(/^mailto:/, '')),
    };
    items.push(...linkMenu(link).map(({ action, label }) => ({ label, click: actions[action] })));
  }
  if (params.isEditable || params.selectionText) {
    if (items.length > 0) items.push({ type: 'separator' });
    items.push(...(params.isEditable
      ? [
        { role: 'cut', enabled: params.editFlags.canCut },
        { role: 'copy', enabled: params.editFlags.canCopy },
        { role: 'paste', enabled: params.editFlags.canPaste },
        { type: 'separator' },
        { role: 'selectAll' },
      ]
      : [{ role: 'copy' }]));
  }
  if (items.length > 0) Menu.buildFromTemplate(items).popup({ window: tabs.win });
}

/*
 * A download opened "in a new tab" - an attachment, an artifact served to be
 * saved - opens a tab that never shows a page. It goes once the file is under
 * way, as a browser's does. The file itself is Electron's: a Save dialog, the
 * same on both systems.
 */
function watchDownloads() {
  session.defaultSession.on('will-download', (_event, item, contents) => {
    const tab = tabOf(contents);
    if (!tab || tab.committed) return;
    item.once('updated', () => tab.leaveIfEmpty());
    item.once('done', () => tab.leaveIfEmpty());
  });
}

/**
 * A window with its strip and its tabs, opening `url` - or the splash, for the
 * first window of a start, while there is no interface yet.
 *
 * The first window opens where the last one was closed; a later one at the size
 * of the window it came from, where the system puts it. Every window remembers
 * its place as it moves, so the next start opens where the last one moved was.
 */
function createWindow(url) {
  const from = current();
  const state = from === null ? windowState.restore(data) : { ...from.getNormalBounds(), x: undefined, y: undefined, maximized: false };
  const created = new BrowserWindow({
    x: state.x,
    y: state.y,
    width: state.width,
    height: state.height,
    minWidth: 900,
    minHeight: 600,
    title,
    backgroundColor: '#09090b',
    icon: trayImage(),
    show: smoke === null,
    // The window's own page is the tab strip; the interface is in the tabs under it.
    webPreferences: { ...PAGE_PREFERENCES, preload: path.join(__dirname, 'tabs-preload.js') },
  });
  if (state.maximized) created.maximize();
  windowState.track(created, data);
  const tabs = new Tabs(created, { webPreferences: PAGE_PREFERENCES, prepare: (tab) => wire(tab, tabs), home: () => home });
  windows.set(created, tabs);
  created.webContents.on('before-input-event', (event, input) => keys(tabs, event, input));
  open(tabs, url);

  /*
   * Closing the window leaves Orknux running in the tray, because a workflow on
   * a schedule or a Slack mention does not wait for the window to be open. Only
   * for the last window, and only where there is a tray to come back from;
   * without one, closing the last window quits.
   *
   * Any other window closes for good - its tabs first, each through its page's
   * own leave-this-page question, and the window once the last of them has gone
   * (Tabs.forget closes it). A window torn down around its tabs would lose what
   * an editor in one of them had not saved, without asking.
   */
  created.on('close', (event) => {
    if (quitting) return;
    const others = [...windows.keys()].some((window) => window !== created && !window.isDestroyed());
    if (others) {
      if (tabs.list.length === 0) return;
      event.preventDefault();
      [...tabs.list].forEach((tab) => tabs.close(tab));
      return;
    }
    if (tray === null) return;
    event.preventDefault();
    created.hide();
    if (!fs.existsSync(path.join(data, '.told-about-tray')) && Notification.isSupported()) {
      new Notification({ title: 'Orknux is still running', body: 'Schedules and Slack keep working. Quit from the tray icon.' }).show();
      fs.writeFileSync(path.join(data, '.told-about-tray'), '');
    }
  });
  created.on('closed', () => {
    windows.delete(created);
  });
  return created;
}

/** "Open link in new window", and a Shift+click: a page of ours in a window of its own, in the same session as every other. */
function openWindow(url) {
  const created = createWindow(url);
  created.show();
  created.focus();
}

function trayImage() {
  const file = path.join(resources, 'tray', 'tray.png');
  return fs.existsSync(file) ? nativeImage.createFromPath(file) : undefined;
}

function createTray() {
  const image = trayImage();
  if (image === undefined || smoke !== null) return;
  try {
    tray = new Tray(image);
  } catch {
    tray = null;
    return;
  }
  tray.setToolTip('Orknux');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open Orknux', click: show },
    { label: 'Open data folder', click: () => shell.openPath(data) },
    { type: 'separator' },
    { label: 'Quit Orknux', click: () => app.quit() },
  ]));
  tray.on('click', show);
}

/** The server stopped and was not asked to: say why, from its own last words, and offer to try again. */
function unexpected(why) {
  const tail = log?.last(25) ?? '';
  dialog.showMessageBox(current() ?? undefined, {
    type: 'error',
    title: 'Orknux',
    message: 'The Orknux server stopped',
    detail: `${why}\n\nThe last lines it wrote:\n\n${tail}\n\nThe whole log is in ${path.join(data, 'logs')}.`,
    buttons: ['Start again', 'Open log folder', 'Quit'],
    defaultId: 0,
    cancelId: 2,
  }).then(({ response }) => {
    if (response === 0) {
      app.relaunch();
      quit();
    } else if (response === 1) {
      shell.openPath(path.join(data, 'logs'));
      quit();
    } else {
      quit();
    }
  });
}

function fatal(title, detail) {
  log?.note(`${title}: ${detail}`);
  dialog.showErrorBox(title, detail);
  quit();
}

function quit() {
  quitting = true;
  app.quit();
}

/*
 * Quitting stops the server first and waits for it, so the database is closed
 * by the process that opened it rather than by the end of one.
 */
app.on('before-quit', (event) => {
  quitting = true;
  if (server === null || server.child === null) return;
  event.preventDefault();
  log?.note('stopping the server');
  server.stop().then(() => {
    server = null;
    app.quit();
  });
});

app.on('window-all-closed', () => {
  if (tray === null) quit();
});

/** The smoke test's photograph: the interface as the window drew it, then out. */
async function photograph() {
  fs.mkdirSync(smoke, { recursive: true });
  await new Promise((resolve) => setTimeout(resolve, 6000));
  // The tab's page, not the window's: the window's own page is only the strip.
  const tabs = windows.get(first());
  const page = tabs.active.contents;
  const image = await page.capturePage();
  fs.writeFileSync(path.join(smoke, 'window.png'), image.toPNG());
  fs.writeFileSync(path.join(smoke, 'result.json'), JSON.stringify({
    url: page.getURL(),
    title: page.getTitle(),
    port: server.port,
    tabs: tabs.list.length,
  }, null, 2));
  log.note(`smoke: photographed ${page.getURL()}`);
  quit();
}

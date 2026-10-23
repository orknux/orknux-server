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
const { classify, shortcut } = require('./links');
const windowState = require('./window-state');
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

let window = null;
/** @type {Tabs|null} */
let tabs = null;
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

function show() {
  if (window === null) return;
  // Closing the last tab hid the window; it comes back with one.
  if (tabs.list.length === 0) open(home);
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

  window = createWindow();
  createTray();

  if (prepared.loud.length > 0) {
    await dialog.showMessageBox(window, { type: 'warning', title: 'Orknux', message: 'Read this before going on', detail: prepared.loud.join('\n\n') });
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
    if (window && !window.isDestroyed()) window.setTitle(network === 'exposed' ? 'Orknux — open to the network' : 'Orknux');
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
    dialog.showMessageBox(window, {
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
  const splash = tabs.list.find((tab) => tab.splash);
  if (splash) {
    splash.splash = false;
    await splash.contents.loadURL(url);
  } else {
    open(url);
  }
  if (smoke !== null) photograph();
}

/** What every page of the interface runs with, in every tab. */
const PAGE_PREFERENCES = { contextIsolation: true, nodeIntegration: false, sandbox: true };

/** A tab on the interface, or on the splash while there is no interface yet. */
function open(url, more = {}) {
  if (url !== null) return tabs.open({ url, ...more });
  const tab = tabs.open({ file: path.join(__dirname, 'splash.html'), ...more });
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
 * asked for a new tab.
 */
function wire(tab) {
  const contents = tab.contents;
  const port = () => (server === null ? null : server.port);
  // A tab that never showed a page and was sent elsewhere at once would stay empty.
  tab.leaveIfEmpty = () => {
    if (!tab.committed) tabs.close(tab);
  };

  contents.setWindowOpenHandler(({ url, disposition }) => {
    const link = classify(url, port());
    if (link.kind === 'ours') open(link.url, { after: tab, activate: disposition !== 'background-tab' });
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
    const choice = dialog.showMessageBoxSync(window, {
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
  contents.on('before-input-event', keys);
  contents.on('context-menu', (_event, params) => contextMenu(tab, params, contents));
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
  dialog.showMessageBox(window, {
    type: 'warning', title: 'Orknux', message, detail: `${url}\n\n${why}`, buttons: ['Copy link', 'Close'], defaultId: 1, cancelId: 1,
  }).then(({ response }) => {
    if (response === 0) clipboard.writeText(url);
  });
}

/** The tab keys, in a tab and in the strip alike. */
function keys(event, input) {
  const asked = shortcut(input);
  if (asked === null) return;
  event.preventDefault();
  if (asked === 'new') tabs.openHome();
  else if (asked === 'close') tabs.close(tabs.active);
  else if (asked === 'next') tabs.step(1);
  else if (asked === 'previous') tabs.step(-1);
}

/*
 * Right-click. Electron draws no menu of its own, so without this a link could
 * not be copied or sent to the browser, a field had no Paste, and a picture -
 * a diagram an agent drew - could not be saved or copied at all.
 */
function contextMenu(tab, params, contents) {
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
    if (link.kind === 'ours' && !link.url.startsWith('blob:')) {
      items.push({ label: 'Open link in new tab', click: () => open(link.url, { after: tab }) });
      items.push({ label: 'Open link in browser', click: () => follow({ kind: 'outside', url: link.url }) });
    } else if (link.kind === 'outside') {
      items.push({ label: /^https?:/.test(link.url) ? 'Open link in browser' : 'Open link', click: () => follow(link) });
    }
    // A mail link copies as the address, which is what gets pasted into a mail.
    items.push({ label: 'Copy link address', click: () => clipboard.writeText(params.linkURL.replace(/^mailto:/, '')) });
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
  if (items.length > 0) Menu.buildFromTemplate(items).popup({ window });
}

/*
 * A download opened "in a new tab" - an attachment, an artifact served to be
 * saved - opens a tab that never shows a page. It goes once the file is under
 * way, as a browser's does. The file itself is Electron's: a Save dialog, the
 * same on both systems.
 */
function watchDownloads() {
  session.defaultSession.on('will-download', (_event, item, contents) => {
    const tab = tabs?.of(contents);
    if (!tab || tab.committed) return;
    item.once('updated', () => tab.leaveIfEmpty());
    item.once('done', () => tab.leaveIfEmpty());
  });
}

function createWindow() {
  const state = windowState.restore(data);
  const created = new BrowserWindow({
    x: state.x,
    y: state.y,
    width: state.width,
    height: state.height,
    minWidth: 900,
    minHeight: 600,
    title: 'Orknux',
    backgroundColor: '#09090b',
    icon: trayImage(),
    show: smoke === null,
    // The window's own page is the tab strip; the interface is in the tabs under it.
    webPreferences: { ...PAGE_PREFERENCES, preload: path.join(__dirname, 'tabs-preload.js') },
  });
  if (state.maximized) created.maximize();
  windowState.track(created, data);
  created.webContents.on('before-input-event', keys);
  tabs = new Tabs(created, { webPreferences: PAGE_PREFERENCES, prepare: wire, home: () => home });
  window = created;
  watchDownloads();
  open(null);

  /*
   * Closing the window leaves Orknux running in the tray, because a workflow on
   * a schedule or a Slack mention does not wait for the window to be open. Only
   * where there is a tray to come back from; without one, closing quits.
   */
  created.on('close', (event) => {
    if (quitting || tray === null) return;
    event.preventDefault();
    created.hide();
    if (!fs.existsSync(path.join(data, '.told-about-tray')) && Notification.isSupported()) {
      new Notification({ title: 'Orknux is still running', body: 'Schedules and Slack keep working. Quit from the tray icon.' }).show();
      fs.writeFileSync(path.join(data, '.told-about-tray'), '');
    }
  });
  created.on('closed', () => {
    window = null;
  });
  return created;
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
  dialog.showMessageBox(window ?? undefined, {
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

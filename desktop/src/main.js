/*
 * Orknux on one machine: the server on SQLite, in a window of its own.
 *
 * The same jar orknux-one runs, started by this process instead of by a
 * container, with its data in the user's profile instead of a volume. Nothing
 * here knows what the server does; it starts it, shows it, keeps it running
 * and stops it. See ../README.md for what is still to come.
 */
const { app, BrowserWindow, Menu, Notification, Tray, dialog, nativeImage, shell } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const { readSettings, prepare } = require('./data');
const { Log } = require('./logs');
const { Server } = require('./server');
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
  await window.loadURL(url);
  if (smoke !== null) photograph();
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
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  if (state.maximized) created.maximize();
  windowState.track(created, data);
  created.loadFile(path.join(__dirname, 'splash.html'));

  /*
   * The app's own pages stay in the window; anything else is the web, and opens
   * where the web is read. A link to a GitHub issue opening inside the app is a
   * window with no address bar and no way back.
   */
  const ours = (target) => {
    try {
      return server !== null && new URL(target).port === String(server.port) && ['127.0.0.1', 'localhost'].includes(new URL(target).hostname);
    } catch {
      return false;
    }
  };
  created.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url) && !ours(url)) shell.openExternal(url);
    else if (ours(url)) created.loadURL(url);
    return { action: 'deny' };
  });
  created.webContents.on('will-navigate', (event, url) => {
    if (url.startsWith('file:') || ours(url)) return;
    event.preventDefault();
    if (/^https?:/.test(url)) shell.openExternal(url);
  });

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
  const image = await window.webContents.capturePage();
  fs.writeFileSync(path.join(smoke, 'window.png'), image.toPNG());
  fs.writeFileSync(path.join(smoke, 'result.json'), JSON.stringify({
    url: window.webContents.getURL(),
    title: window.webContents.getTitle(),
    port: server.port,
  }, null, 2));
  log.note(`smoke: photographed ${window.webContents.getURL()}`);
  quit();
}

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { EventEmitter } = require('node:events');

const { PackageUpdate, installKind, refusalFor, sentenceOf } = require('../src/package-update');

/** electron-updater's autoUpdater as far as this module uses it, answering as told. */
class FakeUpdater extends EventEmitter {
  constructor() {
    super();
    this.calls = [];
  }

  set channel(value) {
    this.calls.push(['channel', value]);
    // What the real one does, and why the module sets allowDowngrade after it.
    this.allowDowngrade = true;
  }

  checkForUpdates() {
    this.calls.push(['check']);
    return Promise.resolve();
  }

  downloadUpdate() {
    this.calls.push(['download']);
    return Promise.resolve();
  }

  quitAndInstall(silent, runAfter) {
    this.calls.push(['quitAndInstall', silent, runAfter]);
  }
}

function made(overrides = {}) {
  const updater = new FakeUpdater();
  const order = [];
  const update = new PackageUpdate({
    updater,
    current: '1.3.0',
    refusal: null,
    client: false,
    beforeInstall: async () => order.push('server stopped'),
    note: () => {},
    later: (fn) => fn(),
    ...overrides,
  });
  return { update, updater, order };
}

test('it only ever goes forward, on the client channel too', () => {
  const { updater } = made({ client: true });
  assert.deepEqual(updater.calls, [['channel', 'client']]);
  assert.equal(updater.allowDowngrade, false);
  assert.equal(updater.autoDownload, false);
  assert.equal(updater.autoInstallOnAppQuit, false);
});

test('the server app reads the default channel', () => {
  const { updater } = made();
  assert.deepEqual(updater.calls, []);
});

test('a newer package is shown with its version and notes, and nothing is downloaded by asking', async () => {
  const { update, updater } = made();
  const seen = [];
  update.onChange((state) => seen.push(state.phase));

  update.check();
  updater.emit('update-available', { version: '1.3.1', releaseNotes: 'Faster.', releaseDate: '2026-11-03' });

  assert.deepEqual(seen, ['checking', 'available']);
  assert.equal(update.state.version, '1.3.1');
  assert.equal(update.state.notes, 'Faster.');
  assert.deepEqual(updater.calls, [['check']]);
});

test('one press downloads it, stops the server, then runs the installer silently and starts again after', async () => {
  const { update, updater, order } = made();
  update.check();
  updater.emit('update-available', { version: '1.3.1' });

  update.install();
  updater.emit('download-progress', { percent: 40, transferred: 40, total: 100, bytesPerSecond: 10 });
  assert.equal(update.state.phase, 'downloading');
  assert.equal(update.state.percent, 40);

  updater.emit('update-downloaded', { version: '1.3.1' });
  assert.equal(update.state.phase, 'installing');
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(order, ['server stopped']);
  assert.deepEqual(updater.calls.slice(-2), [['download'], ['quitAndInstall', true, true]]);
});

test('nothing is downloaded before a newer package was found', () => {
  const { update, updater } = made();
  update.install();
  assert.deepEqual(updater.calls, []);
});

test('the installer still runs when the server would not stop cleanly', async () => {
  const { update, updater } = made({ beforeInstall: async () => { throw new Error('it hung'); } });
  update.check();
  updater.emit('update-available', { version: '1.3.1' });
  update.install();
  updater.emit('update-downloaded', {});
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(updater.calls.at(-1), ['quitAndInstall', true, true]);
});

test('nothing newer, and a failure, are said in words', () => {
  const { update, updater } = made();
  update.check();
  updater.emit('update-not-available', { version: '1.3.0' });
  assert.equal(update.state.phase, 'current');

  update.check();
  updater.emit('error', new Error('Cannot find channel "latest.yml" update info: HttpError: 404'));
  assert.equal(update.state.phase, 'none', 'nothing offered yet is a state, not a failure');
  assert.equal(update.state.error, null);

  update.check();
  updater.emit('error', new Error('net::ERR_NAME_NOT_RESOLVED'));
  assert.equal(update.state.phase, 'failed');
  assert.match(update.state.error, /orknux.ai could not be reached/);
});

test('a copy that cannot update itself says why, and never loads the updater', () => {
  const update = new PackageUpdate({
    updater: null, current: '1.3.0', refusal: refusalFor('msi', true), client: false, beforeInstall: async () => {}, note: () => {},
  });
  assert.equal(update.state.phase, 'unavailable');
  assert.match(update.state.reason, /installed from the MSI/);
  update.check();
  update.install();
  assert.equal(update.state.phase, 'unavailable');
});

test('which installs can update themselves', () => {
  assert.match(refusalFor('nsis', false), /store or the distribution/);
  assert.equal(refusalFor('nsis', true), null);
  assert.equal(refusalFor('appimage', true), null);
  assert.equal(refusalFor('deb', true), null);
  assert.match(refusalFor('development', true), /checkout/);
});

test('an NSIS install is told from an MSI one by its uninstaller', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orknux-install-'));
  const execPath = path.join(dir, 'Orknux.exe');
  const given = { platform: 'win32', packaged: true, execPath, resourcesPath: dir, productName: 'Orknux', env: {} };
  assert.equal(installKind(given), 'msi');
  fs.writeFileSync(path.join(dir, 'Uninstall Orknux.exe'), '');
  assert.equal(installKind(given), 'nsis');
  assert.equal(installKind({ ...given, packaged: false }), 'development');
});

test('on Linux, an AppImage by its variable and a .deb by the package-type file', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orknux-install-'));
  const given = { platform: 'linux', packaged: true, execPath: path.join(dir, 'orknux'), resourcesPath: dir, productName: 'Orknux', env: {} };
  assert.equal(installKind(given), 'other');
  assert.equal(installKind({ ...given, env: { APPIMAGE: '/tmp/Orknux.AppImage' } }), 'appimage');
  fs.writeFileSync(path.join(dir, 'package-type'), 'deb\n');
  assert.equal(installKind(given), 'deb');
});

test('a checksum mismatch is said as one', () => {
  assert.equal(sentenceOf(new Error('sha512 checksum mismatch, expected abc')), 'The downloaded installer did not match its checksum, so it was not run.');
});

test('a listener that throws stops neither the update nor the others hearing of it', () => {
  const noted = [];
  const { update, updater } = made({ note: (line) => noted.push(line) });
  const heard = [];
  update.onChange(() => { throw new ReferenceError('port is not defined'); });
  update.onChange((state) => heard.push(state.phase));
  update.check();
  updater.emit('update-not-available', {});
  assert.deepEqual(heard, ['checking', 'current']);
  assert.match(noted[0], /telling a page failed: port is not defined/);
});

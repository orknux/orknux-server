// When the release launcher is asked before a start, without Electron: `node --test test/` in desktop/docker.
const test = require('node:test');
const assert = require('node:assert/strict');
const { launcherNeeded } = require('../src/server');

const quiet = { afterExit: false, ranRelease: false, blind: false, pinned: false };

test('an ordinary start runs the shipped jar without asking', () => {
  assert.equal(launcherNeeded(quiet), false);
});

test('after the server exited - an update, a rollback or a crash - it is asked', () => {
  assert.equal(launcherNeeded({ ...quiet, afterExit: true }), true);
});

test('a release that ran last time is asked for again, so an update survives quitting the app', () => {
  assert.equal(launcherNeeded({ ...quiet, ranRelease: true }), true);
});

test('a launcher that could not read the releases, or a pin, is asked', () => {
  assert.equal(launcherNeeded({ ...quiet, blind: true }), true);
  assert.equal(launcherNeeded({ ...quiet, pinned: true }), true);
});

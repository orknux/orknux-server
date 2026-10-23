// How a jar is started (#650), without Electron: `node --test test/` in desktop/docker.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { launchOf } = require('../src/server');

/** A resources/server directory: the jar, and as much of scripts/aot.sh's output as asked for. */
function server({ unpacked, cache }) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orknux-launch-'));
  fs.writeFileSync(path.join(dir, 'app.jar'), '');
  if (unpacked) {
    fs.mkdirSync(path.join(dir, 'app'));
    fs.writeFileSync(path.join(dir, 'app', 'app.jar'), '');
    if (cache) fs.writeFileSync(path.join(dir, 'app', 'app.aot'), '');
  }
  return { dir, image: path.join(dir, 'app.jar') };
}

test('the shipped jar starts from its unpacked copy, relative to it, with the cache', () => {
  const { dir, image } = server({ unpacked: true, cache: true });
  assert.deepEqual(launchOf(image, image), { cwd: path.join(dir, 'app'), jar: 'app.jar', options: ['-XX:AOTCache=app.aot'] });
});

test('the launcher may spell the shipped jar differently and it is still the shipped jar', () => {
  const { dir, image } = server({ unpacked: true, cache: true });
  assert.equal(launchOf(path.join(dir, '.', 'app.jar'), image).jar, 'app.jar');
});

test('an unpacked copy with no cache still starts unpacked', () => {
  const { dir, image } = server({ unpacked: true, cache: false });
  assert.deepEqual(launchOf(image, image), { cwd: path.join(dir, 'app'), jar: 'app.jar', options: [] });
});

test('a package made without scripts/aot.sh starts the jar as it is', () => {
  const { image } = server({ unpacked: false });
  assert.deepEqual(launchOf(image, image), { cwd: undefined, jar: image, options: [] });
});

test('a release the server installed starts as a jar of its own', () => {
  const { image } = server({ unpacked: true, cache: true });
  const release = path.join(os.tmpdir(), 'releases', 'orknux-server-1.2.0.jar');
  assert.deepEqual(launchOf(release, image), { cwd: undefined, jar: release, options: [] });
});

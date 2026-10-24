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

test('the jars get back the times the cache was made with, which an installer had replaced', () => {
  const { dir, image } = server({ unpacked: true, cache: true });
  const lib = path.join(dir, 'app', 'lib');
  fs.mkdirSync(lib);
  fs.writeFileSync(path.join(lib, 'one.jar'), '');
  const made = 1_790_000_000;
  fs.writeFileSync(path.join(dir, 'app', 'aot-times.tsv'), `${made}\tapp.jar\n${made}\tlib/one.jar\n${made}\t../app.jar\n`);
  // What an installer leaves: every file stamped now.
  launchOf(image, image);
  for (const file of [path.join(dir, 'app', 'app.jar'), path.join(lib, 'one.jar')]) {
    assert.equal(Math.floor(fs.statSync(file).mtimeMs / 1000), made);
  }
  // Nothing outside the unpacked directory is touched, whatever the list says.
  assert.notEqual(Math.floor(fs.statSync(image).mtimeMs / 1000), made);
});

test('with no list of times, nothing is touched and the start goes on', () => {
  const { dir, image } = server({ unpacked: true, cache: true });
  const before = fs.statSync(path.join(dir, 'app', 'app.jar')).mtimeMs;
  assert.deepEqual(launchOf(image, image).options, ['-XX:AOTCache=app.aot']);
  assert.equal(fs.statSync(path.join(dir, 'app', 'app.jar')).mtimeMs, before);
});

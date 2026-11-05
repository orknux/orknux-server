// How a jar is started (#650), without Electron: `node --test test/` in desktop/docker.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { extractLibraries, launchOf } = require('../src/server');

/** A resources/server directory: the jar, and as much of scripts/aot.sh's output as asked for; `thin` is one without its libraries. */
function server({ unpacked, cache }) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orknux-launch-'));
  fs.writeFileSync(path.join(dir, 'app.jar'), '');
  if (unpacked) {
    fs.mkdirSync(path.join(dir, 'app'));
    fs.writeFileSync(path.join(dir, 'app', 'app.jar'), '');
    if (unpacked !== 'thin') fs.mkdirSync(path.join(dir, 'app', 'lib'));
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

test('a package without its libraries starts from where they were unpacked, with the cache it carries', () => {
  const { dir, image } = server({ unpacked: 'thin', cache: true });
  const extracted = fs.mkdtempSync(path.join(os.tmpdir(), 'orknux-extracted-'));
  assert.deepEqual(launchOf(image, image, extracted), {
    cwd: extracted,
    jar: 'app.jar',
    options: [`-XX:AOTCache=${path.join(dir, 'app', 'app.aot')}`],
  });
});

test('a package without its libraries, which could not unpack them, starts the jar as it is', () => {
  const { image } = server({ unpacked: 'thin', cache: true });
  assert.deepEqual(launchOf(image, image, null), { cwd: undefined, jar: image, options: [] });
});

/** A java that unpacks as `extract` does: the thin jar and lib/, into --destination. */
function fakeJava() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orknux-java-'));
  const java = path.join(dir, 'java');
  fs.writeFileSync(java, '#!/bin/sh\nwhile [ "$1" != --destination ]; do shift; done\nmkdir -p "$2/lib" && echo unpacked > "$2/app.jar" && echo lib > "$2/lib/one.jar" && echo "$2" >> "' + dir + '/calls"\n');
  fs.chmodSync(java, 0o755);
  return { java, calls: () => (fs.existsSync(path.join(dir, 'calls')) ? fs.readFileSync(path.join(dir, 'calls'), 'utf8').trim().split('\n') : []) };
}

const quiet = { note() {} };

test('the libraries are unpacked once per package, with the shipped thin jar and times, and the old ones go', { skip: process.platform === 'win32' }, async () => {
  const { dir, image } = server({ unpacked: 'thin', cache: true });
  fs.writeFileSync(path.join(dir, 'app', 'app.jar'), 'thin');
  fs.writeFileSync(path.join(dir, 'app', 'aot-times.tsv'), '1790000000\tlib/one.jar\n');
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'orknux-data-'));
  fs.mkdirSync(path.join(data, 'server-libraries', 'older'), { recursive: true });
  const { java, calls } = fakeJava();

  const here = await extractLibraries({ java, imageJar: image, data, log: quiet });
  assert.equal(fs.readFileSync(path.join(here, 'app.jar'), 'utf8'), 'thin');
  assert.equal(fs.readFileSync(path.join(here, 'lib', 'one.jar'), 'utf8'), 'lib\n');
  assert.ok(fs.existsSync(path.join(here, 'aot-times.tsv')));
  assert.deepEqual(fs.readdirSync(path.join(data, 'server-libraries')), [path.basename(here)]);

  assert.equal(await extractLibraries({ java, imageJar: image, data, log: quiet }), here);
  assert.equal(calls().length, 1);
});

test('a package that carries its libraries, or no unpacked copy, unpacks nothing', async () => {
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'orknux-data-'));
  for (const unpacked of [true, false]) {
    const { image } = server({ unpacked, cache: true });
    assert.equal(await extractLibraries({ java: '/nonexistent/java', imageJar: image, data, log: quiet }), null);
  }
  assert.equal(fs.existsSync(path.join(data, 'server-libraries')), false);
});

test('a java that fails leaves nothing half unpacked, and the jar starts as it is', async () => {
  const { image } = server({ unpacked: 'thin', cache: true });
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'orknux-data-'));
  assert.equal(await extractLibraries({ java: '/nonexistent/java', imageJar: image, data, log: quiet }), null);
  assert.deepEqual(fs.readdirSync(path.join(data, 'server-libraries')), []);
});

// What connecting decides, without Electron: `node --test test/` in desktop/docker.
const test = require('node:test');
const assert = require('node:assert/strict');
const { addressesOf, isPlainText, judged, whereTo } = require('../src/connect');
const { classify } = require('../src/links');

test('an address with no scheme is tried encrypted first', () => {
  assert.deepEqual(addressesOf(' orknux.example.com:8443/ '), { tries: ['https://orknux.example.com:8443', 'http://orknux.example.com:8443'] });
  assert.deepEqual(addressesOf('http://10.0.0.5:9096/'), { tries: ['http://10.0.0.5:9096'] });
});

test('what cannot be a server is said so, and a password is never kept', () => {
  for (const typed of ['', '   ', 'ftp://host', 'file:///etc/passwd', 'javascript://x', 'https://me:secret@host', 'http://']) {
    assert.ok(addressesOf(typed).wrong, typed);
  }
});

test('plain http is warned about everywhere but this computer', () => {
  assert.equal(isPlainText('http://192.168.0.5:8080'), true);
  assert.equal(isPlainText('https://example.com'), false);
  assert.equal(isPlainText('http://127.0.0.1:8080'), false);
  assert.equal(isPlainText('http://localhost:8080'), false);
});

const answer = (json, status = 200) => ({ status, json });

test('the server says whether a client may use it, and is believed', () => {
  assert.deepEqual(judged('1.3.0', answer({ product: 'orknux', server: '1.9.0', compatible: true }), null), { ok: true, server: '1.9.0' });
  assert.deepEqual(judged('1.3.0', answer({ product: 'orknux', server: '2.0.0', compatible: false, reason: 'Update it.' }), null), { ok: false, why: 'Update it.' });
});

test('a server from before the question is taken at its word within major 1 only', () => {
  const gone = answer(null, 404);
  assert.deepEqual(judged('1.3.0', gone, answer({ method: 'LDAP' })), { ok: true, server: null });
  assert.equal(judged('2.0.0', gone, answer({ method: 'LDAP' })).ok, false);
});

test('something else at that address is not an Orknux server', () => {
  assert.equal(judged('1.3.0', answer({ hello: 'world' }), null).ok, false);
  assert.equal(judged('1.3.0', answer(null, 404), answer(null, 404)).ok, false);
  assert.equal(judged('1.3.0', answer(null, 500), null).ok, false);
});

test('an installation that already runs here is not asked, and a new one is', () => {
  const none = { mode: null, server: null };
  const here = { canRunHere: true, smoke: false };
  assert.equal(whereTo(none, { ...here, hasData: true }), 'local');
  assert.equal(whereTo(none, { ...here, hasData: false }), 'ask');
  assert.equal(whereTo({ mode: 'client', server: 'https://a' }, { ...here, hasData: true }), 'client');
  // Disconnecting asks again, however much data is here.
  assert.equal(whereTo({ mode: 'ask', server: 'https://a' }, { ...here, hasData: true }), 'ask');
  assert.equal(whereTo({ mode: 'local', server: null }, { ...here, canRunHere: false, hasData: true }), 'ask');
  assert.equal(whereTo(none, { hasData: false, canRunHere: true, smoke: true }), 'local');
});

test('connected, the server is ours by its whole origin and nothing else is', () => {
  const ours = 'https://orknux.example.com:8443';
  assert.deepEqual(classify('https://orknux.example.com:8443/chat', ours), { kind: 'ours', url: 'https://orknux.example.com:8443/chat' });
  assert.equal(classify('http://orknux.example.com:8443/chat', ours).kind, 'outside');
  assert.equal(classify('https://orknux.example.com.evil.test:8443/', ours).kind, 'outside');
  assert.equal(classify('http://127.0.0.1:8443/', ours).kind, 'outside');
  assert.equal(classify('blob:https://orknux.example.com:8443/0b1c', ours).kind, 'ours');
});

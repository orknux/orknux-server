// The link and key decisions, without Electron: `node --test test/` in desktop/docker.
const test = require('node:test');
const assert = require('node:assert/strict');
const { classify, shortcut } = require('../src/links');

const PORT = 8080;

test('a page of this server stays in the app, on the address the window uses', () => {
  assert.deepEqual(classify('http://127.0.0.1:8080/workspace/1', PORT), { kind: 'ours', url: 'http://127.0.0.1:8080/workspace/1' });
  // The server's own links say localhost; a localhost page would be another site with no session.
  assert.deepEqual(classify('http://localhost:8080/api/artifacts/7?x=1#top', PORT), { kind: 'ours', url: 'http://127.0.0.1:8080/api/artifacts/7?x=1#top', moved: true });
});

test('another port, another host or https is not this server', () => {
  assert.equal(classify('http://127.0.0.1:9000/', PORT).kind, 'outside');
  assert.equal(classify('https://localhost:8080/', PORT).kind, 'outside');
  assert.equal(classify('http://192.168.0.10:8080/', PORT).kind, 'outside');
  assert.equal(classify('https://github.com/orknux', PORT).kind, 'outside');
});

test('nothing is ours while the server is starting', () => {
  assert.equal(classify('http://127.0.0.1:8080/', null).kind, 'outside');
});

test('mail and phone links go to the operating system', () => {
  assert.deepEqual(classify('mailto:someone@example.com', PORT), { kind: 'outside', url: 'mailto:someone@example.com' });
  assert.equal(classify('tel:+48123456789', PORT).kind, 'outside');
});

test('a file the page made is ours only when the page was', () => {
  assert.equal(classify('blob:http://127.0.0.1:8080/0b1c', PORT).kind, 'ours');
  assert.equal(classify('blob:https://example.com/0b1c', PORT).kind, 'refused');
});

test('files, custom schemes and nonsense are refused, an empty window is nothing', () => {
  assert.deepEqual(classify('file:///C:/Windows/System32/calc.exe', PORT), { kind: 'refused', url: 'file:///C:/Windows/System32/calc.exe', scheme: 'file:' });
  assert.equal(classify('search-ms:query=x', PORT).kind, 'refused');
  assert.equal(classify('slack://open', PORT).scheme, 'slack:');
  assert.equal(classify('not a url', PORT).kind, 'refused');
  assert.equal(classify('about:blank', PORT).kind, 'nothing');
  assert.equal(classify('javascript:void(0)', PORT).kind, 'nothing');
});

const key = (key, more = {}) => ({ type: 'keyDown', control: true, shift: false, alt: false, meta: false, key, code: '', ...more });

test('the browser tab keys', () => {
  assert.equal(shortcut(key('t', { code: 'KeyT' })), 'new');
  assert.equal(shortcut(key('w', { code: 'KeyW' })), 'close');
  assert.equal(shortcut(key('F4')), 'close');
  assert.equal(shortcut(key('Tab')), 'next');
  assert.equal(shortcut(key('Tab', { shift: true })), 'previous');
  assert.equal(shortcut(key('PageDown')), 'next');
  assert.equal(shortcut(key('PageUp')), 'previous');
});

test('a letter is the character typed, and the key only off a Latin layout', () => {
  // AZERTY: the key in QWERTY's W place types Z, and Ctrl+Z is undo, not close.
  assert.equal(shortcut(key('z', { code: 'KeyW' })), null);
  // Russian: the key that carries T types е.
  assert.equal(shortcut(key('е', { code: 'KeyT' })), 'new');
});

test('anything else is left to the page', () => {
  assert.equal(shortcut(key('t', { type: 'keyUp' })), null);
  assert.equal(shortcut(key('t', { control: false })), null);
  assert.equal(shortcut(key('t', { shift: true })), null);
  assert.equal(shortcut(key('t', { alt: true })), null);
  assert.equal(shortcut(key('c', { code: 'KeyC' })), null);
});

// The link and key decisions, without Electron: `node --test test/` in desktop/docker.
const test = require('node:test');
const assert = require('node:assert/strict');
const { classify, linkMenu, shortcut, stepped, tabKeys, windowKeys, DEFAULT_TAB_KEYS, DEFAULT_WINDOW_KEYS } = require('../src/links');

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

test('next and previous are whatever the interface chose, before any fixed key', () => {
  const chosen = { next: 'Ctrl+Alt+ArrowRight', previous: 'Ctrl+Alt+ArrowLeft' };
  assert.equal(shortcut(key('ArrowRight', { alt: true }), chosen), 'next');
  assert.equal(shortcut(key('ArrowLeft', { alt: true }), chosen), 'previous');
  // Chosen away from Ctrl+PageDown, it is the page's again.
  assert.equal(shortcut(key('PageDown'), chosen), null);
  // Ctrl+Tab is the other habit, and stays.
  assert.equal(shortcut(key('Tab'), chosen), 'next');
  assert.equal(shortcut(key('Tab', { shift: true }), chosen), 'previous');
  // Read as the interface writes them: a letter in capitals, compared without case; a choice wins over a fixed key.
  const letters = { next: 'Ctrl+T', previous: 'Ctrl+Shift+T' };
  assert.equal(shortcut(key('t', { code: 'KeyT' }), letters), 'next');
  assert.equal(shortcut(key('T', { code: 'KeyT', shift: true }), letters), 'previous');
  // A function key on its own, which the interface allows.
  assert.equal(shortcut(key('F6', { control: false }), { next: 'F6', previous: 'Shift+F6' }), 'next');
  // A modifier on its own is nobody's choice.
  assert.equal(shortcut(key('Control'), { next: 'Ctrl', previous: 'Ctrl+Shift' }), null);
});

test('what a page hands over is taken only when it is a shortcut', () => {
  assert.deepEqual(tabKeys('Ctrl+Alt+ArrowRight', ' Ctrl+Alt+ArrowLeft '), { next: 'Ctrl+Alt+ArrowRight', previous: 'Ctrl+Alt+ArrowLeft' });
  assert.deepEqual(tabKeys('', 42), DEFAULT_TAB_KEYS);
  assert.deepEqual(tabKeys('x'.repeat(65), undefined), DEFAULT_TAB_KEYS);
  // The interface's defaults, in orknux-ui's session/shortcut.ts.
  assert.deepEqual(DEFAULT_TAB_KEYS, { next: 'Ctrl+PageDown', previous: 'Ctrl+PageUp' });
});

test('stepping goes round at both ends', () => {
  assert.equal(stepped(0, 1, 3), 1);
  assert.equal(stepped(2, 1, 3), 0);
  assert.equal(stepped(0, -1, 3), 2);
  assert.equal(stepped(1, -1, 3), 0);
  assert.equal(stepped(0, 1, 1), 0);
  assert.equal(stepped(0, 1, 0), -1);
});

const actions = (url) => linkMenu(classify(url, PORT)).map(({ action }) => action);
const labels = (url) => linkMenu(classify(url, PORT)).map(({ label }) => label);

test('a link of ours opens in a new tab or a new window, never in the browser', () => {
  assert.deepEqual(actions('http://localhost:8080/workspace/1'), ['tab', 'window', 'copy']);
  assert.deepEqual(labels('http://127.0.0.1:8080/workspace/1'), ['Open link in new tab', 'Open link in new window', 'Copy link address']);
});

test('the web still opens outside, mail and phone are opened, and the rest is only copied', () => {
  assert.deepEqual(linkMenu(classify('https://github.com/orknux', PORT)), [
    { action: 'outside', label: 'Open link in browser' },
    { action: 'copy', label: 'Copy link address' },
  ]);
  assert.deepEqual(labels('mailto:someone@example.com'), ['Open link', 'Copy link address']);
  assert.deepEqual(actions('blob:http://127.0.0.1:8080/0b1c'), ['copy']);
  assert.deepEqual(actions('file:///C:/Windows/System32/calc.exe'), ['copy']);
});

test('Alt and the arrows move between windows unless the interface says otherwise', () => {
  const press = (key, extra = {}) => ({ type: 'keyDown', key, alt: true, control: false, shift: false, meta: false, ...extra });
  assert.equal(shortcut(press('ArrowRight')), 'next-window');
  assert.equal(shortcut(press('ArrowLeft')), 'previous-window');
  const chosen = { ...DEFAULT_TAB_KEYS, ...windowKeys('Ctrl+Shift+N', 'Ctrl+Shift+P') };
  assert.equal(shortcut(press('n', { alt: false, control: true, shift: true }), chosen), 'next-window');
  assert.equal(shortcut(press('ArrowRight'), chosen), null);
  assert.deepEqual(windowKeys('', 7), DEFAULT_WINDOW_KEYS);
});

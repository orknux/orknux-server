/*
 * What a link is to this app, and what a key press asks of the tabs.
 *
 * Kept apart from Electron so both can be tested with plain `node --test`
 * (links.test.js): these are the decisions, main.js and tabs.js only carry them
 * out.
 */

/** The names the server answers to on this machine. The window uses the first; ORKNUX_BASE_URL hands out the second. */
const OUR_HOSTS = ['127.0.0.1', 'localhost'];

/*
 * The schemes handed to the operating system: the web, mail and the phone.
 * Nothing else, because Windows and Linux both run whatever is registered for a
 * scheme - a file: link to an .exe on Windows, or one of the registered
 * handlers with a history of being abused (search-ms:, ms-msdt:) - and a link
 * in a chat is something an agent wrote.
 */
const OUTSIDE = ['http:', 'https:', 'mailto:', 'tel:'];

/** What opens nothing and needs saying nothing about: an empty window.open(), a javascript: href. */
const NOTHING = ['about:', 'javascript:'];

/**
 * What to do with a link: `ours` (a page of this server, at the address the
 * window uses), `outside` (for the operating system to open), `nothing`, or
 * `refused`.
 *
 * A page of ours is always given back on 127.0.0.1, because the server's own
 * links say localhost (ORKNUX_BASE_URL) while the window is on 127.0.0.1: to a
 * browser those are two sites, so a localhost page has no session cookie and
 * signs in again, and a write from it is refused as cross-site. `moved` says
 * the address was changed to get there.
 *
 * @param {string} target the link
 * @param {number|null} port the server's port, or null while it is starting
 * @returns {{kind: 'ours'|'outside'|'nothing'|'refused', url: string, moved?: boolean, scheme?: string}}
 */
function classify(target, port) {
  let url;
  try {
    url = new URL(target);
  } catch {
    return { kind: 'refused', url: String(target), scheme: '' };
  }
  const isOurs = (candidate) =>
    port !== null && candidate.protocol === 'http:' && OUR_HOSTS.includes(candidate.hostname) && (candidate.port || '80') === String(port);

  // A file the page made itself (a report, an export): ours when the page that made it was.
  if (url.protocol === 'blob:') {
    try {
      return isOurs(new URL(url.origin)) ? { kind: 'ours', url: url.href } : { kind: 'refused', url: url.href, scheme: 'blob:' };
    } catch {
      return { kind: 'refused', url: url.href, scheme: 'blob:' };
    }
  }
  if (isOurs(url)) {
    if (url.hostname === OUR_HOSTS[0]) return { kind: 'ours', url: url.href };
    url.hostname = OUR_HOSTS[0];
    return { kind: 'ours', url: url.href, moved: true };
  }
  if (OUTSIDE.includes(url.protocol)) return { kind: 'outside', url: url.href };
  if (NOTHING.includes(url.protocol)) return { kind: 'nothing', url: url.href };
  return { kind: 'refused', url: url.href, scheme: url.protocol };
}

/**
 * What a link's right-click menu offers, in order: `tab` and `window` for a
 * page of ours, `outside` for what only the operating system can open, and
 * `copy` for every link.
 *
 * A page of ours opens in a new tab or a new window of this app, never in the
 * browser: the browser is another site to the server's cookie, so the page
 * there signs in again. The web is the opposite - a site opened inside the app
 * has no address bar and no way back - so it still goes out, and is the only
 * thing that does. A file the page made (blob:) has no address a second page
 * could load, so it is only copied.
 *
 * @param {{kind: string, url: string}} link what classify() said
 * @returns {{action: 'tab'|'window'|'outside'|'copy', label: string}[]}
 */
function linkMenu(link) {
  const items = [];
  if (link.kind === 'ours' && !link.url.startsWith('blob:')) {
    items.push({ action: 'tab', label: 'Open link in new tab' });
    items.push({ action: 'window', label: 'Open link in new window' });
  } else if (link.kind === 'outside') {
    items.push({ action: 'outside', label: /^https?:/.test(link.url) ? 'Open link in browser' : 'Open link' });
  }
  items.push({ action: 'copy', label: 'Copy link address' });
  return items;
}

/**
 * Next and previous tab when the interface has not said otherwise - the same
 * defaults as `DEFAULT_NEXT_TAB_SHORTCUT` and `DEFAULT_PREVIOUS_TAB_SHORTCUT` in
 * orknux-ui's session/shortcut.ts, which is where they are chosen. Written the
 * way the interface writes a shortcut: modifiers in a fixed order, then the key.
 */
const DEFAULT_TAB_KEYS = Object.freeze({ next: 'Ctrl+PageDown', previous: 'Ctrl+PageUp' });

/** Next and previous window: `DEFAULT_NEXT_WINDOW_SHORTCUT` and its pair in the same file, for the same reason. */
const DEFAULT_WINDOW_KEYS = Object.freeze({ nextWindow: 'Alt+ArrowRight', previousWindow: 'Alt+ArrowLeft' });

/** The longest shortcut taken from a page: a format, so a page cannot hand over a novel. */
const LONGEST_SHORTCUT = 64;

/**
 * The tab keys a page handed over, or the defaults for whichever is not one.
 * The page is ours, but it is still a renderer: what crosses is checked.
 */
function tabKeys(next, previous) {
  const fine = (value) => typeof value === 'string' && value.trim() !== '' && value.length <= LONGEST_SHORTCUT;
  return {
    next: fine(next) ? next.trim() : DEFAULT_TAB_KEYS.next,
    previous: fine(previous) ? previous.trim() : DEFAULT_TAB_KEYS.previous,
  };
}

/** The window keys a page handed over, by the same check, or the defaults for whichever is not one. */
function windowKeys(next, previous) {
  const fine = (value) => typeof value === 'string' && value.trim() !== '' && value.length <= LONGEST_SHORTCUT;
  return {
    nextWindow: fine(next) ? next.trim() : DEFAULT_WINDOW_KEYS.nextWindow,
    previousWindow: fine(previous) ? previous.trim() : DEFAULT_WINDOW_KEYS.previousWindow,
  };
}

/** The keys that are only ever part of a combination; the interface's MODIFIER_KEYS. */
const MODIFIERS = new Set(['Control', 'Alt', 'Shift', 'Meta', 'OS', 'AltGraph', 'CapsLock', 'Dead']);

/**
 * A key press written as the interface's `describe` writes one - `Ctrl+PageDown`,
 * `Ctrl+Shift+T`, `Alt+Space` - or null for a modifier on its own, so a binding
 * chosen on Preferences is compared as it was recorded there.
 */
function described(input) {
  if (MODIFIERS.has(input.key)) return null;
  const held = [];
  if (input.control) held.push('Ctrl');
  if (input.alt) held.push('Alt');
  if (input.shift) held.push('Shift');
  if (input.meta) held.push('Meta');
  const key = input.key === ' ' ? 'Space' : input.key.length === 1 ? input.key.toUpperCase() : input.key;
  held.push(key);
  return held.join('+');
}

/*
 * The fixed tab keys, as a browser has them on Windows and Linux. Ctrl and not
 * Cmd: the app is built for those two only. Ctrl+Tab and Ctrl+Shift+Tab are
 * here as well as whatever next and previous are bound to, as the other habit.
 */
const KEYS = {
  Tab: (shift) => (shift ? 'previous' : 'next'),
  F4: (shift) => (shift ? null : 'close'),
  t: (shift) => (shift ? null : 'new'),
  w: (shift) => (shift ? null : 'close'),
};

/**
 * What a key press asks of the tabs - `new`, `close`, `next`, `previous`,
 * `next-window`, `previous-window` - or null. Takes Electron's before-input-event input, and the next and previous
 * keys chosen in the interface, which come first: a choice made there wins over
 * a fixed key here.
 *
 * A letter is read off the character typed, and off the physical key only where
 * that character is not a Latin letter: Ctrl+T on a Polish or a French layout
 * means T, and on a Russian one the key that carries T.
 */
function shortcut(input, bound = { ...DEFAULT_TAB_KEYS, ...DEFAULT_WINDOW_KEYS }) {
  if (input.type !== 'keyDown') return null;
  const said = described(input)?.toLowerCase();
  if (said === bound.next.toLowerCase()) return 'next';
  if (said === bound.previous.toLowerCase()) return 'previous';
  if (said !== undefined && said === bound.nextWindow?.toLowerCase()) return 'next-window';
  if (said !== undefined && said === bound.previousWindow?.toLowerCase()) return 'previous-window';
  if (!input.control || input.alt || input.meta) return null;
  let key = input.key;
  if (key.length === 1) {
    key = /^[a-z]$/i.test(key) ? key.toLowerCase() : String(input.code ?? '').replace(/^Key/, '').toLowerCase();
  }
  return KEYS[key]?.(input.shift) ?? null;
}

/** Where stepping `by` from `at` lands among `count` tabs: round from the end to the start, and back. */
function stepped(at, by, count) {
  if (count < 1) return -1;
  return (((at + by) % count) + count) % count;
}

module.exports = { classify, linkMenu, shortcut, tabKeys, windowKeys, stepped, DEFAULT_TAB_KEYS, DEFAULT_WINDOW_KEYS };

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

/*
 * The tab keys, as a browser has them on Windows and Linux. Ctrl and not Cmd:
 * the app is built for those two only.
 */
const KEYS = {
  Tab: (shift) => (shift ? 'previous' : 'next'),
  PageDown: (shift) => (shift ? null : 'next'),
  PageUp: (shift) => (shift ? null : 'previous'),
  F4: (shift) => (shift ? null : 'close'),
  t: (shift) => (shift ? null : 'new'),
  w: (shift) => (shift ? null : 'close'),
};

/**
 * What a key press asks of the tabs - `new`, `close`, `next`, `previous` - or
 * null. Takes Electron's before-input-event input.
 *
 * A letter is read off the character typed, and off the physical key only where
 * that character is not a Latin letter: Ctrl+T on a Polish or a French layout
 * means T, and on a Russian one the key that carries T.
 */
function shortcut(input) {
  if (input.type !== 'keyDown' || !input.control || input.alt || input.meta) return null;
  let key = input.key;
  if (key.length === 1) {
    key = /^[a-z]$/i.test(key) ? key.toLowerCase() : String(input.code ?? '').replace(/^Key/, '').toLowerCase();
  }
  return KEYS[key]?.(input.shift) ?? null;
}

module.exports = { classify, shortcut };

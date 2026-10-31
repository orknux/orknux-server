/*
 * The decisions behind connecting to a server, kept apart from Electron so they
 * can be tested with plain `node --test` (connect.test.js).
 *
 * Whether a client and a server go together is the server's to say, through
 * /api/client/compatibility, so an app already installed follows the rule of the
 * server it reached. The one thing decided here is what to do about a server too
 * old to have been asked.
 */

/** Long enough for a server across the world, short enough that a wrong address does not look like a hang. */
const PROBE_MILLIS = 8_000;

/** The major version every server made before the question existed is. */
const MAJOR_BEFORE_THE_QUESTION = 1;

/**
 * The addresses somebody's words may mean, in the order to try them, or a reason
 * they mean nothing. With no scheme it is https first, and http only if that
 * fails, which is then warned about.
 *
 * @param {string} typed
 * @returns {{tries: string[]}|{wrong: string}}
 */
function addressesOf(typed) {
  const text = String(typed ?? '').trim();
  if (text === '') return { wrong: 'Enter the address of a server.' };
  const hasScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text);
  const originOf = (candidate) => {
    const url = new URL(candidate);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    // A password in an address would be remembered in a file and shown in a window.
    if (url.username !== '' || url.password !== '' || url.hostname === '') return null;
    return `${url.origin}${url.pathname.replace(/\/+$/, '')}`;
  };
  try {
    const tries = hasScheme ? [originOf(text)] : [originOf(`https://${text}`), originOf(`http://${text}`)];
    if (tries.some((one) => one === null)) return { wrong: 'That is not the address of a server: use http:// or https:// and leave out any password.' };
    return { tries };
  } catch {
    return { wrong: 'That is not the address of a server.' };
  }
}

/** Whether what is sent to this address can be read on the way: http to anywhere but this computer. */
function isPlainText(address) {
  const url = new URL(address);
  return url.protocol === 'http:' && !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
}

/**
 * What a server's answers come to.
 *
 * @param {string} client this app's version
 * @param {{status: number, json: object|null}} asked what /api/client/compatibility answered
 * @param {{status: number, json: object|null}|null} method what /api/auth/method answered, where that had to be asked
 * @returns {{ok: true, server: string|null}|{ok: false, why: string}}
 */
function judged(client, asked, method) {
  if (asked.status === 200 && asked.json?.product === 'orknux') {
    if (asked.json.compatible === false) return { ok: false, why: asked.json.reason || 'This server does not work with this version of the app.' };
    return { ok: true, server: asked.json.server ?? null };
  }
  // Older than the question, which asks to sign in for a path it does not know, or not Orknux at all: the sign-in screen's own question tells which.
  if (method?.status === 200 && typeof method.json?.method === 'string') {
    if (Number.parseInt(client, 10) !== MAJOR_BEFORE_THE_QUESTION) {
      return { ok: false, why: `This server is older than version ${MAJOR_BEFORE_THE_QUESTION}.x's last releases, and this app is ${client}. Update the server.` };
    }
    return { ok: true, server: null };
  }
  return { ok: false, why: 'Something answered at that address, but it is not an Orknux server.' };
}

module.exports = { PROBE_MILLIS, addressesOf, isPlainText, judged };

/*
 * The data directory, and what a first start invents in it.
 *
 * This is docker/one/entrypoint.sh, ported: the same files with the same names
 * and the same warnings, so a directory copied out of an orknux-one volume opens
 * here unchanged and one copied the other way opens there. The encryption key is
 * the part to be careful with - generating a second one on a later start makes
 * every stored credential unreadable without failing anything - which is why it
 * is written once, never rewritten, and why its absence beside an existing
 * database is said out loud rather than quietly repaired.
 */
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

/** How a person reaches this installation: from this machine only, or from the network. */
const NETWORK_LOCAL = 'local';
const NETWORK_EXPOSED = 'exposed';

/**
 * The desktop's own settings, from desktop.json beside the database.
 *
 * A file rather than a row, because what it decides - the address the server
 * binds and how anyone signs in - has to be known before the server starts, and
 * the database is the server's. Admin › Settings › Network access writes it,
 * through the server, and the server then asks to be started again so it is
 * read anew; anything unreadable is read as the default, which is the safe
 * one: this machine only. `account` is who signs in once exposed, named by
 * that switch, which set their password - so no password is generated.
 */
function readSettings(data) {
  const file = path.join(data, 'desktop.json');
  try {
    const held = JSON.parse(fs.readFileSync(file, 'utf8'));
    const account = typeof held.account === 'string' && held.account.trim() !== '' ? held.account.trim() : null;
    return { network: held.network === NETWORK_EXPOSED ? NETWORK_EXPOSED : NETWORK_LOCAL, account };
  } catch {
    return { network: NETWORK_LOCAL, account: null };
  }
}

/** A file only its owner can read, written once. On Windows the mode is advisory; the directory under the user's profile is what keeps it theirs. */
function writePrivate(file, text) {
  fs.writeFileSync(file, text, { mode: 0o600, flag: 'wx' });
}

/**
 * Everything the server needs to find on disk, made where missing.
 *
 * Answers the paths and the decisions taken, and every sentence worth putting in
 * the log - the caller decides where they go, because the first start's admin
 * password is something a person has to be shown, not something written to a
 * file they will never open.
 */
function prepare(data, settings) {
  const said = [];
  const loud = [];
  fs.mkdirSync(data, { recursive: true });
  const database = path.join(data, 'orknux.db');
  const attachments = path.join(data, 'attachments');
  const releases = path.join(data, 'releases');
  const temp = path.join(data, 'tmp');
  [attachments, releases, temp, path.join(data, 'logs')].forEach((one) => fs.mkdirSync(one, { recursive: true }));

  const existing = fs.existsSync(database) && fs.statSync(database).size > 0;

  let secretKey = process.env.ORKNUX_SECRET_KEY || null;
  const keyFile = path.join(data, 'secret.key');
  if (secretKey !== null) {
    said.push('Using the encryption key from ORKNUX_SECRET_KEY.');
  } else if (fs.existsSync(keyFile)) {
    secretKey = fs.readFileSync(keyFile, 'utf8').trim();
    said.push(`Using the encryption key kept at ${keyFile}.`);
  } else {
    if (existing) {
      loud.push(
        `There is a database at ${database} but no ${keyFile}. A new key is being generated, and every credential ` +
          'stored with the old one is unreadable from now on. They are not corrupted and they are not recoverable: ' +
          'they have to be entered again, by hand. Admin -> Doctor lists which. If the old key still exists somewhere, ' +
          `quit Orknux, put it back at ${keyFile}, and start again before anybody saves anything.`,
      );
    }
    secretKey = crypto.randomBytes(32).toString('base64');
    writePrivate(keyFile, secretKey);
    said.push(`Generated an encryption key and kept it at ${keyFile}. It belongs with the database; back the two up together.`);
  }

  /*
   * Who signs in. On this machine only, nobody does: the server listens on the
   * loopback address alone, so the only person who can reach it is the one
   * sitting here, and a password would be one more thing for them to lose.
   * Exposed, everybody does, against accounts this installation holds - and the
   * first time it is exposed there has to be one, which is the entrypoint's
   * generated administrator, shown once.
   */
  let authMethod = 'NONE';
  let admin = null;
  if (settings.network === NETWORK_EXPOSED) {
    authMethod = 'INTERNAL';
    const adminFile = path.join(data, 'admin-password');
    const seeded = path.join(data, '.admin-seeded');
    if (settings.account) {
      // Set from Admin › Settings, with a password chosen there: nothing to generate or show.
      said.push(`Open to the network; ${settings.account} signs in with the password set in Network access.`);
    } else if (process.env.ORKNUX_BOOTSTRAP_ADMIN_PASSWORD) {
      admin = { username: process.env.ORKNUX_BOOTSTRAP_ADMIN_USERNAME || 'admin', password: process.env.ORKNUX_BOOTSTRAP_ADMIN_PASSWORD, first: false };
    } else {
      let first = false;
      if (!fs.existsSync(adminFile) && !fs.existsSync(seeded)) {
        const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
        const password = Array.from(crypto.randomBytes(24), (byte) => alphabet[byte % alphabet.length]).join('');
        writePrivate(adminFile, password);
        // Remembered, so deleting the file once the password is changed does not make a new one next start.
        fs.writeFileSync(seeded, '');
        first = true;
      }
      if (fs.existsSync(adminFile)) {
        admin = { username: 'admin', password: fs.readFileSync(adminFile, 'utf8').trim(), first };
        if (!first) said.push(`The administrator password from the first exposed start is still at ${adminFile}. Delete it once it has been changed.`);
      } else {
        said.push(`No bootstrap administrator: ${adminFile} is gone.`);
      }
    }
  }

  return { database, attachments, releases, temp, secretKey, authMethod, admin, existing, said, loud };
}

module.exports = { NETWORK_LOCAL, NETWORK_EXPOSED, readSettings, prepare };

/*
 * A newer package of this app: found, downloaded, and its installer run - the
 * way a graphics driver's own manager does it, from the Updates page rather
 * than from a browser and a downloads folder.
 *
 * Not the server's self-update, which replaces the jar this app runs and
 * leaves the app as it was. This replaces the app, runtime and shipped jar
 * included, with the next release's package - what somebody who installed
 * from the release page would otherwise fetch by hand.
 *
 * electron-updater does the work (AGENTS.md: never hand-roll what a library
 * does): it reads the update file orknux.ai serves for the newest release, fetches
 * the installer that file names, checks its SHA-512 against it, and on Windows
 * runs the NSIS installer silently and starts the app again after. Nothing
 * here chooses an address: the feed, https://orknux.ai/desktop, is fixed in the
 * package when it is built (electron-builder.yml), so a page asking for an update can only ever get the newest release
 * of this app, never something it names.
 *
 * What may be updated this way is decided here, not by the updater, which
 * would try regardless:
 *
 *   a store build      never - the store updates it, and an app replacing its
 *                      own code underneath one is what they refuse (packageUpdate)
 *   an MSI install     never - electron-updater only runs the NSIS installer,
 *                      and running it over an MSI install leaves two copies
 *   not packaged       never - there is no installer to replace a checkout
 *
 * No Electron in this file, so `node --test` drives it with a stand-in updater.
 */
const fs = require('node:fs');
const path = require('node:path');

/**
 * How this copy was installed, which decides whether it can be updated here.
 *
 * NSIS leaves its uninstaller beside the executable and an MSI install does
 * not; an AppImage is told by the variable its runtime sets, and a .deb by the
 * `package-type` file electron-builder writes into the resources.
 */
function installKind({ platform, packaged, execPath, resourcesPath, productName, env }) {
  if (!packaged) return 'development';
  if (platform === 'win32') {
    return fs.existsSync(path.join(path.dirname(execPath), `Uninstall ${productName}.exe`)) ? 'nsis' : 'msi';
  }
  if (platform === 'linux') {
    if ((env.APPIMAGE ?? '') !== '') return 'appimage';
    try {
      const said = fs.readFileSync(path.join(resourcesPath, 'package-type'), 'utf8').trim();
      if (said === 'deb') return 'deb';
    } catch {
      // No such file: a package type electron-updater cannot update either.
    }
    return 'other';
  }
  return 'other';
}

/** Why this copy cannot update itself, in the words the page shows; null where it can. */
function refusalFor(kind, packageUpdate) {
  if (!packageUpdate) return 'This copy of Orknux is updated by the store or the distribution it came from.';
  if (kind === 'development') return 'This Orknux runs from a checkout, not an installed package.';
  if (kind === 'msi') return 'This Orknux was installed from the MSI, which cannot update itself: install the newer MSI from orknux.ai.';
  if (kind === 'other') return 'This kind of installation cannot update itself: install the newer package from orknux.ai.';
  return null;
}

/** Where a person gets a package by hand, which the refusals point at. */
const RELEASES = 'https://orknux.ai';

/**
 * Where an update stands, as one plain object a page can be handed whole.
 *
 * phase: idle, checking, current (nothing newer), none (orknux.ai offers no
 * package for this app yet), available, downloading,
 * installing (downloaded and handed to the installer), failed, or unavailable
 * (this copy cannot update itself; `reason` says why).
 */
class PackageUpdate {
  /**
   * @param {object} given
   * @param {object|null} given.updater electron-updater's autoUpdater, or null where it is not to be loaded
   * @param {string} given.current this app's version
   * @param {string|null} given.refusal why this copy cannot update itself, or null
   * @param {boolean} given.client whether this is Orknux Client, whose packages are a channel of their own
   * @param {() => Promise<void>} given.beforeInstall stops what holds the install's files - the server - before the installer runs
   * @param {(line: string) => void} given.note the app's log
   * @param {(fn: () => void, ms: number) => void} [given.later] a timer, so a test need not wait
   */
  constructor({ updater, current, refusal, client, beforeInstall, note, later = setTimeout }) {
    this.updater = updater;
    this.beforeInstall = beforeInstall;
    this.note = note;
    this.later = later;
    this.listeners = new Set();
    this.state = refusal === null
      ? { phase: 'idle', current, version: null, notes: null, releasedAt: null, percent: 0, transferred: 0, total: 0, bytesPerSecond: 0, error: null, reason: null }
      : { phase: 'unavailable', current, version: null, notes: null, releasedAt: null, percent: 0, transferred: 0, total: 0, bytesPerSecond: 0, error: null, reason: refusal, releases: RELEASES };
    if (refusal !== null || updater === null) return;

    updater.autoDownload = false;
    updater.autoInstallOnAppQuit = false;
    updater.logger = { info: () => {}, debug: () => {}, warn: (line) => note(`package update: ${line}`), error: (line) => note(`package update: ${line}`) };
    if (client) {
      // The client's update files are client.yml and client-linux.yml beside the server app's latest.yml.
      updater.channel = 'client';
    }
    // Setting a channel allows going back a version as well; an update here only goes forward.
    updater.allowDowngrade = false;
    updater.allowPrerelease = false;

    updater.on('update-available', (info) => this.set({
      phase: 'available', version: info.version, notes: notesOf(info.releaseNotes), releasedAt: info.releaseDate ?? null, error: null,
    }));
    updater.on('update-not-available', (info) => this.set({ phase: 'current', version: info?.version ?? null, error: null }));
    updater.on('download-progress', (progress) => this.set({
      phase: 'downloading',
      percent: progress.percent,
      transferred: progress.transferred,
      total: progress.total,
      bytesPerSecond: progress.bytesPerSecond,
    }));
    updater.on('update-downloaded', () => this.handOver());
    updater.on('error', (failure) => {
      // Once handed over the app is on its way out; a late error has nobody to tell.
      if (this.state.phase === 'installing') return;
      // Nothing offered yet is a state, not a failure: no release has put a package on orknux.ai.
      if (nothingOffered(failure)) {
        this.set({ phase: 'none', error: null });
        return;
      }
      this.set({ phase: 'failed', error: sentenceOf(failure) });
    });
  }

  /** Called with the state on every change. */
  onChange(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  set(changes) {
    this.state = { ...this.state, ...changes };
    // One listener failing - a page that went away - must not stop the update or the others hearing of it.
    this.listeners.forEach((listener) => {
      try {
        listener(this.state);
      } catch (failure) {
        this.note(`package update: telling a page failed: ${failure.message}`);
      }
    });
  }

  /** Asks the release feed whether there is a newer package. Nothing is downloaded. */
  check() {
    if (!this.ready() || ['checking', 'downloading', 'installing'].includes(this.state.phase)) return;
    this.set({ phase: 'checking', error: null });
    this.updater.checkForUpdates().catch((failure) => {
      if (nothingOffered(failure)) this.set({ phase: 'none', error: null });
      else this.set({ phase: 'failed', error: sentenceOf(failure) });
    });
  }

  /**
   * Downloads the newer package and, once it is whole and checked, runs its
   * installer: one press, as asked - "download and run it".
   */
  install() {
    if (!this.ready() || this.state.phase !== 'available') return;
    this.note(`package update: downloading ${this.state.version}`);
    this.set({ phase: 'downloading', percent: 0, transferred: 0, total: 0, bytesPerSecond: 0, error: null });
    this.updater.downloadUpdate().catch((failure) => this.set({ phase: 'failed', error: sentenceOf(failure) }));
  }

  /**
   * The installer's turn. The server is stopped first and waited for, so the
   * installer finds no Java holding the files it replaces and the database is
   * closed by the process that opened it; then the installer runs silently and
   * starts the app again when it is done. A moment between saying so and
   * leaving, so the page shows it.
   */
  handOver() {
    if (this.state.phase === 'installing') return;
    this.note(`package update: ${this.state.version} downloaded; stopping the server and running its installer`);
    this.set({ phase: 'installing', percent: 100, error: null });
    this.later(() => {
      this.beforeInstall()
        .catch((failure) => this.note(`package update: the server did not stop cleanly: ${failure.message}`))
        .then(() => this.updater.quitAndInstall(true, true));
    }, 1500);
  }

  ready() {
    return this.state.phase !== 'unavailable' && this.updater !== null;
  }
}

/** Release notes as one piece of text, whichever shape the feed gave them in. */
function notesOf(given) {
  if (typeof given === 'string') return given;
  if (Array.isArray(given)) return given.map((one) => one?.note ?? '').filter((one) => one !== '').join('\n\n');
  return null;
}

/** Whether a failure only means orknux.ai has no update file for this app yet. */
function nothingOffered(failure) {
  return /Cannot find (latest|client)(-linux)?\.yml|404/.test(String(failure?.message ?? failure ?? ''));
}

/**
 * A failure as a sentence a person can act on.
 *
 * The one worth translating is the release that has no update file: the desktop
 * packages are attached after the images, so for a while - or for good, where
 * their job failed - the newest release has none.
 */
function sentenceOf(failure) {
  const message = String(failure?.message ?? failure ?? 'it failed, and said nothing');
  if (/Cannot find (latest|client)(-linux)?\.yml|404/.test(message)) {
    return 'orknux.ai offers no desktop package yet; try again later.';
  }
  if (/ENOTFOUND|EAI_AGAIN|ECONNREFUSED|ETIMEDOUT|net::ERR_/.test(message)) {
    return `orknux.ai could not be reached: ${message.split('\n')[0]}`;
  }
  if (/sha512 checksum mismatch/i.test(message)) {
    return 'The downloaded installer did not match its checksum, so it was not run.';
  }
  return message.split('\n')[0];
}

module.exports = { PackageUpdate, installKind, refusalFor, sentenceOf, RELEASES };

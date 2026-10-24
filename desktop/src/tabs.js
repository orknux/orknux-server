/*
 * Tabs in the one window: a strip along the top, and a page per tab under it.
 *
 * The window's own page is the strip (tabs.html, drawn by tabs-preload.js); each
 * tab is a WebContentsView laid over the rest of the window, and only the
 * active one is visible. What a tab may load, and where its links go, is
 * main.js's - this file only keeps the tabs.
 */
const path = require('node:path');
const { WebContentsView } = require('electron');

/**
 * The strip's height in pixels. A layout choice rather than a behaviour, and
 * said once: tabs.html is handed it, so the strip and the pages under it
 * cannot disagree about where one ends.
 */
const STRIP_HEIGHT = 36;

class Tabs {
  /**
   * @param {import('electron').BrowserWindow} win a window whose own page is
   *   left to this class - created with tabs-preload.js as its preload
   * @param {object} given
   * @param {object} given.webPreferences what every tab's page runs with
   * @param {(tab: Tab) => void} given.prepare called with each tab before it
   *   loads anything, to attach its link handling
   * @param {() => string|null} given.home what a new tab opens, null while
   *   there is nothing to open
   */
  constructor(win, { webPreferences, prepare, home }) {
    this.win = win;
    this.webPreferences = webPreferences;
    this.prepare = prepare;
    this.home = home;
    /** @type {Tab[]} in the order the strip shows them */
    this.list = [];
    this.active = null;
    this.serial = 0;

    win.loadFile(path.join(__dirname, 'tabs.html'), { query: { height: String(STRIP_HEIGHT) } });
    // The strip's own title is not the window's: main.js sets that.
    win.webContents.on('page-title-updated', (event) => event.preventDefault());
    win.webContents.ipc.on('tabs:ready', () => this.draw());
    win.webContents.ipc.on('tabs:select', (_event, id) => this.select(this.find(id)));
    win.webContents.ipc.on('tabs:close', (_event, id) => this.close(this.find(id)));
    win.webContents.ipc.on('tabs:new', () => this.openHome());
    for (const event of ['resize', 'maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen']) {
      win.on(event, () => this.layout());
    }
    // A WebContentsView's page outlives its window unless it is closed.
    win.on('closed', () => [...this.list].forEach((tab) => tab.contents.isDestroyed() || tab.contents.close()));
  }

  find(id) {
    return this.list.find((tab) => tab.id === id) ?? null;
  }

  /** The tab whose page this is, if it is one. */
  of(contents) {
    return this.list.find((tab) => tab.contents === contents) ?? null;
  }

  /**
   * Opens a tab, right of `after` where it is given - a link's tab beside the
   * page it was on, as a browser puts it.
   *
   * @param {{url?: string, file?: string, activate?: boolean, after?: Tab|null}} what
   */
  open({ url, file, query, activate = true, after = null }) {
    const view = new WebContentsView({ webPreferences: this.webPreferences });
    view.setBackgroundColor('#09090b');
    /** @type {Tab} */
    const tab = { id: ++this.serial, view, contents: view.webContents, title: 'Orknux', committed: false };
    const at = after === null ? -1 : this.list.indexOf(after);
    this.list.splice(at === -1 ? this.list.length : at + 1, 0, tab);

    const contents = tab.contents;
    contents.on('page-title-updated', (_event, title) => {
      tab.title = title || 'Orknux';
      this.draw();
    });
    // A tab that never showed a page is one a link sent elsewhere or turned into a download; see main.js.
    contents.on('did-navigate', () => {
      tab.committed = true;
    });
    contents.on('destroyed', () => this.forget(tab));

    this.prepare(tab);
    view.setVisible(false);
    this.win.contentView.addChildView(view);
    if (activate || this.active === null) this.select(tab);
    else this.draw();
    this.layout();
    if (file) contents.loadFile(file, query === undefined ? undefined : { query });
    else contents.loadURL(url);
    return tab;
  }

  /** A new tab on the home page; nothing while the server is not yet there to show one. */
  openHome() {
    const url = this.home();
    if (url !== null) this.open({ url });
  }

  select(tab) {
    if (tab === null) return;
    if (this.active !== null && this.active !== tab) this.active.view.setVisible(false);
    this.active = tab;
    tab.view.setVisible(true);
    tab.contents.focus();
    this.draw();
  }

  /** Ctrl+Tab and Ctrl+Shift+Tab: the next or the previous tab, round from the end to the start. */
  step(by) {
    if (this.list.length < 2) return;
    const at = this.list.indexOf(this.active);
    this.select(this.list[(at + by + this.list.length) % this.list.length]);
  }

  /**
   * Closes a tab, once its page lets it go: one with unsaved work asks first
   * (will-prevent-unload, in main.js), and the tab goes when its page is
   * destroyed. The last one closes the window, which main.js keeps in the tray.
   */
  close(tab) {
    if (tab === null || !this.list.includes(tab)) return;
    if (tab.contents.isDestroyed()) this.forget(tab);
    else tab.contents.close({ waitForBeforeUnload: true });
  }

  forget(tab) {
    const at = this.list.indexOf(tab);
    if (at === -1) return;
    this.list.splice(at, 1);
    if (!this.win.isDestroyed()) this.win.contentView.removeChildView(tab.view);
    if (this.active === tab) {
      this.active = null;
      // The neighbour to the right, as a browser does, or the left one at the end.
      const next = this.list[Math.min(at, this.list.length - 1)];
      if (next) this.select(next);
    }
    this.draw();
    if (this.list.length === 0 && !this.win.isDestroyed()) this.win.close();
  }

  /** Every tab's page fills the window under the strip; only the active one is visible. */
  layout() {
    if (this.win.isDestroyed()) return;
    const { width, height } = this.win.getContentBounds();
    const bounds = { x: 0, y: STRIP_HEIGHT, width, height: Math.max(0, height - STRIP_HEIGHT) };
    this.list.forEach((tab) => tab.view.setBounds(bounds));
  }

  /** Hands the strip what it draws: a title per tab and which is active. */
  draw() {
    if (this.win.isDestroyed()) return;
    this.win.webContents.send('tabs', this.list.map((tab) => ({ id: tab.id, title: tab.title, active: tab === this.active })));
  }
}

/**
 * @typedef {object} Tab
 * @property {number} id
 * @property {WebContentsView} view
 * @property {import('electron').WebContents} contents
 * @property {string} title the page's own title, which is what the strip shows
 * @property {boolean} committed whether it has shown a page yet
 */

module.exports = { Tabs };

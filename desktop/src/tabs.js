/*
 * Tabs in a window, in one pane or several: a strip along the top of each pane,
 * and a page per tab under it. How the panes are cut is split.js's.
 *
 * The window's own page draws the strips (tabs.html, by tabs-preload.js); each
 * tab is a WebContentsView laid over its pane, and only each pane's active one is
 * visible. What a tab may load, and where its links go, is main.js's - this file
 * only keeps the tabs.
 *
 * A drag has to cross the pages, and a page takes the pointer from whatever is
 * under it. So for as long as one lasts a transparent view (drag-overlay.html)
 * lies over all of them: it is what is dragged over, what is dropped on, and what
 * lights up where the tab would go. The drag is the browser's own, which is also
 * what tells the strip it ended when it ended outside the window.
 */
const path = require('node:path');
const { WebContentsView } = require('electron');
const { stepped } = require('./links');
const { Layout, dropAt } = require('./split');

/**
 * A strip's height in pixels. A layout choice rather than a behaviour, and said
 * once: tabs.html is handed it, so a strip and the page under it cannot disagree
 * about where one ends.
 */
const STRIP_HEIGHT = 36;
/**
 * The narrowest the interface is drawn for, as the window's own minimum in
 * main.js. Below it its bars and tables run into each other, so a narrower pane
 * shows the same layout smaller, which is also what keeps every control reachable.
 */
const PAGE_WIDTH = 900;

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
    /** @type {Tab[]} every tab of the window; which pane holds which, and in what order, is the layout's */
    this.list = [];
    /** The tab being worked in: of the panes' active tabs, the one with the focus. */
    this.active = null;
    this.serial = 0;
    this.layoutTree = new Layout();
    /** What is being dragged: `{ tab: id }` or `{ divider: id }`, null the rest of the time. */
    this.dragging = null;

    win.loadFile(path.join(__dirname, 'tabs.html'), { query: { height: String(STRIP_HEIGHT) } });
    // The strip's own title is not the window's: main.js sets that.
    win.webContents.on('page-title-updated', (event) => event.preventDefault());
    const said = win.webContents.ipc;
    said.on('tabs:ready', () => this.draw());
    said.on('tabs:select', (_event, id) => this.select(this.find(id)));
    said.on('tabs:close', (_event, id) => this.close(this.find(id)));
    said.on('tabs:new', (_event, paneId) => this.openHome(paneId));
    said.on('tabs:drag-start', (_event, what) => this.dragStart(what));
    said.on('tabs:drag-end', () => this.dragEnd());
    said.on('tabs:even', (_event, id) => {
      this.layoutTree.even(id);
      this.layout();
    });

    this.overlay = new WebContentsView({
      webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, preload: path.join(__dirname, 'drag-overlay-preload.js') },
    });
    this.overlay.setBackgroundColor('#00000000');
    this.overlay.setVisible(false);
    win.contentView.addChildView(this.overlay);
    this.overlay.webContents.loadFile(path.join(__dirname, 'drag-overlay.html'));
    const over = this.overlay.webContents.ipc;
    over.on('overlay:over', (_event, x, y) => this.dragOver(x, y));
    over.on('overlay:drop', (_event, x, y) => this.drop(x, y));
    over.on('overlay:end', () => this.dragEnd());

    for (const event of ['resize', 'maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen']) {
      win.on(event, () => this.layout());
    }
    // A WebContentsView's page outlives its window unless it is closed.
    win.on('closed', () => {
      [...this.list].forEach((tab) => tab.contents.isDestroyed() || tab.contents.close());
      if (!this.overlay.webContents.isDestroyed()) this.overlay.webContents.close();
    });
  }

  find(id) {
    return this.list.find((tab) => tab.id === id) ?? null;
  }

  /** The tab whose page this is, if it is one. */
  of(contents) {
    return this.list.find((tab) => tab.contents === contents) ?? null;
  }

  /** The pane a new tab goes into: the one being worked in. */
  focusedPane() {
    return (this.active !== null ? this.layoutTree.paneOf(this.active.id) : null) ?? this.layoutTree.panes()[0];
  }

  /**
   * Opens a tab, right of `after` and in its pane where it is given - a link's
   * tab beside the page it was on, as a browser puts it - or last in `pane`,
   * or in the pane being worked in.
   *
   * @param {{url?: string, file?: string, activate?: boolean, after?: Tab|null, pane?: string}} what
   */
  open({ url, file, query, activate = true, after = null, pane = null }) {
    const view = new WebContentsView({ webPreferences: this.webPreferences });
    view.setBackgroundColor('#09090b');
    /** @type {Tab} */
    const tab = { id: ++this.serial, view, contents: view.webContents, title: 'Orknux', committed: false };
    this.list.push(tab);
    const beside = after === null ? null : this.layoutTree.paneOf(after.id);
    const into = beside ?? (pane === null ? null : this.layoutTree.pane(pane)) ?? this.focusedPane();
    this.layoutTree.add(tab.id, into.id, beside === null ? null : after.id);

    const contents = tab.contents;
    contents.on('page-title-updated', (_event, title) => {
      tab.title = title || 'Orknux';
      this.draw();
    });
    // A tab that never showed a page is one a link sent elsewhere or turned into a download; see main.js.
    contents.on('did-navigate', () => {
      tab.committed = true;
      // A page in a new process starts without what fit() asked of the old one.
      tab.fitted = undefined;
      this.layout();
    });
    // A press in another pane's page moves the work there, without anything to click in the strip.
    contents.on('focus', () => {
      if (this.active === tab || !this.list.includes(tab)) return;
      this.active = tab;
      this.draw();
    });
    contents.on('destroyed', () => this.forget(tab));

    this.prepare(tab);
    view.setVisible(false);
    this.win.contentView.addChildView(view);
    // The overlay stays on top of whatever opens while a tab is being dragged.
    this.win.contentView.addChildView(this.overlay);
    if (activate || this.active === null) this.select(tab);
    else this.refresh();
    if (file) contents.loadFile(file, query === undefined ? undefined : { query });
    else contents.loadURL(url);
    return tab;
  }

  /** A new tab on the home page; nothing while the server is not yet there to show one. */
  openHome(pane = null) {
    const url = this.home();
    if (url !== null) this.open({ url, pane });
  }

  select(tab) {
    if (tab === null) return;
    const pane = this.layoutTree.paneOf(tab.id);
    if (pane === null) return;
    pane.active = tab.id;
    this.active = tab;
    this.refresh();
    tab.contents.focus();
  }

  /** The next or the previous tab of the pane being worked in (links.js says which keys), round from the end to the start and back. */
  step(by) {
    if (this.active === null) return;
    const pane = this.layoutTree.paneOf(this.active.id);
    if (pane === null || pane.tabs.length < 2) return;
    this.select(this.find(pane.tabs[stepped(pane.tabs.indexOf(this.active.id), by, pane.tabs.length)]));
  }

  /** The tab each pane is showing, in reading order: where a step between panes stops. */
  workingPlaces() {
    return this.layoutTree.panes().map((pane) => this.find(pane.active)).filter((tab) => tab !== null);
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
    const pane = this.layoutTree.paneOf(tab.id);
    const panes = this.layoutTree.panes();
    const place = pane === null ? -1 : panes.indexOf(pane);
    this.layoutTree.remove(tab.id);
    if (!this.win.isDestroyed()) this.win.contentView.removeChildView(tab.view);
    if (this.active === tab) {
      this.active = null;
      // The pane's next tab; where the pane has gone with it, the pane that took its place.
      const left = this.layoutTree.panes();
      const nextPane = pane !== null && pane.active !== null ? pane : left[Math.min(Math.max(place, 0), left.length - 1)];
      const next = nextPane.active === null ? null : this.find(nextPane.active);
      if (next !== null) this.select(next);
    }
    this.refresh();
    if (this.list.length === 0 && !this.win.isDestroyed()) this.win.close();
  }

  arranged() {
    const { width, height } = this.win.getContentBounds();
    return this.layoutTree.arrange(width, height);
  }

  /** What everything drawn hangs on, after anything that changes which tab is where. */
  refresh() {
    this.layout();
    this.draw();
  }

  /** Each pane's tabs fill it under its strip; only its active one is visible. */
  layout() {
    if (this.win.isDestroyed()) return;
    const arranged = this.arranged();
    for (const rect of arranged.panes) {
      const pane = this.layoutTree.pane(rect.id);
      const bounds = { x: rect.x, y: rect.y + STRIP_HEIGHT, width: rect.width, height: Math.max(0, rect.height - STRIP_HEIGHT) };
      for (const id of pane.tabs) {
        const tab = this.find(id);
        if (tab === null) continue;
        tab.view.setBounds(bounds);
        tab.view.setVisible(id === pane.active);
        this.fit(tab, bounds);
      }
    }
    if (this.dragging !== null) this.coverWindow();
    this.draw();
  }

  /**
   * Draws a page in a pane narrower than it needs at its full width, smaller.
   * Emulation and not setZoomFactor: Chromium keeps zoom per site, and every tab
   * here is one site, so one narrow pane shrank all of them.
   */
  fit(tab, { width, height }) {
    // Before its first page there is no renderer to emulate on, and asking crashes the app; did-navigate comes back here.
    if (!tab.committed) return;
    const scale = width > 0 && width < PAGE_WIDTH ? width / PAGE_WIDTH : 1;
    const wanted = scale === 1 ? null : `${width}x${height}`;
    if (wanted === tab.fitted) return;
    tab.fitted = wanted;
    if (wanted === null) {
      tab.contents.disableDeviceEmulation();
      return;
    }
    const viewSize = { width: PAGE_WIDTH, height: Math.round(height / scale) };
    tab.contents.enableDeviceEmulation({
      screenPosition: 'desktop', screenSize: viewSize, viewPosition: { x: 0, y: 0 }, deviceScaleFactor: 0, viewSize, scale,
    });
  }

  /** Hands the page what it draws: each pane's place and tabs, and the spaces between the panes. */
  draw() {
    if (this.win.isDestroyed()) return;
    const { panes, dividers } = this.arranged();
    this.win.webContents.send('tabs', {
      panes: panes.map((rect) => {
        const pane = this.layoutTree.pane(rect.id);
        return {
          ...rect,
          working: this.active !== null && pane.tabs.includes(this.active.id),
          tabs: pane.tabs.map((id) => this.find(id)).filter((tab) => tab !== null)
            .map((tab) => ({ id: tab.id, title: tab.title, active: tab.id === pane.active })),
        };
      }),
      dividers: dividers.map(({ id, direction, x, y, width, height }) => ({ id, direction, x, y, width, height })),
    });
  }

  coverWindow() {
    const { width, height } = this.win.getContentBounds();
    this.overlay.setBounds({ x: 0, y: 0, width, height });
  }

  /** The strip says a drag began: the overlay goes over the pages, in time for the pointer to leave the strip. */
  dragStart(what) {
    this.dragging = what;
    this.coverWindow();
    this.win.contentView.addChildView(this.overlay);
    this.overlay.setVisible(true);
  }

  dragOver(x, y) {
    if (this.dragging === null) return;
    if (this.dragging.divider !== undefined) {
      this.layoutTree.resize(this.dragging.divider, this.arranged(), x, y);
      this.layout();
      return;
    }
    const drop = dropAt(this.layoutTree, this.arranged(), this.dragging.tab, x, y, STRIP_HEIGHT);
    this.overlay.webContents.send('overlay:light', drop === null ? null : drop.rect);
  }

  drop(x, y) {
    const dragged = this.dragging;
    this.dragEnd();
    if (dragged === null || dragged.tab === undefined) return;
    const tab = this.find(dragged.tab);
    const drop = dropAt(this.layoutTree, this.arranged(), dragged.tab, x, y, STRIP_HEIGHT);
    if (tab === null || drop === null) return;
    this.layoutTree.move(tab.id, drop.pane, drop.zone);
    this.select(tab);
  }

  dragEnd() {
    if (this.dragging === null) return;
    this.dragging = null;
    this.overlay.setVisible(false);
    this.overlay.webContents.send('overlay:light', null);
    if (this.active !== null) this.active.contents.focus();
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

module.exports = { Tabs, STRIP_HEIGHT };

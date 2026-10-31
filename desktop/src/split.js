/*
 * How a window is divided: a tree whose leaves are panes, each holding tabs, and
 * whose branches cut their space in two, side by side or one above the other.
 * Any arrangement of panes a drag can make is a tree of those two cuts.
 *
 * Only numbers and ids in here, no Electron, so `node --test` can cover it: the
 * views and the strip are tabs.js's.
 */

/**
 * The space between two panes, which is also what is pressed to resize them. It
 * cannot reach over the pages beside it, so it has to be wide enough to find.
 */
const GAP = 8;
/**
 * The smallest a pane is cut to. Small, on purpose: a limit near half the
 * interface's 900 stopped an ordinary window at two columns, with the edges of
 * a pane offering nothing and no word why. A pane under 900 is drawn smaller
 * (tabs.js), and somebody who wants four small panes can have them.
 */
const MIN_WIDTH = 320;
const MIN_HEIGHT = 200;
/** How near a pane's edge, as a share of its size, a drop has to be to split it rather than join it. */
const EDGE = 0.25;

class Layout {
  constructor() {
    this.serial = 0;
    this.root = this.newPane();
  }

  newPane() {
    return { kind: 'pane', id: `pane-${++this.serial}`, tabs: [], active: null };
  }

  /** Every pane, in reading order. */
  panes(node = this.root) {
    return node.kind === 'pane' ? [node] : [...this.panes(node.first), ...this.panes(node.second)];
  }

  pane(id) {
    return this.panes().find((pane) => pane.id === id) ?? null;
  }

  paneOf(tabId) {
    return this.panes().find((pane) => pane.tabs.includes(tabId)) ?? null;
  }

  /** Puts a tab in a pane, right after `after` where that is given, or last. */
  add(tabId, paneId, after = null) {
    const pane = this.pane(paneId);
    const at = after === null ? -1 : pane.tabs.indexOf(after);
    pane.tabs.splice(at === -1 ? pane.tabs.length : at + 1, 0, tabId);
    if (pane.active === null) pane.active = tabId;
  }

  /**
   * Takes a tab out of its pane; a pane left empty goes, and what it shared its
   * space with takes all of it. The neighbour to the right becomes active, as a
   * browser does, or the left one at the end.
   */
  remove(tabId) {
    const pane = this.paneOf(tabId);
    if (pane === null) return;
    const at = pane.tabs.indexOf(tabId);
    pane.tabs.splice(at, 1);
    if (pane.active === tabId) pane.active = pane.tabs[Math.min(at, pane.tabs.length - 1)] ?? null;
    if (pane.tabs.length === 0) this.collapse(pane);
  }

  /** The window always has one pane, even empty, for the next tab to go into. */
  collapse(pane) {
    if (pane === this.root) return;
    const parent = this.parentOf(pane);
    const sibling = parent.first === pane ? parent.second : parent.first;
    this.replace(parent, sibling);
  }

  parentOf(target, node = this.root) {
    if (node.kind === 'pane') return null;
    if (node.first === target || node.second === target) return node;
    return this.parentOf(target, node.first) ?? this.parentOf(target, node.second);
  }

  replace(node, by) {
    if (node === this.root) {
      this.root = by;
      return;
    }
    const parent = this.parentOf(node);
    if (parent.first === node) parent.first = by;
    else parent.second = by;
  }

  /**
   * Moves a tab into a pane (`center`) or into a new pane cut from one edge of
   * it (`left`, `right`, `top`, `bottom`). The new place is made before the old
   * one is emptied: moving a pane's tab to that same pane's edge leans on it.
   */
  move(tabId, paneId, zone) {
    const target = this.pane(paneId);
    let into = target;
    if (zone !== 'center') {
      into = this.newPane();
      const before = zone === 'left' || zone === 'top';
      const split = {
        kind: 'split',
        id: `split-${++this.serial}`,
        direction: zone === 'left' || zone === 'right' ? 'row' : 'column',
        ratio: 0.5,
        first: before ? into : target,
        second: before ? target : into,
      };
      this.replace(target, split);
    }
    const from = this.paneOf(tabId);
    if (from === into) return;
    // Taken out first, so `remove` cannot collapse a pane the tab is on its way into.
    this.remove(tabId);
    into.tabs.push(tabId);
    into.active = tabId;
  }

  /** Where everything goes in a window this size: a rectangle per pane, and one per space between two. */
  arrange(width, height) {
    const panes = [];
    const dividers = [];
    const walk = (node, x, y, w, h) => {
      if (node.kind === 'pane') {
        panes.push({ id: node.id, x, y, width: w, height: h });
        return;
      }
      const within = { x, y, width: w, height: h };
      if (node.direction === 'row') {
        const left = Math.max(0, Math.round((w - GAP) * node.ratio));
        dividers.push({ id: node.id, direction: 'row', x: x + left, y, width: GAP, height: h, within });
        walk(node.first, x, y, left, h);
        walk(node.second, x + left + GAP, y, Math.max(0, w - left - GAP), h);
      } else {
        const top = Math.max(0, Math.round((h - GAP) * node.ratio));
        dividers.push({ id: node.id, direction: 'column', x, y: y + top, width: w, height: GAP, within });
        walk(node.first, x, y, w, top);
        walk(node.second, x, y + top + GAP, w, Math.max(0, h - top - GAP));
      }
    };
    walk(this.root, 0, 0, width, height);
    return { panes, dividers };
  }

  /** Moves the space between two panes to a point, no nearer either end than a pane may be cut. */
  resize(splitId, arranged, x, y) {
    const divider = arranged.dividers.find((one) => one.id === splitId);
    const split = divider && this.splitById(splitId);
    if (!split) return;
    const { within } = divider;
    const row = split.direction === 'row';
    const room = (row ? within.width : within.height) - GAP;
    const least = row ? MIN_WIDTH : MIN_HEIGHT;
    if (room < least * 2) return;
    const at = (row ? x - within.x : y - within.y) - GAP / 2;
    split.ratio = Math.min(room - least, Math.max(least, at)) / room;
  }

  /** Back to an even cut: a double-click on the space between two panes. */
  even(splitId) {
    const split = this.splitById(splitId);
    if (split) split.ratio = 0.5;
  }

  splitById(id, node = this.root) {
    if (node.kind === 'pane') return null;
    if (node.id === id) return node;
    return this.splitById(id, node.first) ?? this.splitById(id, node.second);
  }
}

/**
 * What dropping a tab at a point would do: `{ pane, zone, rect }`, the rectangle
 * being what to light up, or null where it would change nothing - over a gap, on
 * the pane it is already in, or on the edge of the pane it is alone in.
 *
 * A pane too small to be cut in two takes the tab whole rather than offering a
 * half nobody could work in, and so does its strip, which is where a tab belongs.
 */
function dropAt(layout, arranged, tabId, x, y, stripHeight) {
  const rect = arranged.panes.find((one) => x >= one.x && x < one.x + one.width && y >= one.y && y < one.y + one.height);
  if (!rect) return null;
  const zone = zoneIn(rect, x, y, stripHeight);
  const from = layout.paneOf(tabId);
  if (from !== null && from.id === rect.id && (zone === 'center' || from.tabs.length === 1)) return null;
  return { pane: rect.id, zone, rect: lit(rect, zone) };
}

function zoneIn(rect, x, y, stripHeight) {
  if (y < rect.y + stripHeight) return 'center';
  const across = (x - rect.x) / rect.width;
  const down = (y - rect.y) / rect.height;
  const edges = [];
  if (rect.width >= MIN_WIDTH * 2 + GAP) edges.push(['left', across], ['right', 1 - across]);
  if (rect.height >= MIN_HEIGHT * 2 + GAP) edges.push(['top', down], ['bottom', 1 - down]);
  const nearest = edges.reduce((best, edge) => (best === null || edge[1] < best[1] ? edge : best), null);
  return nearest !== null && nearest[1] <= EDGE ? nearest[0] : 'center';
}

/** Whether a pane can be cut along this edge at all: the rule a drop and the tab's menu share. */
function canCut(rect, zone) {
  if (zone === 'left' || zone === 'right') return rect.width >= MIN_WIDTH * 2 + GAP;
  return rect.height >= MIN_HEIGHT * 2 + GAP;
}

/** The half a drop would make, or the whole pane where it joins. */
function lit(rect, zone) {
  const { x, y, width, height } = rect;
  if (zone === 'left') return { x, y, width: width / 2, height };
  if (zone === 'right') return { x: x + width / 2, y, width: width / 2, height };
  if (zone === 'top') return { x, y, width, height: height / 2 };
  if (zone === 'bottom') return { x, y: y + height / 2, width, height: height / 2 };
  return { x, y, width, height };
}

module.exports = { Layout, dropAt, canCut, GAP, MIN_WIDTH, MIN_HEIGHT };

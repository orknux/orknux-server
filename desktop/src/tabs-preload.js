/*
 * Draws each pane's tab strip and the spaces between panes in tabs.html, from
 * what tabs.js sends, and says back which tab was chosen, closed or dragged.
 *
 * In the preload rather than in a script of the page, so the page can forbid
 * scripts outright; the preload is sandboxed and holds nothing but ipcRenderer.
 *
 * Elements are kept from one drawing to the next rather than made again, and put
 * back only where the order changed. A press selects its tab, which draws; a tab
 * replaced under the pointer at that moment is a tab that can never be dragged.
 */
const { ipcRenderer } = require('electron');

/** A drag with no picture of its own, for the space between panes: the panes themselves move. */
function withoutPicture(event) {
  const nothing = document.createElement('canvas');
  nothing.width = 1;
  nothing.height = 1;
  event.dataTransfer.setDragImage(nothing, 0, 0);
}

/** A drag needs data to be a drag at all; what is moving is known to tabs.js, which was told at the start. */
function begin(event, what) {
  event.dataTransfer.setData('application/x-orknux-tab', '1');
  event.dataTransfer.effectAllowed = 'move';
  // After the event: Chromium ends a drag whose source is covered during dragstart.
  setTimeout(() => ipcRenderer.send('tabs:drag-start', what), 0);
}

function makeTab(id) {
  const item = document.createElement('div');
  item.className = 'tab';
  item.draggable = true;
  item.setAttribute('role', 'tab');
  const title = document.createElement('span');
  title.className = 'title';
  const close = document.createElement('button');
  close.textContent = '×';
  item.append(title, close);

  // On press, as a browser does, so dragging off a tab still chose it.
  item.addEventListener('mousedown', (event) => {
    if (event.button === 0) ipcRenderer.send('tabs:select', id);
  });
  // The middle button closes a tab, as it does in a browser.
  item.addEventListener('auxclick', (event) => {
    if (event.button === 1) ipcRenderer.send('tabs:close', id);
  });
  item.addEventListener('contextmenu', (event) => {
    event.preventDefault();
    ipcRenderer.send('tabs:menu', id);
  });
  item.addEventListener('dragstart', (event) => begin(event, { tab: id }));
  item.addEventListener('dragend', () => ipcRenderer.send('tabs:drag-end'));
  close.addEventListener('mousedown', (event) => event.stopPropagation());
  close.addEventListener('click', () => ipcRenderer.send('tabs:close', id));
  return item;
}

function makeStrip(paneId) {
  const strip = document.createElement('div');
  strip.className = 'strip';
  const row = document.createElement('div');
  row.className = 'tabs';
  row.setAttribute('role', 'tablist');
  row.setAttribute('aria-label', 'Tabs');
  const add = document.createElement('button');
  add.className = 'add';
  add.textContent = '+';
  add.setAttribute('aria-label', 'New tab (Ctrl+T)');
  add.title = 'New tab (Ctrl+T)';
  add.addEventListener('click', () => ipcRenderer.send('tabs:new', paneId));
  strip.append(row, add);
  return strip;
}

function makeDivider(id) {
  const divider = document.createElement('div');
  divider.draggable = true;
  divider.title = 'Drag to resize; double-click to even out';
  divider.addEventListener('dragstart', (event) => {
    withoutPicture(event);
    begin(event, { divider: id });
  });
  divider.addEventListener('dragend', () => ipcRenderer.send('tabs:drag-end'));
  divider.addEventListener('dblclick', () => ipcRenderer.send('tabs:even', id));
  return divider;
}

function place(element, { x, y, width, height }) {
  element.style.left = `${x}px`;
  element.style.top = `${y}px`;
  element.style.width = `${width}px`;
  if (height !== undefined) element.style.height = `${height}px`;
}

/** Makes a parent's children these, touching nothing that is already in its place. */
function arrange(parent, children) {
  const same = parent.children.length === children.length && children.every((child, at) => parent.children[at] === child);
  if (!same) parent.replaceChildren(...children);
}

/** The elements for what is drawn now, keeping those already made and forgetting the rest. */
function keep(kept, ids, make) {
  for (const id of [...kept.keys()]) {
    if (!ids.includes(id)) kept.delete(id);
  }
  return ids.map((id) => {
    if (!kept.has(id)) kept.set(id, make(id));
    return kept.get(id);
  });
}

window.addEventListener('DOMContentLoaded', () => {
  const height = new URLSearchParams(window.location.search).get('height');
  if (height) document.documentElement.style.setProperty('--strip', `${height}px`);

  const stage = document.getElementById('panes');
  const strips = new Map();
  const items = new Map();
  const dividers = new Map();

  ipcRenderer.on('tabs', (_event, drawn) => {
    const stripElements = keep(strips, drawn.panes.map((pane) => pane.id), makeStrip);
    const allTabs = drawn.panes.flatMap((pane) => pane.tabs);
    keep(items, allTabs.map((tab) => tab.id), makeTab);
    drawn.panes.forEach((pane, at) => {
      const strip = stripElements[at];
      place(strip, { x: pane.x, y: pane.y, width: pane.width });
      strip.classList.toggle('working', pane.working);
      arrange(strip.firstElementChild, pane.tabs.map((tab) => {
        const item = items.get(tab.id);
        item.setAttribute('aria-selected', String(tab.active));
        item.title = tab.title;
        item.querySelector('.title').textContent = tab.title;
        item.querySelector('button').setAttribute('aria-label', `Close ${tab.title} (Ctrl+W)`);
        return item;
      }));
    });
    const dividerElements = keep(dividers, drawn.dividers.map((divider) => divider.id), makeDivider);
    drawn.dividers.forEach((divider, at) => {
      place(dividerElements[at], divider);
      dividerElements[at].className = `divider ${divider.direction}`;
    });
    arrange(stage, [...stripElements, ...dividerElements]);
  });
  ipcRenderer.send('tabs:ready');
});

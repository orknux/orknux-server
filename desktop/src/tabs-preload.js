/*
 * Draws the tab strip in tabs.html, from what tabs.js sends, and says back
 * which tab was chosen or closed.
 *
 * In the preload rather than in a script of the page, so the page can forbid
 * scripts outright; the preload is sandboxed and holds nothing but ipcRenderer.
 */
const { ipcRenderer } = require('electron');

window.addEventListener('DOMContentLoaded', () => {
  const height = new URLSearchParams(window.location.search).get('height');
  if (height) document.documentElement.style.setProperty('--strip', `${height}px`);

  const strip = document.getElementById('tabs');
  document.getElementById('add').addEventListener('click', () => ipcRenderer.send('tabs:new'));

  ipcRenderer.on('tabs', (_event, tabs) => {
    strip.replaceChildren(...tabs.map((tab) => {
      const item = document.createElement('div');
      item.className = 'tab';
      item.setAttribute('role', 'tab');
      item.setAttribute('aria-selected', String(tab.active));
      item.title = tab.title;
      const title = document.createElement('span');
      title.className = 'title';
      title.textContent = tab.title;
      const close = document.createElement('button');
      close.textContent = '×';
      close.setAttribute('aria-label', `Close ${tab.title} (Ctrl+W)`);
      item.append(title, close);

      // On press, as a browser does, so dragging off a tab still chose it.
      item.addEventListener('mousedown', (event) => {
        if (event.button === 0) ipcRenderer.send('tabs:select', tab.id);
      });
      // The middle button closes a tab, as it does in a browser.
      item.addEventListener('auxclick', (event) => {
        if (event.button === 1) ipcRenderer.send('tabs:close', tab.id);
      });
      close.addEventListener('mousedown', (event) => event.stopPropagation());
      close.addEventListener('click', () => ipcRenderer.send('tabs:close', tab.id));
      return item;
    }));
  });
  ipcRenderer.send('tabs:ready');
});

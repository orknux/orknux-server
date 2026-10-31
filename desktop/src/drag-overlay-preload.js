/*
 * The view that lies over the pages during a drag: says where the pointer is and
 * where it let go, and lights up what tabs.js says a drop there would make.
 */
const { ipcRenderer } = require('electron');

window.addEventListener('DOMContentLoaded', () => {
  const light = document.getElementById('light');

  document.addEventListener('dragover', (event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
    ipcRenderer.send('overlay:over', event.clientX, event.clientY);
  });
  document.addEventListener('drop', (event) => {
    event.preventDefault();
    ipcRenderer.send('overlay:drop', event.clientX, event.clientY);
  });
  document.addEventListener('dragleave', (event) => {
    // Out of the window, where a drop does nothing; relatedTarget is an element while it is only crossing this page.
    if (event.relatedTarget === null) light.style.display = 'none';
  });
  /*
   * A plain move is the pointer with no button down, so the drag is over. The
   * strip normally says so, but not when the tab it started from has gone since.
   */
  document.addEventListener('mousemove', () => ipcRenderer.send('overlay:end'));

  ipcRenderer.on('overlay:light', (_event, rect) => {
    if (rect === null) {
      light.style.display = 'none';
      return;
    }
    light.style.display = 'block';
    light.style.left = `${rect.x}px`;
    light.style.top = `${rect.y}px`;
    light.style.width = `${rect.width}px`;
    light.style.height = `${rect.height}px`;
  });
});

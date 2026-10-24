/*
 * What a page of the interface may say to the app, and all it may say: which
 * keys move between tabs, and that it is recording a shortcut so those keys
 * should reach it meanwhile. orknux-ui's session/desktop.ts is the other half,
 * and is a no-op in a browser, where there is no `orknuxDesktop`.
 *
 * Sandboxed and context-isolated like the page: strings and a boolean cross,
 * nothing that could reach back into this process.
 */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('orknuxDesktop', {
  tabShortcuts: (next, previous) => ipcRenderer.send('desktop:tab-shortcuts', String(next), String(previous)),
  recordingKeys: (on) => ipcRenderer.send('desktop:recording-keys', on === true),
});

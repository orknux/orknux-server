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
  windowShortcuts: (next, previous) => ipcRenderer.send('desktop:window-shortcuts', String(next), String(previous)),
  // The server this app is connected to, or null where it runs its own; it is what shows Disconnect.
  connectedServer: () => ipcRenderer.sendSync('desktop:connected-server'),
  // Asks the person first, in a dialog of the app's: a page cannot do this behind anybody's back.
  disconnect: () => ipcRenderer.send('desktop:disconnect'),
  recordingKeys: (on) => ipcRenderer.send('desktop:recording-keys', on === true),
});

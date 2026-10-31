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

/*
 * A way off a server too old to draw one, on its sign-in pages. Their page is the
 * server's and cannot be changed from here, so this lies over it, and only there:
 * anywhere else the tab's right-click has it. Follows the address, since the
 * interface moves between pages without loading one.
 */
window.addEventListener('DOMContentLoaded', () => {
  const server = ipcRenderer.sendSync('desktop:server-without-menu');
  if (server === null) return;
  // The address beside it as words, and only the button to press.
  const link = document.createElement('div');
  link.dataset.orknuxDisconnect = '';
  Object.assign(link.style, {
    position: 'fixed', left: '50%', bottom: '24px', transform: 'translateX(-50%)', zIndex: '2147483647',
    display: 'flex', alignItems: 'center', gap: '12px', font: '13px system-ui, sans-serif', color: '#a1a1aa', whiteSpace: 'nowrap',
  });
  const where = document.createElement('span');
  where.textContent = new URL(server).host;
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = 'Disconnect';
  const resting = '#18181b';
  Object.assign(button.style, {
    height: '32px', padding: '0 14px', background: resting, color: '#fafafa', border: '1px solid #3f3f46', borderRadius: '6px',
    font: 'inherit', fontWeight: '500', cursor: 'pointer',
  });
  button.addEventListener('mouseenter', () => { button.style.background = '#27272a'; });
  button.addEventListener('mouseleave', () => { button.style.background = resting; });
  button.addEventListener('click', () => ipcRenderer.send('desktop:disconnect'));
  link.append(where, button);
  const place = () => {
    const signingIn = /^\/(login|forgot-password|reset-password)(\/|$)/.test(window.location.pathname);
    if (signingIn && !link.isConnected) document.body.append(link);
    if (!signingIn && link.isConnected) link.remove();
  };
  new MutationObserver(place).observe(document.body, { childList: true, subtree: true });
  place();
});

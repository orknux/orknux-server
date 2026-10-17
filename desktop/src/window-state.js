/*
 * The window opens where it was closed, at the size it was.
 *
 * Kept only while it still lands on a screen: a laptop undocked from the monitor
 * the window was last on would otherwise open it somewhere nobody can see.
 */
const fs = require('node:fs');
const path = require('node:path');
const { screen } = require('electron');

const DEFAULT = { width: 1440, height: 900 };

function stateFile(data) {
  return path.join(data, 'window.json');
}

function restore(data) {
  try {
    const held = JSON.parse(fs.readFileSync(stateFile(data), 'utf8'));
    const visible = screen.getAllDisplays().some(({ workArea }) =>
      held.x >= workArea.x - 50 && held.y >= workArea.y - 50 &&
      held.x < workArea.x + workArea.width && held.y < workArea.y + workArea.height);
    if (!visible || !(held.width > 400) || !(held.height > 300)) return { ...DEFAULT, maximized: false };
    return { x: held.x, y: held.y, width: held.width, height: held.height, maximized: held.maximized === true };
  } catch {
    return { ...DEFAULT, maximized: false };
  }
}

/** Remembers the window's bounds whenever it is moved or resized, and whether it was maximised. */
function track(window, data) {
  let pending = null;
  const save = () => {
    clearTimeout(pending);
    pending = setTimeout(() => {
      if (window.isDestroyed()) return;
      // The normal bounds, so a maximised window comes back maximised over the size it had before.
      const bounds = window.getNormalBounds();
      try {
        fs.writeFileSync(stateFile(data), JSON.stringify({ ...bounds, maximized: window.isMaximized() }));
      } catch {
        // Not worth failing over: the next start opens at the default.
      }
    }, 300);
  };
  ['resize', 'move', 'maximize', 'unmaximize'].forEach((event) => window.on(event, save));
}

module.exports = { restore, track };

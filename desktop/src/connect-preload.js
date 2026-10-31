/*
 * Draws the window that asks where to connect, in connect.html.
 *
 * In the preload for tabs-preload.js's reason: the page can then forbid scripts
 * outright. Sandboxed, holding ipcRenderer and the form.
 */
const { ipcRenderer } = require('electron');

window.addEventListener('DOMContentLoaded', async () => {
  const form = document.getElementById('form');
  const address = document.getElementById('address');
  const button = document.getElementById('connect');
  const note = document.getElementById('note');
  const started = await ipcRenderer.invoke('connect:start');
  document.getElementById('version').textContent = `App version ${started.version}`;
  address.value = started.server ?? '';

  let accepted = null;
  const say = (text, kind) => {
    note.textContent = text ?? '';
    note.className = kind ?? '';
    note.hidden = !text;
  };
  say(started.said, 'wrong');
  address.addEventListener('input', () => {
    accepted = null;
    button.textContent = 'Connect';
    say(null);
  });
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    button.disabled = true;
    button.textContent = 'Connecting…';
    say(null);
    const answer = await ipcRenderer.invoke('connect:try', address.value, accepted !== null);
    button.disabled = false;
    button.textContent = 'Connect';
    if (answer.why) say(answer.why, 'wrong');
    if (answer.warn) {
      accepted = answer.warn;
      button.textContent = 'Connect anyway';
      say(`${answer.warn} is not encrypted: your password and everything you do there can be read by anybody on the way.`, 'warn');
    }
  });
  address.focus();
  address.select();
});

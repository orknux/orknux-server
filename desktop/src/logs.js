/*
 * One log file for the desktop and the server it starts, rotated by size.
 *
 * A container's log is whatever Docker keeps; a desktop app has no Docker, so
 * the server's output would otherwise go to a console nobody opened. Both halves
 * write here, the desktop's lines marked, so the file reads as one account of a
 * start - and the last of it is what an unexpected exit is explained with.
 */
const fs = require('node:fs');
const path = require('node:path');

const MOST_BYTES = 10 * 1024 * 1024;
const KEPT = 5;
const REMEMBERED_LINES = 60;

class Log {
  constructor(directory) {
    fs.mkdirSync(directory, { recursive: true });
    this.file = path.join(directory, 'orknux.log');
    this.recent = [];
    this.partial = '';
    this.rotate();
  }

  /** orknux.log -> orknux.1.log -> ... -> orknux.5.log, the oldest dropped, once the current one is past its size. */
  rotate() {
    let size = 0;
    try {
      size = fs.statSync(this.file).size;
    } catch {
      return;
    }
    if (size < MOST_BYTES) return;
    for (let at = KEPT - 1; at >= 1; at -= 1) {
      const from = path.join(path.dirname(this.file), `orknux.${at}.log`);
      if (fs.existsSync(from)) fs.renameSync(from, path.join(path.dirname(this.file), `orknux.${at + 1}.log`));
    }
    fs.renameSync(this.file, path.join(path.dirname(this.file), 'orknux.1.log'));
  }

  write(text) {
    try {
      fs.appendFileSync(this.file, text);
    } catch {
      // A log that cannot be written is not a reason to stop the app.
    }
    this.rotate();
  }

  /** A line of the desktop's own. */
  note(line) {
    const stamped = `${new Date().toISOString()} desktop: ${line}\n`;
    this.remember(stamped.trimEnd());
    this.write(stamped);
  }

  /** The server's output, as it arrives in chunks: written whole, remembered by line. */
  server(chunk) {
    const text = chunk.toString();
    this.write(text);
    const lines = (this.partial + text).split(/\r?\n/);
    this.partial = lines.pop() ?? '';
    lines.forEach((line) => this.remember(line));
  }

  remember(line) {
    if (line.trim() === '') return;
    this.recent.push(line);
    if (this.recent.length > REMEMBERED_LINES) this.recent.shift();
  }

  /** The last lines written, for a dialog explaining why the server stopped. */
  last(count) {
    return this.recent.slice(-count).join('\n');
  }
}

module.exports = { Log };

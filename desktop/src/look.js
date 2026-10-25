/*
 * The splash in the interface's own colours.
 *
 * The splash is a file inside the app, drawn before the server is up, so it
 * cannot read the accent and theme a person chose: those live in the
 * interface's storage, on the server's origin. So the colours the interface is
 * actually drawn in are read off a page of it whenever one loads, kept beside
 * the window's state, and handed to the splash on the next start. Read as
 * computed colours rather than as the setting's name, so the splash holds no
 * copy of the accent table to drift from tokens.css.
 */
const fs = require('node:fs');
const path = require('node:path');

/** What the splash falls back to before the interface has said its own: the default accent, blue, on the dark ground. */
const DEFAULT = { brand: '#4f7fc4', ground: '#09090b', text: '#a1a1aa' };

/** A colour as the page computes it, and nothing else: it goes into a style, so a value that is not one is refused. */
const COLOUR = /^(#[0-9a-f]{3,8}|rgba?\([0-9.,\s%]+\))$/i;

function lookFile(data) {
  return path.join(data, 'look.json');
}

function remembered(data) {
  try {
    const held = JSON.parse(fs.readFileSync(lookFile(data), 'utf8'));
    const pick = (key) => (typeof held[key] === 'string' && COLOUR.test(held[key].trim()) ? held[key].trim() : DEFAULT[key]);
    return { brand: pick('brand'), ground: pick('ground'), text: pick('text') };
  } catch {
    return { ...DEFAULT };
  }
}

/** Reads the colours off a page of the interface and keeps them, when they changed. */
async function capture(contents, data) {
  if (contents.isDestroyed()) return;
  try {
    const read = await contents.executeJavaScript(`(() => {
      const root = getComputedStyle(document.documentElement);
      const body = getComputedStyle(document.body);
      return {
        brand: root.getPropertyValue('--color-accent-brand').trim(),
        ground: body.backgroundColor,
        text: root.getPropertyValue('--color-text-secondary').trim(),
      };
    })()`);
    if (!read || !COLOUR.test(read.brand ?? '')) return;
    const look = {
      brand: read.brand,
      ground: COLOUR.test(read.ground ?? '') ? read.ground : DEFAULT.ground,
      text: COLOUR.test(read.text ?? '') ? read.text : DEFAULT.text,
    };
    const was = remembered(data);
    if (was.brand === look.brand && was.ground === look.ground && was.text === look.text) return;
    fs.writeFileSync(lookFile(data), JSON.stringify(look));
  } catch {
    // Not read is not broken: the splash keeps the colours it had.
  }
}

module.exports = { remembered, capture };

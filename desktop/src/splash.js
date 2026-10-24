// The colours the interface was last drawn in, handed over in the address by look.js; the splash's own are the fallback.
const asked = new URLSearchParams(window.location.search);
const COLOUR = /^(#[0-9a-f]{3,8}|rgba?\([0-9.,\s%]+\))$/i;
for (const [key, property] of [['brand', '--brand'], ['ground', '--ground'], ['text', '--text']]) {
  const value = asked.get(key);
  if (value !== null && COLOUR.test(value)) document.documentElement.style.setProperty(property, value);
}

/*
 * Drags tabs with a real pointer in a real window, and reads what came of it:
 * whether a drag crosses the pages is Chromium's and the display's business, and
 * nothing short of this can see it. split.js's arithmetic is test/split.test.js's.
 *
 *   docker run --rm --shm-size=1g -v "$PWD/desktop:/desktop" orknux-desktop-dev \
 *     xvfb-run -a -s "-screen 0 1400x1000x24" \
 *     node_modules/.bin/electron scripts/split-check.js --no-sandbox --disable-gpu
 */
const { execFile } = require('node:child_process');
const http = require('node:http');
const { promisify } = require('node:util');
const { app, BrowserWindow } = require('electron');
const path = require('node:path');

const { Tabs } = require('../src/tabs');

const run = promisify(execFile);
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
/** One origin for every tab, as in the app: Chromium keeps some things per site, and that has bitten here. */
let origin_ = null;
const page = (name) => `${origin_}/${name}`;

let failures = 0;
function check(what, ok, seen) {
  if (!ok) failures += 1;
  console.log(`${ok ? 'PASS' : 'FAIL'}: ${what}${seen === undefined ? '' : ` (${JSON.stringify(seen)})`}`);
}

const pointer = (...args) => run('xdotool', args.map(String));

/** Press, travel in steps as a hand does, let go - a drag is only begun by moving. */
async function drag(from, to, { hold = 250 } = {}) {
  await pointer('mousemove', from.x, from.y);
  await pause(120);
  await pointer('mousedown', 1);
  await pause(120);
  for (let step = 1; step <= 12; step += 1) {
    await pointer('mousemove', Math.round(from.x + ((to.x - from.x) * step) / 12), Math.round(from.y + ((to.y - from.y) * step) / 12));
    await pause(40);
  }
  await pause(hold);
}

async function release() {
  await pointer('mouseup', 1);
  await pause(400);
}

app.whenReady().then(async () => {
  const site = http.createServer((request, response) => {
    const name = request.url.slice(1);
    response.setHeader('Content-Type', 'text/html');
    response.end(`<title>${name}</title><body style="background:#334"><h1 style="color:white">${name}</h1>`);
  });
  await new Promise((resolve) => site.listen(0, '127.0.0.1', resolve));
  origin_ = `http://127.0.0.1:${site.address().port}`;
  const win = new BrowserWindow({
    x: 0, y: 0, width: 1200, height: 800, backgroundColor: '#09090b',
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, preload: path.join(__dirname, '..', 'src', 'tabs-preload.js') },
  });
  const tabs = new Tabs(win, {
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
    prepare: () => {},
    home: () => page('home'),
  });
  const a = tabs.open({ url: page('A') });
  const b = tabs.open({ url: page('B') });
  const c = tabs.open({ url: page('C') });
  await pause(1500);

  const shape = () => tabs.layoutTree.panes().map((pane) => pane.tabs);
  const origin = () => win.getContentBounds();
  /** Where a tab's own strip draws it, on the screen. */
  const tabAt = async (tab) => {
    const box = await win.webContents.executeJavaScript(
      `(() => { const r = [...document.querySelectorAll('.tab')].find((t) => t.title === ${JSON.stringify(tab.title)}).getBoundingClientRect(); return { x: r.x + 20, y: r.y + r.height / 2 }; })()`,
    );
    return { x: origin().x + box.x, y: origin().y + box.y };
  };
  const inWindow = (x, y) => ({ x: origin().x + x, y: origin().y + y });

  check('three tabs open in one pane', shape().length === 1 && shape()[0].length === 3, shape());

  // A tab to the right edge: two panes side by side, while the drag lights up the half it would make.
  await drag(await tabAt(b), inWindow(1150, 400));
  check('the overlay lies over the pages during the drag', tabs.overlay.getVisible() === true);
  const lit = await tabs.overlay.webContents.executeJavaScript(
    `(() => { const r = document.getElementById('light'); return { shown: r.style.display, left: r.style.left, width: r.style.width }; })()`,
  );
  check('and lights up the right half', lit.shown === 'block' && lit.left === '600px' && lit.width === '600px', lit);
  await release();
  check('dropped on the right edge, the tab has a pane of its own on the right', JSON.stringify(shape()) === JSON.stringify([[a.id, c.id], [b.id]]), shape());
  check('and the overlay is out of the way', tabs.overlay.getVisible() === false);
  check('the dragged tab is what is being worked in', tabs.active === b);
  const left = a.view.getBounds();
  const right = b.view.getBounds();
  check('each pane draws its active tab', c.view.getVisible() && b.view.getVisible() && !a.view.getVisible());
  check('the two pages sit side by side under their strips', right.x > left.x + left.width - 1 && left.y === 36 && right.y === 36, { left, right });

  // Cut again the other way.
  await drag(await tabAt(c), inWindow(900, 760));
  await release();
  // C leaves A alone on the left and joins nothing: dropped on the bottom of B's pane, it cuts it.
  check('dropped on the bottom edge of the other pane, it cuts that one the other way', JSON.stringify(shape()) === JSON.stringify([[a.id], [b.id], [c.id]]), shape());
  const upper = b.view.getBounds();
  const lower = c.view.getBounds();
  check('one above the other', lower.y > upper.y + upper.height - 1 && lower.x === upper.x, { upper, lower });

  // A press in a page moves the work there.
  await pointer('mousemove', origin().x + 200, origin().y + 400);
  await pointer('click', 1);
  await pause(300);
  check('a press in a page makes its pane the one worked in', tabs.active === a);
  tabs.open({ url: page('D') });
  await pause(300);
  check('and a new tab opens there', shape()[0].length === 2, shape());

  // The space between the panes.
  const before = a.view.getBounds().width;
  const divider = tabs.arranged().dividers.find((one) => one.direction === 'row');
  await drag(inWindow(divider.x + 2, 400), inWindow(divider.x - 150, 400));
  check('the panes follow the space between them while it is dragged', a.view.getBounds().width < before - 100, { before, now: a.view.getBounds().width });
  await release();
  check('and it stays where it was let go', a.view.getBounds().width < before - 100 && tabs.overlay.getVisible() === false);

  // Each pane draws at its own scale, though all are one site: a shared zoom shrank every pane to the narrowest's.
  const narrow = await tabs.find(4).contents.executeJavaScript('window.innerWidth');
  const wider = tabs.find(4).view.getBounds().width < 900;
  check('a pane narrower than the interface is laid out at the width it needs', wider && narrow === 900, narrow);
  win.setContentSize(1390, 800);
  tabs.layoutTree.resize(tabs.arranged().dividers.find((one) => one.direction === 'row').id, tabs.arranged(), 930, 400);
  tabs.layout();
  await pause(500);
  const wide = tabs.find(4).view.getBounds().width;
  const inner = await tabs.find(4).contents.executeJavaScript('window.innerWidth');
  const beside = await b.contents.executeJavaScript('window.innerWidth');
  check('a pane wide enough is at its own width while its neighbour is scaled', wide >= 900 && inner === wide && beside === 900 && b.view.getBounds().width < 900, { wide, inner, beside });
  win.setContentSize(1200, 800);
  tabs.layoutTree.even(tabs.arranged().dividers.find((one) => one.direction === 'row').id);
  tabs.layout();
  await pause(300);

  // Let go outside the window: nothing moves, and the overlay does not stay over the pages.
  const held = JSON.stringify(shape());
  await drag(await tabAt(b), { x: 1350, y: 950 });
  await release();
  check('let go outside the window, nothing has moved', JSON.stringify(shape()) === held, shape());
  check('and the pages take the pointer again', tabs.overlay.getVisible() === false);

  // Into another pane's strip: the pane it left goes, and the rest take its space.
  await drag(await tabAt(c), await tabAt(b));
  await release();
  check('dropped on a strip it joins those tabs, and the pane it left is gone', shape().length === 2 && shape()[1].includes(c.id) && shape()[1].includes(b.id), shape());

  // Closing the last tab of a pane does the same, and the window lives on.
  tabs.close(b);
  await pause(200);
  tabs.close(c);
  await pause(500);
  check('the pane closed with its last tab goes too', shape().length === 1, shape());
  const whole = tabs.list[0].view.getBounds();
  check('and what is left has the whole window', whole.width === origin().width, whole);

  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`);
  app.exit(failures === 0 ? 0 : 1);
}).catch((failure) => {
  console.error(failure);
  app.exit(2);
});

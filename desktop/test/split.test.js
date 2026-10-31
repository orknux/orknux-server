const test = require('node:test');
const assert = require('node:assert/strict');

const { Layout, dropAt, canCut, GAP, MIN_WIDTH } = require('../src/split');

const STRIP = 36;

/** A window of one pane holding the tabs given. */
function holding(...tabs) {
  const layout = new Layout();
  tabs.forEach((tab) => layout.add(tab, layout.root.id));
  return layout;
}

const tabsByPane = (layout) => layout.panes().map((pane) => pane.tabs);

test('a tab dropped on an edge cuts that pane in two, on that side', () => {
  for (const [zone, direction, expected] of [
    ['left', 'row', [[2], [1]]],
    ['right', 'row', [[1], [2]]],
    ['top', 'column', [[2], [1]]],
    ['bottom', 'column', [[1], [2]]],
  ]) {
    const layout = holding(1, 2);
    layout.move(2, layout.root.id, zone);
    assert.equal(layout.root.direction, direction, zone);
    assert.deepEqual(tabsByPane(layout), expected, zone);
  }
});

test('panes cut again make any arrangement, and each keeps its own active tab', () => {
  const layout = holding(1, 2, 3, 4);
  const first = layout.root.id;
  layout.move(2, first, 'right');
  layout.move(3, layout.paneOf(2).id, 'bottom');
  layout.move(4, first, 'bottom');
  assert.deepEqual(tabsByPane(layout), [[1], [4], [2], [3]]);
  assert.deepEqual(layout.panes().map((pane) => pane.active), [1, 4, 2, 3]);
});

test('a pane emptied goes, and what it shared its space with takes all of it', () => {
  const layout = holding(1, 2);
  layout.move(2, layout.root.id, 'right');
  layout.remove(2);
  assert.equal(layout.root.kind, 'pane');
  assert.deepEqual(tabsByPane(layout), [[1]]);

  const again = holding(1, 2);
  again.move(2, again.root.id, 'right');
  again.move(2, again.paneOf(1).id, 'center');
  assert.equal(again.root.kind, 'pane');
  assert.deepEqual(tabsByPane(again), [[1, 2]]);
  assert.equal(again.root.active, 2);
});

test('the last tab leaves one empty pane for the next tab to go into', () => {
  const layout = holding(1);
  layout.remove(1);
  assert.deepEqual(tabsByPane(layout), [[]]);
  assert.equal(layout.root.active, null);
});

test('closing the active tab makes the neighbour on the right active, or the left one at the end', () => {
  const layout = holding(1, 2, 3);
  layout.root.active = 2;
  layout.remove(2);
  assert.equal(layout.root.active, 3);
  layout.remove(3);
  assert.equal(layout.root.active, 1);
});

test('panes and the space between them fill the window exactly', () => {
  const layout = holding(1, 2, 3);
  layout.move(2, layout.root.id, 'right');
  layout.move(3, layout.paneOf(2).id, 'bottom');
  const { panes, dividers } = layout.arrange(1001, 801);
  const [left, top, bottom] = panes;
  assert.equal(left.width + GAP + top.width, 1001);
  assert.equal(top.height + GAP + bottom.height, 801);
  assert.equal(top.x, left.width + GAP);
  assert.equal(dividers.length, 2);
});

test('what a drop does depends on where in the pane it lands', () => {
  const layout = holding(1, 2);
  const arranged = layout.arrange(1200, 800);
  const zone = (x, y) => dropAt(layout, arranged, 2, x, y, STRIP)?.zone ?? null;
  assert.equal(zone(10, 400), 'left');
  assert.equal(zone(1190, 400), 'right');
  assert.equal(zone(600, 60), 'top');
  assert.equal(zone(600, 790), 'bottom');
  // The middle of the pane it is already in, and its own strip, change nothing.
  assert.equal(zone(600, 400), null);
  assert.equal(zone(600, 10), null);
});

test('a tab alone in its pane cannot split it, and may join another', () => {
  const layout = holding(1, 2);
  layout.move(2, layout.root.id, 'right');
  const arranged = layout.arrange(1200, 800);
  assert.equal(dropAt(layout, arranged, 2, 1190, 400, STRIP), null);
  const joined = dropAt(layout, arranged, 2, 300, 400, STRIP);
  assert.equal(joined.zone, 'center');
  assert.equal(joined.pane, layout.paneOf(1).id);
  assert.equal(dropAt(layout, arranged, 2, 300, 10, STRIP).zone, 'center');
});

test('a pane too small to cut in two is not offered as two', () => {
  const layout = holding(1, 2);
  const narrow = layout.arrange(MIN_WIDTH * 2, 800);
  assert.equal(dropAt(layout, narrow, 2, 5, 400, STRIP), null);
  assert.equal(dropAt(layout, narrow, 2, MIN_WIDTH, 790, STRIP).zone, 'bottom');
});

test('a drop lights up the half it would make', () => {
  const layout = holding(1, 2);
  const { rect } = dropAt(layout, layout.arrange(1200, 800), 2, 1190, 400, STRIP);
  assert.deepEqual(rect, { x: 600, y: 0, width: 600, height: 800 });
});

test('the space between two panes moves with the pointer and stops short of a pane too small', () => {
  const layout = holding(1, 2);
  layout.move(2, layout.root.id, 'right');
  const id = layout.root.id;
  layout.resize(id, layout.arrange(1208, 800), 504, 400);
  assert.equal(layout.arrange(1208, 800).panes[0].width, 500);
  layout.resize(id, layout.arrange(1208, 800), 5, 400);
  assert.equal(layout.arrange(1208, 800).panes[0].width, MIN_WIDTH);
  layout.resize(id, layout.arrange(1208, 800), 1200, 400);
  assert.equal(layout.arrange(1208, 800).panes[1].width, MIN_WIDTH);
  layout.even(id);
  assert.equal(layout.arrange(1208, 800).panes[0].width, 600);
});

test('a pane can be cut along an edge only where both halves would be big enough', () => {
  assert.equal(canCut({ width: MIN_WIDTH * 2 + GAP, height: 100 }, 'right'), true);
  assert.equal(canCut({ width: MIN_WIDTH * 2 + GAP - 1, height: 1000 }, 'right'), false);
  assert.equal(canCut({ width: 100, height: 408 }, 'bottom'), true);
  assert.equal(canCut({ width: 2000, height: 300 }, 'bottom'), false);
});

test('merging a line puts both sides in one pane, and a line further out still stands', () => {
  const layout = holding(1, 2, 3);
  layout.move(2, layout.root.id, 'right');
  layout.move(3, layout.paneOf(2).id, 'bottom');
  const inner = layout.root.second.id;
  layout.merge(inner, 3);
  assert.deepEqual(tabsByPane(layout), [[1], [2, 3]]);
  assert.equal(layout.paneOf(3).active, 3);
  layout.merge(layout.root.id);
  assert.equal(layout.root.kind, 'pane');
  assert.deepEqual(tabsByPane(layout), [[1, 2, 3]]);
});

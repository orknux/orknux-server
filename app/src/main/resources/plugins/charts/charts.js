/*
 * Charts, as a plugin - bar, column, line, area, pie and donut, drawn here.
 *
 * The companion to the diagram plugins. Mermaid and nomnoml draw the shape of
 * a system; this draws the shape of a number - revenue by quarter, errors by
 * service, share of a whole - which is the other picture a workflow ends up
 * wanting to put in front of somebody.
 *
 * Nothing is bundled. A chart is arithmetic on a list of numbers and a few
 * hundred lines of SVG, and a library that does the same thing measures its
 * text through a DOM, which this sandbox has not got. So the layout is this
 * file's own: scales, ticks, bars with a rounded data-end, lines with a ring
 * around each marker, a row of figures above the plot and a legend. Text is
 * measured by a rule of thumb - about 0.55 em per character in a sans face -
 * which is what every renderer without a font on hand does, and is a label a
 * hair off its slot rather than a chart that will not draw.
 *
 * ## What it asks the server for
 *
 * Drawing, and only drawing. Slack draws no SVG - it hosts one as a file and
 * shows a card - so a picture needs a rasteriser, and `RENDER_PNG` is the one
 * capability here. Nothing is fetched: markup this plugin just wrote goes out,
 * bytes computed from it come back. `format: svg` answers the markup without
 * the server being asked anything at all.
 *
 * ## The card a chart is
 *
 * A chart here is a report card, not a plot: an eyebrow in small capitals
 * saying what it is about, a title in a display face, a subtitle, then a row
 * of the figures the plot is really about - each series' total or latest
 * value, with how far it moved - and the plot under them, with a source line
 * in the footer. A picture posted to a channel has to make its point without
 * the paragraph that would have gone round it, and the figures row is that
 * paragraph.
 *
 * ## The rules the drawing keeps
 *
 * These are the ones a chart is read by, so they are not options:
 *
 * - Eight series, in a fixed order of hues chosen so that neighbours stay
 *   apart under every kind of colour-blindness. A ninth is refused: fold the
 *   small ones into "Other" rather than reaching for a ninth hue nobody can
 *   tell from the third.
 * - One axis. Two measures of different scale are two charts.
 * - Marks are thin: a bar at most 24 pixels thick, rounded at its data end
 *   and square at the baseline; a line two pixels wide; a marker eight
 *   across with a ring of surface colour so it stays legible where lines
 *   cross. A two-pixel gap of surface between touching bars and between the
 *   segments of a stack, rather than a stroke drawn around them.
 * - Text wears ink, never the series colour. Identity comes from the swatch
 *   beside it. Figures are set in the display face and ticks in the mono one,
 *   so a number reads as a number.
 * - A legend whenever there are two series or more; none for one, because
 *   the title already says what is plotted. Values are labelled selectively -
 *   the end of a line, the cap of a single series' bars - never on every
 *   point.
 * - The grid is a hairline one step off the surface, and the baseline is the
 *   only line that is darker.
 *
 * ## Faces
 *
 * The markup names a display face, a text face and a mono face, each with a
 * fallback stack ending in a generic family. An SVG put in a page that loads
 * those faces is set in them; the server's rasteriser sets whatever it has
 * installed, and the stacks are written so that what it has is a sans, not a
 * serif. Widths are estimated, not measured, either way.
 *
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */

/** The size a chart lays itself out at; a png is this, scaled. */
const SIZE = { width: 800, height: 480 };

/** The air between the card's edge and anything in it. */
const PAD = 28;

/** The three faces, as stacks a page can load and a rasteriser can fall back through. */
const FACES = {
  display: "'Familjen Grotesk', 'Bricolage Grotesque', 'Segoe UI', Helvetica, Arial, sans-serif",
  text: "'Source Sans 3', 'Segoe UI', Helvetica, Arial, sans-serif",
  mono: "'IBM Plex Mono', 'JetBrains Mono', Consolas, 'Liberation Mono', monospace",
};

/**
 * What a chart is drawn in, per theme.
 *
 * The neutrals lean cool rather than sitting at a pure grey, so the chrome
 * reads as chosen; the series hues are the same eight in both themes, stepped
 * for each surface and validated as a set - worst adjacent pair under
 * colour-blindness 9.1 light and 8.4 dark, on a scale where 8 is the target.
 * `good` and `bad` are for a delta and nothing else, and are kept apart from
 * the series so a status never impersonates one.
 */
const THEMES = {
  light: {
    surface: '#ffffff',
    sunk: '#eef2f4',
    ink: '#131a20',
    soft: '#4a5865',
    faint: '#71808c',
    rule: '#dde3e7',
    ruleSoft: '#e9eef1',
    good: '#1f7a3a',
    bad: '#b42318',
    series: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#4a3aa7', '#e34948'],
  },
  dark: {
    surface: '#141a1f',
    sunk: '#0f1418',
    ink: '#e6ecf0',
    soft: '#a6b4be',
    faint: '#7a8994',
    rule: '#26323b',
    ruleSoft: '#1c262d',
    good: '#6fd68e',
    bad: '#f08a80',
    series: ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'],
  },
};

/** The kinds this draws, and the sentence that names them when one is asked for that is not here. */
const KINDS = ['bar', 'column', 'line', 'area', 'pie', 'donut'];

/** The most series a chart carries; past this the hues stop being tellable apart. */
const MOST_SERIES = 8;

/** The most series the figures row holds; past this it is a legend again. */
const MOST_FIGURES = 4;

/** Mark specs: fixed, and the reason the charts look like one family. */
const MARK = { barThickness: 24, gap: 2, line: 2, marker: 4, ring: 2, cornerRadius: 4 };

/**
 * A short name for one drawing, derived from the drawing itself. Content
 * rather than a counter or a clock, so the same chart twice lands on the
 * same key. FNV-1a because it is four lines and this is a name.
 */
function keyFor(text) {
  let hash = 0x811c9dc5;
  for (let at = 0; at < text.length; at += 1) {
    hash ^= text.charCodeAt(at);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return `charts.${hash.toString(36)}`;
}

/** Text as it may appear inside markup. */
function escaped(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * About how wide some text is, in the size it is set at.
 *
 * A rule of thumb rather than a measurement, because there is no font here to
 * measure with. 0.55 em a character is a sans face's average once digits,
 * capitals and the narrow letters are mixed the way labels mix them; a mono
 * face is 0.6 by definition.
 */
function widthOf(text, size, mono = false) {
  return String(text).length * size * (mono ? 0.6 : 0.55);
}

/** One piece of text, set. */
function text(x, y, said, { size, face = FACES.text, fill, weight = 400, anchor = 'start', spacing = 0 }) {
  return (
    `<text x="${Number(x).toFixed(1)}" y="${Number(y).toFixed(1)}"` +
    (anchor === 'start' ? '' : ` text-anchor="${anchor}"`) +
    ` font-family="${face}" font-size="${size}"` +
    (weight === 400 ? '' : ` font-weight="${weight}"`) +
    (spacing === 0 ? '' : ` letter-spacing="${spacing}"`) +
    ` fill="${fill}">${escaped(said)}</text>`
  );
}

/** A number written for a person: thousands separated, at most two decimals, nothing trailing. */
function formatted(value) {
  if (!Number.isFinite(value)) {
    return '';
  }
  const magnitude = Math.abs(value);
  const decimals = magnitude >= 100 || Number.isInteger(value) ? 0 : magnitude >= 10 ? 1 : 2;
  let written = magnitude.toFixed(decimals);
  if (decimals > 0) {
    written = written.replace(/\.?0+$/, '');
  }
  const [whole, fraction] = written.split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return (value < 0 ? '-' : '') + grouped + (fraction === undefined ? '' : `.${fraction}`);
}

/** A figure, compact past ten thousand: 12.9K, 4.2M, 1.1B. */
function compact(value) {
  const magnitude = Math.abs(value);
  if (magnitude < 10000) {
    return formatted(value);
  }
  const [unit, by] = magnitude >= 1e9 ? ['B', 1e9] : magnitude >= 1e6 ? ['M', 1e6] : ['K', 1e3];
  const scaled = magnitude / by;
  const written = (scaled >= 100 ? scaled.toFixed(0) : scaled.toFixed(1)).replace(/\.0$/, '');
  return `${value < 0 ? '-' : ''}${written}${unit}`;
}

/** A value with the chart's unit on it - a currency sign before, anything else after. */
function labelled(value, unit, short = false) {
  const written = short ? compact(value) : formatted(value);
  if (unit === '') {
    return written;
  }
  if (/^[$€£¥]$/.test(unit)) {
    return value < 0 ? `-${unit}${written.slice(1)}` : `${unit}${written}`;
  }
  return unit === '%' ? `${written}%` : `${written} ${unit}`;
}

/**
 * Ticks for an axis: the clean numbers - 1, 2, 5 times a power of ten -
 * that cover the data in four to seven steps, always including zero.
 */
function ticksFor(low, high) {
  const floor = Math.min(0, low);
  const ceiling = Math.max(0, high);
  const span = ceiling - floor || 1;
  const rough = span / 5;
  const power = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((unit) => unit * power).find((unit) => span / unit <= 7) ?? 10 * power;
  const ticks = [];
  for (let at = Math.floor(floor / step) * step; at <= ceiling + step / 2; at += step) {
    ticks.push(Math.abs(at) < step / 1e6 ? 0 : Number(at.toFixed(10)));
  }
  return ticks;
}

/**
 * The spec, read and refused where it is not a chart.
 *
 * Refused with a sentence that says what was expected, because the caller is
 * most often a model and a model corrects itself from a sentence and not
 * from a stack trace.
 */
function specOf(text) {
  let spec;
  try {
    spec = JSON.parse(text);
  } catch (failure) {
    throw new Error(`the spec is not JSON: ${failure instanceof Error ? failure.message : String(failure)}`);
  }
  if (spec === null || typeof spec !== 'object' || Array.isArray(spec)) {
    throw new Error('the spec is not an object: it is {"type", "labels", "series"} and a "title"');
  }

  const kind = typeof spec.type === 'string' ? spec.type.trim().toLowerCase() : '';
  if (!KINDS.includes(kind)) {
    throw new Error(`no chart type called ${spec.type}: it is ${KINDS.slice(0, -1).join(', ')} or ${KINDS[KINDS.length - 1]}`);
  }

  if (!Array.isArray(spec.labels) || spec.labels.length === 0) {
    throw new Error('labels is missing: the categories along the axis, or the slices of a pie, as a list of strings');
  }
  const labels = spec.labels.map((label) => (label === null || label === undefined ? '' : String(label)));

  let series;
  if (Array.isArray(spec.series)) {
    series = spec.series;
  } else if (Array.isArray(spec.values)) {
    series = [{ name: typeof spec.title === 'string' ? spec.title : '', values: spec.values }];
  } else {
    throw new Error('series is missing: a list of {"name", "values"}, or values alone for a single series');
  }
  if (series.length === 0) {
    throw new Error('series is empty: there is nothing to draw');
  }
  if (series.length > MOST_SERIES) {
    throw new Error(
      `${series.length} series is more than the ${MOST_SERIES} a chart can keep apart: fold the small ones into "Other", or draw two charts`,
    );
  }
  const round = kind === 'pie' || kind === 'donut';
  if (round && series.length > 1) {
    throw new Error(`a ${kind} shows one series as shares of a whole; it was given ${series.length}`);
  }

  const stacked = spec.stacked === true && !round && kind !== 'line';
  const read = series.map((one, at) => {
    if (one === null || typeof one !== 'object' || !Array.isArray(one.values)) {
      throw new Error(`series ${at + 1} is not {"name", "values"}`);
    }
    const name = typeof one.name === 'string' ? one.name : `Series ${at + 1}`;
    if (one.values.length !== labels.length) {
      throw new Error(
        `series ${name === '' ? at + 1 : name} has ${one.values.length} values for ${labels.length} labels; they go together one to one`,
      );
    }
    const values = one.values.map((value) => (value === null ? null : Number(value)));
    const bad = values.findIndex((value) => value !== null && !Number.isFinite(value));
    if (bad !== -1) {
      throw new Error(`series ${name === '' ? at + 1 : name} has a value that is not a number at position ${bad + 1}`);
    }
    if ((round || stacked) && values.some((value) => value !== null && value < 0)) {
      throw new Error(`a ${stacked ? 'stacked' : kind} chart cannot show a negative value; ${name === '' ? at + 1 : name} has one`);
    }
    return { name, values };
  });

  const word = (field) => (typeof spec[field] === 'string' ? spec[field].trim() : '');
  return {
    kind,
    title: word('title'),
    subtitle: word('subtitle'),
    eyebrow: word('eyebrow'),
    source: word('source'),
    unit: word('unit'),
    labels,
    series: read,
    stacked,
    summary: spec.summary !== false,
  };
}

/** A series' figure: its total, its latest value, and how far it moved from its first. */
function figureOf(kind, one) {
  const present = one.values.filter((value) => value !== null);
  if (present.length === 0) {
    return null;
  }
  const total = present.reduce((sum, value) => sum + value, 0);
  const first = present[0];
  const last = present[present.length - 1];
  const change = present.length > 1 && first !== 0 ? ((last - first) / Math.abs(first)) * 100 : null;
  const overTime = kind === 'line' || kind === 'area';
  return {
    value: overTime ? last : total,
    caption: overTime ? 'latest' : 'total',
    change: overTime ? change : null,
    total,
    average: total / present.length,
  };
}

/**
 * The frame every chart shares: the surface with its hairline edge, the
 * eyebrow, title and subtitle, the figures row or the legend, and the source
 * line in the footer. Answered with the markup so far and the box left for
 * the plot.
 */
function framed(spec, theme) {
  const parts = [];
  parts.push(`<rect x="0" y="0" width="${SIZE.width}" height="${SIZE.height}" fill="${theme.surface}"/>`);
  parts.push(
    `<rect x="0.5" y="0.5" width="${SIZE.width - 1}" height="${SIZE.height - 1}" fill="none" stroke="${theme.rule}" stroke-width="1"/>`,
  );

  let y = PAD;
  if (spec.eyebrow !== '') {
    parts.push(text(PAD, y + 9, spec.eyebrow.toUpperCase(), { size: 11, face: FACES.mono, fill: theme.faint, spacing: 1.3 }));
    y += 20;
  }
  if (spec.title !== '') {
    parts.push(text(PAD, y + 17, spec.title, { size: 20, face: FACES.display, fill: theme.ink, weight: 700 }));
    y += 26;
    if (spec.subtitle !== '') {
      parts.push(text(PAD, y + 11, spec.subtitle, { size: 13, fill: theme.soft }));
      y += 20;
    }
  }

  const round = spec.kind === 'pie' || spec.kind === 'donut';
  const figures = spec.summary && !round ? spec.series.map((one) => figureOf(spec.kind, one)) : [];
  const many = spec.series.length > MOST_FIGURES;

  if (figures.length > 0 && !many && figures.some((figure) => figure !== null)) {
    /*
     * The figures row: what the plot adds up to, before the plot. One figure
     * per series - its swatch, its name and its total or latest value, with
     * how far a line moved - or, for a single series, the three numbers a
     * reader would otherwise work out: total, average, and the latest or the
     * highest.
     */
    const tiles = [];
    if (spec.series.length === 1) {
      const figure = figures[0];
      const one = spec.series[0];
      tiles.push({ name: 'Total', value: labelled(figure.total, spec.unit, true) });
      tiles.push({ name: 'Average', value: labelled(figure.average, spec.unit, true) });
      if (figure.change !== null || spec.kind === 'line' || spec.kind === 'area') {
        tiles.push({ name: 'Latest', value: labelled(figure.value, spec.unit, true), change: figure.change });
      } else {
        let highest = -1;
        one.values.forEach((value, at) => {
          if (value !== null && (highest === -1 || value > one.values[highest])) highest = at;
        });
        tiles.push({
          name: `Highest · ${spec.labels[highest]}`,
          value: labelled(one.values[highest], spec.unit, true),
        });
      }
    } else {
      spec.series.forEach((one, at) => {
        const figure = figures[at];
        if (figure === null) return;
        tiles.push({
          swatch: theme.series[at],
          name: `${one.name} · ${figure.caption}`,
          value: labelled(figure.value, spec.unit, true),
          change: figure.change,
        });
      });
    }

    y += 12;
    let x = PAD;
    for (const tile of tiles) {
      const valueWidth = widthOf(tile.value, 20);
      const changeText = tile.change === null || tile.change === undefined ? '' : `${tile.change >= 0 ? '+' : '-'}${formatted(Math.abs(tile.change))}%`;
      const changeWidth = changeText === '' ? 0 : widthOf(changeText, 12, true) + 8;
      const nameWidth = (tile.swatch === undefined ? 0 : 16) + widthOf(tile.name, 12);
      const wide = Math.max(valueWidth + changeWidth, nameWidth, 96);
      if (x + wide > SIZE.width - PAD && x > PAD) {
        break;
      }
      parts.push(text(x, y + 20, tile.value, { size: 20, face: FACES.display, fill: theme.ink, weight: 600 }));
      if (changeText !== '') {
        parts.push(
          text(x + valueWidth + 8, y + 19, changeText, {
            size: 12,
            face: FACES.mono,
            fill: tile.change >= 0 ? theme.good : theme.bad,
            weight: 500,
          }),
        );
      }
      if (tile.swatch !== undefined) {
        parts.push(`<rect x="${x}" y="${y + 28}" width="10" height="10" rx="2" fill="${tile.swatch}"/>`);
      }
      parts.push(text(x + (tile.swatch === undefined ? 0 : 16), y + 37, tile.name, { size: 12, fill: theme.faint }));
      x += wide + 32;
    }
    y += 48;
    parts.push(`<line x1="${PAD}" y1="${y}" x2="${SIZE.width - PAD}" y2="${y}" stroke="${theme.ruleSoft}" stroke-width="1"/>`);
    y += 10;
  } else if (spec.series.length > 1) {
    /* A legend, in rows: a swatch and the name in soft ink. */
    let x = PAD;
    y += 14;
    for (const [at, one] of spec.series.entries()) {
      const wide = 16 + widthOf(one.name, 12) + 20;
      if (x + wide > SIZE.width - PAD && x > PAD) {
        x = PAD;
        y += 20;
      }
      parts.push(`<rect x="${x}" y="${y - 9}" width="10" height="10" rx="2" fill="${theme.series[at]}"/>`);
      parts.push(text(x + 16, y, one.name, { size: 12, fill: theme.soft }));
      x += wide;
    }
    y += 12;
  } else {
    y += 8;
  }

  /* The footer: where the numbers came from, in the mono face, faint. */
  let bottom = SIZE.height - PAD;
  if (spec.source !== '') {
    parts.push(text(PAD, SIZE.height - PAD + 8, spec.source, { size: 11, face: FACES.mono, fill: theme.faint }));
    bottom -= 14;
  }

  return { parts, plot: { top: y + 8, bottom: bottom - 12, left: PAD, right: SIZE.width - PAD } };
}

/**
 * The value each bar or point stands at, series by series - the value itself,
 * or for a stack the running sum with where the segment starts.
 */
function stacksOf(spec) {
  const totals = spec.labels.map(() => 0);
  return spec.series.map((one) =>
    one.values.map((value, at) => {
      if (value === null) {
        return null;
      }
      if (!spec.stacked) {
        return { from: 0, to: value };
      }
      const from = totals[at];
      totals[at] += value;
      return { from, to: totals[at] };
    }),
  );
}

/** The lowest and highest value the value axis has to reach. */
function extentOf(stacks) {
  let low = Infinity;
  let high = -Infinity;
  for (const series of stacks) {
    for (const point of series) {
      if (point !== null) {
        low = Math.min(low, point.from, point.to);
        high = Math.max(high, point.from, point.to);
      }
    }
  }
  if (low === Infinity) {
    throw new Error('every value is null: there is nothing to draw');
  }
  return { low, high };
}

/**
 * A bar from one edge to another, rounded at its data end and square at the
 * baseline. `along` is the axis the bar grows on: y for a column, x for a bar.
 * A bar too short to hold its corners is drawn as the rectangle it is.
 */
function barPath(along, x, y, width, height, growsUp) {
  const r = Math.min(MARK.cornerRadius, along === 'y' ? height / 2 : width / 2, along === 'y' ? width / 2 : height / 2);
  if (r < 1) {
    return `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${width.toFixed(1)}" height="${height.toFixed(1)}"`;
  }
  const x2 = x + width;
  const y2 = y + height;
  let d;
  if (along === 'y') {
    d = growsUp
      ? `M${x},${y2}V${y + r}Q${x},${y} ${x + r},${y}H${x2 - r}Q${x2},${y} ${x2},${y + r}V${y2}Z`
      : `M${x},${y}V${y2 - r}Q${x},${y2} ${x + r},${y2}H${x2 - r}Q${x2},${y2} ${x2},${y2 - r}V${y}Z`;
  } else {
    d = growsUp
      ? `M${x},${y}H${x2 - r}Q${x2},${y} ${x2},${y + r}V${y2 - r}Q${x2},${y2} ${x2 - r},${y2}H${x}Z`
      : `M${x2},${y}H${x + r}Q${x},${y} ${x},${y + r}V${y2 - r}Q${x},${y2} ${x + r},${y2}H${x2}Z`;
  }
  return `<path d="${d.replace(/(\d+\.\d{2})\d+/g, '$1')}"`;
}

/**
 * Columns, bars, lines and areas share an axis, a grid and a set of category
 * slots, so they share this. What differs is the mark drawn in each slot.
 */
function drawnOnAxes(spec, theme) {
  const { parts, plot } = framed(spec, theme);
  const stacks = stacksOf(spec);
  const { low, high } = extentOf(stacks);
  /* A little air past the extremes, so a bar's value label and a line's end marker are not on the edge of the plot. */
  const ticks = ticksFor(low < 0 ? low * 1.06 : low, high > 0 ? high * 1.06 : high);
  const horizontal = spec.kind === 'bar';
  const count = spec.labels.length;

  /* Room on the left for whichever labels sit there: tick values, or the categories of a bar chart. */
  const leftLabels = horizontal
    ? spec.labels.map((label) => widthOf(label, 12))
    : ticks.map((tick) => widthOf(labelled(tick, spec.unit), 11, true));
  plot.left = PAD + Math.min(220, Math.max(...leftLabels)) + 10;

  /* Room on the right for a line's end labels, which sit past the last point. */
  if (spec.kind === 'line' || spec.kind === 'area') {
    const ends = spec.series.map((one) => {
      const last = [...one.values].reverse().find((value) => value !== null);
      return last === undefined ? '' : labelled(last, spec.unit);
    });
    plot.right = SIZE.width - PAD - Math.min(120, Math.max(...ends.map((label) => widthOf(label, 12, true))) + 12);
  }

  const first = ticks[0];
  const last = ticks[ticks.length - 1];
  const valueSpan = last - first || 1;
  const width = plot.right - plot.left;
  const height = plot.bottom - plot.top;

  /* Where a value lands: down the page for a column, across it for a bar. */
  const at = (value) =>
    horizontal ? plot.left + ((value - first) / valueSpan) * width : plot.bottom - ((value - first) / valueSpan) * height;
  const slot = (horizontal ? height : width) / count;
  const centre = (index) => (horizontal ? plot.top : plot.left) + slot * (index + 0.5);

  /* The grid: a hairline per tick, the tick's value in the mono face, and a darker baseline at zero. */
  for (const tick of ticks) {
    const place = at(tick);
    const stroke = tick === 0 ? theme.rule : theme.ruleSoft;
    const said = labelled(tick, spec.unit);
    if (horizontal) {
      parts.push(`<line x1="${place.toFixed(1)}" y1="${plot.top}" x2="${place.toFixed(1)}" y2="${plot.bottom}" stroke="${stroke}" stroke-width="1"/>`);
      parts.push(text(place, plot.bottom + 18, said, { size: 11, face: FACES.mono, fill: theme.faint, anchor: 'middle' }));
    } else {
      parts.push(`<line x1="${plot.left}" y1="${place.toFixed(1)}" x2="${plot.right}" y2="${place.toFixed(1)}" stroke="${stroke}" stroke-width="1"/>`);
      parts.push(text(plot.left - 8, place + 4, said, { size: 11, face: FACES.mono, fill: theme.faint, anchor: 'end' }));
    }
  }

  /* Category labels: every one where they fit, every nth where they do not. */
  const widest = Math.max(...spec.labels.map((label) => widthOf(label, 12)));
  const every = horizontal ? Math.max(1, Math.ceil(16 / slot)) : Math.max(1, Math.ceil((widest + 8) / slot));
  spec.labels.forEach((label, index) => {
    if (index % every !== 0) {
      return;
    }
    if (horizontal) {
      parts.push(text(plot.left - 8, centre(index) + 4, label, { size: 12, fill: theme.soft, anchor: 'end' }));
    } else {
      parts.push(text(centre(index), plot.bottom + 18, label, { size: 12, fill: theme.soft, anchor: 'middle' }));
    }
  });

  if (spec.kind === 'bar' || spec.kind === 'column') {
    drawnAsBars(spec, theme, parts, stacks, { horizontal, slot, centre, at, zero: at(0) });
  } else {
    drawnAsLines(spec, theme, parts, stacks, { slot, centre, at, zero: at(0), right: plot.right });
  }

  return parts;
}

/** The bars: thin, a surface gap between neighbours, rounded at the data end. */
function drawnAsBars(spec, theme, parts, stacks, axes) {
  const lanes = spec.stacked ? 1 : spec.series.length;
  const room = axes.slot * 0.72;
  const thickness = Math.max(2, Math.min(MARK.barThickness, (room - MARK.gap * (lanes - 1)) / lanes));
  const group = thickness * lanes + MARK.gap * (lanes - 1);
  const single = spec.series.length === 1 && spec.labels.length <= 12;

  stacks.forEach((series, which) => {
    const colour = theme.series[which];
    series.forEach((point, index) => {
      if (point === null) {
        return;
      }
      const lane = spec.stacked ? 0 : which;
      const across = axes.centre(index) - group / 2 + lane * (thickness + MARK.gap);
      /*
       * A stack's segments are separated by the same gap as neighbouring bars
       * are - the surface doing the separating, not a stroke - so each
       * segment above the first starts a gap later than the sum says.
       */
      const from = axes.at(point.from) + (spec.stacked && which > 0 ? (axes.horizontal ? MARK.gap : -MARK.gap) : 0);
      const to = axes.at(point.to);
      const outermost = !spec.stacked || which === spec.series.length - 1 || stacks.slice(which + 1).every((later) => later[index] === null || later[index].to === later[index].from);
      const grows = point.to >= point.from;

      let mark;
      if (axes.horizontal) {
        const x = Math.min(from, to);
        const w = Math.abs(to - from);
        mark = outermost ? barPath('x', x, across, w, thickness, grows) : `<rect x="${x.toFixed(1)}" y="${across.toFixed(1)}" width="${w.toFixed(1)}" height="${thickness.toFixed(1)}"`;
      } else {
        const y = Math.min(from, to);
        const h = Math.abs(to - from);
        mark = outermost ? barPath('y', across, y, thickness, h, grows) : `<rect x="${across.toFixed(1)}" y="${y.toFixed(1)}" width="${thickness.toFixed(1)}" height="${h.toFixed(1)}"`;
      }
      parts.push(`${mark} fill="${colour}"/>`);

      /* One series, a dozen bars or fewer: the value at the tip, in ink. */
      if (single) {
        const said = labelled(point.to, spec.unit);
        if (axes.horizontal) {
          parts.push(
            text(grows ? to + 6 : to - 6, across + thickness / 2 + 4, said, {
              size: 12,
              face: FACES.mono,
              fill: theme.ink,
              anchor: grows ? 'start' : 'end',
            }),
          );
        } else {
          parts.push(
            text(across + thickness / 2, grows ? to - 7 : to + 14, said, { size: 12, face: FACES.mono, fill: theme.ink, anchor: 'middle' }),
          );
        }
      }
    });
  });
}

/** The lines: two pixels, round joins, a ringed marker on each point, the value at the end. */
function drawnAsLines(spec, theme, parts, stacks, axes) {
  const area = spec.kind === 'area';
  const many = spec.labels.length > 24;
  const ends = [];

  stacks.forEach((series, which) => {
    const colour = theme.series[which];
    const points = series.map((point, index) => (point === null ? null : { x: axes.centre(index), y: axes.at(point.to), base: axes.at(point.from) }));

    /* A run of points is one polyline; a null breaks the line rather than bridging it. */
    const runs = [];
    let run = [];
    for (const point of points) {
      if (point === null) {
        if (run.length > 0) runs.push(run);
        run = [];
      } else {
        run.push(point);
      }
    }
    if (run.length > 0) runs.push(run);

    for (const one of runs) {
      if (area) {
        const forward = one.map((point) => `${point.x.toFixed(1)},${point.y.toFixed(1)}`);
        const back = [...one].reverse().map((point) => `${point.x.toFixed(1)},${point.base.toFixed(1)}`);
        parts.push(`<polygon points="${[...forward, ...back].join(' ')}" fill="${colour}" fill-opacity="0.1"/>`);
      }
      if (one.length > 1) {
        parts.push(
          `<polyline points="${one.map((point) => `${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(' ')}" fill="none" stroke="${colour}" stroke-width="${MARK.line}" stroke-linejoin="round" stroke-linecap="round"/>`,
        );
      }
      /* Markers everywhere on a short series, at the ends of a long one, each with its ring of surface. */
      const marked = many ? [one[0], one[one.length - 1]] : one;
      for (const point of marked) {
        parts.push(
          `<circle cx="${point.x.toFixed(1)}" cy="${point.y.toFixed(1)}" r="${MARK.marker + MARK.ring}" fill="${theme.surface}"/>` +
            `<circle cx="${point.x.toFixed(1)}" cy="${point.y.toFixed(1)}" r="${MARK.marker}" fill="${colour}"/>`,
        );
      }
    }

    const lastIndex = series.map((point) => point !== null).lastIndexOf(true);
    if (lastIndex !== -1) {
      ends.push({ y: points[lastIndex].y, x: points[lastIndex].x, text: labelled(series[lastIndex].to, spec.unit) });
    }
  });

  /*
   * The value at the end of each line, unless two ends sit too close for the
   * labels to stay with their lines - then the legend and the axis carry it,
   * because a label nudged away from its line reads as noise.
   */
  const sorted = [...ends].sort((a, b) => a.y - b.y);
  const collide = sorted.some((end, at) => at > 0 && end.y - sorted[at - 1].y < 14);
  if (!collide) {
    for (const end of ends) {
      parts.push(text(end.x + MARK.marker + MARK.ring + 5, end.y + 4, end.text, { size: 12, face: FACES.mono, fill: theme.ink }));
    }
  }
}

/**
 * A pie, or a donut with the total in the hole. Each slice is labelled with
 * its name and its share, outside the rim on its own side; the slices are
 * kept apart by a surface-coloured stroke, which is the two-pixel gap.
 */
function drawnAsPie(spec, theme) {
  const { parts, plot } = framed(spec, theme);
  const values = spec.series[0].values.map((value) => (value === null ? 0 : value));
  const total = values.reduce((sum, value) => sum + value, 0);
  if (total <= 0) {
    throw new Error('every slice is zero: there is nothing to draw');
  }

  const cx = (plot.left + plot.right) / 2;
  const cy = (plot.top + plot.bottom) / 2;
  const radius = Math.min((plot.bottom - plot.top) / 2 - 16, (plot.right - plot.left) / 2 - 150);
  const hole = spec.kind === 'donut' ? radius * 0.62 : 0;

  let angle = -Math.PI / 2;
  const labels = [];
  values.forEach((value, at) => {
    if (value <= 0) {
      return;
    }
    const sweep = (value / total) * Math.PI * 2;
    const from = angle;
    const to = angle + sweep;
    angle = to;

    const point = (r, a) => `${(cx + r * Math.cos(a)).toFixed(2)},${(cy + r * Math.sin(a)).toFixed(2)}`;
    const large = sweep > Math.PI ? 1 : 0;
    /* A single slice is a full circle, which an arc cannot spell; drawn as two halves. */
    const whole = sweep >= Math.PI * 2 - 1e-9;
    let d;
    if (whole) {
      d = hole > 0
        ? `M${point(radius, from)}A${radius},${radius} 0 1 1 ${point(radius, from + Math.PI)}A${radius},${radius} 0 1 1 ${point(radius, from)}Z` +
          `M${point(hole, from)}A${hole},${hole} 0 1 0 ${point(hole, from + Math.PI)}A${hole},${hole} 0 1 0 ${point(hole, from)}Z`
        : `M${point(radius, from)}A${radius},${radius} 0 1 1 ${point(radius, from + Math.PI)}A${radius},${radius} 0 1 1 ${point(radius, from)}Z`;
    } else if (hole > 0) {
      d = `M${point(radius, from)}A${radius},${radius} 0 ${large} 1 ${point(radius, to)}L${point(hole, to)}A${hole},${hole} 0 ${large} 0 ${point(hole, from)}Z`;
    } else {
      d = `M${cx},${cy}L${point(radius, from)}A${radius},${radius} 0 ${large} 1 ${point(radius, to)}Z`;
    }
    parts.push(`<path d="${d}" fill="${theme.series[at]}" fill-rule="evenodd" stroke="${theme.surface}" stroke-width="${MARK.gap}"/>`);

    const mid = (from + to) / 2;
    labels.push({
      angle: mid,
      name: spec.labels[at],
      share: `${formatted((value / total) * 100)}%`,
      right: Math.cos(mid) >= 0,
    });
  });

  /*
   * Labels outside the rim, each on the side its slice faces, spread apart
   * where two would overlap - and joined to the slice by a leader where the
   * spreading moved one off its own angle.
   */
  for (const side of [true, false]) {
    const own = labels.filter((label) => label.right === side).sort((a, b) => Math.sin(a.angle) - Math.sin(b.angle));
    let floor = -Infinity;
    for (const label of own) {
      let y = cy + (radius + 22) * Math.sin(label.angle);
      if (y < floor + 18) y = floor + 18;
      floor = y;
      const anchorX = cx + (radius + 6) * Math.cos(label.angle);
      const anchorY = cy + (radius + 6) * Math.sin(label.angle);
      const x = side ? cx + radius + 24 : cx - radius - 24;
      parts.push(
        `<polyline points="${anchorX.toFixed(1)},${anchorY.toFixed(1)} ${(side ? x - 8 : x + 8).toFixed(1)},${y.toFixed(1)}" fill="none" stroke="${theme.rule}" stroke-width="1"/>`,
      );
      const anchor = side ? 'start' : 'end';
      const nameWidth = widthOf(label.name, 12);
      parts.push(text(x, y + 4, label.name, { size: 12, fill: theme.soft, anchor }));
      parts.push(
        text(side ? x + nameWidth + 8 : x - nameWidth - 8, y + 4, label.share, { size: 12, face: FACES.mono, fill: theme.ink, anchor }),
      );
    }
  }

  /* The donut's hole holds the total: the one number the whole chart adds up to. */
  if (hole > 0) {
    parts.push(text(cx, cy + 8, labelled(total, spec.unit, true), { size: 26, face: FACES.display, fill: theme.ink, weight: 700, anchor: 'middle' }));
    parts.push(text(cx, cy + 26, 'total', { size: 11, face: FACES.mono, fill: theme.faint, anchor: 'middle', spacing: 1 }));
  }

  return parts;
}

/** The whole drawing, as markup declaring its own size. */
function svgOf(spec, theme) {
  const parts = spec.kind === 'pie' || spec.kind === 'donut' ? drawnAsPie(spec, theme) : drawnOnAxes(spec, theme);
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${SIZE.width} ${SIZE.height}" width="${SIZE.width}" height="${SIZE.height}" role="img">\n` +
    (spec.title === '' ? '' : `<title>${escaped(spec.title)}</title>\n`) +
    parts.join('\n') +
    '\n</svg>'
  );
}

/**
 * The same markup declaring the size it is to be drawn at. Both sides, so the
 * document's own aspect equals the viewBox's and a rasteriser has nothing to
 * letterbox against - the lesson the diagram plugins learned the hard way.
 */
function sized(svg, width, height) {
  const close = svg.indexOf('>');
  const root = svg.slice(0, close).replace(/\s(?:width|height)="[^"]*"/g, '');
  return `${root} width="${width}" height="${height}"${svg.slice(close)}`;
}

/** The picture's size: what was asked for, or 1200 across, where the text reads without squinting. */
function drawnSize(asked) {
  const width = typeof asked === 'number' && asked > 0 ? Math.round(asked) : 1200;
  return { width, height: Math.round((width * SIZE.height) / SIZE.width) };
}

/** The same drawing with the bytes taken out, which is what a model wants; the key names them. */
function keyedOnly(drawing) {
  if (drawing.key.length === 0) {
    return drawing;
  }
  return { ...drawing, svg: '', png: '' };
}

export default class Charts extends OrknuxPlugin {

  id() {
    return 'charts';
  }

  apiVersion() {
    return 1;
  }

  parameters() {
    return [];
  }

  permissions() {
    // None. A chart is arithmetic on a list of numbers, written out as markup.
    return [];
  }

  capabilities() {
    /*
     * Drawing is the server's to do, and only drawing. The chart is laid out
     * in this file; turning the markup into a picture needs a rasteriser this
     * sandbox does not have. Nothing is fetched, from anywhere.
     */
    return ['RENDER_PNG'];
  }

  skills() {
    return [
      new OrknuxSkill({
        name: 'Drawing a chart',
        description: 'Which chart fits which question, what the spec looks like, and how a chart reaches somebody.',
        content: `# Drawing a chart

\`charts_render\` draws a chart from numbers you already have. It is for a
picture of a **quantity** - how much, how it moved, what share - where the
diagram tools draw the shape of a system.

## Which chart

| The question | type |
|---|---|
| How do these categories compare? | \`column\` (a few, short names) or \`bar\` (many, or long names) |
| How did it change over time? | \`line\` - or \`area\` for one series where the amount matters |
| How does the whole break down? | \`donut\` (the total in the middle) or \`pie\`; up to eight slices |
| How do the parts of each category add up? | \`column\` or \`bar\` with \`"stacked": true\` |

**One axis.** Two measures on different scales - revenue and headcount - are
two charts, never one with two axes.

## The spec

JSON, as the first argument:

    {
      "type": "column",
      "eyebrow": "Finance · Q1-Q4 2026",
      "title": "Revenue by quarter",
      "subtitle": "In thousands, before tax",
      "unit": "$",
      "labels": ["Q1", "Q2", "Q3", "Q4"],
      "series": [
        { "name": "Product", "values": [412, 468, 455, 521] },
        { "name": "Services", "values": [120, 131, 149, 158] }
      ],
      "source": "Source: the finance workbook, 25 September 2026"
    }

- \`labels\` are the categories along the axis, or the slices of a pie.
- \`series\` is one entry per line or bar colour, each with a \`name\` and as
  many \`values\` as there are labels. A single series can be written as
  \`"values": [...]\` at the top level instead. A \`null\` is a gap.
- \`unit\` is written on every number: a currency sign before it, \`%\` or a
  word after it.
- \`eyebrow\` is the small line above the title saying what the chart is
  about - a team, a period, a system. \`source\` is the footer line saying
  where the numbers came from. Both are worth filling in: a picture posted to
  a channel has to make its point without the paragraph around it.
- \`stacked\` stacks the series of a bar, column or area chart.
- The row of figures above the plot - each series' total or latest value and
  how far it moved, or total, average and latest for one series - is drawn
  by default. \`"summary": false\` leaves it out.

**Eight series at most**, and the eighth is already hard to tell from the
third. Fold the small ones into "Other" before you get there.

## Arguments

\`theme\` is \`light\` (the default) or \`dark\`. \`format\` is \`png\` unless you
say otherwise, and png is what you want: Slack draws no SVG. \`width\` is the
picture's width in pixels, 1200 when left out.

## When it refuses

The error is a sentence saying what it expected - a type it has not got, a
series with more values than there are labels, a ninth series, a negative in
a pie. Fix that one thing and call again; do not rewrite the whole spec.

## Getting it to somebody

The answer carries a **key**, not the picture. Pass the key:

    charts_render(spec)  ->  { key: 'charts.1fpehnu', width: 1200, height: 720 }
    slack_uploadBinary(channel, 'revenue.png', 'charts.1fpehnu', comment, threadTs)

Into the message, where whoever asked is already looking - not saved as an
artifact for them to go and find.`,
      }),
    ];
  }

  objects() {
    return [
      new OrknuxObject({
        name: 'Chart',
        description: 'A chart drawn here, in the sandbox.',
        properties: [
          {
            name: 'svg',
            kind: 'string',
            description: 'The SVG markup, where svg was asked for. Empty for png.',
          },
          {
            name: 'png',
            kind: 'string',
            description: 'The picture as base64, where png was asked for - the default. Empty for svg.',
          },
          { name: 'bytes', kind: 'number', description: 'How long the answer is.' },
          {
            name: 'width',
            kind: 'number',
            description: 'How wide the picture came out, in pixels - read off the file, so a server ceiling shows here. For an svg, what the markup declares.',
          },
          { name: 'height', kind: 'number', description: 'How tall it came out.' },
          {
            name: 'key',
            kind: 'string',
            description:
              'Where this chart is kept for the rest of this session. Pass it rather than copying the answer out - slack_uploadBinary takes it for a png, slack_upload as contentKey for an svg. Empty where there was no session to keep it in.',
          },
        ],
      }),
    ];
  }

  /*
   * A tool of its own rather than a proxy, for the reason the diagram plugins
   * have one: what a model should be handed is the key, not the several
   * thousand characters of picture the key names.
   */
  tools() {
    const drawing = this.functions().find((one) => one.name === 'render');
    return [
      new OrknuxTool({
        name: 'render',
        description:
          drawing.description +
          ' The answer carries the key and not the drawing itself: the bytes stay on the server ' +
          'and the key names them, so pass it to slack_uploadBinary as contentKey. Where there is ' +
          'no session to keep a drawing in, the drawing comes back instead.',
        params: drawing.params,
        returnType: drawing.returnType,
        run: (spec, theme, format, width) => keyedOnly(drawing.run(spec, theme, format, width)),
      }),
    ];
  }

  functions() {
    return [
      new OrknuxFunction({
        name: 'render',
        description:
          'Draws a chart right here - no service, no browser - from a JSON spec: {"type", "eyebrow", ' +
          '"title", "subtitle", "unit", "labels", "series": [{"name", "values"}], "stacked", ' +
          '"source", "summary"}. type is bar, column, line, area, pie or donut; labels are the ' +
          'categories along the axis or the slices of a pie; each series has one value per label, ' +
          'null for a gap, and a single series may be written as "values" alone. unit goes on ' +
          'every number - a currency sign before, % or a word after. eyebrow is the small line ' +
          'above the title saying what the chart is about; source is the footer line saying where ' +
          'the numbers came from. A row of figures above the plot - each series\' total or latest ' +
          'value and how far it moved - is drawn unless summary is false. Up to eight series, in a ' +
          'fixed order of hues that stay apart under colour-blindness; one axis; values labelled at ' +
          'the end of a line and on the bars of a single series. theme is light (the default) or ' +
          'dark. format is png (the default) for a picture people can see, or svg for the markup; ' +
          'width is the picture width in pixels, 1200 when left out. Answers png as base64 or svg ' +
          'as text, the byte count, the width and height it came out at, and a short key the ' +
          'answer is kept under for this session - pass that key to slack_uploadBinary rather ' +
          'than copying the answer out.',
        params: [
          { name: 'spec', type: 'string' },
          { name: 'theme', type: 'string', required: false, default: 'light' },
          { name: 'format', type: 'string', required: false, default: 'png' },
          { name: 'width', type: 'number', required: false, default: 0 },
        ],
        returnType: 'Chart',
        run: (spec, theme, format, width) => {
          if (typeof spec !== 'string' || spec.trim().length === 0) {
            throw new Error('there is no spec to draw');
          }

          const named = typeof theme === 'string' && theme.trim().length > 0 ? theme.trim().toLowerCase() : 'light';
          const palette = THEMES[named];
          if (palette === undefined) {
            throw new Error(`no theme called ${theme}: it is ${Object.keys(THEMES).join(' or ')}`);
          }

          /* Checked before the drawing, so a typo costs nothing. */
          const asked = typeof format === 'string' && format.length > 0 ? format.trim().toLowerCase() : 'png';
          if (asked !== 'png' && asked !== 'svg') {
            throw new Error(`no format called ${format}: it is png or svg`);
          }

          const svg = svgOf(specOf(spec), palette);

          if (asked === 'png') {
            const want = drawnSize(width);
            const drawn = orknux.render.pngFromSvg(sized(svg, want.width, want.height), want.width);
            if (drawn.error !== undefined) {
              throw new Error(`could not draw the chart: ${drawn.error}`);
            }
            const drawnKey = keyFor(drawn.base64);
            const drawnKept = orknux.session.store.put(drawnKey, drawn.base64);
            return {
              svg: '',
              png: drawn.base64,
              bytes: drawn.bytes,
              width: drawn.width,
              height: drawn.height,
              key: drawnKept.error === undefined ? drawnKey : '',
            };
          }

          const key = keyFor(svg);
          const kept = orknux.session.store.put(key, svg);
          return {
            svg: svg,
            png: '',
            bytes: svg.length,
            width: SIZE.width,
            height: SIZE.height,
            key: kept.error === undefined ? key : '',
          };
        },
      }),
    ];
  }
}

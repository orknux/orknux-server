---
name: Diagrams and charts
description: Which tool draws what, the syntax that works, and how a drawing reaches a chat, an HTML page, a PDF or an archive.
---

# Diagrams and charts

## Objective

Draw the picture the work needs with the tool made for it, in syntax that tool
reads, and get it to where it is going by its key - never by typing the drawing
out.

## Which tool

- **A diagram** - boxes and arrows, a sequence, states, a timeline - is
  `diagram_render`.
- **A chart** - numbers with labels - is `charts_render`. A pie is a chart, not
  a diagram.
- **A report with drawings in it, as a PDF** is `pdf_fromHtml` with the drawing
  written into the page as a block (see below). Do not draw first and paste.

## Diagram syntax

`diagram_render` reads mermaid, or PlantUML written out between `@startuml` and
`@enduml`. Send the source only: no code fence, and no `mermaid` line on top -
the first line is the diagram's own header.

Mermaid headers that draw: `flowchart LR` / `graph TD` (and TB, BT, RL),
`sequenceDiagram`, `classDiagram`, `erDiagram`, `stateDiagram-v2`, `gantt`,
`mindmap`, `journey`.

In a flowchart:

    flowchart LR
      UI[Console] -- GraphQL --> API[Server]
      API -->|SQL| DB[(PostgreSQL)]
      API --> Q{Queued?}
      subgraph Core
        API
        DB
      end

- Boxes `A[text]`, a database `A[(text)]`, a circle `A((text))`, a decision
  `A{text}`.
- Edge labels either way: `A -- text --> B` or `A -->|text| B`.
- `subgraph Name` ... `end` groups boxes; list a box by its id inside it.
- `classDef`, `style` and `click` lines are ignored - colours do not survive.

If it refuses, the answer says what failed. Simplify the line it names rather
than switching languages at random.

## Chart syntax

`charts_render` takes a `kind` - bar, column, line, area, pie, donut, scatter -
a `values` object of label to number, and a `title`:

    {"kind": "column", "title": "Commits per repository",
     "values": {"server": 134, "ui": 40, "website": 28}}

A bar lies along the axis; a column stands up. Keep labels short.

## PNG or SVG

Both answer a `contentKey`, never the picture itself.

- **PNG** (the default) for a chat, a message, anything that shows a picture
  inline.
- **SVG** for an HTML page: it is markup, so it goes into the page and stays
  sharp.

## Getting it where it is going

- **Into a chat or a channel:** a PNG key to the tool that uploads *bytes* - a
  binary upload - not the one that uploads text. Text uploads are for things you
  could read: CSV, JSON, markdown.
- **Into an HTML page you are writing in a scratchpad:** draw as SVG, then
  `scratchpad_append` with the key as `key`, or put a placeholder like
  `DIAGRAM_1` in the page and `scratchpad_replace` it with the key as `newKey`.
  The SVG goes in as markup and draws in any browser, offline too.
- **Into a PDF:** write the drawing into the page and let `pdf_fromHtml` draw it:
  `<pre class="mermaid">…</pre>` for a diagram, `<pre class="chart">{"kind":…}</pre>`
  for a chart, or `<img src="KEY">` for something already drawn.
- **Into an archive:** pass the key to `zip_files` with the file name the page
  uses, `images/flow.png`.

## What does not work

- **Scripts that draw.** No JavaScript runs when a PDF is laid out, so Chart.js,
  mermaid.js and anything in a `<canvas>` come out blank. In an HTML page they
  need the reader to be online; a drawn SVG does not.
- **A key in an `<img src>` of a page somebody opens.** A key means something
  only in this session. Put the SVG in as markup, host the picture, or zip it
  beside the page.
- **Typing a drawing out.** An SVG is thousands of characters; written into a
  call it is cut off at your output limit. Pass the key.

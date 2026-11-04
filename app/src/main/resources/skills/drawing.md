---
name: Diagrams and charts
description: Which tool draws what - a diagram, a chart, a picture, or a mockup of a screen or form as a wireframe - the syntax that works, and how a drawing reaches a chat, an HTML page, a PDF or an archive.
---

# Diagrams and charts

## Objective

Draw the picture the work needs with the tool made for it, in syntax that tool
reads, and get it to where it is going by its key - never by typing the drawing
out.

## Which tool

- **A mockup or wireframe** - of a screen, a page, a form, a dialog, an app's
  UI: a sign-up form, a dashboard, a settings screen - is a wireframe, drawn
  with `orknux_create_wireframe` where you have it (in hand, or loaded with the
  tool search). Not SVG: a wireframe is something the person can open and
  change in the wireframe editor, and a picture is not. A picture of it goes
  into the conversation by itself; answer with the wireframe's `url`, which is
  where it opens. Change one with `orknux_change_wireframe`, never by drawing a
  new one. Checkboxes, radio buttons and switches are elements of their own,
  and any element can be given a colour, a gradient, a border or a shadow: the
  tool's description lists the kinds and the style props there are.
- **A diagram of the workspace's systems, a process or an architecture that
  people will keep** - one they open, change by hand and come back to - is
  drawn with `{{tool:orknux_create_diagram}}` and changed with
  `{{tool:orknux_change_diagram}}`, where you hold them; see *Drawing a diagram
  people keep* below.
- **A diagram** - boxes and arrows, a sequence, states, a timeline - as a
  picture for this answer is `diagram_render`.
- **A chart** - numbers with labels - is `charts_render`. A pie is a chart, not
  a diagram.
- **Anything else** - an icon, a badge, an illustration, a picture made of
  shapes - is `svg_draw`, with SVG you write yourself. Any colour SVG or CSS can say draws:
  names, hex, `rgb()`, `hsl()`, alpha, gradients. `svg_toPng` turns an SVG
  already kept under a key into a PNG, for a chat that will not show an SVG.
- **A report with drawings in it, as a PDF** is `pdf_fromHtml` with the drawing
  written into the page as a block (see below). Do not draw first and paste.

## Drawing a diagram people keep

A diagram in the editor is read by people, so it is not done when it is
saved: it is done when it reads. A picture is too small for you to see that
two lines share a run or that one passes behind a box, so measure it instead:

1. **Lay it out on a grid.** Boxes about 160 by 60, 80 pixels or more apart,
   flowing left to right or top to bottom. Leave room between boxes for lines
   to pass: a gap narrower than about 40 pixels has no room for one.
2. **Keep labels short.** A box's label is a few words; a line's is one short
   line. The rest goes in the box's notes.
3. **Route the lines** with `{{tool:orknux_route_diagram}}` once the boxes are
   where they belong. It runs every line in right angles around the boxes and
   off the other lines, and answers line by line what came of each, with the
   problems left before and after.
4. **Check it** with `{{tool:orknux_check_diagram}}` before saying it is done,
   and after every change that moves boxes or adds lines. It names each
   problem with its ids and where it is:
   - `lines-overlap` or `line-through-shape`: route those lines again; where a
     line still cannot get through, move the boxes apart and route again.
   - `shapes-overlap`: move one of the boxes.
   - `label-cut`: make the box bigger, or the label shorter and the rest notes.
   - `label-covered`: move the boxes, or route again so the line runs elsewhere.
   - Warnings (`lines-cross`, `label-on-line`) are fine now and then; many of
     them mean the boxes want a different order.
5. **Link what is described elsewhere.** A box can carry a link - a web
   address, or another diagram - which a reader opens from it. A box that
   stands for a whole system drawn in another diagram links to that diagram;
   an `off-page` shape is the one to use for a jump and nothing else.
6. **Say what is left.** When the check still finds errors you could not fix,
   say which, rather than calling the diagram clean.

### Which shape for what

Pick the shape a reader already knows the meaning of; a diagram of plain boxes
says less. Give each node its `kind`:

- **Plain parts.** `box` - a system, a service, a step. `rounded` - a start
  or an end, a person, a team. `group` - an area others sit in (a network, a
  cluster, a team), as their `parent`. `note` - a remark about the diagram,
  not part of it.
- **Flow and decisions** (flowcharts, processes). `diamond` - a decision.
  `step` - one stage of a pipeline, chevrons left to right. `subroutine` - a
  step described elsewhere. `delay` - a wait or a timer. `octagon` - a stop,
  where a flow ends or is refused. `ellipse` - a state or an event.
  `hexagon` - preparation, setup. `triangle` - a warning, a merge.
- **Data and documents.** `database` - a database. `queue` - a message
  queue, a topic, a stream (Kafka, RabbitMQ, SQS). `storage` - object or file
  storage (S3, blob storage). `document` - one document or file;
  `documents` - many. `parallelogram` - input or output; `manual-input` -
  data typed in by hand; `display` - something shown on a screen.
- **Where people and the world meet the system.** `actor` - a person or an
  outside system that acts. `browser` - a web application or page. `mobile` -
  a phone app. `cloud` - the internet, a cloud provider, anything outside.
- **Machines.** `server` - a host, a virtual machine, a cluster of them.
  `device` - a UML node, an execution environment.
- **UML.** `class` (first line the name, `--` between compartments),
  `component`, `package`, `interface`, and `boundary`, `control`, `entity` for
  the three kinds of object in a use case.
- **Pointing things out.** `callout` - a comment or a quote. `star` - a
  highlight, a milestone. `off-page` - a jump to another page or diagram,
  with a link.
- **Pictures.** `image` - a logo, a screenshot, a photo. Its `image` is the
  picture's address on the web, `https://...`, and its label a caption under
  it; not `link`, which is what a click opens.

The tool's own description lists the same kinds with what each is for, and
is the one to trust where they differ.

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
a `values` object of label to number, a `title`, and - where the reader needs
telling - `labelAxis` and `valueAxis`, the words written along each axis:

    {"kind": "column", "title": "Commits per repository",
     "values": {"server": 134, "ui": 40, "website": 28},
     "valueAxis": "Commits"}

An axis left unnamed has no title. A bar lies along the axis; a column stands
up. Keep labels short. The same keys work in a `<pre class="chart">` block.

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

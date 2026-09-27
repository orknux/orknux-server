---
name: Making a PDF
description: What pdf_fromHtml takes, how to put a diagram, a chart or a picture in a report, and how to check what came out.
---

# Making a PDF

`pdf_fromHtml` takes HTML and answers a document. It is a report writer built
on a real layout engine, so write the page the way you would write any page.

## What it sets

Real CSS. Headings, paragraphs, lists, **tables with borders**, page breaks,
colours, widths, alignment. `<style>` in the head and `style=` on an element
both work. There is a plain house style underneath, and anything you set wins.

Useful ones, because a report needs them and they are easy to forget:

    <p style="page-break-before: always">…</p>   <!-- start a new page -->
    <table style="page-break-inside: avoid">     <!-- keep a table whole -->

Every alphabet the machine has a font for. Polish, Czech, Turkish, Greek,
Cyrillic — not the Latin-only subset an older version was limited to.

## Putting a drawing in the page

Three ways, and all three are drawn onto the page rather than pasted on, so
they stay sharp at any zoom.

**A diagram** — mermaid, or PlantUML written out:

    <h2>How a delivery is handled</h2>
    <pre class="mermaid">
    flowchart LR
      A[Webhook] --> B{Verified?}
      B -->|yes| C[Describe]
    </pre>

**A chart** — a kind, some labelled numbers, a title:

    <pre class="chart">
    {"kind":"pie","title":"Where the money went","values":{"Rent":45,"Food":30,"Rest":25}}
    </pre>

`kind` is bar, column, line, area, pie, donut or scatter. A bar lies along the
axis; a column stands up.

**A picture** — by the name of a scratchpad in this session, or a key something
handed you:

    <img src="chart.png">

Do **not** call `diagram_render` or `charts_render` to get a picture and then
try to put that in the page. The block is the way in, and it is one call
instead of three. Those two tools are for when somebody wants the picture
itself, to look at or to send.

A web address in an `img` is refused: a page is not fetched while it is laid
out. Put the file in a scratchpad first.

## Do not retype the page through yourself

If the report is already in a scratchpad, pass `scratchpad` with its name and
leave `html` out. The page is read on the server. Reading it back into your own
output and passing it as `html` costs the turn twice and, past your output
limit, arrives cut in half.

## What comes back

A `contentKey`, not the document. Pass that key to whatever uploads, sends or
saves a file. The bytes are hundreds of thousands of characters of base64 and
do not survive being written out through you.

Also `pages`, `bytes`, and `problems` — a list of anything that could not be
drawn. **Read `problems`.** A document is still produced when one diagram
fails, with a note where the drawing would have been, so an empty `problems` is
the only evidence that the report is whole.

## Checking what came out

`pdf_preview` draws a page as a picture so you can look at it. Worth doing for
anything you are about to send to a person: a table that broke across a page or
a diagram that came out tiny is obvious in a picture and invisible in a success
message.

`pdf_read` reads a document's text back — for finding a total or quoting a
clause from something you were sent.

One thing it cannot tell you: the words inside a diagram or chart are drawn as
shapes, not as text, so they will not appear in `pdf_read` and cannot be
searched for in a PDF reader. That is normal and not a sign the drawing failed.

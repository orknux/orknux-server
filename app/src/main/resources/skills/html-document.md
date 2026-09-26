---
name: Complex HTML
description: Build an HTML document or site in scratchpads, and hand it over as one archive with its pictures inside.
---

# Complex HTML

## Objective

Anything made of HTML that is more than a snippet: a report somebody opens
offline, a page of results, a site of several pages. It is one or more files
that refer to each other, so the work is not one long answer. Build each file in
a scratchpad, keep them consistent, and deliver the whole thing as one archive
somebody can open.

A single-page report follows the same steps with fewer files: the page, its
stylesheet where it has one, and its pictures.

## Steps

1. **Say the shape in one line, then build it in the same turn.** Which files
   there will be and what each is for - one sentence, not a proposal. Do not
   wait for approval: somebody who asked for a report wants the report, and a
   turn that ends on a question has delivered nothing. Ask only where the
   request is genuinely ambiguous and you would otherwise build the wrong thing,
   and say what you will do if nobody answers.
2. **One scratchpad per file.** `index.html`, `about.html`, `styles.css`, and so
   on, named as they will be named inside the archive. Never compose a page
   inside your answer: it costs the page twice and gets cut off.
3. **Edit in place as you go.** Once a pad exists, change it with the replace
   tool and add to it with append, the way a file is edited. Writing the whole
   document again to change one section is the slow way and the way work gets
   lost.
4. **Share what is shared.** One stylesheet, linked by every page with a
   relative href; one header and footer written the same way on each page. Make
   the links between pages relative too - `about.html`, never a full address and
   never a path beginning with a slash.
5. **Read each pad back before you pack it.** What you meant to write and what
   is in the file are two different things, and the archive is made from the
   file.
6. **Pack it into one archive** with the zip tool: every page, the stylesheet,
   and every picture, each named exactly as the HTML refers to it. A name may
   carry a folder - `charts/metals.png` puts it in a folder inside the archive,
   which is all a folder is. Pass the scratchpad names and the content keys;
   never retype a file's content into the call.
7. **Hand over the archive's key** to whatever sends, uploads or saves a file,
   and say in one line what is in it.

## Pictures

This is the part that goes wrong.

- **A picture already on the web** is linked by its address, and nothing needs
  packing. This is the best answer where it is available: the page stays small
  and the picture stays where it is maintained.
- **A picture you made or were given** - one you drew, one somebody attached,
  one a tool handed you a key for - does not have an address anybody else can
  reach. Reference it by a relative path, `images/chart.png`, and put the file
  in the archive under exactly that path. A page that points at a key, a
  session, or a file on this machine is a page whose images are broken for
  everyone but you.
- **Do not paste a picture into the HTML as base64** unless it is tiny and there
  is one of them. It makes the page several times the size of the picture, and
  the model pays for every character of it.
- Where a picture cannot be packed and cannot be linked, leave it out and say so
  in a line. A broken image is worse than an absent one.

## What not to do

Do not deliver a site as several separate files in several messages: whoever
receives it has to reassemble something they did not design. Do not inline a
stylesheet into every page to avoid a second file. And do not describe the site
in prose instead of building it - a page that exists is the answer, and a
description of one is not.

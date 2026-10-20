---
name: Box Code Reading
description: Load before you answer a question about how code works - where something is defined, who calls it, how a value travels. Clone it into a box and search it with ripgrep, ctags and ast-grep instead of opening files one by one.
grant: box
---

# Reading code in a box

Opening files one at a time through an API costs a round each, and every file
you open stays in the conversation. A checkout in a box answers the same
questions in a command, and you read only the lines that matter.

## Checklist

1. `orkbox_open` with `git_url`, `git_depth: 1`, and `git_ref` for a branch or
   tag you were told. A private repository needs the token the box was given;
   if the clone is refused, say so rather than falling back to file-by-file.
2. Index the definitions once: `ctags -R --fields=+n --extras=+q -f .tags .`
3. Answer with the three tools below, narrowest first.
4. Read only what they point at: `sed -n '120,170p' path/File.kt`, never `cat`
   on a whole file you have not located a line in.
5. Say which files and lines your answer rests on, as `path:line`.

## The three tools

**Where is it defined - ctags.** Exact definitions, not every mention.

- `grep -P '^placeOrder\t' .tags` - the definition of `placeOrder`, with its
  file and line.
- `grep -P '^OrderService\t' .tags` - a class.
- `ctags --list-kinds=Kotlin` says what kinds it indexes (classes, methods,
  properties...).

**Who uses it - ripgrep.** Fast text search, with context.

- `rg -n -C2 'placeOrder\('` - every call, two lines around each.
- `rg -n -t kotlin -t java 'OrderStatus\.SHIPPED'` - only Kotlin and Java files.
- `rg -l 'placeOrder\('` - just the files, when there are many hits.
- Exclude tests when they drown the answer: `-g '!**/test/**'`.

**What shape it has - ast-grep.** Search by syntax, not text: what ripgrep
cannot tell apart.

- `ast-grep -p 'send($A, null)' -l kotlin` - calls of `send` whose second
  argument is `null`.
- `ast-grep -p '$X.status = $V' -l java` - every assignment to a `status`
  field, whatever the object.
- `ast-grep -p 'fun $F($$$): Order { $$$ }' -l kotlin` - functions returning
  `Order`.
- `$A` matches one node, `$$$` any number. `-l` names the language: `java`,
  `kotlin`, `typescript`, `python`...

## Following a flow

To trace how a value gets somewhere - an order's status, a header, a flag -
work backwards one hop at a time:

1. Find where it is finally used or set (`rg`, or `ast-grep` for assignments).
2. For the function that does it, find its callers (`rg -n 'name\('`).
3. Repeat from each caller until you reach the entry point: a controller, a
   listener, a scheduled job.
4. Write the chain down as you go, `file:line` per hop, in a scratchpad if it
   is long - not in your head.

## The rules

- Names are text to these tools. Two methods called `save` on different
  classes look the same: check the file and the class before claiming a call
  goes where you think.
- Do not paste large blocks of code into your answer. Quote the few lines that
  prove the point and give `path:line` for the rest.
- Generated code, `build/`, `target/` and `node_modules/` are noise: ripgrep
  skips what `.gitignore` lists, and say so when a hit is in generated code.

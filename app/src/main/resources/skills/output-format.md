---
name: Output format
description: Answer as JSON, YAML or XML - the format named after the equals sign, and nothing else in the message.
---

# Output format

## Which format

The command carries it after an equals sign: `::output-format=json`,
`::output-format=yaml`, `::output-format=xml`. Read the command as it was
written in the message and use that format. Where the message names none, or
names one that is not among the three, say so in one line and answer as JSON,
which is the one everything reads.

## The rule

The whole answer is that document. Not a sentence before it, not a note after
it, not "here is the JSON you asked for", and no code fence unless the person
asked for one - whatever reads this is a parser, and a parser is what the
format is for.

## Shape

- Answer the question that was asked, in the fewest fields that say it.
  Invent no envelope: no `status`, no `data`, no `result` wrapper, unless the
  request named one.
- Where the answer is a list, the document is a list.
- Where something could not be answered, put it in the document as a field -
  `"error"` with the reason - rather than breaking out of the format to
  explain in prose.
- Keep the types honest: a number is a number, a missing value is null (JSON),
  empty (YAML) or an empty element (XML), and a date is ISO-8601.
- Name fields in lower camel case unless the request used another spelling, in
  which case match what it used.

## Per format

- **JSON** - valid JSON, UTF-8, double-quoted keys, no trailing commas, no
  comments. Nothing outside the single top-level value.
- **YAML** - two-space indentation, no tabs, no document start marker unless
  more than one document is being sent. Quote a string that could be read as a
  number, a boolean or a date.
- **XML** - one root element, every element closed, attributes for what
  identifies a thing and elements for what it holds. Escape `&`, `<` and `>`;
  put anything that cannot be escaped in CDATA.

## Where it cannot be done

Where the answer is genuinely not data - a refusal, a question back, something
that needs a paragraph - say it inside the format as a single field rather
than abandoning it. The one exception is a request to stop using the format:
that is a person talking to you, and it is answered in words.

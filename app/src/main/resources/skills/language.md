---
name: Language
description: Answer in the language named after the equals sign - the whole reply, not a translation stapled to it.
---

# Language

## Which language

The command carries it after an equals sign, and either spelling works: a code
or the language's name. `::language=pl` and `::language=polish` are the same
request, so are `::language=en` and `::language=english`, `::language=cz`,
`::language=cs` and `::language=czech`, `::language=de` and `::language=german`.
Read what was written in the message and answer in that language.

Where the code is one you are unsure of, take your best reading of it and say in
one short line, in that language, which language you took it to be. Where the
command names no language at all, or names one you cannot write, say so in one
line and answer in the language the message was written in.

## The rule

The whole answer is in that language: the first line, the headings, the labels
on a list, the sentence at the end. Not the answer in one language with a
translation after it, and no note explaining that you have switched - the person
wrote the command, so they know.

It holds for the rest of the conversation, not for one reply, until somebody
writes another `::language` or asks you in words to stop. Where a later message
comes in a different language, that is what the next reply is in only if the
command said so; otherwise keep to the language the command named.

## What stays as it was

- Code, commands, file paths, identifiers, field names and configuration keys.
  Translating `user_id` makes it a different key.
- Error text quoted from a system, and a log line. Quote it as it came, and put
  what it means in the language you are writing in.
- Names of people, products, companies and repositories.
- Numbers, units and dates as the language writes them: its decimal separator,
  its date order, its thousands mark.

## Getting it right

Write it as somebody who has the language, not as a translation: its own word
order and its own way of being polite, rather than an English sentence with the
words swapped. Keep every diacritic - a language written without its accents
reads as careless in exactly the way this command is asking you to avoid. Where
a term of art has no settled translation, use the original and put the gloss
beside it once.

---
name: Commands
description: List the commands this agent answers to, and nothing else.
---

# Commands

## Objective

Print the commands. Somebody has asked what they can write at you, so the whole
answer is the list.

## Steps

1. **Call `skill_list` first, before writing anything.** That tool is what knows
   the answer: it returns every skill you have been given, each with its id and
   what it is for. Do not answer from what you remember of your instructions,
   and do not answer from the skills loaded into this turn - the one you are
   reading now is loaded because somebody wrote its command, and it is not the
   list.
2. **Turn what it returned into the list.** One line per skill: the marker and
   the skill's `id`, then a dash, then its description in a few words. Keep the
   order the tool gave them in. Do not invent, rename, or leave any out.
3. **Write it as a list the place you are writing it renders as one.** Whatever
   channel this answer goes out on has its own way of marking a list, and the
   whole point of this answer is that somebody can read down it: put each
   command on its own line, in that channel's list syntax, and do not run them
   together into a sentence or a comma-separated line. Where you are unsure what
   the channel renders, plain lines each starting with the command read
   correctly everywhere.
4. **Say the marker once, at the top,** in a line: how a command is written and
   that it can go anywhere in a message. The marker is the one in front of the
   command that brought you here.
5. **Nothing else.** No preamble, no offer to explain further, no summary of
   what you can do in general - that is a different question, and answering it
   here is what makes this list hard to read.
6. **Where `skill_list` comes back empty,** say in one line that you have been
   given no skills, and that an administrator grants them on your agent's page.
   Do not apologise, do not list your tools instead, and do not pad the answer
   with the one skill this turn loaded.

## Shape

    Write a command anywhere in a message, like ::review.

    ::review - how this workspace reviews a change
    ::handover - the note the next shift reads
    ::plan - propose a plan and wait for approval before acting

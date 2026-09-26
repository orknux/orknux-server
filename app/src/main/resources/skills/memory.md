---
name: Memory
description: Look in memory first - answer from what this workspace has written down before answering from what you assume.
---

# Memory

## Objective

Before answering anything about how this workspace works, what it has decided,
who its customers are or what happened last time - search the memory. What is
written down there is what somebody wanted the next conversation to know, and
it beats a good guess every time.

## Steps

1. **Search before you answer.** Call `memory_search` first, with the words of
   the question: a name, a system, an abbreviation, a customer. Not "check your
   memory" as a formality - this is where the answer most likely already is.
2. **Search more than once where the first try misses.** The search matches
   words, so try the other words for the same thing: the abbreviation and what
   it stands for, the product name and the internal one, the person and the
   team. A miss on one phrasing is not an absence.
3. **Use what you find, and say it is what you found.** Where a memory answers
   the question, answer from it and name it - "the runbook says", "we decided
   in March" - so the person can go and read the same thing.
4. **Where memory and your own assumption disagree, memory wins**, unless the
   person in front of you says otherwise. Say that it disagreed rather than
   quietly picking one.
5. **Say when there was nothing.** "There is nothing written down about that,
   so this is my reading" is a different answer from "here is the answer", and
   the difference is what stops a guess becoming the record.
6. **Write down what should outlive this conversation.** When you are told
   something the next conversation would need - a decision, a name, a rule of
   thumb, who owns what - save it with `memory_save`: a short title somebody
   would search for, and the fact in a line or two. Not the whole exchange, and
   not what is already there.

## What not to do

Do not search for things memory cannot hold: the current state of a system, a
run's output, today's numbers. Memory is what was written down deliberately,
not a cache of everything that ever happened - use the tools for the live
answer and memory for what the workspace has decided about it.

---
name: Agents
description: List the agents this conversation has asked, what each is on, whether it is still working, and what came back.
---

# Agents

## Objective

Say what has been handed out and how it is going. Somebody wants to know what
your agents are doing, so the whole answer is that list.

## Steps

1. **Call `agent_asks` first.** It returns every agent this conversation has
   asked, what each was asked about, whether it is still working, what a
   finished one answered, and how many asks you have left. Do not answer from your own memory of what you sent: an
   ask you made ten turns ago may have finished since, and the tool is the only
   thing that knows.
2. **One line per ask, in the order it came back:** the agent's name, then what
   it is on, then its state in a word - working, or done. Where the tool gives a
   last-activity time, put it at the end of the line for anything still working.
   For a finished ask, add the gist of its answer in a few words after "done",
   and say that the full answer is kept where it came back with a key.
3. **Write it as a list the channel renders as one.** Each ask on its own line,
   in that channel's list syntax, never run together into a sentence.
4. **Say how many asks are left** in one short line under the list, because that
   is the next thing anybody asks.
5. **Where nothing has been asked,** say exactly that in one line, with how many
   asks you have. Where you cannot ask agents at all, say that instead, and do
   not offer to.
6. **Nothing else.** No summary of what each agent is for, no offer to chase
   one up, no guess at when something will finish, and no full answer pasted in
   - the gist is enough here.

## Shape

    Working on it:

    Researcher - reading the incident thread - working, last active 40s ago
    Writer - drafting the summary - done: a five-line draft, kept as a file

    Two asks left.

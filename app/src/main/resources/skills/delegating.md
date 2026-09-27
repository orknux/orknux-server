---
name: Delegating
description: When to ask another agent, how to write the question, and how to wait for and use what comes back.
---

# Delegating

## Objective

Hand work to another agent only when it gets the job done better than doing it
yourself, write the question so that agent can answer it without having seen
anything you have, and bring back what it produced without retyping it.

## When to ask

- **For work you have no tool for.** Another agent may hold tools, connections
  or skills you do not. That is the main reason to ask.
- **For independent pieces that can run at the same time.** Research one thing
  while another agent drafts something else.
- **Not for something you can do in one or two calls yourself.** Every ask is a
  whole conversation of its own, with its own model calls, and you have a limited
  number of them in this conversation.
- **Not to avoid a decision.** An ask that says "work out what the user wants"
  comes back as a guess.

## Writing the question

The agent you ask sees nothing of this conversation: not the person's message,
not what you have looked up, not what you have decided. Only your question.

- **Say the goal, not only the step.** "Find the three most recent failed deploys
  of the billing service and say what each failed on" beats "check the deploys".
- **Give it what it needs:** names, ids, dates, links, the channel or issue it is
  about. Anything you know that it would otherwise have to find again.
- **Say what to return, and in what shape:** a list, a yes or no with the reason,
  a draft of a message. Say when a short answer is enough.
- **For anything long, ask for a key.** A document, a report, a picture: ask it to
  put the result in a scratchpad or a file and hand back the key, rather than
  typing it into its answer.
- **Give it a short title** that says what the ask is about, so the list of asks
  reads well.

## The flow

Asking does not wait for the answer.

1. **Ask** with `ask_agent`. It answers at once that the ask has started.
2. **Carry on** with anything else the work needs. Ask other agents now too if
   the pieces are independent; a few asks can work at the same time.
3. **Call `agent_wait`** when you have nothing left to do but wait. It returns
   when your asks have finished, or after the time you give it.
4. **Call `agent_asks`** to read what came back. Each finished ask carries its
   answer, and a `contentKey` where the answer was kept as a file.
5. **If something is still working,** wait again or carry on with what you have.
   Do not ask the same agent the same thing a second time while the first ask is
   still going.

## Using what comes back

- **Pass keys on, not text.** To post, upload or save an agent's answer or a file
  it made, give the key to the tool that takes one. Do not paste a long answer
  back in.
- **Read it before you relay it.** You are answerable for what you send on. If an
  answer is wrong, incomplete or off the question, say so, or ask again with a
  better question.
- **Say where it came from when that matters** to the person, for example "the
  deploy agent found".
- **If an ask failed or was refused,** say what you could not find out rather than
  filling the gap yourself.

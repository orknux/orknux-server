---
name: Watchers
description: Wait for something outside you without checking by hand - have the server call one of your tools on an interval and wake you when the result matches.
---

# Watchers

## Objective

When you are waiting for something that will change on its own - a build to
finish, a deployment to go green, a ticket to be answered, a file to appear -
do not keep calling the tool yourself and do not promise to "check later".
Set a watcher: the server calls the tool for you every so often, and wakes
you, in this conversation, when what it returns matches the condition you
wrote. You can carry on with other work or finish your turn in the meantime.

## The tools

- `watcher_set` - starts one. Returns at once with its number.
- `watcher_list` - your own watchers, from every conversation you are in, and
  never another agent's: each one's number, the tool and arguments it calls,
  its condition, interval and timeout, when it gives up, its state, when it
  was set and last checked, and how many times it has looked. Running ones
  only, unless you pass `include_finished` as true - then the ended ones too,
  with when they ended and why.
- `watcher_finish` - ends one of yours by its number, when you no longer need
  what it waits for.
- `watcher_update` - changes one of yours by its number: the arguments, the
  condition, the result path, the interval, how often you are shown the
  result, or the note. What you leave out stays.

**Call `watcher_list` before setting a watcher.** You may already have one
on the same thing from earlier in this conversation or another; two watchers
on one build wake you twice and use up your limit.

## Setting one

`watcher_set` takes:

- `tool` - one of **your own** tools, spelled exactly as your list of tools
  spells it. The watcher calls it with your permissions, so it can do nothing
  you could not do yourself. `ask_agent` and `agent_wait` cannot be watched,
  and neither can the tools lent for a conversation - the watcher, timer,
  note, to-do and scratchpad tools.
- `arguments` - what to call it with, as a JSON object: `{"id": "42"}`, or
  `{}` for none. The same arguments every time.
- `tool_result_path` - which part of the tool's result the condition is held
  against, as a JSONPath, and you have to choose it: `$` for the whole result,
  `$.body` for the body of an `http_get`, `$.status` for a status field. A
  regex held against the whole result also finds what is in the status and
  headers, so name the field when you mean one.
- `condition_type` - `jsonpath` or `regex`.
- `condition` - what the result has to match before you are woken. See below.
- `interval_seconds` - how long between two calls.
- `timeout_seconds` - how long before it gives up.
- `note` - what you are waiting for and what you mean to do then, in your own
  words. It is handed back to you when the watcher fires, by which time you
  may have forgotten why you set it. Always write one.

Before setting a watcher, **call the tool once yourself** and look at what it
returns. Write the condition against that real result, not against what you
imagine it looks like.

## Writing the condition

### JSONPath - for a result that is JSON

A JSONPath matches when it **finds something**: a field that exists and is not
null or false, or a filter that keeps at least one thing.

That means a bare path such as `$.status` matches as soon as there is any
status at all - including "running". To wait for a value, write a filter:

- A status of done, in `{"status": "done"}`:
  `$[?(@.status == 'done')]`
- Either of two states, in `{"state": "failed"}`:
  `$[?(@.state == 'failed' || @.state == 'passed')]`
- A nested field, in `{"build": {"result": "SUCCESS"}}`:
  `$.build[?(@.result == 'SUCCESS')]`
- Any item in a list, in `{"jobs": [{"name": "test", "state": "failed"}]}`:
  `$.jobs[?(@.state == 'failed')]`
- A number crossing a line, in `{"queue": 12}`:
  `$[?(@.queue >= 10)]`
- A field appearing at all, such as a `url` that is only there once ready:
  `$.url`
- A list becoming non-empty, such as `replies`:
  `$.replies[*]`

Strings go in single quotes inside the filter. A path that is not valid is
refused when you set the watcher, with the reason - fix it and set it again.
A result that is not JSON never matches a JSONPath; use a regular expression
for those.

### Regular expression - for text, or anything else

A regular expression matches when it is **found anywhere** in the result, as
the tool returned it. Java syntax.

- The word deployed, in any case: `(?i)\bdeployed\b`
- A status inside JSON text: `"status"\s*:\s*"done"`
- One of several outcomes: `(?i)\b(succeeded|failed|cancelled)\b`
- A version number: `v2\.4\.\d+`

Remember that a result is often JSON: `"status":"done"` has quotes and maybe
spaces around the colon, which the expression has to allow for.

The **whole** result is searched, not only the part you care about. `http_get`
answers JSON with the status, the headers and the body, so a bare `0` meant
for a body of 0 also finds the 0 in a status of 200. Name the field instead -
a body of 0 is `"body"\s*:\s*"0"`, or the JSONPath `$[?(@.body == '0')]`.

The condition is checked once when you set the watcher. If it already matches
what the tool returns now, no watcher is set and you are shown what matched:
either the thing has already happened, or the condition is too broad.

### Wait for the end, not only for success

If the thing you watch can fail, write the condition so it also matches the
failure - `$[?(@.status == 'done' || @.status == 'failed')]` - and look at
what came back when you are woken. A watcher that waits only for success
waits until it times out when the build breaks.

## Choosing the interval and the timeout

- **Interval:** as long as you can bear. A build that takes ten minutes does
  not need checking every fifteen seconds; every minute or two is plenty. The
  installation sets the shortest interval allowed - the `watcher_set`
  description says how short - and the clock only looks every few seconds, so
  a very short interval is not kept exactly.
- **Timeout:** a little longer than the thing should ever take. The longest a
  watcher may run is set by the installation and stated in the `watcher_set`
  description - a week unless somebody changed it.
- `interval_seconds` cannot be longer than `timeout_seconds`.

## Looking for yourself

A condition is a guess at what "done" will look like, written before you have
seen a single result. When you are not sure it will recognise it - a status
whose words you do not know, a page whose shape may change - also give
`agent_check_interval_seconds`. While the condition has not matched, you are
woken that often with a message starting "Watcher #N has not matched yet" and
the latest result. Then:

- if what you are waiting for has in fact happened, end it with
  `watcher_finish` and act on it;
- if the condition, the result path or the arguments are wrong, fix them with
  `watcher_update`;
- if it is simply not there yet, do nothing - it carries on.

Each look costs you a turn, so ask for as few as will do: the installation
sets the shortest allowed, and it can be no shorter than `interval_seconds`.

## The limit

You may have only so many watchers running at once - ten unless the
installation says otherwise; the `watcher_set` description gives the number.
The count is across all your conversations, not just this one. When you are at
the limit, `watcher_set` refuses: end one you no longer need with
`watcher_finish`, or wait for one to fire.

## When it wakes you

A watcher ends the first time its condition matches. You are woken in the
conversation where you set it - between two of your steps if you are still
working, or by being started again if you had finished - with a message that
starts "Watcher #N fired." It says which tool and condition, on which check and
how long after you set it, your note, **what matched**, and **the whole result
the tool returned**. Act on that result; you do not need to call the tool
again to see it.

You are also told, with a message about watcher #N, when:

- it **timed out** without matching - with how many times it looked and the
  last result;
- a person **stopped** it from the Watchers page;
- it **could not go on** - because the tool was taken away from you, for
  example.

None of those will wake you a second time for the same watcher. If you still
need to wait, set a new one.

## Finishing one

Call `watcher_finish` with the watcher's number as soon as you no longer need
it - the person cancelled the request, you found the answer another way, or you
set it wrong. Unneeded watchers use up your limit and call the tool for
nothing. To change a watcher, finish it and set a new one.

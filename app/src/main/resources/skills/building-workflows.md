---
name: Building workflows
description: Load before you create or change a workflow with the orknux_ tools - how to wire a graph so it runs, passes data the editor can show, and opens as a readable drawing.
---

# Building workflows

A workflow you save is opened by a person in the editor. It has to run, and it
has to read as a drawing: cards apart, every step joined by an arrow, and every
piece of data a node uses joined by a line to the node it came from.

## Checklist

1. `orknux_workflow_parts` - the triggers, actions and conditions there already
   are. Use one before making a new one.
2. `orknux_workflow_reference` with part `graph` - the fields a node and an edge
   take. Read it; do not guess field names.
3. Make what is missing: `orknux_create_trigger`, `orknux_create_action`,
   `orknux_create_condition`.
4. `orknux_save_workflow_graph` - the whole graph at once. Fix every problem the
   answer names and save again until it names none.
5. `orknux_publish_workflow` - only when the person asked for it to go live.
   A trigger, a schedule and the API run the published copy, not the draft.

## Leave positions out

Do not send `x` or `y`. The server lays the graph out left to right: a column
per step from the trigger, nodes that are reached at the same depth stacked in
one column, with room between them for the arrows. Positions you invent are
points; a card is 260 pixels wide, and cards placed 100 apart sit on each other
and hide the edges underneath. If any two positions you give overlap, the
server throws all of them away and lays the graph out anyway - so giving them
gains nothing.

The one time to give positions is editing a graph a person has arranged: read
it with `orknux_workflow`, and send back the `x` and `y` of every node you did
not add, unchanged.

## Join every step with an edge

An edge is what runs: a node with no edge into it is never reached. Every node
except the trigger has at least one edge into it, and the first one comes from
the trigger. Out of a condition, give each edge its `branch` - `YES` or `NO`.
Out of an action or agent with `fallbackEnabled`, the edge to take on failure
is `FAILURE`; the ordinary next step has no branch.

Check before saving: follow the edges from the trigger and make sure you reach
every node, and that no node is joined to itself.

## Pass data by reference

A node's `mappings` say what it passes, one per parameter. There are two modes,
and only one of them draws a line in the editor:

- **`REFERENCE`** - the parameter is another node's output. `expression` is the
  field (`reply`, `trigger.channel`, `order.total`), and `sourceNodeKey` is the
  key of the node that produces it. The editor draws a line from that node to
  this one, which is how a person sees where a value comes from. A trigger's
  whole payload is `trigger` with the trigger node's key.
- **`VALUE`** - written text, the default when `mode` is left out. It may hold
  placeholders, `Hi {{ trigger.user }}`, which are filled when the node runs.
  Placeholders draw no line.

So: when a parameter is exactly one value from another node, write it as a
`REFERENCE` with `sourceNodeKey`, never as a `VALUE` of `{{ trigger.user }}`.
Use `VALUE` with placeholders only for text that mixes values into words. A
reference also keeps the value's type - a number stays a number and an object
stays an object - where a placeholder always makes text.

A reference must name something the source node produces. The save answers
with each reference it cannot resolve; a node reading a field nobody produces
passes nothing when it runs.

## Names

Give every node a short `name` that says what it does - "Fetch order", "Is it
a refund?" - not its kind or its action's name. A `key` is for wiring and is
never shown: short, lowercase, unique in the graph (`fetch`, `refund`).

## Changing a workflow that exists

Read it first with `orknux_workflow`. Saving replaces the whole graph, so send
back every node and edge you are keeping, with its mappings and positions, plus
your change. A draft with unpublished changes is somebody's work in progress:
the save refuses it unless you say `overwrite_draft`, and you should say it
only for a workflow you are building yourself.

## When you are done

Give the person the workflow's `url` from the answer, say whether it is
published, and name anything the last save still reported.

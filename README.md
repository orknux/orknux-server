<div align="center">

<a href="https://orknux.ai"><img src="docs/brand/orknux-mark.svg" alt="Orknux" width="96" height="96"></a>

# Orknux

### The open-source AI workspace you run yourself.

Chat, agents, workflows, research, documents, diagrams, images and video, in one
product on your own hardware. AGPL-3.0, no paid tier, nothing sent home.

[**Get Orknux**](#get-orknux) &nbsp;·&nbsp; [orknux.ai](https://orknux.ai) &nbsp;·&nbsp;
[Documentation](https://orknux.ai/docs) &nbsp;·&nbsp; [Marketplace](https://orknux.ai/market) &nbsp;·&nbsp;
[What's new](CHANGELOG.md)

[![Release](https://img.shields.io/github/v/release/orknux/orknux-server?label=release&sort=semver&color=4F7FC4)](https://github.com/orknux/orknux-server/releases/latest)
[![CI](https://github.com/orknux/orknux-server/actions/workflows/ci.yml/badge.svg)](https://github.com/orknux/orknux-server/actions/workflows/ci.yml)
[![Docker](https://img.shields.io/docker/v/orknux/orknux-server?label=docker&sort=semver&color=4F7FC4)](https://hub.docker.com/r/orknux/orknux-server)
[![Licence](https://img.shields.io/badge/licence-AGPL--3.0-4F7FC4)](LICENSE)

**Runs on** Windows · Linux · JVM · Docker

<br>

<img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/chat-rich.png" alt="A chat with one of the workspace's agents: maths, code it can run, a table, and the replies you might send next" width="900">

</div>

<br>

| | |
|---|---|
| [**Chat**](#chat) | Talk to any agent, by text or by voice. |
| [**Agents**](#agents) | Skills, memory, tools and a sandbox to build in. |
| [**Flow**](#flow) | Workflows you draw, runs that survive. |
| [**Design**](#design) | Images, video, wireframes and documents. |
| [**Explore**](#explore) | Research with sources, and diagrams agents keep. |
| [**Issues**](#issues) | A tracker beside the work, for people and agents. |
| [**Running it**](#running-it) | Your directory, your models, your network. |
| [**Plugins**](#plugins) | Slack, GitHub, Teams and more from the marketplace. |

## Chat

**A conversation with the agents your workspace defines.** Every chat is held
with one of the workspace's agents, with its instructions, tools and memory -
not with a bare model. Type, attach, or just talk to it.

- **Plan first, or let it run.** Plan mode offers the agent only tools that
  change nothing and ends with a plan to approve. Auto lets a step that would
  wait for you go ahead, within the agent's grants.
- **Any file you have.** Pictures, PDFs, spreadsheets, sound and video reach the
  model where it takes them; a large PDF or workbook is read a page at a time.
- **Voice, both ways.** Voice mode listens, answers aloud and listens again.
  Talk over it to stop it.
- **Help while you type.** The composer suggests how your sentence goes on, and
  a few likely replies wait under each answer.
- **It shows what it cost.** Tokens and money under every answer, and a running
  total per chat.

<table><tr>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/chat-plan.png" alt="Plan mode"></td>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/chat-suggested-replies.png" alt="Suggested replies"></td>
</tr></table>

## Agents

**One agent, whether somebody asks, a run calls, or it works alone.** An agent
is a model with a briefing, skills, memory, tools and limits. The same
configuration answers in a chat, runs as a node in a workflow and works a task
to its end.

- **Skills and memory, read when needed.** A skill says how to work and a memory
  says what is true here; the agent searches them rather than carrying them in
  every prompt.
- **Tools it is granted, and finds.** The workspace's JavaScript, MCP servers,
  plugins and dozens of built-ins. It searches for and loads what it needs, and
  can ask for what it lacks.
- **A box to build in.** A sandbox where it clones a repository, edits, builds
  and tests - built in, or the separate [orkbox](https://github.com/orknux/orkbox)
  service, where every session is a container thrown away with it.
- **It works with others, and with time.** It asks other agents, sets reminders
  and schedules, and sets watchers that wake it when a build goes green.
- **Tasks: a problem, left with it.** Give an agent a goal and it works until it
  is done, stops to ask, or reaches its limits.
- **It can drive Orknux itself.** It reads and builds workflows, documents,
  diagrams and research - and the same tools are served over MCP, for an agent
  in your editor.

<table><tr>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/task.png" alt="A task at work"></td>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/boxes.png" alt="Boxes"></td>
</tr></table>

## Flow

**A workflow is a graph you draw, and a run outlives a restart.** Triggers,
actions, conditions and agents are defined once and drawn together. Runs are
carried by Temporal, so a workflow survives the restart of every process
involved.

- **Something fires it.** A cron schedule in its own timezone, a Slack mention,
  a webhook, another workflow, or somebody pressing Run.
- **Typed from end to end.** Every node says what it takes and produces, and the
  editor points out a field nothing produces before anything runs.
- **Failure is something you plan for.** Retries with backoff, a timeout per
  node, a failure line to wire somewhere, parallel paths, and a node that runs
  several times over.
- **Drawn together.** Several people edit one workflow at once, it saves as it
  goes, and every publication is kept with a Restore beside it.
- **Every run is on the record.** The graph as it ran, each step's input and
  output, what its model calls cost, and Re-run from any step.

<table><tr>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/editor.png" alt="A workflow on the canvas"></td>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/execution-detail.png" alt="A run, step by step"></td>
</tr></table>

## Design

**Make the picture, the video, the mockup and the document here.** Four
editors, each with a chat down its side that works in what is open. What you
make stays in the workspace, where agents and workflows can use it.

- **Images.** Draw from a prompt, then edit: layers, brushes, crop, blur, and
  Magic edit to redraw a selected part. Upscale through a Stable Diffusion
  server.
- **Video.** A clip from a prompt or a starting picture, on a local or
  OpenAI-shaped model; upscale it and export it as MP4, MKV or GIF.
- **Wireframes.** Ask for a screen and a model draws it in low fidelity; edit by
  hand and export it as HTML, SVG or PNG.
- **Documents.** A paged editor with grammar and spelling, completion as you
  type, highlights with notes, links and history. Imports PDF, DOCX and ODT;
  exports PDF, ODT, ODS and Markdown.

<table><tr>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/image-editor.png" alt="The image editor"></td>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/document-editor.png" alt="The document editor"></td>
</tr></table>

## Explore

**Research you can check, and diagrams of how things fit.** A research plans,
searches, reads and writes a report that cites every source. Diagrams record how
your systems fit together, and agents read them before they answer.

- **From question to cited report.** Shallow, medium or deep: sub-questions, web
  search and your own documents, saved to Artifacts as a PDF or a document.
- **Reviewed before it is saved.** A reviewer reads the draft against its
  sources and can send it back. Every step is an ordinary workflow you can change.
- **And what to ask next.** A finished research suggests follow-up questions;
  one press starts the next.
- **Diagrams.** Flowchart and UML shapes, Arrange, notes on every node, and
  export to PNG, SVG, PDF or Mermaid. Agents search them and keep them current.

<table><tr>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/research-report.png" alt="A research report"></td>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/diagram-editor.png" alt="A diagram"></td>
</tr></table>

## Issues

**A small tracker, where the work already is.** Every workspace has one: a
title, what is wrong, who has it and the conversation underneath. Small on
purpose, and agents are on it as much as people.

- **Hand it to an agent.** Start by AI gives an agent the whole thread as a
  task, with a link to watch it work.
- **Statuses and types are yours.** Rename, add and remove them per workspace;
  issues link to each other, and every filter is in the address.
- **Told, not looking.** The bell and your mail say when something is assigned
  to you, changes or names you.
- **Filed by assistants too.** The tracker is offered over MCP, so an agent that
  finds a problem writes it down.

<img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/issue.png" alt="An issue" width="100%">

## Running it

**One process and a database, on hardware you already have.** Kotlin on Spring
Boot beside Postgres, or SQLite for one machine. It signs in against your
directory, calls the models you choose, and asks nobody's permission to run.

- **Any model, including your own.** OpenAI, Anthropic, Azure OpenAI, Ollama,
  llama.cpp, stable-diffusion.cpp, or anything speaking the OpenAI shape. Local
  servers are first class: prompt caches are kept warm.
- **Your directory decides.** LDAP, single sign-on or GitHub, mapped onto roles,
  and every change in an audit log under the name of whoever made it.
- **Watched over.** Reports on usage, ratings and errors; Doctor and Monitoring
  say whether it is healthy; Prometheus if you want it measured.
- **It updates itself, only to what was signed.** Admin → Updates installs a
  newer release in place, checks its signature at every start, and rolls back.
- **Inside your network.** Proxy rules per address, your own certificate
  authorities, and an allow list for agents' HTTP.
- **Nothing phones home.** No telemetry and no licence check.

<table><tr>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/doctor.png" alt="Doctor"></td>
<td><img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/audit.png" alt="Audit log"></td>
</tr></table>

## Plugins

**What other people have already written.** A plugin brings functions, tools,
skills and object shapes in one file. The [marketplace](https://orknux.ai/market)
lists them, and an installation takes one with a press.

- **Taken from inside Orknux.** The server checks what it downloads against the
  published digest, and offers an update only when it is newer.
- **It says what it needs first.** A plugin asks for capabilities from a short,
  closed list, and an administrator agrees to them by name.
- **Templates too.** Agents, workflows, models and connections are published for
  every workspace to start from, credentials left out.

<img src="https://raw.githubusercontent.com/orknux/orknux-ui/main/public/screens/plugins.png" alt="Plugins" width="100%">

## Get Orknux

One container to look at it, an application for one computer, or the full stack
for a team. All three are the same product.

### On the desktop

Windows and Linux packages with their own Java, on [every release](https://github.com/orknux/orknux-server/releases/latest).
Nobody signs in, it starts with a workspace, and it updates itself.

| | |
|---|---|
| **Windows** | `Orknux-Setup-<version>.exe`, or `Orknux-<version>.msi` for managed installs |
| **Linux** | `orknux-desktop_<version>_amd64.deb`, or `Orknux-<version>.AppImage` for any distribution |

### One container

The interface, the server and a SQLite file in one image. It makes its own key
and an administrator, and prints the password. For trying it: runs are not
durable.

```sh
docker run -p 8080:8080 -v orknux-data:/var/lib/orknux orknux/orknux-one
```

### For a team

Postgres, a directory and Temporal beside the server, from the published images,
in one compose file. [Kubernetes manifests](deploy/kubernetes/) are beside it.

```sh
curl -O https://raw.githubusercontent.com/orknux/orknux-server/main/deploy/compose.yaml
export ORKNUX_SECRET_KEY="$(openssl rand -base64 32)"
docker compose up -d        # http://localhost:8080
```

[deploy/README.md](deploy/README.md) covers what each service is for and what to
change before production. There is a terminal client as well: `orkx` signs in,
runs workflows, talks to a chat and checks the installation, and can never do
more than the browser can.

## The source

| | |
|---|---|
| [**orknux-server**](https://github.com/orknux/orknux-server) | The service, with the interface, the manual and the changelog. This repository. |
| [**orknux-ui**](https://github.com/orknux/orknux-ui) | The interface: every screen on this page. |
| [**orknux-extension**](https://github.com/orknux/orknux-extension) | The plugins - Slack, GitHub, Teams, Jira, Confluence and the rest - and the library a new one is written against. |
| [**orkbox**](https://github.com/orknux/orkbox) | The sandbox agents build in: sessions that are a container each. |

Building and working on it: [docs/reference.md](docs/reference.md) explains how
it is built, tested and put together, and [AGENTS.md](AGENTS.md) holds the rules
for changing it.

---

<div align="center">

[AGPL-3.0-or-later](LICENSE) with the attribution term in [NOTICE](NOTICE) - a commercial licence is available.
<br>© 2026 Michał Szymański

</div>

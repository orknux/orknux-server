# Scripted answers by chapter group

One file per group of the manual's chapters (#684): `<group>.mjs` exporting `answer(asked, { textOf, parsed })`,
answering `{ text }`, `{ call: { name, arguments } }` or nothing. Tried by `../screens-draw.mjs` before its drawing script,
in file-name order. See `orknux-ui/docs-plan/PLAN.md`, "How a writer works".

A file may also export `serve({ request, response, path, body, answer, draw })`, tried before anything else
for every request, answering `true` where it answered: how a group stands in for a video server or a Stable
Diffusion WebUI. Match only what your own providers send - a path prefix their endpoint carries, or the host
they were given (`127.0.0.1` where the core seed says `localhost`) - or you will answer the core's requests:
an answer to `/sdcpp/v1/capabilities` for the host the Writer is on makes it an image server (#670).

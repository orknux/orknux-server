# Scripted answers by chapter group

One file per group of the manual's chapters (#684): `<group>.mjs` exporting `answer(asked, { textOf, parsed })`,
answering `{ text }`, `{ call: { name, arguments } }` or nothing. Tried by `../screens-draw.mjs` before its drawing script,
in file-name order. See `orknux-ui/docs-plan/PLAN.md`, "How a writer works".

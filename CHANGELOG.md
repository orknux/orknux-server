# Changelog

What changed in each release, one line per change.

Written for the person deciding whether to upgrade and what to watch for
afterwards - not a list of commits, which git already keeps, and not a list of
issue numbers, which say nothing on their own. A line carries the change and
the one thing worth knowing about it; the reasoning that went into it is in the
commit that made it. Anything that changes what an existing installation does
is under **Changed** and says so in the line itself, because that is the
section people actually need.

One file for both halves of the product: the server and the interface are
released together, under one version, and a reader who has to hold two
changelogs side by side to work out what a release contains is a reader we
have failed.

## 1.2.3

### ✨ Added

- **The manual covers everything since 1.1.0**, with pictures: eight new chapters - Images, Videos, Documents, Wireframes, Diagrams, Research, Reports and the desktop app - and sections of their own in the chapters on workflows, triggers, variables, models and agents, chat, issues and administration, where most of it had been a line or nothing.
- **The document editor's outline column can be dragged wider or narrower**, and keeps its width.
- **A Magic accent**, under Preferences → Accent: blue running into violet, more blue than violet. Filled surfaces - primary buttons, your messages, what is selected - paint with the gradient; text and borders take a blue-violet.
- **A diagram shape's colour from three rows of swatches and a picker**: the pale row is shown, the stronger and deeper rows on asking, and any other colour from the browser's picker. Text on any of them is drawn light or dark to suit, in the editor and the exports.
- **The diagram toolbar offers the common drawn shapes up front** - database, document, parallelogram, hexagon, cloud, actor, class and component - where the window is wide enough; all of them stay under More shapes.
- **A document lists the documents it mentions**, above Mentioned by, and resting on a document link shows a card with the document's name, its catalog, its first words and, while editing, Ctrl+click to open.
- **Delete in the document editor**, and the delete dialog - from the editor or the list - names the documents that mention it as links that open in a tab of their own.
- **An artifact on its own at `/workspace/…/artifacts/…/content`**: the file itself with nothing of Orknux around it, at an address that stays the same as the artifact is replaced.
- **Every field of New research has a (?)** saying what it does.
- **A diagram's line runs straight, in right angles or curved**, picked beside its style; Arrange bends the straight ones into right angles, and a line saved before runs straight as it did. Wide shapes keep their slants, tabs and depth to their height rather than their width, and the dot that says a node has notes is quiet until the node is looked at.
- **A diagram's line has a style and a mark at each end**: solid, dashed or dotted, and at either end nothing, an arrow, an open arrow, a triangle, a diamond, a filled diamond or a circle - enough for UML's relations. Each is picked with its picture beside its name, agents draw them too, and a line saved as one of the four old kinds looks exactly as it did.
- **A workflow node can have a timeout**: how long one attempt of its work may take, in seconds, waits included. A step past it is cut short - a model call hung up on, a script cancelled, a sub-workflow asked to stop - and fails saying so, which its retry policy and failure edge treat as any other failure. Each retry and each repeat gets its own time; Admin Settings' "The longest timeout a node may be given" caps it, a day by default.
- **`!progress-none`, `!progress-low`, `!progress-medium` and `!progress-high` in a message set how much the agent says while it works**, for that message only - in a chat and in a Slack mention through an agent node, with the workspace's own marker. None is held to by the server, as an agent's own None is; a chat offers them in its command menu, and they are never looked for as skills.
- **Diagrams, documents and wireframes can be worked on together, as workflows can**: each editor shows who else has it open and where they point, saves as it goes after Admin Settings' Workflow Autosave, and takes in what the others save as it lands - merging shapes and elements by id, and a document paragraph by paragraph, so two people changing it at once both keep their work. There is no Save button: like the workflow editor, they save as they go (Ctrl+S still saves at once). An editor's saves are audited, and a document's earlier version kept, once per visit rather than once per save.
- **A run's agent step says whether Output streaming was on**, under its Parameters, so a message that reached Slack late can be told from one the step held on purpose.
- **A chat draws your avatar beside what you said**, as the agent's is beside its answers; Preferences → Avatar in Chat turns it off.
- **Voice mode reads at the speed you pick**, from 0.75× to 2× in its panel; the choice is kept in your browser and also applies to reading an answer aloud. The browser plays the reading faster or slower, pitch kept, so it works with any speech server; one asked for a speed of its own answered silence. The installation's own pace, `ORKNUX_TUNING_SPEECH_SPEED_PERCENT` (100 by default), is still asked of the speech model, and at 100 nothing is.
- **A document can link to another**, put in with the editor's Documents button: the link shows the other document's current name, so a rename is followed, and opens it on Ctrl+click in the editor or a click in View. A document's editor lists the documents that mention it, and deleting a mentioned document names them first. An agent writes one as `[name](document:12)`.
- **The documents list imports several files at once**: each PDF, Markdown, ODT, DOCX, HTML or text file becomes a document named after it, in the catalog in view, and a file that cannot be read is named and leaves nothing behind. The page-size picker for new documents now stands apart from the buttons beside it.
- **Diagrams have flowchart and UML shapes**, under More shapes beside the shape buttons: database, document, hexagon, parallelogram, trapezoid, triangle and cloud; actor, class, component, package, interface, device, boundary, control and entity. Agents can draw them too, and a line now ends on a shape's outline - a diamond's and an ellipse's included - rather than at the box around it.
- **The diagram editor's panel can be dragged wider, and the line above its chat up or down**; both sizes are kept. A long description and a node's notes no longer leave the chat a strip at the bottom.
- **A tool call in a chat that started a run, filed an issue or named another item links to its page** from the call's own line, folded or not, while the turn is going and after a reload - "Workflow run #12: Nightly export".
- **An agent node in a workflow chooses how hard its model thinks**, beside Progress messages, from the efforts its model takes; Default is the model's own. A word the model the run reaches does not take - a model read off the run - is said in the run's log and the model's own setting is used.
- **An action or an agent node can run more than once in a run**: Times it runs, beside its retries, does the node's work that many times, each time on what the last produced, and the node after it gets the last. A failure in any of them is the node's failure, and its retries count afresh for each. Admin Settings caps it (`node.repeats.most`, 100).
- **A research's Plan, Write and Review keep sessions you can open** from a Sessions line on its page: what each agent was asked, the tools it called and what it answered.
- **A Refresh button beside Auto** on the Research list and on a research's page.

### 🔧 Changed

- **The diagram, document and wireframe editors have no Save button**: they save as they go, after Admin Settings' Workflow Autosave pause, as the workflow editor does.
- **The diagram toolbar's Delete deletes the whole diagram**, asked first, and goes back to the list; selected shapes and lines are deleted with the Delete key.
- **A settings page's choices are drawn as its text fields are** - their type and box - and as wide as their options rather than the page; the design chats' suggestions are smaller.
- **Your avatar in a chat sits at the top left of your message**, as far from it as the agent's is from its answer.
- **A research runs with one agent for every step, or one chosen for each - and a workspace has no research agents of its own.** New research asks which: Single agent shows one Agent picker, An agent per step shows Planner, Writer and Reviewer, each left for you to choose; nothing is chosen for you, and Start waits until it is. New workspaces get no Research planner, writer or reviewer; existing ones keep theirs as ordinary agents, which can still be picked. Plan and Write now carry their instructions in the step itself, so any agent can do them, and untouched research workflows are redrawn that way at start-up.
- **A chat shows what each answer cost, for everybody**, unless they turn it off under Preferences → Answer Cost. It was off unless turned on; on upgrade every person is switched on, so somebody who wants it off again sets it there.
- **A chat message's time carries its date, today's included**, where it showed only the clock for today's lines.

### 🐛 Fixed

- **A voice turn left while the model thinks is finished and kept.** Switching chat or leaving the page in voice mode threw the answer away; it is now written as text and shown when the chat is opened again, live if it is still being written. The circle and talking over the model still stop it and keep nothing.
- **A long highlight note no longer widens the document editor's outline column** until the page went under the chat; it is cut short inside the column.
- **The grammar check no longer underlines every document link** for repeated whitespace: it read a link as one space.
- **The Research list shows the Reviewed badge whole**, beside the mode, where it was cut to "Review…"; on a window under 1600px wide the Search rounds column gives way so the question keeps its room.
- **A document opened and left alone no longer saves itself.** The editor adds an empty paragraph after a closing code block or table, and that alone counted as a change, so every visit wrote a new revision.
- **A wireframe's HTML export no longer draws a second scrollbar inside the page** when a frame is filled to its edge.
- **The attribution line at the foot of a page no longer runs under Ask AI**; it kept a fixed width clear that the button's label had outgrown.
- **A run step's Output streaming value is drawn in the other parameters' type**, not the page's larger one.
- **An agent that streams its output posts what it says as it ends a turn to wait for an agent it asked**, rather than holding it until that agent has answered and posting it together with the answer, minutes later.
- **pdf_read says which pages hold pictures**, that a picture's content is not in the text, and - to an agent that holds the page preview - how to draw a page to look at it. A tool an agent does not hold is no longer named in a PDF answer.
- **A long PDF read stops after a whole page and says which page to read on from**, instead of being refused for its length; how much one read answers is `ORKNUX_TUNING_PDF_READ_CHARACTERS` (200,000 by default). An answer read in pages that is too long for the model's window is cut between pages too, never in the middle of one.
- **A research that searches the web is refused before it starts when the workspace has no search key it can read**, and New research says so under its options; it used to plan and then fail at Gather.
- **An agent starts a Shallow research unless the person asked for a deeper one**; told the modes as a list to choose from, it picked Deep for any question that sounded big.
- **An MCP tool's answer is kept whole in the transcript when nothing in it is long.** Its JSON arrives as one string, which was cut to the longest value kept (Admin Settings, 1,000 by default) as if it were a payload, so a later turn in that conversation was handed the first thousand characters of ten log lines and the agent said the tool had cut them.
- **Two messages to an agent in one conversation no longer get two answers at once.** A second Slack mention while the agent is still answering the first joins that turn and is answered in the same reply; two mentions a minute apart had made the same task twice.
- **A long role under the account name no longer squeezes Quick actions to a sliver**; its results opened as a column one letter wide. The name and role are cut short, with the whole of them on hover.
- **The diagram's description grows when its corner is dragged**; the corner was drawn and did nothing.
- **A diagram's agents setting says what it decides** - "What agents may do with it" - and what the chosen one means on hover; it read only "Agents: Read and change".
- **The desktop packages are built again.** 1.2.2 shipped without them: its quick start unpacked the signed release jar, and the copy kept a signature its rewritten manifest no longer matched, so it would not start.

## 1.2.2

### 🐛 Fixed

- **1.2.2 is the first published 1.2 release.** 1.2.1 was tagged but never published either: a test in its build failed on SQLite, so no image or jar was made. Upgrading from 1.1.9 to 1.2.2 brings everything listed under 1.2.1 and 1.2.0 below.
- **Voice mode speaks what the agent says between its tool calls**, as it arrives and in order with the answer, without holding the turn back; it was drawn but never spoken, under Thinking. A line the answer repeats is spoken once.
- **A voice turn cut short by talking over it or leaving is no longer recorded as a failure**; the session said "could not answer: Nobody was left to read the answer". A typed turn stopped with Stop still says so.

## 1.2.1

### 🐛 Fixed

- **1.2.1 is 1.2.0 as it was meant to ship.** 1.2.0 was tagged but never published: its build ran out of memory in the test suite, so no image or jar was made. Upgrading from 1.1.9 to 1.2.1 brings everything listed under 1.2.0 below.

## 1.2.0

### ✨ Added

- **Replacing a saved artifact's file.** Replace in the Artifacts page's viewer, after a confirmation, puts a new file behind the artifact at the same key and address, so a link somebody was sent opens the new file; the old file is removed, the name follows the new file and the description stays. Held to the upload limit and audited ("Artifact X replaced"). The picture viewer has Replace too, both viewers' bars now round icons. Agents do the same with `save_artifact`'s `replaces`, naming the artifact by key, number or link; over HTTP it is `POST /api/workspaces/{id}/artifacts/{key}` with a `file` (and an optional `description`). A replaced file's reading address is marked anew, as its download address already was, so the viewer does not show the old one.
- **A model's speed over time.** Under its speed figures, a model's page charts the prompt and generation speed averaged per minute, hour, day, month or year (Day, or Hour for a single day) over the usage card's dates, weighted by tokens as the figures are; a period with no timed calls is a gap, never a zero. Past `ORKNUX_TUNING_MODEL_SPEED_POINTS` (1000) periods only the newest are drawn, and the chart says so (`Model.speedOverTime`).
- **A diagram's PNG at 1×, 2× or 3×**, chosen beside PNG in the editor's Export menu, 2× by default: so many pixels for each drawn one, up to 4096 wide (`/api/diagrams/{id}/export/png?scale=`). The link an agent hands out is unchanged; the SVG was already vector.
- **A dark page in the document editor.** A switch in the toolbar draws the sheets dark - dark paper, light ink, code, quotes, links and Find in colours of their own - remembered in the browser; the document and every export stay the white page.
- **Import in the document editor**: a PDF, Markdown (or Export's zip of it), ODT, DOCX, HTML or text file read into the document, in place of it or at the caret, as an unsaved change Undo takes back; its pictures are kept as Design pictures. Limits `ORKNUX_TUNING_DOCUMENT_IMPORT_MB` and `ORKNUX_TUNING_DOCUMENT_IMPORT_PICTURES_MOST`.
- **Tables in an imported PDF come in as tables**, found by Tabula: a table drawn with ruling lines always, and words lined up in columns only where plainly a table - enough rows and columns, nearly every row full, short cells, not a list - so prose with a gap in it stays prose. The table keeps its place among the paragraphs, its words are not repeated as paragraphs, and a bold or ruled-off first row becomes its header. A document's PDF export now rules and heads its tables as the editor draws them, so they come back. Thresholds `ORKNUX_TUNING_DOCUMENT_IMPORT_TABLE_ROWS_LEAST`, `_COLUMNS_LEAST`, `_COLUMN_GAP_PERCENT`, `_ROWS_FULL_PERCENT` and `_CELL_CHARACTERS`.
- **Lists and merged cells in an imported PDF.** Lines opening with a bullet - set as a letter, or painted as a dot, as the server's own PDF export draws one - or with a number, letter or Roman numeral come in as bulleted and numbered lists, a numbered one counting from where its first item does, nested by how far in each item's words start; a line of prose that merely opens with a dash or a number stays in its paragraph unless there are `ORKNUX_TUNING_DOCUMENT_IMPORT_LIST_ITEMS_LEAST` such lines in a row (2) or its lines hang at its words. A ruled cell spanning columns or rows comes back spanning them rather than as one cell and empty ones beside it, so a document exported to PDF with merged cells imports back the same shape. And an unruled table with an empty cell is found where its columns' words are lined up down the rows, which the nearly-every-row-full rule had refused.
- **Import in the wireframe editor**: a picture from a file, from Orknux, or an SVG the editor exported, placed as an image element that draws the picture, fitted to the frame; SVG, PNG and HTML exports carry it.

- **Documents in Design.** Design -> Documents, after Wireframes, lists the workspace's documents in catalogs of their own - a rail of catalogs with All documents at the top, New catalog, rename, and delete refused while a catalog still holds anything - with a search over names and the words in them, New document, rename in place, move to another catalog and delete after a confirm. A document is stored as the editor's own rich text with a plain copy of its words for the search; its pictures stay in the artifact store, named by id. Four new Tunables under Limits: how long a document may be, how much of one a model is handed, how many a search answers and how much of one it shows. A workspace copy carries no documents, as it carries no pictures.
- **The document editor writes in pages.** A document opens on sheets of its own paper, the text flowing from one to the next and a page break starting a new one, with a bar for headings, bold, italic, underline, strikethrough, code, links, lists, quotes, tables, code blocks, horizontal lines and pictures - uploaded, pasted, dropped or picked from Orknux's pictures, dragged wider or narrower by a corner, with a caption - and undo, redo and Save as in the wireframe editor. Down the right is the wireframe editor's chat, writing in the document through the `orknux_*` document tools while the page follows each change it saves, every one a step undo goes back over. Built on TipTap (MIT); the pages are the editor's own layout rather than TipTap's paid pagination.
- **While typing, the document editor suggests how the sentence goes on** - the chat composer's suggestion, from the workspace's completion model, shown the document's title, the page and the text around the caret, held to the same timeout and token cap - grey after the caret for Tab to take. Auto-completion and Grammar are switches in the bar, on until a person turns them off, and remembered for them.
- **A document's view, and Find.** View shows a document as clean pages with nothing that changes it, at an address of its own to send to anybody in the workspace, from the list's eye and the editor's View. Ctrl+F - the browser's own find, changeable in Preferences - opens Find in the editor and the view: every match marked across every page, "2 of 7", Enter and Shift+Enter, Match case and Whole word, and in the editor Replace and Replace all.
- **Quick actions finds documents**, by name and by any word in them, with the catalog each is filed in and the words around the match.
- **Agents write documents.** Ten new `orknux_*` tools - `orknux_documents`, `orknux_document` (an outline and the whole document as Markdown), `orknux_search_documents`, `orknux_find_in_document`, `orknux_document_export`, `orknux_create_document`, `orknux_write_document` (all of it or a range of blocks), `orknux_insert_into_document`, `orknux_append_document_section` and `orknux_add_document_image` (a picture by artifact id, or one the conversation holds under a key, kept as a Design picture) - offered over MCP and to every agent with Orknux access, and marked Always on existing ones by a migration. A model reads and writes Markdown; a page break is `<!-- pagebreak -->` and a picture `![alt](artifact:DESIGN-12 "caption")`.
- **Grammar and spelling in documents, in English and Polish.** The document editor underlines what LanguageTool finds in a paragraph a moment after the typing stops, with the message, the replacements a click puts in and Ignore; a document's language is its own setting, the reader's language by default. LanguageTool runs inside the server and loads a language the first time it is checked (under a second here, and some hundreds of megabytes of heap per language); Admin Settings or `ORKNUX_GRAMMAR_SERVER_URL` can point it at a LanguageTool server instead. Two Tunables: the pause before a check, and the most text one check takes. The server image is about 25 MB larger for it.
- **A document's page is its own:** A4, Letter, A5, Legal or A3, portrait or landscape, Normal, Narrow or Wide margins - drawn in the editor and the view, and the page its PDF and ODT are laid out on. A workspace's administrators choose the page new documents start on, A4 until they do.
- **Code blocks in documents**, from the toolbar, a fence (```kotlin) or an agent's Markdown: a language picker, highlight.js colours from the theme's tokens, Tab indenting, monospace, Copy, and long lines scrolled rather than wrapped. The PDF and the ODT keep the colours, Markdown keeps the fence's language. `orknux_find_in_document` finds words in one document with the block, page and words around each place.
- **Colour, highlights with notes and an outline in documents.** A text colour in the format bar, kept in the PDF and ODT; Highlight over a selection marks the words with a note, shown on hover and listed beside the sheets with the headings to jump to - a column that folds. Explain and Summarise over a selection, and Summarise in the top bar for the whole document, ask the chat without changing anything; Translate puts the chat model's translation in place of the selection. Two new Tunables, `ORKNUX_TUNING_DOCUMENT_SELECTION_CHARACTERS` and `ORKNUX_TUNING_DOCUMENT_SELECTION_CONTEXT_CHARACTERS`.
- **A document exports as PDF, Markdown, ODT or ODS**, from the editor and through `orknux_document_export`, which also hands an agent the file under a key: PDF laid out on the document's own pages with its pictures and page breaks, Markdown as a zip with an `images` folder when it has pictures, ODT through the ODF Toolkit, and ODS with each table on a sheet named after the heading above it. Each export is in the audit log.
- **Agents ask about a large file instead of reading it whole.** Three new built-ins, on and Always for every agent (existing ones included): `xml_query` answers an XPath 1.0 question about an XML or SVG file kept under a key - a count, the matching elements with their attributes, one value - `svg_measure` answers the boxes of an SVG's shapes with every group transform applied, the extent and millimetre scale, and on request distinct sizes, nearest-neighbour pitch, the closest gaps and groups by proximity (pads into footprints), and `pdf_search` finds words, a phrase or a regular expression in a PDF and answers the pages and the text around each match, with a scan said to have no text layer. Only the answers enter the turn; `svg_read`, `pdf_read`, an attachment's note and the cut-result note point at them, and the file is parsed with external entities and DTDs refused. Their limits are under Admin Settings -> Agents (`ORKNUX_TUNING_XML_*`, `ORKNUX_TUNING_SVG_GROUP_GAP_PERCENT`, `ORKNUX_TUNING_PDF_*`).
- **A finished research suggests what to research next.** Under the report, Follow-up questions lists the next questions a reader would want answered - five by default, `research.followups` (`ORKNUX_TUNING_RESEARCH_FOLLOWUPS`, 0 for none), the workspace's own number under Workspace Settings > Research, or the number on New research, remembered per person. They are asked for in one short call once the report is final, with thinking off, so they are never in the report or its file. Pressing one asks first and starts a research on it with everything this one was started with; the new one says "Follow-up of #N" and the parent lists it. `orknux_research` returns them and `orknux_start_research` takes `parentId` and `followUps`.
- **A research can be reviewed before it is saved.** Review on New research, with a Reviewer model, has every mode's workflow run Review report between Write and Save: the workspace's new Research reviewer agent reads the draft against the plan and the notes on its sources and answers accept or revise with the issues it found - unsupported claims, missing parts, wrong citations, contradictions, formatting - and the Research writer revises its own report with them, for as many rounds as `research.review.rounds` (`ORKNUX_TUNING_RESEARCH_REVIEW_ROUNDS`, 1) or the workspace's own Review rounds under Workspace Settings > Research say. The page draws Review in the strip and a Review panel with each round's verdict and issues, the revision shows as it is written, the list marks a reviewed research, and `orknux_start_research` takes `review` and `reviewerModel`.
- **Agents compose music.** The new built-in `midi_write` writes a MIDI file from notes an agent describes - tempo, time signature, tracks with a General MIDI instrument by number or name, drums on channel 10, chords, and a long piece added to in sections - and `midi_render` plays one into an MP3, FLAC or WAV through the JDK's synthesizer and FFmpeg; both answer a key that `save_artifact` or an upload takes, and the server skill *Composing music (MIDI)* says how to plan, write, check and hand over a piece. The JDK's own instrument bank is small: set `ORKNUX_MUSIC_SOUNDFONT` to a General MIDI `.sf2` for real instruments. Admin -> Settings -> Limits holds the longest render (300 seconds), the sample rate (44100 Hz) and the MP3 bitrate (192 kbit/s); every existing agent is given both tools. A server started other than with `java -jar` needs `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED` to render, and `midi_render` says so where it is missing.
- **Sound plays in a chat, MIDI included, and an agent can play it to you.** An mp3, flac, wav, ogg or m4a under a message plays in the browser's own player with a download beside it; a MIDI file has Play, which renders it on the server the first time (kept after, in a new `audio_location` column) and then plays it. An agent attaches what its tools made to its answer with `chat_attach_file`, and in a voice conversation - and only there - `audio_play` plays a sound or a MIDI file through the person's speakers before the answer is read, stopping as the answer does when they talk over it.
- **Reasoning effort in the chat composer.** Beside the microphone, a chat whose model takes a reasoning effort offers Model default and that model's levels; the one picked is what this chat's next turns send, and the model's own setting is left as it was.
- **Plan and Auto modes in a chat.** A switch in the composer, per chat: Plan offers the agent only the tools that change nothing - read off each tool's declaration, an MCP server's `readOnlyHint` included - and ends with a plan in the chat to Approve or Keep planning, where Approve returns the chat to Default and hands the plan back to be carried out; Auto lets a call that would wait for a person's leave, such as exposing a box port, go ahead, within the agent's grants. Default is how chats always worked.
- **A wireframe exports as HTML, SVG or PNG.** Export in the wireframe editor saves what is on the screen, named after the wireframe: HTML is one page with inline styles and no script, each frame a screen and buttons, fields and pictures real HTML elements; `orknux_wireframe_html` hands an agent the same page with a link to download it (`ORKNUX_TUNING_WIREFRAME_HTML_INLINE_CHARACTERS` caps what is handed over).
- **Files in the document, wireframe and diagram chats.** A file dropped on the chat down the right of an editor, pasted as a picture or picked with its paperclip waits in a chip above the box and goes with the next turn: a picture to the model as a picture, as the main chat sends one, and a text file, a PDF, a CSV or a workbook as its text. Uploaded through the main chat's door, to its size limit and only where attachments are on; `editor.chat.file.characters` (`ORKNUX_TUNING_EDITOR_CHAT_FILE_CHARACTERS`, 100000) caps how much of one message's files a model reads. The diagram editor's chat is now the same component as the other two.
- **Copy on every code block.** A fenced block in a chat answer, an issue or the manual has a bar with its language and a Copy that takes exactly the block's text.
- **Videos in Design.** A Videos page lists the workspace's videos with their poster, prompt, model, length and state, and its editor makes one from a prompt with a model of the new kind Video - through stable-diffusion.cpp's server (Wan and the other video models it loads) or an OpenAI-shaped `/videos` - offering only the settings that model takes: frames, fps, size and seed, or length and size, and a picture to start from. A video is queued, followed with its progress, kept with the attachments and played in the page, and survives a reload or a restart of the server; Convert to video on a picture opens the editor with it as the first frame. How many are made at once, how often each is asked about, how long one may take and the largest kept are under Limits.
- **A video being made says how long it has taken and about how long is left.** "1:12 elapsed · about 2 min left" under the bar, the time left worked out from the progress its server reports and nothing else - Estimating… under five points of it, only the time taken where the server reports none. stable-diffusion.cpp counts only its sampling steps, so there the time left is the sampling's and the decode after it reads Finishing…; `DesignVideo` gains `progressSamplingOnly` and `asOf`, the server's clock, so the count does not depend on the browser's. Convert to video in the image editor is a button on its bar beside AI, and asks before leaving a picture with changes not saved.
- **A picture being drawn in the image editor says how long it has taken and how long is left, where anything knows.** "0:42 elapsed · about 20 s left" on the wait's line before Stop. A Stable Diffusion WebUI's own reckoning (`/sdapi/v1/progress`, asked every `ORKNUX_TUNING_IMAGES_PROGRESS_POLL_SECONDS` while it draws an upscale, a Clean up or a Harmonize, and only while it draws nothing else of that model's) is "about"; for a server that reports nothing - stable-diffusion.cpp, a FLUX edit server, OpenAI - it is "estimated", the median of how long that model's newest pictures of the same kind took (`ORKNUX_TUNING_IMAGES_ESTIMATE_JOBS`, at least `ORKNUX_TUNING_IMAGES_ESTIMATE_LEAST` of them) less the time taken. Too few, or run past it, and only the time taken is said. `design_image_job` gains `finished_at`, so only pictures drawn after this upgrade are timed; `DesignImageJob` gains `progress`, `secondsLeft`, `typicalSeconds` and `asOf`.
- **Upscale a finished video.** Upscale on a video in the video editor opens the image editor's upscale dialog - an Upscale model, its WebUI's algorithm and 1.5 to 4 times - and makes a new version beside the original: every frame through the WebUI's `extra-batch-images`, a batch at a time, put back together at the same frame rate with the original's sound, the page counting frames done of all. It waits in the image queue under the same limit as pictures; `ORKNUX_TUNING_VIDEO_UPSCALE_BATCH_FRAMES` sets the frames per call and `ORKNUX_TUNING_VIDEO_UPSCALE_MAX_PIXELS` the longest side allowed. Video is read and written by FFmpeg as JavaCV packages it inside the jar, for Linux and Windows on x86-64 - both images and the desktop app - so no ffmpeg needs installing; the jar is about 57 MB larger for it.
- **Export a video as GIF, MP4 or MKV.** Export on the video editor's bar converts a finished video on the server and downloads it named after the video: a GIF without sound, at most `ORKNUX_TUNING_VIDEO_EXPORT_GIF_FPS` frames a second and `ORKNUX_TUNING_VIDEO_EXPORT_GIF_WIDTH` wide, in a palette made from the video itself (FFmpeg's palettegen and paletteuse) so a gradient is not banded; an MP4 or an MKV as H.264 and AAC with its sound, and a video that already is an MP4 handed over as it is. An export is stopped and refused past `ORKNUX_TUNING_VIDEO_EXPORT_SECONDS` or `ORKNUX_TUNING_VIDEO_EXPORT_MAX_MB`, and the newest `ORKNUX_TUNING_VIDEO_EXPORT_KEPT` are kept in the server's temporary directory so asking again does not convert again. `exportDesignVideo` is the mutation; each export is in the audit log.
- **Run on a chat answer's shell blocks.** Beside Copy, an administrator can run a `sh`, `bash`, `powershell` or `cmd` block on the machine the server is on and read what it printed and its exit code under the block, or stop it; Run as administrator asks the operating system (UAC on Windows, pkexec on Linux), never Orknux. Admin -> Settings -> Chat switches it; a fresh installation starts from `ORKNUX_CHAT_RUN_COMMANDS`, off for a server and its images and on in the desktop app. Every run is audited, the timeout and the output kept are under Limits.
- **Explore > Research.** A new section in the top bar whose first page lists the workspace's research and starts one: a question, a mode (Shallow, Medium, Deep, or one added with Add mode), a chat model, websites to include and exclude, documents from the Artifacts page - found by a word, or uploaded there from the dialog - to include and exclude, an academic, colloquial or formal register, and a PDF (or a document) as the result, shown on the page and saved to Artifacts. A research plans sub-questions, searches each with the workspace's search and reads what it finds through the HTTP tools (both as an agent would, switches and proxy rules included), notes every source, asks follow-up questions to the mode's depth, and writes a report citing numbered sources; the page shows the step, the sub-questions and the sources while it runs. Each mode is a hidden workflow of the workspace drawn as its five steps - Plan, Gather, Follow up, Write, Save. Plan and Write are agent nodes: the workspace's Research planner and Research writer, two ordinary agents on the Agents page whose instructions are what planning and writing are told, offered no tools, and thinking with the model the research was started with, which each node names; Gather, Follow up and Save are action nodes calling their own functions (`research_gather`, `research_followUp`, `research_save`, beside `research_plan`, `research_write` and `research_run` for a workflow drawn around them), and Save puts the numbered sources after the writer's report. The knobs are on the steps: sub-questions in Plan's prompt; results per query, sources and pages per source on Gather; breadth and depth on Follow up - so editing a mode is editing the step it is about, and the page says which step a research is in, and Add mode can copy one to change; the Workflows list shows them, marked Built-in, under the new Show built-in box. Agents and MCP clients start one with `orknux_start_research` and follow it with `orknux_research`. `ORKNUX_TUNING_RESEARCH_SOURCES_MOST`, `ORKNUX_TUNING_RESEARCH_PAGE_CHARACTERS` and `ORKNUX_TUNING_RESEARCH_DOCUMENT_CHARACTERS` bound what one research reads.
- **A research always has its model, and may leave the web alone.** The dialog's model picker has no empty row and arrives with the workspace's first chat model chosen, a start without one is refused, and the list and the research show the model used; `orknux_start_research` takes the first chat model when none is named. Use web search, ticked by default beside the sources, can be unticked to read only the documents picked and the websites to keep to, at the address typed - with neither, the start is refused - and the research keeps the choice.
- **A research can include images.** Include images, beside Use web search and off by default, has Gather look for pictures too - the picture search's answers for the question, then the `og:image`s and captioned figures on the pages read and the pictures inside the documents read, and without web search only the latter - and keep each that is a PNG, JPEG or GIF by its bytes, within `ORKNUX_TUNING_RESEARCH_IMAGES_MOST`, `ORKNUX_TUNING_RESEARCH_IMAGE_MB` and `ORKNUX_TUNING_RESEARCH_IMAGE_PIXELS`, as an artifact of its own, saying in the step's log why each other one was passed over. The writer is told the kept ones by number and may place them; the PDF lays them out from the kept bytes with their caption and source, a document links them, any other picture the report names is left as its words, and nothing is fetched while laying out. The research page shows them as a gallery under the report, and `orknux_start_research` and `orknux_research` carry `includeImages`.
- **A research has a page of its own.** Opened from the list or straight after it is started, it is no longer a card above the list: a Back link to the list, the question as the title, and its state with the spinner while it runs, mode, model, language, output and Open the run; the five steps drawn in order with the one it is in; the sub-questions; the sources as numbered rows with their host and kind; and the report with Download and Open in Artifacts.
- **A research's steps say in the run's log what they did.** Gather and Deepen findings name each search and how many results it gave - a refusal quoted as the search worded it - each page read or passed over and by which rule, and the documents read; Deepen findings names the follow-up questions it asked; Save report names the file and its artifact.
- **An agent node can name its model.** Its `model` parameter, written or read off the run - the id of a chat model the workspace may use - is the model the agent thinks with on that node instead of its own; empty is the agent's own, and a model the workspace cannot use fails the step. It is how the research modes hand their planner and writer the model each research was started with.
- **An agent can ask for a box.** `tools_ungranted` lists the box labels it does not hold and `tools_request` asks for one in the chat; accepted, the label is added to the agent as on its page.
- **`svg_read`** answers the markup of an SVG kept under a key - a drawing or an attached .svg - so an agent can read and change it.
- **`sheet_read` reads an attached CSV, .xlsx or .xls** a page of rows at a time: a workbook's sheets by name, dates as ISO 8601, formulas as their saved values, a CSV's delimiter (comma, semicolon, tab) found by itself and quoting and a byte-order mark handled. The note on an attached sheet names it. Rows per page, characters per answer and how far a workbook may unpack are on Admin → Settings (`ORKNUX_TUNING_SHEET_*`).
- **Admin Settings has a Limits heading** listing 29 numbers the server used to keep in its code - how long a Retry-After is waited out and how often, the longest a throttled call is held, agent_wait's default and ceiling, tool_find's batch sizes, how many scratchpads a session may open, the HTTP and web search tools' timeouts and sizes, pictures and speeches per run, a waiting action's interval and timeout, trigger de-duplication, schedules per tick, plugin request and upload sizes (Slack's download and upload doors included), library and npm limits, and how long marketplace icons are kept. Each applies without a restart, is audited, and starts at an `ORKNUX_TUNING_*` variable (listed in the README) with the old number as its default.
- **A plugin's skill catalogue can be searched** like a workspace's own, and both search what a skill says as well as its name and description.
- **Sentence completion in the chat.** A workspace that chooses a Completion model on its settings page gets a suggestion of how the sentence being typed goes on, in grey after the caret; Tab takes it, typing on or Escape puts it away. With no Completion model the composer is as it was. How much of the chat it is shown, how long a suggestion may be, its time limit and the pause before asking are on Admin → Settings.
- **Suggested replies under a chat's answer.** In a workspace with a Completion model, a few short replies the person might send next (three by default) are offered as chips under the latest answer once it is finished and nothing is typed; a click sends one as the person's message, and they go once anything is sent. Written by the Completion model with thinking off, kept per answer so a reload does not ask again, drawn as plain one-line text. How many (0 for none), their tokens and their time limit are on Admin → Settings and `ORKNUX_CHAT_REPLY_SUGGESTIONS`, `ORKNUX_CHAT_REPLY_SUGGESTIONS_MAX_TOKENS`, `ORKNUX_CHAT_REPLY_SUGGESTIONS_TIMEOUT_MILLIS`.
- **Plugins and functions can read Slack's Web API and download Slack files through a connection.** `orknux.slack.api(connection, method, args, { as })` makes one of `auth.test`, `conversations.list`, `users.conversations`, `conversations.history`, `conversations.replies`, `users.list`, `users.lookupByEmail` or `files.info` with the connection's bot token - or its user token, with `as: 'user'` - and answers Slack's own JSON; `orknux.slack.download(connection, fileId)` fetches a file with the bot token, as text or base64, up to 5 MB. Both are the new `SLACK_WEB_API_READ` capability, so the Slack plugin can stop keeping its own `botToken` and `userToken`; a plugin that declares it asks for the grant again on its next update.
- **Connections and MCP servers as templates.** Save as template on their rows on Integrations publishes the settings without the credentials, and Use a template beside each card's Add makes them in another workspace, linking to each one that needs its credential given again.
- `ORKNUX_SESSION_COOKIE_SECURE` marks the session cookie Secure (`auto`, the default, follows the request); `ORKNUX_CONTENT_SECURITY_POLICY` replaces the interface's Content-Security-Policy where the built-in one is too strict.
- **Videos in the chat.** A video attached to a message plays under it, and is sent to the model itself where the model's page has Takes video on - offered for llama.cpp servers and OpenAI-compatible endpoints other than OpenAI's own (vLLM with Qwen-VL, llama.cpp's multimodal server), never for OpenAI, Azure, Ollama or Anthropic. A model without it, or a video over Admin → Settings' Video Attachment Limit (`ORKNUX_CHAT_VIDEO_MAX_MB`, 20 MB), is refused in a sentence before the model is asked. The video goes with its own turn only, not again with every turn after it.
- **Sound in the chat.** An mp3, wav or flac attached to a message plays under it, and is sent to the model itself where the model's page has Takes audio on - offered for OpenAI (its audio models) and for llama.cpp and other OpenAI-compatible servers (vLLM), never for Azure, Ollama or Anthropic. OpenAI's own API takes only wav and mp3, so a flac sent there is refused in a sentence; llama.cpp and vLLM decode flac and are sent it as it is. A model without the switch, or a sound over Admin → Settings → Limits' largest sound (`ORKNUX_TUNING_CHAT_AUDIO_MAX_MB`, 20 MB), is refused before the model is asked, and the sound goes with its own turn only.
- **MIDI files are read.** No model hears MIDI, so an attached `.mid` is read by the new built-in `midi_read`: the format, the tracks, tempo and time signature, and every note per track with its pitch, start and length in beats and milliseconds, and velocity - up to Admin → Settings → Limits' note count (`ORKNUX_TUNING_MIDI_READ_NOTES`, 2000). A `.mid` the browser sends as an unknown type is kept as MIDI, and every existing agent is given the tool.
- **Tabs in the desktop app.** A link the interface opens in a new tab, Ctrl+click and the middle button open a tab instead of a window; Ctrl+T, Ctrl+W, Ctrl+Tab and Ctrl+Shift+Tab work as in a browser, and closing the last tab closes the window.
- **New windows and your own tab keys in the desktop app.** Right-click on a link into Orknux offers Open link in new window instead of the browser (Shift+click opens one too; a link to another site still opens in the browser), and next and previous tab - Ctrl+PageDown and Ctrl+PageUp, going round at the ends - are set under Preferences, with Ctrl+Tab and Ctrl+Shift+Tab kept.
- **Wireframes in Design.** Design → Wireframes lists the workspace's wireframes, each drawn small on its card, with New, rename and remove; one opens in an editor laid out as the image editor is - a pan-and-zoom grid with frame, rectangle, text, button, input, image placeholder, line, arrow and sticky note tools, select, marquee, move and resize, words typed in place, duplicate, delete, undo, redo and Save - in a low-fidelity greyscale. Down its right is a chat: ask a chat model of your choosing for a screen and it draws into the wireframe, where it can be edited by hand afterwards and its change is one step undo goes back over. The model draws through new `orknux_wireframes`, `orknux_wireframe`, `orknux_create_wireframe`, `orknux_change_wireframe` and `orknux_draw_wireframe` tools, which an agent with Orknux access and an MCP client have too (at Offer on an agent made before them). How many elements one may hold is on Admin → Settings → Limits (`ORKNUX_TUNING_WIREFRAME_ELEMENTS_MOST`, 2000). A workspace copy leaves wireframes behind, as it does pictures.
- **Model speed.** Where the provider reports its timings - llama.cpp does; OpenAI, Azure, vLLM, Ollama and Anthropic do not, and show nothing - a session's page shows the prompt and generation speed in tokens per second beside its cost, averaged by tokens over its calls, and a Speeds panel lists each call's; a model's page shows the same over its newest calls, as many as Admin → Settings says (`ORKNUX_TUNING_MODEL_SPEED_CALLS`, 50).

- **Negative prompts for pictures.** What a picture must not contain is sent beside the prompt where the server takes one - stable-diffusion.cpp's server (inside the prompt, as `<sd_cpp_extra_args>`) and a Stable Diffusion WebUI's Harmonize (`negative_prompt`) - from the image editor's Negative prompt box, an agent's draw tool and a default on the model's page; a model on OpenAI's API, which takes none, is not offered one and refuses one sent anyway.
- **Rotate and mirror in the image editor.** A rail of tools down the canvas's left turns the picture a quarter left or right and flips it horizontally or vertically, in the browser and without a model; each is kept as an unsaved version, as a Magic edit is, so Undo, Save and Discard treat them alike (`keepDesignImageChange`).
- **Import a picture from Orknux in the image editor.** Import is a menu: From a file as before, or From Orknux - the workspace's pictures off the Artifacts page, Design's and those runs, tasks and agents drew or kept, searched by a word - and the one chosen opens in the editor.
- **Crop in the image editor.** The rail's Crop selects the part to keep with a rectangle, dims the rest and says the size it will be; Crop or Enter keeps it as a version.
- **A brush in the image editor.** Paint over the picture by hand in a width and colour of your own; each stroke is a step Undo takes back, and Apply - or putting the brush down, or Save - keeps them as one version. Its width is in the picture's own pixels and starts at Admin → Settings → Limits (`ORKNUX_TUNING_IMAGES_BRUSH_PIXELS`, 12).
- **Brush tips in the image editor.** The brush's bar offers Hard, Soft (its edge fading by a Hardness), Square, Marker (see-through, its own overlaps flat, darkening what it crosses) and Eraser (paints transparency into a layer; off on the picture itself), with an Opacity for each; a pen's pressure makes a stroke thinner where it is pressed lightly. Opacity starts at 100%, the marker's at 50% and Hardness at 50%, set in Admin → Settings → Limits (`ORKNUX_TUNING_IMAGES_BRUSH_OPACITY_PERCENT`, `ORKNUX_TUNING_IMAGES_MARKER_OPACITY_PERCENT`, `ORKNUX_TUNING_IMAGES_BRUSH_HARDNESS_PERCENT`).
- **Shortcuts for the image editor's tools.** R and Shift+R turn the picture right and left, H and V flip it, B, C and U pick up the brush, crop and blur, Shift+L shows the layers, [ and ] narrow and widen the brush, and 1, 2 and 3 blur with a rectangle, a lasso or gradually; each is a setting on Preferences, and each button's tooltip names its key.
- **Selective blur in the image editor.** Select part of the picture with a rectangle or a lasso and blur inside it only; the radius is in the picture's own pixels and starts at Admin → Settings → Limits (`ORKNUX_TUNING_IMAGES_BLUR_PIXELS`, 12). Each part blurred is a version.
- **Gradual blur in the image editor.** A third shape on the blur's bar draws a line - sharp at its start, blurred at the full radius at its end, both ends draggable, Mirrored for a sharp band in the middle - and a Falloff on rectangle and lasso fades the blur out inside the selection's edge, starting at `ORKNUX_TUNING_IMAGES_BLUR_FALLOFF_PIXELS` (0, a hard edge as before).
- **Layers in the image editor.** New layer adds an empty one over the picture; layers are renamed, shown or hidden, dragged or moved up and down the stack and removed, the picture itself staying at the foot, and the brush and the blur work on the layer chosen. Every change is a step Undo takes back. A picture keeps its layers - each one's pixels, name, place and whether it is shown - with every version, through Save and in its .orkx archive, and opens with them again; its file stays the layers flattened, so Artifacts, chats, agents and workflows see what it looks like. Only Flatten merges them. A model's change to a picture with layers comes back as a layer of its own over them - a Magic edit's holding only the part changed - and an upscale makes every layer larger with it. At most 32 layers over the picture, set in Admin → Settings → Limits (`ORKNUX_TUNING_IMAGES_LAYERS_MOST`).
- **Clean up in the image editor.** Beside Upscale, the same road at 1x: a Stable Diffusion WebUI's restoration models - SCUNet and SwinIR, built into AUTOMATIC1111 and Forge, and FBCNN or 1x models such as 1x_JPEGDestroyer added to its models folder - take out the blocks and speckle repeated edits leave, as a draft at the picture's own size. `designUpscaleAlgorithms` names them in `restorers`. Colour drift is Harmonize's.
- **Feather for the Magic edit.** A Feather in pixels beside the prompt softens the selection's edge, so the redrawn part blends into the picture instead of leaving a seam; 0 is a hard edge, as before. It is in the picture's own pixels and starts at Admin → Settings → Limits (`ORKNUX_TUNING_IMAGES_FEATHER_PIXELS`, 8). The blend is done by the server as it lays the change over the picture - the edit endpoint reads a mask as yes or no, and no edit provider here takes a mask blur of its own.

- **Export a model provider** from its row on the Models page: the provider and all its models in one file, without its key, which Import reads back in.
- **A wireframe an agent draws in a chat is shown in the chat.** `orknux_create_wireframe`, `orknux_change_wireframe` and `orknux_draw_wireframe` put a grey picture of the drawing above the agent's answer - one per wireframe per turn, as it was last drawn - beside the link that opens it in the editor, and keep it as SVG under a key for whatever sends a file; it is at most 1600 pixels wide (`ORKNUX_TUNING_WIREFRAME_PICTURE_PIXELS`).
- **`sheet_read` reads an OpenDocument spreadsheet (.ods)** from LibreOffice or OpenOffice as it reads an .xlsx: its sheets by name, typed cells as text, the same rows per page and the same limit on what one part may unpack to. A zip is known for an .ods by its content, not its name, and a zip that is no spreadsheet is refused saying so. Read with SODS, a 220 kB library with no dependencies.
- **A research restarts from where it stopped.** Restart in the header of a failed or stopped research runs it again as the same research from the step it failed or was cut short at, the steps before it carried over from its run - a plan is not asked for again, and sources already read are kept from Deepen findings on; a step taken offers Restart from here on hover, and `orknux_restart_research` does the same for an agent. A research still running, or a step its run never reached, is refused with the run's own reason.
- **A running research shows its work as it goes, and about how long is left.** The page or document being read is a row under the sources before its notes are written, and the report is drawn as the writer writes it, marked Writing…, until the finished one takes its place; beside Started, "about 4 min left" is measured from the workspace's finished research in the same mode, step by step, once there are three (`ORKNUX_TUNING_RESEARCH_ESTIMATE_RUNS`), and nothing is said before.
- **Explore > Diagrams.** A second page in Explore lists the workspace's diagrams by catalog - New catalog, New diagram, rename, Move, Remove after asking - with a search that finds a word in any node's label or notes, or on a connection, and opens the editor on that node. Its editor draws boxes, rounded boxes, ellipses, diamonds, notes and groups, joins them handle to handle, moves, resizes, colours and aligns them, lays them out with Arrange (left to right or top to bottom, Ctrl+Shift+L, rebindable under Preferences), copies, pastes and undoes, and exports PNG, SVG, PDF, Mermaid or JSON. Down its right, as in the wireframe editor, the selected node's label, shape, colour and notes, and a chat whose model changes the diagram as one step undo goes back over.
- **A diagram's nodes carry notes, 5000 characters each by default.** The editor counts them and stops at the cap, and the server refuses a save past it from the editor and from an agent alike. The cap is a row under Admin Settings -> Limits, `ORKNUX_TUNING_DIAGRAM_NOTE_CHARACTERS`, beside how many nodes and connections a diagram holds, how many hits a search answers, how much of each node's notes a whole read shows and how wide a diagram's picture is.
- **Agents read, search, draw and export diagrams.** `orknux_diagrams`, `orknux_diagram`, `orknux_diagram_node` (one node's notes whole, its group and every connection with the node at the other end), `orknux_diagram_search` (across the workspace or in one diagram, labels before notes), `orknux_diagram_export` (PNG, SVG, PDF, Mermaid or JSON, kept in the conversation for `chat_attach_file` or `save_artifact`, and a download link), `orknux_create_diagram` and `orknux_change_diagram` (nodes and connections added, changed and removed by id in one save, a picture of the diagram put in the chat). `orknux_delete_diagram` is there too and starts hidden on every agent, its owner turning it on agent by agent. The write tools are not offered to a read-only scope.
- **Each diagram says what agents may do with it: Read only, or Read and change.** On the list and in the editor; a new diagram is Read and change, so agents keep it right as they learn. On a read-only one `orknux_change_diagram` and `orknux_delete_diagram` are refused, and the list and read tools say which it is before an agent tries. The editor's own chat changes the diagram open in it either way, because a person is there asking.
- **Agents are sent to the diagrams.** Where a workspace has a diagram and an agent holds `orknux_diagram_search`, its briefing says to search the diagrams before answering how the workspace's systems, processes or architecture fit together, to read a node that looks relevant, and - with `orknux_change_diagram` - to update a diagram when it learns what one lacks or has wrong, on diagrams open to it, and say what it changed. The built-in Memory skill says the same.
- **Quick actions finds a diagram by its name and a node by its label**, and opens the editor on the node.
- **A diagram has a description** of what it is for, typed in the editor's panel and shown on the list on one line. Agents read it in `orknux_diagrams` and `orknux_diagram`, set it with `orknux_create_diagram` and `orknux_change_diagram`, and `orknux_diagram_search` matches it; the editor's chat is told it, and the briefing says it is how to pick the right diagram. Its length is a Limit, `ORKNUX_TUNING_DIAGRAM_DESCRIPTION_CHARACTERS`, 2000 by default.
- **A diagram's labels are typed over where they are drawn.** Double-click a node's words or a connection, or press Enter or F2 on the one selected; Enter puts the label down, Escape puts the old one back, and it is one step undo goes back over.
- **A diagram's details have a page of their own.** The pencil on the Diagrams list opens it - name, description, catalog and Agents, with Save, Discard and a question before leaving unsaved - and the list no longer has an Agents column; a read-only diagram is marked Read only. In the editor, a Diagram section is always in the side panel, folded or open as you left it, holding the description and the Agents setting.
- **A document's View shows its highlights' notes.** Hovering a highlight opens its note under it and a press keeps it open, only to read; the column of the outline and the highlights with their notes is on the left, as in the editor.
- **A document has a history.** Every save keeps the version it replaced - from the editor, an import saved there, the document chat, the `orknux_*` tools and a restore - with who saved it and through which door; the saves of one turn of the document chat keep one version, the state before the turn. History in the editor's bar lists them in place of the chat, opens one over the desk with the blocks and words its save added and took out marked, and Restore, asked first, saves what is on screen and puts the version back as a version of its own, audited. How many are kept per document is `ORKNUX_TUNING_DOCUMENT_VERSIONS_KEPT` (50; 0 keeps them all), and the days component history is kept for do not apply to documents.
- **An agent looks at a picture at an address.** `picture_view` takes a `url` - or an address put in its key - fetches the picture into the session store, shows it, and answers the key it is kept under for attaching or uploading. Any address a picture search answered in the conversation, for every agent; any other only for an agent holding an HTTP tool, under Admin Settings -> HTTP tools. Through the proxy rules and the address guard, typed by its bytes, a page or anything not a picture refused, and at most `ORKNUX_TUNING_PICTURE_URL_MB` (5). `web_searchImages` says so in its answer.
- **A file out of a box as itself.** `orkbox_export` given one file keeps that file under its key with its type - a picture to look at, a PDF to attach - instead of a `.tar.gz` of one entry; a directory or the whole workspace is still a `.tar.gz`.
- **The upload limit is set in Admin Settings.** Largest file, under Attachments, 1 MB or more with no ceiling and audited, holds every upload to one number - a chat's files, an issue's, the Artifacts page's, a research's, a picture or document imported - and starts at `ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB` (25) until somebody sets it (`setAttachmentMaxFileSizeMb`). A file past it is refused before it is sent, and by the server if it gets there, in one line naming its size, the limit and where to raise it.

### 🔧 Changed

- **The chat list's + New wears the main accent**, as every page's create button does, and the top bar's sections sit closer together below 1920px wide.
- **An agent can draw a picture for its own eyes only.** `svg_toPng`, `svg_draw`, `diagram_render`, `charts_render` and the wireframe and diagram drawing tools take `show`: left out it is true and the picture goes into the chat as before; false keeps it from the person, still shows it to the agent with its key, and tells the agent the person has not seen it - an agent checking a PCB layer had posted the render into the person's chat.
- **The desktop app accepts uploads of up to 500 MB**, on Windows and Linux, where the jar and the Docker images keep 25; an `ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB` already in the environment wins, and Admin Settings over both. Spring's multipart caps, `ORKNUX_UPLOAD_MAX_FILE_SIZE` and `ORKNUX_UPLOAD_MAX_REQUEST_SIZE`, are now unlimited (`-1`) by default, so they never refuse before the limit does, and the interface sends one file per request; an installation that set them lower to bound uploads should set the limit in Admin Settings instead, since what they refuse the browser can only report as a broken connection. Uploads to the Artifacts page and a research's sources, and an agent's `save_artifact`, are held to the upload limit rather than to 5 MB.
- **The basic built-ins travel again, and the skill tools cannot be left behind the tool search.** On upgrade every agent is marked Always for each built-in a new agent starts Always - the scratchpads, to-dos, notes, the clock and date tools, timers, watchers, schedules, the skill tools, artifacts, zip, the PDF and sheet readers, asking agents and people, finish_answer - unless it has that tool hidden; agents whose marks predated those tools had them at Offer, found only through the tool search under a tool limit. The orknux_*, orkbox_*, picture, MIDI and rarer reader tools and the ratings keep the marks each agent has. skill_list, skill_load and skill_search are now held at Always like finish_answer while the workspace keeps its built-ins fixed: the agent form draws their rows fixed, and a save from any door puts the mark back.
- **A tool handed an address where it reads a key says how an address becomes a key**, instead of that nothing is kept under it, which an agent took to mean it could not see pictures at all. `http_download`'s `contentType` is now what the bytes are where they say, rather than the server's header.
- **Two provider settings decide how an agent reaches the tools it does not carry from the start, and every existing provider is turned to its type's default.** **Extend the tool list with loaded tools**: on, a tool loaded with `tool_load` joins the conversation's tool list at the next turn and is called by its own name; off, it is called through `tool_run` until the conversation is compacted or a new one starts. Off by default for llama.cpp - whose next turn after a load would read the whole conversation again, a minute and more on a long one - and on for every other type; an agent on llama.cpp with it off is told plainly, in its instructions and in what `tool_find`, `tool_describe` and `tool_load` answer, that `tool_run` is the only way to call a loaded tool, and switched on there the page says what it costs in one line. **All tools up front** is back, off for every type: on, an agent carries every tool it holds from its first request, past its own **Tools carried at once** and its Offer marks, up to **Most tools per request**, so nothing is loaded. On upgrade every provider has All tools up front off, whatever it held in 1.1.9 (where it was on by default for OpenAI, Ollama and llama.cpp), and follows its type's default for the join; nothing to do, unless a llama.cpp agent relied on calling a loaded tool by name - switch the join on for its provider, or All tools up front. Both are `offerAllTools` / `offersAllTools` and `joinLoadedTools` / `joinsLoadedTools` on the API, and a change to either takes effect at a session's next turn.
- **Blue is the default accent.** Anybody who never chose one on Preferences sees blue rather than moss, the logo included; a choice already made is kept, and Moss is still offered.
- **Every agent with Orknux access is given the diagram tools at Always, and `orknux_delete_diagram` is hidden on every agent.** A migration marks the seven that read, search, export and draw; nothing to do on upgrade. A diagram is not copied with a workspace, as a wireframe is not.
- **A research looks for images unless told not to.** Include images is ticked on New research for anybody who has not left it off before, and a research started through the API or `orknux_start_research` without `includeImages` looks for them too; pass `includeImages: false` to keep the old behaviour.
- **The research modes' workflows gain two nodes.** Review asked for? and Review report sit between Write report and Save report in every seeded mode still drawn as it was seeded, upgraded on start-up; a mode somebody edited is left as it is and is never reviewed. Without Review the question goes straight to Save and Review report is shown skipped.
- **A condition's written value may name a field.** `{{trigger.review}}` on a condition node is that field's value, as on any other node, and a field the run does not carry is empty rather than the braces as text.
- **An agent holding more tools than its provider takes carries as many as fit from its first message.** Past the cap it carried only its Always tools and loaded the rest one at a time, its briefing marked "(load it first)" on tools the request already carried, and on llama.cpp every load had the whole conversation read again; now only the overflow is loaded, and the briefing's marks say what the request carries and stay put after a load.
- **A conversation sends the same tool array and system turn from its first request to its last.** A tool granted in the chat, a `tool_load`, an MCP server's or a plugin's new tools - anything that changed what an agent holds or is told - changed the array or the system turn, and a provider caching the prompt by its prefix read the whole conversation again: on llama.cpp with a hybrid model, 167,792 tokens and minutes after one grant. What changed now arrives as a message - the new tools with how each is called, the ones now declared differently (a drawing tool after the workspace's image model changed), the ones gone, the lines of the briefing that moved - and a tool the array does not carry is called through the new `tool_run`, or by its own name, and runs as itself, checked, approved and logged under that name. `tool_load` answers with a tool's schema instead of adding it to the array, which is now filled to the provider's cap. The array and system turn are made again for a new conversation, another agent or model, an agent edited on its page, and after a compaction. A chat in plan mode keeps the array it has and refuses a tool that changes something when it is called, as it already did one named anyway.
- **A page `pdf_preview` draws, and a picture `picture_view` shows, go to the agent alone.** They are how an agent looks at something, and were also put in the chat - a person who asked about their sheet music was shown a full-size render of their own PDF. The agent is told the person has not seen it. A tool says which kind of picture it makes with `pictureForModel` on its answer.
- **`pdf_toPng` puts its picture in the chat only when called with `show: true`**, which its description tells an agent to do when the person asked to see the picture; otherwise only the agent sees it. Drawings made for the person - `svg_draw`, `charts_render`, `diagram_render`, wireframes, a drawn picture - are put in the chat as before.
- **A reasoning effort for OpenAI, OpenAI-compatible, llama.cpp and Ollama models, not only Azure.** Their model page and the chat composer now offer None, Minimal, Low, Medium, High, Extra high and Max, sent as the chat completions' `reasoning_effort`, which llama.cpp's server hands to the model's chat template (`none` turns thinking off) and Ollama maps onto its own thinking levels; unset sends nothing, as before, and Anthropic still takes none.
- **The chat's mode switch is the application's own list.** Default, Plan and Auto open above the composer with each row's meaning on hover, drawn like the reasoning-effort pill it now sits beside, before the microphone.
- **Analysis is called Explore**, between Design and Flow in the top bar, and Research is at `/workspace/<id>/explore/research`; an `/analysis/...` address opens the same page there, and links the server hands out say `explore`.
- **The search on Skills and Memory is one box above the catalogs and searches every catalog, a plugin's included:** matches are grouped under each catalog's name, the column counts them, and choosing a catalog narrows the search to it.
- **An agent's note to itself may be 2,000 characters by default**, up from 1,000; an installation that set its own limit in Admin Settings keeps it.
- **The desktop app starts in about half the time.** Its server runs from an unpacked copy of the jar with an AOT cache made for the runtime it ships - about 8 seconds to the first page instead of 18 on a Windows test machine. A release the server installs itself still starts the old way.
- **A chat's title no longer waits on a busy companion model.** Past Admin Settings' Chat Title Time Limit (30 seconds, `ORKNUX_CHAT_TITLE_SECONDS`), or when it fails, the chat's own model names it.
- **Cross-site writes are refused.** A write a browser says came from another site is refused with 403, by `Origin`, `Sec-Fetch-Site` or `Referer`; the CLI, `/mcp` and anything sending a bearer token are unaffected. If the interface is served from another origin, list it in `ORKNUX_ALLOWED_ORIGINS`.
- A workspace function's `orknux.http`, an agent's HTTP tools and a plugin's requests refuse cloud metadata addresses, including after a redirect, and read an answer only up to their limit rather than whole.
- A workflow's own action can no longer name another workspace's connection, function, condition, speech model or workflow, and a run will not send through another workspace's connection whatever was saved.
- An access token can no longer make another token; make tokens from a signed-in session.
- Setting somebody's password, or changing your own, ends their other sessions.
- A webhook whose authentication function refuses a caller answers 404, like an unknown path, rather than 401.
- `ORKNUX_SESSION_COOKIE_SAME_SITE` and `ORKNUX_SESSION_COOKIE_HTTP_ONLY` now take effect; they were ignored.
- **An agent's orknux_ tools can be set to Offer.** While Orknux access is on each cycles Hide, Offer and Always on the Tools list like every other row, and one at Offer is found rather than carried once a tool limit is set. Existing agents with Orknux access have every orknux_ tool they had not hidden marked Always on upgrade, so they carry exactly what they did; a new agent, or one given the grant, starts with them at Offer.
- **A new agent starts most of the server's specialised tools at Offer rather than Always**, so under a tool limit they are found with `tool_find` when a job needs them instead of carried on every turn: `rate_session` and `remove_session_rating`; every `orknux_*` tool (workflows, agents, functions, runs, sessions, the manual, documents, diagrams, wireframes, research, `orknux_news` and the tracker); every `orkbox_*` box tool; the picture tools `picture_view`, `draw_picture`, `picture_link`, `svg_draw`, `svg_toPng`, `pdf_toPng`, `pdf_preview`, `charts_render` and `diagram_render`; `midi_read`, `midi_write` and `midi_render`; and the readers `svg_read`, `svg_measure`, `xml_query`, `pdf_search` and `validate_format`. `pdf_read` and `sheet_read` stay Always, as do the notes, to-dos, clock, timers, scratchpads and the rest. The same goes for Orknux access or a box label given afresh. Without a tool limit nothing changes: every offered tool is carried. **Existing agents keep the marks they have**; nothing to do on upgrade.
- **The box tools take Offer and Always.** While an agent holds a box label each `orkbox_*` row on its Tools list cycles Offer and Always - never Hide, the label still grants them as one - and one at Offer is found once a tool limit is set. Existing agents holding a label have every box tool marked Always on upgrade, so they carry what they did.
- **The server's own tools can be set to Offer or Always in a workspace that keeps its built-ins fixed**; only hiding one still needs Allow unsafe built-in tool visibility, and `finish_answer` stays Always.
- **A chat's thinking is drawn where it happened, and as the session draws it.** What a model thinks after a tool call is a folded section of its own among the calls, live and after a reload, rather than added to one block above them; each is the session page's red thinking card, and the chat's calls and thinking are a step smaller than the answer.
- **An address typed without a scheme is taken as http.** `192.168.0.10:8081` in a box's, an MCP server's, a model provider's, a connection's, an HTTP action's or the installation's address, or a release URL, is stored as `http://192.168.0.10:8081`, and the field shows the `http://` once it is left; one typed with `https://` is kept as typed, and a mail server's host stays a host.
- **A package is installed from a registry only with a SHA-256, -384 or -512 integrity, and its file only over https.** A registry that leaves `dist.integrity` out and offers only the SHA-1 `dist.shasum` is refused, saying so, where it used to be trusted; a registry reached over https that names an `http://` file is refused. An installation whose `ORKNUX_LIBRARY_REGISTRY_URL` is itself `http://` still fetches its files over http, by its administrator's choice. **On upgrade:** a private mirror that serves only shasums has to be configured to publish `integrity`, or its packages uploaded as files instead; libraries already installed are not affected.
- **What a function or a plugin action says with `orknux.log` is in the run's log**, under its step on the run page, whether it returned or threw; it was kept on the answer and shown nowhere. As many lines as `ORKNUX_TUNING_SCRIPT_LOG_LINES` keeps (500), an error as an error and a warning marked Warning.
- **The research modes' steps are called Plan research, Gather sources, Deepen findings, Write report and Save report**, and their actions Research: Gather sources, Research: Deepen findings and Research: Save report. A mode's workflow still as it was seeded under Plan, Gather, Follow up, Write and Save is renamed at start-up, its actions with it; one somebody edited keeps its names.
- **A research mode's steps are tried again when they fail in a way that could come out differently** - a model answering with nothing, as Write did on llama.cpp - twice, ten seconds apart, set by `ORKNUX_TUNING_RESEARCH_STEP_RETRIES` and `ORKNUX_TUNING_RESEARCH_STEP_RETRY_SECONDS` or under Admin > Settings > Limits; a research that read nothing still fails at once. Written on each step as its node's retry policy, which a mode's workflow without any is given at start-up unless somebody edited it, and the research says the step is being tried again.
- **A research step cut short by a server restart is run again.** Plan, Gather, Deepen findings and Write change nothing outside the research, so one the restart interrupted is run again when the server is back rather than failing the research - twice at most, `ORKNUX_TUNING_RESEARCH_RESUME_AFTER_RESTART`, 0 to fail it as before; Save still fails and is restarted from the page.
- **Re-running a research's run carries the research with it.** Rerun, or Rerun from a step, on the Runs page - and `orknux_rerun_execution` - move the research onto the new run, so its page follows that run to the end instead of showing the old failure; the runs it was on before are kept on it.
- **The web search key is a secret field like every other.** On both workspace settings pages it is masked once stored with a Show that reveals it (to whoever may read the workspace's secrets, and recorded in the audit log), can be read from a workspace secret instead of kept as its own copy - a secret the key reads cannot be deleted - and the engine, the key and the composed-answer switch are saved by the page's Save Changes and put back by Discard rather than each on its own; the engines are shown as Tavily and Brave.

### 🐛 Fixed

- **`pdf_read`, the date tools and search take a number sent in quotes.** A model on llama.cpp sent `"from": "1"`, read as no range, so a 118-page PDF was read whole and refused as too long.
- **A long prompt stays inside its box in the image and video editors**; it ran over the Model field below.
- **A question an agent asked is drawn once in the chat**, and a chat waiting for its answer shows one message rather than two.
- **A file attached to a chat is readable by key at any size the upload limit takes.** It was copied into the session's store as base64 JSON, so one over Session Value Size - a 28 MB PDF against the 8 MB default - got no key, and pdf_read with its name answered that nothing was kept under it. The key now names the attachment and every tool reads it from the attachment store, a PDF opened in place so a page range of a long one is that range; the note says its size and a PDF's pages, and from `ORKNUX_TUNING_PDF_LONG_PAGES` (20) pages says to read it `ORKNUX_TUNING_PDF_STEP_PAGES` (5) at a time with pdf_read's from and to, summarising each part first. A tool given an attached file's name instead of its key says which key to pass.
- **tools_request says which of the names it refused the agent already has.** A tool it carries is said to be one to call, one behind the tool search one to load with tool_load, and only a name it holds nowhere is pointed at tools_ungranted - it used to answer every one "either you already hold it, or it does not exist here".
- **tool_load given a skill's id says it is a skill, read with skill_load**, instead of that there is no tool by that name.
- **A tool granted while the agent's turn is still running can be used in that turn.** The note said the tools were the agent's "from your next turn", and in the same turn `tool_load` answered "You have no tool called" them and `tool_run` "There is no tool called" them, so the agent told the person they had not arrived. A grant accepted mid-turn now goes into the turn at once: `tool_load` finds the tools and `tool_run` runs them, the note and a message after it say they can be called now through `tool_run`, with the call's shape and each tool's arguments, and the tool list sent to the model stays as it was until the next turn, which carries the grant as before. A granted tool that still could not be reached in that turn is said to be usable from the next one, never not to exist. And `tools_request` asked for a tool already held, in a conversation that sends every tool up front, says to call it through `tool_run` rather than load it with a `tool_load` it does not have.
- **A seeded research mode that missed an upgrade is brought up to date, and can be reviewed.** Each start-up upgrade of Shallow, Medium and Deep looked only for the shape the one before it left, so a mode that missed one - the rename, Gather's `includeImages`, the retries or the review - was left behind by all of them, and starting it with Review was refused for want of a Review step. A seeded mode's workflow is now read for what it does - its nodes, their actions or agents, its lines and its prompts, whatever the names, retries, card positions and the boxes the editor writes into an agent node - and any shape this product ever seeded is drawn again at start-up as today's, with the review, keeping the mode's own numbers and any name somebody gave a step. One somebody really changed - a node or line added or removed, another action or agent, a prompt of their own, a knob read off the run - is left alone, and New research now draws Review switched off on such a mode with a (?) saying why, rather than letting Start fail (`ResearchMode.reviewable`).
- **A model rewriting part of a document no longer strips its colours and highlights.** The document tools hand a model coloured words as `<span style="color:#rrggbb">` and highlights as `<mark data-note="..." data-note-id="...">`, read them back as the editor's marks (a colour the editor accepts, a note cut to 2000 characters, anything else in a tag dropped), and on a rewrite that leaves the tags out put the marks back on the words that came back unchanged; a colour or a highlight whose words were rewritten on their own moves onto the words that replaced them, and one that cannot - a sentence rewritten round it, or two rewritten together - is named in the tool's answer. How much rewritten text is compared is `ORKNUX_TUNING_DOCUMENT_MARKS_CARRY_CHARACTERS` (20000).
- **The row under the top bar is the same in every editor.** The back link and the name in the document, wireframe, image, video and diagram editors took the size of the page around them; both are 13px on one line now, the name medium, and a focused back link is underlined rather than boxed.
- **A step down a condition's "no" is handed what the run carried.** The condition's "did not hold" took the place of everything the run was holding, so whatever came after the "no" read nothing it was wired to.
- **A model server that answers a streamed request in one piece is asked once.** Where a provider ignored `stream: true` and sent the whole answer, the answer was read as an empty stream and the question asked again without streaming, so every streamed call to it - a chat turn, a research's report as it is written - cost two answers; the body it sent is now read as the answer.
- **A PDF opens on the Artifacts page and as a research's report.** Both framed the preview, which is served sandboxed, and Chrome's PDF reader does not run in a sandboxed document, so a PDF was its broken-document icon. The pages are drawn by pdf.js now, with selectable text, page buttons and zoom; an HTML file is still read in its sandboxed frame. The reader also sits in the middle of the window again rather than low and to the right of it.
- **A file too big for the model's window no longer ends the turn.** An agent reading a 476,668-character KiCad SVG with `svg_read` was handed all of it, llama.cpp refused the next request at 452,827 tokens against its 262,144-token window, and two compactions kept that answer "word for word" until the turn failed. A tool's answer is now cut to a share of the model's window (Admin > Settings > Agents, 25% by default), saying how long it was, how to read the rest with the tools the agent holds, and for an SVG that drawing it is the better way to look; a turn the provider still refuses is cut to the size the refusal names, an older long answer kept as one line saying what it was rather than summarised, with one harder attempt after the configured ones; the window is the model's own, or the one the refusal names where the model's row has none.
- **Answer again in voice mode is a voice turn.** It was sent as a typed one, so leaving the page did not stop it and the agent was not lent what a voice turn is lent.
- **Asked for a mockup, an agent draws a wireframe rather than an SVG picture.** Nothing it read said a mockup was a wireframe: `svg_draw`'s description and the drawing skill sent "a layout" to SVG, and the wireframe tools, at Offer behind the tool search on an agent with a tool limit, were described only as making a wireframe. `svg_draw`, the wireframe tools and the Diagrams and charts skill now say a mockup of a screen, page or form is drawn with `orknux_create_wireframe`, and the tool search finds it by "mockup", "screen" or "form".
- **Sentence completion no longer stalls after a burst of typing.** A suggestion the composer had stopped waiting for still ran on the model to its end, so on a model that answers one request at a time - llama.cpp's default - the one the person was waiting for queued behind it and ran out of time; a newer request for the chat now hangs up the one before.
- **Sentence completion keeps suggesting after a suggestion is taken.** The model was told to stop at the end of the sentence and say nothing when nothing follows, which is what a taken suggestion leaves; a finished sentence is now answered with the next one. Typing the suggested words keeps the rest drawn instead of taking it away at the first key, a model that begins with a line break or repeats the last words typed still has its new words shown, and a repeat cut short by the token limit is no longer shown as the text again. The pause before asking is now 300 ms and a suggestion may be 64 tokens, from 600 ms and 32.
- **A picture drawn in a turn stays above what the person wrote into that turn afterwards.** It was drawn at the end of the turn's working, so a message written while the agent was still at work appeared above a picture made before it, which read as the answer to it.
- **An agent's turn reads as one message after a reload.** What it said between its calls was drawn as messages of their own, each with the avatar, name, time and Copy, so one turn read as several; now a turn has one header, its lines - what it said, its calls, its thinking, its pictures and the answer - follow under it in the order they came, and only the answer has Copy. After a reload a message the person wrote while the agent was working starts a new turn, since the history keeps no mark of where a turn ended.
- **A turn's thinking is drawn once a round, with that round's time.** The block above the first call showed the whole turn's thinking time beside only the first round's thinking, and each later round showed the page's own count - "0.0 seconds" for reasoning that arrived at once, nothing for some - so a turn looked as if it thought twice. The server now closes each round's thinking with its measured time, a round that thought nothing has no row, and a time too short to say is not shown.
- **A research that read nothing no longer writes a report.** When every search is refused - a workspace with no search key, a spent quota - Gather fails saying what the search answered, and when nothing else was read Follow up fails saying how many searches it made, before the writer is asked, rather than the model writing up what it already believed and the report being saved as a PDF.
- **An action calling a built-in function opens with that function chosen.** The functions Orknux brings itself - `research_plan` and the other steps, `pdf_fromHtml` - were missing from the workspace's function list and their scope from the API, so the Research step actions showed an empty Function picker and the function's page failed; they are listed now, under Built in on the Functions page, and open read-only.
- **The Research list reads as one table**: every cell but the question and the status at one size, text regular and links medium, and the status the Runs list's badge. The model a research used links to the model's page, in the list and on the research.
- **A workflow with no trigger may write `{{trigger.x}}` in a value**, as it could always point a parameter at `trigger.x`: what starts such a run hands it whatever it hands it, so the placeholder is no longer refused on save as produced by nothing. And a node after a named agent is no longer warned that what reached the agent is produced by nothing - the agent's answer is added to what the run carries, as it always was at run time.
- **A workflow with no trigger no longer warns that its first node is never reached.** Without a trigger a run begins at the nodes no line leads into, as the engine always did; the editor said otherwise on every one of them.
- **A chat turn whose agent passed a long script to a tool is no longer lost.** Hiding passwords in the call's arguments ran out of stack on a few thousand characters, the turn ended without a word and the chat kept only the question; and a turn that breaks for any reason now says why in the chat and in its session.
- **An SVG attached to a chat no longer breaks it.** It was sent to the model as an SVG picture, which no provider loads, and every later message in that chat failed with it; it is now shown to the model drawn, a chat that already holds one recovers, and `picture_view` draws one kept as text.
- **An agent's chats follow it to a new model.** Each chat kept the model it began with, so an agent moved to another model or provider went on calling the old one until a new chat was started.
- An identity provider's sign-in whose username matched an internal account's was given that account's roles.
- Spraying sign-in attempts over ten thousand names wiped the pause on the one being guessed.
- One mailed password-reset link could be spent twice by two requests at once.
- Audit and session lines now hide credentials in JSON (`"password": "..."`) and this installation's own `orkx_` tokens.
- Links in the desktop app on Windows and Linux: mail and phone links open the mail program, a link to the server's own `localhost` address keeps you signed in, a download opened in a new tab leaves no empty tab, a page with unsaved work asks before it is left instead of silently staying, right-click offers to copy a link or open it in a new tab, and a link the app will not open says so.
- **A .csv attached on Windows reached the agent as base64 under a type nothing read.** The browser files it as `application/vnd.ms-excel` where Excel is installed, or with no type at all; a .csv, .tsv, .xlsx or .xls given one of those is now filed under its own type, so a CSV is kept as text - including one attached before the upgrade.
- **A watcher's regular expression can no longer stop every watcher.** A pattern such as `(a|a?){60}b` never finishes matching, and the tick checks watchers one after another, so one such watcher held the rest - and the tick itself - for ever. A match now stops after Admin Settings' regular expression time limit (2 seconds, `ORKNUX_TUNING_REGEX_MATCH_SECONDS`): setting such a watcher is refused, and one already set ends and tells its agent why. Its pattern is held to the 500 characters a workflow condition's is, and a result over a million characters (`ORKNUX_TUNING_WATCHER_MATCH_CHARACTERS`) ends the watcher with a note to narrow `tool_result_path`. Workflow conditions, scratchpad searches and HTTP tool rules run their patterns against the same clock; one that runs out of time does not match.
- `orkbox_export` holds at most what the session store keeps of one value (Session Value Size), and past it refuses, saying the size: a remote box's archive stops being read there, and the local box's is read to its end with nothing more kept. It used to read the whole archive into memory, copy it again as base64, and only then be refused for its size - a box workspace an agent had filled could take the server's heap with it.
- A watcher keeps, and tells its agent, the first 10,000 characters of what its tool returned (`ORKNUX_TUNING_WATCHER_RESULT_CHARACTERS`), saying how many more there were. Every check used to rewrite the whole answer into the watcher's row, and every wake-up put all of it into the agent's conversation; the condition is still held against the whole result.

## 1.1.9

### ✨ Added

- **A part's dependency graph**, from the Dependency graph button on its Used by panel: what uses it branching out to the left and what it uses to the right, followed to the end. A box chosen opens its part, or becomes the middle of its own graph; the window drags bigger.
- **A workflow's Used by**: its settings page lists the workflows that run it.
- **Agents ask with options.** `ask_person` puts a question in the chat with an option per row and a line on what each means, several to tick where the agent allows it, and a box for your own words; the answer goes back to the agent. Agents use it for any question with answers they can list, a quiz included.
- **Drawing in SVG.** Agents have `svg_draw`, which draws SVG they write in any colour, and `svg_toPng`, which turns an SVG they hold into a PNG.
- **Working on a workflow together.** Everybody with the editor open sees who else is there, their pointers with their logins, and their changes as they are made. Two changes made at once are merged rather than one writing over the other.
- **A model's own answer time limit**, on its page beside Requests per Minute. Empty takes the installation's Model Answer Time Limit.
- **The Errors report's columns sort**, in both tables: press a heading, press it again to turn it round.
- **Admin → Reports keeps the report in the address** (`?report=errors`), so a link or a reload opens it.
- **A field that reads a variable links to it**: an open button beside the reference on plugin parameters and action headers, landing on the variable in its catalog.
- **An agent can take back a review it sent** with `remove_session_rating`, when the person asks; on beside `rate_session` for every agent.

### 🔧 Changed

- **Diagrams look better by default**: rounded boxes without shadows, quieter lines, and a colour per kind of thing - services, stores, queues, groups. A diagram that sets its own `skinparam`, `!theme` or `<style>` keeps it.
- **The workflow editor saves as it goes**: Save and Discard are gone, and the toolbar says Not saved only when a save fails. A change is saved, and seen by everybody else in the editor, within Admin Settings → Workflow runs → Workflow Autosave (300 ms), also while a node is still being dragged or a name typed. Ctrl+S still saves at once; Undo is still there.
- **Anthropic models follow the Model Answer Time Limit** (an hour unless changed) instead of `ORKNUX_MODEL_TIMEOUT`'s two minutes, which is no longer read. If you set `ORKNUX_MODEL_TIMEOUT`, set the limit in Admin Settings or on the model instead.
- **A review taken back stays in the chat**, marked as taken back, with a line where it happened; it no longer counts in the reports.
- **Diagram decisions are ovals** in a colour of their own. They were written as hexagons, which drew no outline at all.
- **Workflow on and off is a green switch** in the editor, like every other on-and-off.

### 🐛 Fixed

- **A saved SVG file is shown as the picture it is** under the answer that links it, captioned with its file name, not as an unknown file.
- **A review an agent sends appears in the chat at once** and gets a line in the session, as one from the chat menu does.
- **A workflow's publications are paged** on its settings page.
- **A drawn picture is shown once, inside the answer it belongs to**, not twice and not as a message of its own above the tool call.
- **`svg_toPng` on a drawing that is already a PNG** answers that picture instead of refusing it.
- **Long workflow names** are cut on one line in the editor's toolbar instead of wrapping.
- **A question an agent asks scrolls into view** in the chat.
- **Pictures agents draw appear in the chat**: a diagram, a chart or an SVG drawing was handed to the agent alone, which then described a picture nobody could see.
- **SVG colours in pictures**: `hsl()`, `rgba()`, hex with alpha and `transparent` drew black in every PNG made from an SVG, and now draw in their colour.

## 1.1.8

### ✨ Added

- **Session ratings.** A person reviews a conversation - a score from 1 to 5 and their words - from a chat's + menu, or by telling the agent, which sends it with the new `rate_session` built-in. Admin -> Reports -> Ratings lists every review with charts of reviews per day and the mean score; the review is drawn in the chat it was sent from.
- **An Errors report** in Admin -> Reports: failed workflow runs, agents that could not answer and the quick chat's model failures, with a chart per day and what failed most often. Runs that had already failed are read in on upgrade.
- **Workspace sharing.** A workspace lends another its variable catalogs and model providers from Workspace Settings -> Sharing; the borrower's agents and pickers use them, and taking the share back takes them away at once.
- **A Workflow node**: one workflow runs another, waits for it and hands on what it produced. The run page links the two runs.
- **Cut, Copy and Paste in the workflow editor**, nodes and the joints on their lines, into the same workflow or another; rebindable in Preferences.
- **Node shapes** in the workflow editor - rectangle, rounded, pill, notched, hexagon, circle, triangle and star - and a Joint tool, with a preview of where a joint will go.
- **Workspace variables inside placeholders**: `{{variables.<id>}}` in a written value, offered by name after `{{`. A secret is never offered or filled.
- **Models as templates**, made from a model's own row as every other component's template is, and used from Use a template on the Models page.
- **Bulk import**: several files, or several ticked templates, as one import plan. The plan filters by what happens to each row.
- **Copying a workspace asks what to take**, and brings variables (secrets with their values), memories and speech actions; its workflows arrive published.
- **A variable, secret, skill or memory moves to another catalog** from its row.
- **A reference to a variable can be cleared** on plugin parameters, action headers, node parameters and secret fields; switching one to Value now saves.
- **Cache writes are counted and priced**, with a cache write price on a model's page; Anthropic's cache reads are counted too.
- **The Users list shows when each person last signed in.**
- **An MCP server can be tested in the Add dialog** before it is saved.
- **Plugins and functions can put a file on Slack through a connection.** `orknux.slack.upload` and `orknux.slack.remoteFile`, under the new `SLACK_UPLOAD_FILE` capability, use the connection's own bot token - so the Slack plugin no longer needs a second copy of it in its `botToken` parameter to upload. A plugin that declares it asks for the grant again on its next update.
- **`orknux_update_model` and `orknux_start_task`** for agents and the MCP endpoint; `orknux_models` shows each model's provider and prices.
- **The usage chart counts sessions and model calls per day.** A run's canvas draws the lines the run travelled in green, and a skipped step in grey.

### 🔧 Changed

- **Reading a secret back now takes a role.** Installation and workspace administrators keep it; anybody else needs a role with the new Reads secrets scope assigned to that workspace. **On upgrade, give that scope to whoever reveals secrets today without being an administrator.**
- **Ratings and chat reviews are on by default.** Their switches, and usage tracking, moved from Admin -> Reports to Admin -> Settings -> Reports; a report says in one line when its recording is off. Two reviews per conversation by default.
- **Boxes carry a required label again, and an agent is granted boxes by label.** Existing grants keep working; a box with no label must be given one the next time it is saved.
- **Providers send up to 200 tools at once by default**, except Azure and OpenAI's own API, and an agent may carry up to 1024. A local model's prompt cache holds across turns.
- **An agent may ask 25 other agents in a conversation by default**, not 10. An explicit value in Admin Settings or a workspace still wins.
- **Talking while the model thinks no longer stops it** unless the workspace turns that on.
- **Basic auth sends a secret that is already base64-encoded as it is**, rather than encoding it twice.
- **Every choice in the interface opens the application's own list**, themed, with the keyboard and a filter on long lists; an option's label is its name, and what it means is a hint beside it.
- **Test on a model provider checks the form without saving it**, and duplicating a provider no longer copies its models.
- **Removing a provider, a model, a trusted certificate, a saved file or a picture asks first**, naming what goes with it.
- **Use a template offers only templates made from that page's kind.**
- **Models are no longer offered as issue assignees**; Start by AI follows the assignee picker.
- **The import dialog opens wider and can be resized from any edge**, and remembers its size.
- **The manual says what this release added**, and its search no longer freezes after typing stops.

### 🐛 Fixed

- **A task that lacks something stops and asks again.** Since agents may ask for more tools by default, a task's agent asked a chat nobody was in and the task never stopped; a task now asks only through its own permission request.
- **The Design image editor kept its undo, redo and name** after a picture finished drawing; they were lost since 1.1.5.
- **The Objects page paged only the workspace's own objects** while drawing every plugin shape under them; it now pages both as one list.
- **The workflow editor's Save saves an agent edited in the panel beside it.**
- **An imported agent brings its variable catalogs**, made empty where missing.
- **`agent_wait` is no longer offered in a streaming step**, where it could not wait.
- **A variable moved into a catalog that already holds its name is refused by name** rather than failing in the database.
- **A Workflow node's link opens the called workflow's editor**, and the Variables page keeps its catalog in the address.

## 1.1.7

1.1.6 was tagged but never published: its build failed on two tests that held only on the machine that cut it. 1.1.7 is 1.1.6 with them fixed, and everything listed under 1.1.6 below arrives with it.

### 🔧 Changed

- **Progress messages None is held to by the server.** What an agent writes beside its tool calls is kept in the session's log and no longer shown in the chat or handed on to the next node, whatever the model does. Each level's instruction to the model now says plainly how many lines it may write and that each one is sent at once.

### 🐛 Fixed

- **A long manual section read through `orknux_manual_section` could come in a part one character over its limit.**

## 1.1.6

### ✨ Added

- **Harmonize in the image editor.** Redraws the whole picture lightly through a Stable Diffusion WebUI, so the seams a Magic edit leaves blend in. Its strength and the size it redraws at (`ORKNUX_IMAGE_HARMONIZE_STRENGTH`, `ORKNUX_IMAGE_HARMONIZE_SIZE`) are in Admin -> Settings; a larger picture is scaled down to that size and back.
- **A picture waiting or being drawn in Design can be stopped**, an upscale and a Harmonize included. The WebUI is asked to interrupt; a WebUI busy with more than one picture stops whichever it is drawing.
- **Progress messages, None to High.** How much an agent writes while it works is set on the agent, and an agent node can set its own; every existing agent is on Medium, which is what agents were told until now.
- **Output streaming on an agent node.** On by default: what the agent writes between its tool calls goes on to the next nodes as it is written, so a Slack reply goes out while it is still working. Off, the node finishes first and hands everything on together.
- **A workspace's own Tool Rounds**, in Workspace Settings, between an agent's own number and the installation's.
- **A price for cached input** on a chat model's page and in Add Model; costs use it, and fall back to the input price where there is none.
- **Charts on the usage report**: day by day, tokens by kind (input, cached input, output) or cost. Sessions under Tokens by person show when they ran and what each cost.
- **An agent can read the Orknux manual** with `orknux_manual`, `orknux_manual_section` and `orknux_manual_search`, offered over MCP too.
- **An agent may ask to use one of the workspace's connections**, which the person accepts or declines in the chat.
- **A Box Code Reading skill**: answering questions about code from a checkout with ripgrep, ctags and ast-grep, which the box toolchain image now carries.
- **History says what each save changed** and shows a version as JSON against the one before. An agent's history now keeps which agents it may ask.
- **Use a template can leave an agent's model out**, and the agent arrives with none chosen. Its dialog and the import plan can be resized from their corner.
- **The image editor numbers its pictures** in the order they were asked for, and its history of a picture survives a reload.

### 🔧 Changed

- **Asking an agent again in the same conversation continues the conversation it had**, with what it answered before; `fresh: true` starts a new one. A follow-up does not count against Subagents Per Conversation.
- **`agent_wait` no longer holds back messages in a workflow step's conversation.**
- **An asked agent's calls are booked in the usage report to whoever prompted the conversation that asked it**, rather than to nobody.
- **A plugin tool's connection argument offers only connections of its plugin's kind**, and says it may be left out - agents had been inventing a Slack connection id.
- **An agent is listed as used by the agents that may ask it.**
- **A chat model's prices are edited on its own page.** Costs from a dollar up are rounded to cents.
- **Publishing a template closes its dialog** rather than opening a message to close. A template's name opens it.
- **Agent settings**: Tool Rounds and Tools carried at once are boxed numbers on one line; Add a model is a green plus beside the model's link.
- **Session side panels explain themselves behind (?)**, and the Thinking filter is no longer red while off.

### 🐛 Fixed

- **Workspace Settings wrote a late load over what was typed** in its first seconds, and the save then sent the old value.
- **The workflow editor's pickers held only the first hundred** actions, agents or conditions.
- **Design's gallery showed a picture's published version where it had a draft.**
- **The image editor's Drawing… and Stop broke over two lines**, and a large picture being upscaled pushed them out of view.
- **Watchers jumped on auto-refresh.**
- **A returned tool call's status sat halfway along its row** instead of beside its time.
- **Save as a template cropped its description**, and its chosen option showed no dot.
- **A hint opened by itself when a dialog did.**

## 1.1.5

### ✨ Added

- **Schedules: a reminder an agent sets that comes back on its own.** `schedule_set` takes a note and either `every_seconds` or a five-field `cron` (with a `timezone`), and optionally an end time or a number of runs; each run is left in the session's inbox the way a timer rings, and runs missed while the server was down are not replayed. On every agent by default, listed on a workspace's AI -> Schedules page and a session's panel, where a person can stop one; bounded by `ORKNUX_SCHEDULE_MIN_INTERVAL_SECONDS` (five minutes) and `ORKNUX_SCHEDULE_MAX_PER_AGENT` (ten; `0` switches them off), both overridden in Admin -> Settings.
- **An agent can read, make and configure agents.** `orknux_agent` reads one in full and `orknux_models` lists the workspace's models; with write scope, `orknux_create_agent` and `orknux_update_agent` go through the agent page's own save, so its refusals, audit entries and versions are the same. Agents with Orknux access are offered the two writing tools on upgrade; hide them on the agent's page to withhold them.
- **`app_link` and an installation address in Admin -> Settings.** Every link the server sends out - mails, `orknux_*` answers, `app_link`, pictures handed to a model - is absolute and built on Admin -> Settings -> Address, which falls back to `ORKNUX_BASE_URL`.
- **Placeholders in any written node value.** `{{ field }}` is checked on save and refused when nothing in the graph answers it; in the editor typing `{{` suggests fields, placeholders are highlighted, and each draws the green dependency line a reference does.
- **A run's step shows its parameters** - an agent's prompt, an action's arguments - worked out from what it received, above its input. Workspace variables are left as written.
- **A run of an older version says so.** A run that started while the draft had changes the publication it ran did not is marked Older version above its graph, and the graph drawn is the one it ran, not the workflow as edited since.
- **Upscaling through a Stable Diffusion WebUI** (AUTOMATIC1111, Forge, SD.Next) as its own provider type, with the algorithms each model offers.
- **Pictures imported, dropped or pasted into the image editor, and exported** as PNG, JPEG or `.orkx`, an archive of the picture with its prompt, name and parameters. New, beside Discard, opens a blank canvas.
- **Models exported and imported between workspaces**, providers included and API keys left out; enter the keys again after importing.
- **A connection may be granted to every new agent**, chosen on the connection.
- **A Building workflows skill** that tells an agent how to wire a graph so it runs and opens as a readable drawing.
- **Each tool call in a chat shows the time it was made**, to the second.
- **Every quick-edit panel in the workflow editor links to the component's own page**, in a new tab.
- **Cached input on the model page and in Tokens by person**, read from Azure's and OpenAI's Responses usage.

### 🔧 Changed

- **A run begins only at a trigger.** In a workflow with a trigger, a node the trigger does not lead to is skipped, where it used to run beside it. A workflow drawn without the line from its trigger does nothing until the line is drawn, and its run log opens by naming the trigger and saying to draw it. A workflow with no trigger at all runs as before.
- **A reminder an agent sets no longer holds its workflow step**, and neither does waiting on an agent it asked: the step answers at once, so the Slack reply goes out, and the reminder or the asked agent's answer wakes the agent later in the same session, its reply following the first.
- **Message times are shown in every chat by default.** Upgrading turns them on in every workspace; Workspace Settings turns them off again.
- **A chat is named once at its start and again every N exchanges**, N an Admin setting, rather than after every answer.
- **An agent's model has to be one that chats.** Saving an agent with a newly chosen embedding, transcription or speech model is refused; an agent already holding one is left as it is.
- **Node text fields are dragged taller** instead of opened in a larger box, and a run step's input and output are too. Lines a run travels in the workflow editor are animated; dependency lines stay still.
- **The workflow list is ordered by a press of its column headings** - Workflow, Description, Last Run - and the sort selector is gone. Triggers can be ordered by Last fired, and a trigger's execution history is open by default and paged.
- **A trigger's line in a run's log reads "<trigger> fired with: ..."**, the payload it started the run with, for every kind of trigger.

### 🐛 Fixed

- **A condition that could not be answered went both ways**, the "no" side handed what the "yes" side produced. It now ends the run there and says why.
- **Two custom actions in a workflow mixed their parameters** when switching between them in the editor.
- **The Azure prompt cache read as 0%.** Cached tokens are counted from now on; earlier usage stays at zero.
- **Tokens by person listed a Slack member twice, left names unresolved, and called live sessions "Removed session".**
- **A trigger's firing was marked Failed when its workflow did start**, because other workflows it reaches were switched off or never published.
- **A skipped step said "the condition before it went the other way" in workflows with no condition.** It now says what happened before it.
- **Every trigger step logged "TRIGGER nodes have no runtime yet".**
- **An error inside a streamed answer was shown as "200: ...".** It reads "The provider stopped part way: ...".
- **A failed picture change hid the picture.** It stays, with the model's reason and the picture's size.
- **The executions table jumped on each refresh**, and a run's log now follows what it writes.
- **A schedule's note was cut off at 180px.**
- A wait an agent step parked on was written twice in the run's log.

## 1.1.4

### ✨ Added

- **Design, a new section with an Images page.** Every picture a model drew in the workspace, as a gallery that keeps loading as it is scrolled. A picture opened shows its name, its prompt and what it was drawn with - model, size, quality, style and who drew it - with Remove and Edit beside it, and the gallery scrolls on its own down its left.
- **An image editor.** The canvas, and beside it the prompt, the image model and that model's own parameters. Generate queues the picture and is drawn behind it, so several can be asked for and the editor left: queued and drawing pictures show in the gallery, marked, and open in the editor from there. Zoom in and out with Ctrl+= and Ctrl+-, undo and redo, and name a picture before or after it is drawn.
- **Magic edit: select part of a picture and say what to change.** Rectangle, lasso and select all - M, L and Ctrl+A, each changeable in Preferences. The change is asked of the image model through the standard `/v1/images/edits`, and every pixel outside the selection comes back exactly as it was.
- **Changes are a draft until saved.** Save writes them over the picture, whatever drew it; Save as copy keeps them as a new picture and leaves the original as it was; Discard throws them away. A draft is kept through leaving the editor and restarts, and its picture is marked Draft. Ctrl+S saves.
- **Open in Design** on a picture on the Artifacts page.
- **A stable-diffusion.cpp provider type**, whose picture edits send the mask that server reads, and an **Edit mask** setting on a provider to say which way its server reads one.
- **Longest image prompt** and **Images drawn at once** in Admin › Settings, with `ORKNUX_IMAGE_MAX_PROMPT` (2000) and `ORKNUX_IMAGE_DRAWS_AT_ONCE` (1) where a fresh installation starts.

### 🔧 Changed

- **The top bar is in the alphabet's order**, with Design in it: AI, Chat, Design, Flow, Workspace.
- **OpenAI-compatible, Ollama and llama.cpp providers work without an API key.** A server on your own machine usually checks none; one that does answers 401, and the provider's card says so. Anthropic and Azure still need one.
- **An OpenAI provider whose server is stable-diffusion.cpp's is saved as that type**, recognised by its `/sdcpp/v1/capabilities`. An existing one changes the next time it is saved; until then its edits are sent the standard mask, which that server reads as "change nothing".
- **How long a picture's description may be is one setting** for chat, tasks, workflows and Design, where each had its own fixed limit. It starts at the same 2000 characters.
- **A chat's header has one link to its session**, not two.

## 1.1.3

### ✨ Added

- **Orknux on the desktop, for Windows and Linux.** The orknux-one server in a window of its own, with its own Java and a SQLite database in your profile: a `.exe`/`.msi` installer and a `.deb`/AppImage, built and attached to each release after the images are published. It keeps running in the tray when the window is closed, updates itself through the same signed releases as the server, and names its one account after the person at the machine.
- **Network access for the desktop app, in Admin › Settings.** Switch between this computer only, with nobody signing in, and anyone on the network, with everybody signing in: going to the network sets the password you sign in with, and the app starts again to apply it. Only the desktop app's server shows the card.
- **A fresh installation starts with a workspace.** The first start on an empty database - the desktop app, a new orknux-one, a new server - makes one called Default, set up as the admin page sets one up, so chat works straight away. An installation that already has workspaces is not given one, and removing every workspace later does not bring it back.
- **An agent can ask for the tools it lacks.** Instead of saying it cannot do something, an agent lists the tools it could be given - built-ins switched off for it, the workspace's and plugins' tools, MCP servers, Orknux access - and asks for the ones it needs. The question in the chat names each tool, its kind and what it does, with Accept and Refuse; accepted, the tools are the agent's for good, recorded as a change to the agent. An agent may also ask for them to be loaded at once, so it can call them on its next step.
- **Unread answers beside each chat.** The chat sidebar shows, beside a chat's name, how many answers arrived since it was last open - a silver pill and a bold name, so news stands out in a long list - and opening the chat clears it. An answer that finishes in a chat you left counts at once, and one that lands in the open chat while the window is in the background waits for you to come back. Existing chats start as read.
- **Attached files reach the agent.** A PDF, a log or any other file attached to a chat message is kept where the agent's tools can read it, and the agent is told how - before, only pictures reached it, and it could only guess at the rest.
- **Drop files on a chat to attach them**, any kind and several at once, as the attach button does.
- **A chat being named says so**: "Naming this chat…" stands in the title and a spinner turns beside it in the list until the name comes.
- **The chat list can be dragged wider** by its edge; the width is remembered, and a double-click puts it back.
- **Chat opens a new chat by itself** when the workspace has none yet.
- **Chat answers render LaTeX maths.** `$x$`, `$$x$$`, `\(x\)` and `\[x\]` are set by KaTeX wherever the product draws markdown - chat, task outcomes, issues - while a price like "$5 and $10" and anything inside code stay as written.
- **A model's page says what uses it.** Used by on a model lists the agents that think with it, the workspace settings that name it, the actions that speak with it and the workflows whose image or decision nodes use it; a provider's page lists its models. Removing a model still in use is refused with that list, and so is removing a provider while any of its models is in use.
- **Test a connection before adding it.** The Add Connection dialog has Test Connection, which checks what is typed and stores nothing.
- **A plugin's connection kind can bring a setup guide, a check and a default address.** The guide is shown beside the Add Connection form; Test asks the plugin whether the address really is its service, not only whether something answers; and a kind with a default address may be added with the URL left empty. The Anki plugin's AnkiConnect uses all three.
- **Picking a model a llama.cpp server offers fills its context window**, the one the server runs it with, and puts 0 in both prices.
- **"+ New function" opens a panel** with a name and a description, beside the node panel in the workflow editor and as a dialog on the action, condition and trigger pages; the function is made at once and chosen.
- **Save and Discard along the foot of every page that saves**, always in the same place, off until something has changed.
- **A menu down the side of an agent's settings**, as the settings pages have.
- **An "Add a model" link under an agent's Model**, opening the Models page with the Add Model dialog.
- **Used by is paged** where it runs long.
- **The Updates page says why a chosen release was not started** - one built for a newer Java than the desktop app carries - where it used to say so only in the log.
- **The top bar shows your display name** where the installation has one, rather than your username.

### 🔧 Changed

- **Every agent may ask for more tools, existing ones included.** Asking grants nothing until a person accepts. To stop an agent asking at all, untick **May ask for more tools** on its page.
- **New agents start with Orknux access on** - asking Orknux about Orknux. Existing agents keep what they had.
- **A box has a name only.** The label is gone; an agent's box grant names the box, and existing grants were moved from the label to the boxes that had it on upgrade. Renaming a box moves the agents granted it.
- **An agent's Boxes grant is a list of ticks**, as Connections is.
- **Test Connection sits beside the status line under the form** on a provider, connection, MCP server and box page, rather than in the page's header.
- **Voice mode is always on the chat bar.** Where the workspace has no speech or transcription model it is drawn off instead of hidden, and hovering it says which model is missing and links to where it is chosen. A workspace that wants it gone ticks **Hide the voice mode button** in its settings' Voice card.
- **The release launcher skips a release built for a newer Java** than the runtime it has, and keeps running the one it has, rather than restarting into a release that cannot start.

### 🐛 Fixed

- **Function pickers showed only the first hundred functions.** A workspace with more could not find or choose the rest, even by searching.
- **Testing a new provider emptied the API key** and left Create switched off.
- **A function name refused as taken stayed refused** after it was changed, and a node's panel stopped saving.
- **"Writing the answer…" stayed under an answer** that was already showing.
- **Admin pages pushed their footer off the screen**, and a long admin menu now scrolls in its own column.
- **A settings page opened at `#section` lands there**, and long menu entries wrap instead of being cut off.
- **Tool calls in a row sat a whole message apart**; they are one stack now.
- **Table headings and cells mix fonts no more**: every heading in a table is drawn alike, the sorted one included, and a cell no longer falls back to a larger size; every pager is the same pager.
- **Ctrl+Q closes the quick actions** it opened.
- **The notification bell said "Nothing yet." when its list could not be loaded**; it says it could not load, and drops a count it could not refresh.
- **The connection type filter shows** even when a workspace has one type.
- **In the desktop app, a dropped file replaced the window**, and a link meant for a new tab threw away unsaved work; such links open a new window.

## 1.1.2

### ✨ Added

- **Admin > Reports: tokens by the person who prompted them.** A usage tracking switch, off until an administrator turns it on and recording nothing at all while off; what was recorded stays readable after. The report filters by period, workspace and source, lists people biggest first, and opens each to their sessions with their share of every one - two people in one session are two people. Slack members and Orknux users for now; Jira is left for later.
- **Workspace members.** A Members section on a workspace's settings lists everybody whose roles open it and makes any of them a workspace admin, or an ordinary member again. Somebody let in by a directory group alone is listed once they have signed in.
- **An object inside a property.** The object editor's type list offers `object`: fields of its own, drawn as rows inside the row, as deep as the installation allows. An object can also be made from a pasted JSON Schema, from the Objects page and from the workflow editor's Create Object panel: the schema is read as it is typed - Valid or Invalid, the details on hover - and a key a reference could not follow (`issue-related-events`, `2fa`) is written with underscores, the key it came from kept in the description. A field an Object node names for itself may be an object defined in place too.
- **Rectangle select on the workflow canvas.** A switch at the left of the editor's toolbar, or `S` on the canvas: on, a drag over empty canvas selects every node the rectangle touches instead of panning; the keystroke is rebound in Preferences. Shift-drag selects as before, and Escape lets a selection go.
- **The Add menu opens at the pointer and takes the arrow keys.** `A` over the canvas draws the menu where the mouse is and puts the node there; Down steps onto the first entry, the arrows wrap, and Enter adds the entry under focus.
- **An Object node's fields are drawn as a tree**, an object's own fields folded under it until unfolded, and a later node can point at one inside by its dotted name.
- **Type filters** on the providers, models and connections lists, and the providers table says each row's type; the triggers list is narrowed by where a trigger listens.
- **Reports can be searched by person, sorted by any column and exported as CSV.**
- **A menu down the side of both settings pages**, read off their headings, with a search that narrows the page to the settings whose words match; the settings scroll under the top bar, the sidebar and that menu, which stay put.
- **A row of the Users list opens the user**, and saving a user keeps the page open, with a Saved note beside the button.
- **Object Depth** in Admin > Settings: how deep an object may nest and how deep a value is checked against one. `ORKNUX_OBJECT_MAX_DEPTH` is where a fresh installation starts, 30.

### 🔧 Changed

- **Talking over the answer waits a second by default**, not half of one: half a second still let a word said to somebody else in the room stop the answer. A workspace that set its own value keeps it.
- **A workspace's settings are its admins' to change.** Every setting on a workspace's settings page used to be changeable by anybody who could see the workspace; each now needs somebody who administers it - a role marked as administering in Admin, or a member made a workspace admin on the page itself. The Settings entry is offered in the menu only to them. On upgrade, somebody who changed a workspace's settings without administering it will find the page refused until an administrator makes them a workspace admin.

### 🐛 Fixed

- **A list that refreshes itself no longer jumps.** The audit logs and the triggers list drew a spinner above the rows on every reload, so with auto-refresh on the table moved once a second.
- **A sortable heading starts where its column's cells start.** The Roles table drew NAME centred over left-aligned names.
- **The line under a table starts where its first heading starts.** The pager sat ten pixels to the left of every list it was drawn under.
- **An object node's Shape panel draws an object defined in place** as the object's page does. It asked for a type on every nested field and showed none of their fields; the panel's rows are also laid out evenly, the name and description boxed and the remove in the card's corner.
- **Back from a user's page leaves in one press.** Opening a row pushed the page twice.

## 1.1.1

### ✨ Added

- **An access token can be read-only.** Chosen when it is made, beside its name: a read-only token answers queries and is refused every mutation, is offered only the MCP tools that read, and is refused any other request that is not a read. Existing tokens stay read and write.

### 🔧 Changed

- **Access tokens are a table** - name, access, created, last used, and Revoke under Actions - and a new token's secret can be dismissed once it is copied.

### 🐛 Fixed

- **The first session on a freshly started local box keeps its workspace.** The box's clean-up of what a previous run left could take a session opened as the box came up for a leftover and delete it, and its first command answered that the workspace was gone.
- **Stopping a run mid-step on SQLite records the step as stopped.** The interrupt that ends the call could arrive after the step had already ended and break the write recording it, so the step failed instead of stopping.

## 1.1.0

### ✨ Added

- **A node's Action and Condition pickers offer what the plugins bring**: every loaded plugin's actions and functions beside the workspace's own, each marked with its plugin - and in a condition, the functions that answer yes or no. Picking one makes the node's own definition around it, which used to take Custom, a subtype and the plugin's entry.
- **Shift+click selects several nodes** in the workflow editor. They move together, the panel says how many are selected, and Delete removes them all - Delete now works for one node too, not only Backspace.
- **Quick filters in a node's Action and Condition pickers**: All, Workspace and each plugin, several at once.
- The **field picker marks the row that hands on a whole object**, with its fields indented under it, so it is not missed among them.

### 🔧 Changed

- **Creating a workflow opens its editor**, as duplicating one already did.

## 1.0.9

### ✨ Added

- **A node parameter can reference a workspace variable.** The Reference picker has a Workspace variables group, read when the step runs. Secrets are not offered and never resolve there: a parameter is handed on as text, into a message or a prompt, and a secret referenced into one would be published.
- **An agent can build a workflow** through the orknux tools, over MCP or granted in its settings: read one (`orknux_workflow`), list what its nodes can point at (`orknux_workflow_parts`), read what every field means (`orknux_workflow_reference`), create the workflow and the triggers, actions and conditions it needs, save its graph and publish it. Everything goes through the checks the editor uses and is audited under the agent's name. Saving over a draft somebody has not published is refused unless the agent says so.
- The **workflow editor has a Refresh button**: it reads the graph and the lists its nodes pick from again, so something made in another tab appears, and asks first when there are unsaved changes.

### 🐛 Fixed

- **A plugin parameter's pickers wait for Save** like its text boxes: choosing a connection, an option or a variable stored at once, while typing waited.
- **A session's cost meter is shown even before anything was counted**, at nought with the reason on hover. It hid itself, which read as switched off on every session from before 1.0.7.
- **A condition switched to Function can be saved.** One that had been a Value, Slack or Jira condition kept the old type's check and was refused with INTERNAL_ERROR.
- The **function editor's return type follows the code**: `: Promise<number>` written in the declaration moves the Return Type field, and choosing one there rewrites an annotation already in the code.
- **An action that sends a message offers only Slack connections**, and saving one on another kind is refused. A Jira or HTTP connection could be chosen before, and every run skipped the step with "cannot send messages yet". An action already saved that way keeps skipping until it is pointed at a Slack connection.
- **Duplicating an agent no longer fails** when it is still granted tools that are gone, such as those of a plugin switched off since. The copy arrives without them, and the audit log says which.

## 1.0.8

### ✨ Added

- **The sessions list shows each session's tokens and cost**, and sorts by either. The columns follow the workspace's switch for showing a session's cost.

### 🔧 Changed

- **Memory catalogs and plugin skills are granted to agents by default.** A new agent holds every memory catalog its workspace has and every plugin's skill catalog; a memory catalog created later, or a plugin that brings skills for the first time, is granted to the agents already there. Each can be unticked on the agent, and re-uploading a plugin does not hand back a catalog somebody took away.
- **A session page's side column has no scrollbar of its own**: it grows with its panels and the page scrolls to them, while a long list still scrolls inside its panel.
- **Save is at the top** of Preferences, beside the title with the count of unsaved changes, and at the top of a connection's General card.

## 1.0.7

### ✨ Added

- A **session page shows what the session has cost**: the tokens every model call made into it was charged - in, out and cached - and the money where the models have prices. A workspace setting, on by default. It counts from this release on; earlier calls were not recorded.

### 🔧 Changed

- **Preferences has a Save button.** A change there is held until you press Save, and the page counts what is waiting; before, each one took effect the moment it was made. The email address no longer has a Save of its own.

### 🐛 Fixed

- The **Integrations tables line up** again: an MCP server's name was centred and drawn in the wrong weight, and the Auth heading sat over the Status column, which had no heading. A connection's description now has a column of its own.

## 1.0.6

### ✨ Added

- A **connection has a description** - what it is for, such as "production Jira, the support project". It shows under the name in the Connections list and in full on hover, the list's search finds it, and an agent is told it beside the connection's name and can search by it, so two connections to the same system are told apart. An inherited connection takes its admin default's.

### 🐛 Fixed

- A plugin parameter could not be emptied by saving - deleting a value or a revealed secret from its box left Save disabled, and only Clear removed it. Saving an emptied box now clears it.

## 1.0.5

### ✨ Added

- An answer **read aloud shows how much of it has been heard**, as a bar and a percentage beside the speaker and under voice mode's caption.
- A plugin's **secret parameter can be shown** on the workspace's Plugins page once it is stored, with the eye beside it; the workspace's audit log records that it was read.
- A **chat timer chimes** when it reaches nought in the open chat. A workspace setting, on by default; an agent can make one timer sound or stay silent when you tell it to.

### 🐛 Fixed

- **A SQLite installation - the all-in-one `orknux-one` image - that upgraded to 1.0.4 did not start**: 1.0.4 changed a schema file such an installation had already applied, and the migration tool refuses that. Upgrading to 1.0.5 starts it again, with nothing to do by hand; one made fresh on 1.0.4 is put right on its first start, keeping its settings.
- A workspace's own connection can have its **URL changed** on its page - and a mail connection its server, which could not be changed at all. Before, only the URL override could move one.
- Saving a plugin's secret parameter left the page saying "1 unsaved" with Save still pressable, and the typed secret in the box; it now clears once stored.
- After sending a message, a chat sometimes stopped short of the end of the answer; it now follows the answer to the bottom, and leaves you where you are if you scroll up while it arrives.

## 1.0.4

### ✨ Added

- **Mute yourself in voice mode**, with a button on the panel or a shortcut - Alt+M unless you choose another in Preferences. Muted, nothing you say is heard, transcribed or sent, and the answer being read aloud goes on.
- In voice mode, a message **waiting** for the next turn can be cancelled before it goes.
- In voice mode, **talking while the model is still thinking stops it**, as talking over the answer does, and what you said is the next turn. On by default; the workspace's Voice card can turn it off, and then what you say waits for the turn after.

### 🔧 Changed

- **An agent node hands on everything its agent wrote**, not only its last message: what it said beside its tool calls, round by round, then its answer, separated by a blank line - across every pass of a step that slept and was woken, and in what a watcher's follow-up hands on. Its thinking, tool calls and notes are still left out. A workflow that posts an agent's output to Slack will now post its progress remarks too; an agent that should send only its conclusion has to be told to write nothing beside its calls.

### 🐛 Fixed

- A session page's side column no longer makes the page scroll when its panels are taller than the window; it scrolls inside itself, so an open scratchpad fits the page again.

## 1.0.3

### ✨ Added

- A session's page lists the **watchers** and **timers** its agent set, below Notes. Running watchers come first, each with what it is for and where it stands, and a row opens the Watchers page. Timers are one row per reminder, saying when it is next due and how often it rang - so a reminder the agent re-arms on every ring stays one row.
- `timer_list` and `timer_cancel`: an agent can see the reminders it set and call one off before it comes up, so told to stop something it keeps re-arming, it can. `timer_set` now answers the reminder's id. Both are on for every agent, like `timer_set`.

### 🔧 Changed

- A session's **Sessions** panel is always shown, listing the session alone where it asked no other agent, like the other panels beside the transcript.

## 1.0.2

### ✨ Added

- **Free sessions on a box.** Admin → Boxes shows each box's free sessions of the most it takes, and splits active sessions into working and idle. Agents see the same in `orkbox_list`, so they can pick a box with room.
- **Widgets** in a chat: timers and stopwatches an agent puts above the message box with its new `widget_*` tools, ticking as you watch and appearing mid-turn without a reload. You can pause, resume, reset, lap and remove them yourself; a timer the agent asks to be woken by tells it when it ends. **Widgets Per Chat** (8) and **Longest Chat Timer** (a day) are Admin settings.
- **Time Zone** in Preferences: every date and time the interface shows is drawn in the zone you choose, kept with your account. Left unset, your browser's own zone is used as before.
- A chat that ends on your own message with no answer under it - the turn died, was stopped, or the page lost it - offers **Answer this** under the message, instead of making you type it again.

### 🔧 Changed

- **A watcher's own looks back off exponentially.** The agent no longer has to choose an agent check: the first look comes after 5 min, each wait after that doubles, and none is longer than a day. Admin Settings sets the three numbers and shows when the agent would look on watchers from 10 min to a week. An agent check the agent gives must now be shorter than the watcher's timeout, and running watchers set without one get looks at their next check.
- Retry notes in a conversation and step retries in a run log say when the next attempt is, in the working timezone, and the Watchers page shows times on hover in your own time zone rather than UTC.
- **A box's session limit counts only sessions at work** - being made or running a command. An idle session no longer takes a place; it takes one when it starts a command, and is refused while none is free. This applies to the local box and to a standalone orkbox once it is upgraded.
- **Subagents share box sessions with the agent that asked them**, both ways, the way they share scratchpads: either can run commands in a session the other opened - on the grant of whoever opened it, so a subagent needs no grant of its own for that box - and `orkbox_sessions` lists them with who opened each. A conversation outside the family still cannot reach them.
- A watcher that times out tells its agent the wait is not settled - set another watcher with a longer timeout or a better condition, or tell whoever it was working for - instead of letting it finish quietly.
- Admin → Boxes reloads itself on the shared refresh interval, so health and sessions stay current.
- A session's Boxes panel lists the box sessions it shares with the agent that asked it or the subagents it asked, marked **shared**.
- A chat whose model gives no usable title is named from its first words, and says for a moment that the model could not name it.
- "Reading the prompt…" shows only once a llama.cpp server has been reading for five seconds, so the short reads a warm prompt cache makes no longer flash a card; **Reading Progress Shown After** in Admin Settings changes it, and 0 shows every read as before.

### 🐛 Fixed

- **Continue in chat** opened an empty chat when the session's newest line was longer than the memory allowance.
- An answer's "Thought for" counted more than its own turn, and a turn still running showed one.
- Times shown on hover - session lines, box events and expiry, trigger firings, watchers - were raw UTC timestamps.

## 1.0.1

### ✨ Added

- A **llama.cpp** provider type: spoken to as OpenAI is, and asked how far it has read a prompt - the chat and the session page say "Reading the prompt…" with how far it has got, and the tokens read and cached under it, while a long session is read, which was minutes of nothing on the screen. Move a llama.cpp server registered as OpenAI to the new type to see it.
- A chat's running total says how much of its input the provider reused from its cache, where it reports it - every round sends the whole conversation, so a long chat on a local server read as millions of tokens it mostly never read again.

### 🐛 Fixed

- A round asked again because a person wrote mid-turn was left out of the turn's token count.
- Pinning a chat took two or three seconds to show: the chat list loaded every chat's agent and model one by one. It moves at once now, and the list itself loads in a fraction of the time.
- A box call whose answer could not be read because part of the server's code would not load - a server updated under itself - now says the call may have taken effect and to check before retrying, rather than that it failed.

## 1.0.0

### ✨ Added

- **Boxes - sandboxes where agents work.** An agent can open a session on a box, clone a repository into it, run commands, edit files, build and test, and hand back what it made - on a box built into the server or on remote orkbox instances. Everything below about boxes is part of it.
- **Boxes**, the replacement for shells: an Admin → Boxes page lists the orkbox sandboxes agents can run commands in, with remote ones added by address and token, their health, active sessions and what each offers.
- A local box built into the server, on by default, configured in Admin Settings - where its sessions live, the local or docker runtime, how many sessions it holds and how long they last; it runs commands as the server's own user.
- An agent's **Box labels**: it may use a box whose label it was given, and none by default - the local box included.
- Box tools for agents - opening a session on a box, running commands, reading and editing files, searching, reading the session log and moving archives in and out - with how long a command is waited for, how long it may run and how much output an agent is handed set in Admin Settings.
- A box edit can replace lines by number or a regular expression's matches, and `orkbox_undo` / `orkbox_redo` take back and put again an agent's edits and writes in a session - refusing a file something else changed since - with how many steps are kept per session set by **Box edit undo steps** in Admin Settings (10 by default, 0 to switch it off; held in memory, so a restart forgets them).
- A conversation's **Box** panel: the box sessions its agents opened, whether each is still running, and each one's event log.
- An agent can ask to expose a port of a running box session with `orkbox_expose`, and nothing opens until a person allows it: the chat and the session transcript ask with **Allow** and **Reject**, and a **Ports** panel below Boxes lists every port asked for - its box label, whether something answers on it, and Allow or Enable, Disable and Delete at any time. The agent is told the outcome, with the URL, as a message that also wakes it. Admin Settings sets how many ports a session may expose (5) and how often and how long whether one answers is looked at; a box forwards a port through `docker exec`, so it works on Docker Desktop too, and listens on `ORKBOX_PUBLISH_HOST` (every interface by default).
- An agent's **Secret Catalogs** and **Variable Catalogs** grants: `variable_list` and `variable_get` read the plain values of the second, and `orkbox_exec` / `orkbox_open` take secrets and values by name and set them as environment variables for the command - a secret is never shown to the model, and anything a box prints that holds one reads `***` in the tool's answer, the session log, the chat and the Box panel.
- `orkbox_import` takes a .zip as well as a .tar or .tar.gz, and a secret handed to a box is set upper-cased too (`github_token` is also `$GITHUB_TOKEN`), with the answer naming what was set.
- An agent puts a draft into a box straight from a scratchpad (`orkbox_write` with `from_scratchpad`), and a saved artifact into a box with `orkbox_fetch_artifact`, unpacked if it is an archive: a box cannot reach this server, so it could not download one itself.
- You can write in a chat while its agent is working: the message goes to the running turn, the agent reads it at its next step, and it shows in the conversation where it was read.
- While a model writes a long tool call or its answer, the chat and the session page say what it is writing and how much has arrived, so a model putting a file into one call no longer looks stalled.
- A model provider can hand an agent **all tools up front** - every tool it holds from the first round, nothing to find or load - so the tool list never changes mid-turn and a local server such as llama.cpp keeps its prompt cache; on by default for Ollama and OpenAI-type providers, and a provider's **Most tools per request** caps how many one request carries (the type's own limit by default, 128 for OpenAI), past which the rest are still found as needed.
- A reminder or watcher set by an agent another agent asked now wakes that agent when it goes off, after its answer, and what it then says reaches the agent that asked - which a chat or a task is woken for; a workflow step that has already finished reads it on its next turn in that conversation.
- A conversation's **To-do** and **Notes** panels, below its boxes: the plan its agent keeps with the todo tools, done items ticked off, and what it wrote down with `note_to_self`, both updating as it works.
- How big one value an agent keeps in its session may be, and how many it may keep, are Admin settings: **Session Value Size** (8 MB by default, up to 256 MB, so an exported project can be handed to an upload tool) and **Session Store Keys** (200 by default).
- A chat links to the session it is answered in, from its header, from the moment its first turn starts; the session records which agent setup each turn ran under, as a workflow step's and a task's always did.
- A picture pasted into the chat composer is attached as an uploaded one is, and a message may be sent with only an attachment.
- What an agent writes beside its tool calls - how it reports progress - appears in the chat as it is written, and every agent is told to say what it is about to do and post a line every few calls.
- A long block in a session or a tool call folds from an icon at its top right, which says its size in characters and tokens; the session page's side panels scroll instead of growing past the page.

### 🔧 Changed
- A chat's files are shown under the message they came with - yours, or a picture the agent drew - instead of in a strip above the composer.
- A question waiting for your answer (an agent asking to expose a port) sits at the bottom of the chat; once answered it moves to where it was asked, between the calls around it.
- A message written while the agent works says Queued until the agent reads it, then Read.
- A chat in a workspace with no companion model is named by its own model rather than staying "New chat".
- A model's thinking is drawn in red, apart from its answer, and a thought longer than a minute says how long in minutes.
- Chat rows in the sidebar drop their icon; a chat nothing was said in sorts by when it was made.
- A box session an agent opens now stays up through two hours with no command, not the box's own fifteen minutes - an agent waiting for you to try what it served no longer comes back to a stopped session. Box Session Idle Timeout in Admin Settings changes it; an agent can still ask for its own.
- How often the "Writing…" line is updated and how long it is believed without a new report are Admin settings, Writing Progress Interval (1 s) and Writing Progress Forgotten After (30 s).
- Agents on Ollama and OpenAI-type providers - which includes llama.cpp and other OpenAI-compatible servers - are now handed every tool from their first round, past their own **Tools carried at once** limit and up to the provider's per-request limit; to keep the old behaviour, switch **All tools up front** off on the provider.
- Shells are removed: agents can no longer open a shell on a machine, and the Admin → Shell pages are gone. Existing shell definitions stay in the database untouched, but nothing uses them any more.
- A conversation's next turn now sends the model exactly what the last turn sent - every tool call, its reasoning and its result - and adds to it, so a provider with a prompt cache (llama.cpp, and hosted ones that bill cached input less) reads only the new message instead of the whole session again. What was sent is kept, encrypted, per session; once it outgrows half the model's context window it is started again from what was said, in one step. Notes, to-dos, a task's turn count and a memory count no longer sit in the system prompt, where they changed it every turn. Nothing to do on upgrade: existing sessions switch over on their next turn.
- An empty answer from the model is asked again up to 5 times by default rather than twice, and both that and how often an unusable reply goes back to the model are Admin settings, **Empty Answer Retries** and **Unusable Reply Retries**. Each retry now says which attempt it is and how the provider stopped.
- A call to a model may take up to 60 minutes by default, where it was cut at a fixed 10 minutes - a slow local model thinking at length was stopped mid-thought and reported as unreachable. It is an Admin setting, **Model Answer Time Limit**.
- A compacted turn's summary may run to 5,000 tokens by default rather than 500, so less of what the turn did is lost. An installation or workspace that saved its own number keeps it.
- A model provider no longer gets the model's earlier thinking echoed back in later requests, for every existing provider too: a new **Send thinking back** switch on the provider's page, off by default, turns it back on; after upgrading, turn it on for any provider that relied on it, such as a reasoning model that loses its plan without it, or Azure's Responses API. The thinking is still captured and shown as before.

### 🐛 Fixed

- On Postgres, every chat nothing had been said in sorted above the one being talked in, and reopening Chat opened one of those.
- A box label granted to an agent while its turn was running reached the watcher but not the box tools, which refused the box the watcher had just found.
- The lines an agent wrote between tool calls showed no time after a reload.
- A finished command's long output was read to the limit at once and could not be paged by scrolling.
- An agent repeating a cycle of different calls, rather than the same call twice in a row, was no longer stopped by the repetition guard.
- A tool whose code could not be loaded ended the agent's turn; it now answers as a failed call.
- A new secret could not be typed into until its value was revealed; an open to-do's checkbox was barely visible; the chat's Upload files menu wrapped over the input.
- Editing a box file with Windows line endings failed to match text the agent had just read.
- A message written while the agent works is drawn where the agent read it, after the calls it made before reading it - and on a chat reopened mid-turn it is no longer drawn above the whole turn.
- The memory tools name the agent's catalogs. An agent holding more than one was told the catalog was optional, left it out, and was refused - frontier models included.
- On SQLite, naming a chat no longer holds the database's one writer while the model answers - a slow model used to stall every other write, and on a dropped connection left later writes failing to commit.
- A chat's thinking is now in its session's transcript, as a task's and a workflow step's always was.
- When a turn outgrows the model's window and is summarised, the session's note of it carries the summary the model was given.
- Two Orknux servers on one machine no longer delete each other's local box sessions when one starts: each cleans up only the workspaces it made, and a session whose workspace was removed says so instead of calling every path outside it.
- A conversation that filled a llama.cpp model's context window failed with "no message" after empty retries, because llama.cpp stops for length rather than refusing; it is now summarised to fit and carries on.
- Deleting a directory in a local box while a process still held files in it - a running jar in `target/` on Windows - reported success and left it there; it now says what was left and why.
- A chat's agent now has the same session tools as a workflow step or a task - notes to self, the to-do list and the clock - where it had only scratchpads, timers and watchers.
- A chat whose answer took longer than five and a half minutes stopped updating and showed the answer only after a reload, because the server closed its stream at the async request timeout; a chat's stream now lasts as long as its turn, and a page whose stream ends early picks the turn up again.

## 0.9.9.32

### ✨ Added

- How many notes to self one conversation keeps is an Admin setting, **Notes Per Conversation**, a hundred by default where it was a fixed twenty.

### 🔧 Changed

- A chat's files are drawn under the message they came with - the person's, or the answer of the turn that filed them - rather than in one strip above the composer, and appear there without a reload. A question still waiting for an answer is the last thing in the chat; once answered it sits between the calls it was asked between.
- An agent setting a watcher must say how often it looks at the result itself (`agent_check_interval_seconds`), and can no longer switch that off with 0; a condition guessed wrong no longer runs silently to its timeout. Watchers already running are left as they are.

## 0.9.9.30

### 🔧 Changed

- How often a watcher's agent looks at the result itself has a column of its own on the Watchers page, a dash where it never does.

## 0.9.9.29

### ✨ Added

- How long a note an agent writes to itself may be is an Admin setting, **Note Length**, a thousand characters by default where it was a fixed five hundred; the tool tells the agent the limit before it writes.

### 🔧 Changed

- A fresh installation gives an agent 100 tool rounds rather than 8, and Tool Rounds - in Admin and on an agent - goes up to 10000 rather than 100. An installation that saved its own number in Admin keeps it; one relying on the default gets 100 unless `ORKNUX_CHAT_MAX_ROUNDS` says otherwise.
- A watcher's description has a column of its own on the Watchers page.

### 🐛 Fixed

- A watcher set by an agent in a workflow run wakes that run's agent and reaches the run's next node - the Slack thread - even after somebody opened the conversation with **Continue in chat**; the chat took those over, so the agent's news answered into it and the thread never heard. Only a chat with no run behind it is woken as a chat.

## 0.9.9.26

### 🔧 Changed

- An agent woken by its watcher is told, last, that its answer goes to the person: news in plain words, its own bookkeeping kept in a note, and `finish_answer` when nothing has changed - so a thread no longer gets "still in_progress, watcher #66 active" on every look.

## 0.9.9.25

### 🔧 Changed

- An agent woken by its watcher after its workflow step has finished now has its answer delivered the way the step's answer was: the run carries on past the agent with the new answer and the nodes after it run again, so a Slack reply reaches the thread. The agent can end with `finish_answer` and no answer to send nothing.

## 0.9.9.24

### ✨ Added

- A watcher can carry a short description, at most 100 characters, which the Watchers page shows above its tool call so whoever is debugging can tell watchers apart.

### 🐛 Fixed

- An agent woken by its watcher after a rate limit or an outage is now asked again under the retry policy of the workflow node that set the watcher, instead of the turn being dropped.
- Switching workspace while on the variables page no longer fails with "No catalog with id"; it opens the other workspace's catalogs.

## 0.9.9.23

### 🔧 Changed

- An agent now checks its skills before starting a piece of work: its briefing tells it to call skill_list once per conversation and load the ones that fit, where it used to say to look only when a request needed one - which agents almost never decided it did.

## 0.9.9.22

### 🐛 Fixed

- An MCP tool parameter that is not text - an array of items, an object of variables - reaches the model as what it is rather than as a string, so the model sends the array and the server stops refusing the call. One a model still sends as the text of an array or object is parsed back before the call.

## 0.9.9.21

### ✨ Added

- A watcher can wake its agent to look at the result itself while the condition has not matched: `agent_check_interval_seconds` on `watcher_set`, and a new `watcher_update` tool to change the condition, arguments, interval or note afterwards. Admin Settings → Watchers sets how often, at most, an agent may be woken this way - five minutes unless `ORKNUX_WATCHER_MIN_AGENT_CHECK_SECONDS` says otherwise. The Watchers page shows it on the row.

### 🔧 Changed

- A workflow agent that sets a watcher no longer holds its step until the watcher ends: the step completes with the agent's answer and the run goes on to the next node. When the watcher fires, times out or is stopped, the agent is woken in the same session for a turn of its own and tells people with its tools.

## 0.9.9.20

### ✨ Added

- An agent can read the documents an MCP server it was granted offers as resources: two more tools appear under the server's name, `list_resources` and `read_resource`, where the server advertises any. A document that is not text comes back as a key, like a drawn picture.
- An MCP server's prompts are skills: granting the server puts them in a `<server>_mcp` catalog, listed and loaded like any other skill, and `skill_load` passes the arguments a prompt takes.
- The MCP endpoint offers the workspace's skills to outside clients, as prompts and as resources - so Claude Code and similar tools can use them as slash commands or attach them as documents.

### 🐛 Fixed

- An MCP server whose tools have a parameter with no description no longer loses all of its tools: listing them failed outright.

## 0.9.9.19

What 0.9.9.18 was to be: that tag was never published, its build having stopped on a test the new memory guard refused. Everything below under 0.9.9.18 is in this release.

## 0.9.9.18

### 🔧 Changed

- A plugin bigger than 1 MB of code gets an engine of its own for each call, thrown away after it, instead of the shared engine that keeps every script it parses: a 4 MB bundle held 340 MB that way from its first call. `ORKNUX_PLUGIN_SHARED_ENGINE_MAX_KB` sets the size.
- The Temporal worker keeps 50 runs in memory between steps instead of Temporal's default of 600, and holds up to 100 workflow threads instead of 600. A cached run keeps its input, every step's output and a thread of its own, and one that parks stays cached for hours - so an idle server filled its heap. A run that is not cached replays from history at its next step, which costs a moment and nothing else. `ORKNUX_TEMPORAL_WORKFLOW_CACHE` and `ORKNUX_TEMPORAL_WORKFLOW_THREADS` set them.

### 🐛 Fixed

- An agent's settings page could take most of a minute to open or save. An agent was read in one query that joined all ten of its lists, so one with a few names in each came back as every combination of them; "where is this used" read and parsed every workflow in the workspace; and a save rewrote every list it had not changed. Each list is now read on its own, the dependants come from three queries whatever the number of workflows, the history lists without its snapshots, and the agents and workspaces lists no longer ask once a row.

## 0.9.9.17

### 🐛 Fixed

- Pages listing plugins read every plugin's code; they read it only where it runs. A workspace's or an agent's settings no longer loads a multi-megabyte plugin bundle to draw a list.
- Thirty-six foreign keys had no index on their referencing side, so deleting an agent, a function, a session or an issue scanned whole tables, and lookups like the chats of an agent or the actions calling a function had nothing to use. They are all indexed now.
- A plugin installed from the marketplace and later dropped from it - PlantUML, which moved into the server - could not be unloaded: its only bin was on a listing that no longer exists. Admin → Plugins → Local now lists it with its bin.

## 0.9.9.16

### ✨ Added

- Admin → Settings → Bulkheads: walls that keep one agent turn from taking the server down - how many turns run at once and how long one more waits, a limit on the heap left after a collection beyond which a turn stops with a sentence saying why, and how much one turn may hold of tool results. Each can be switched off, which restores exactly what happened before.
- `ORKNUX_DB_POOL_SIZE`, `ORKNUX_DB_POOL_WAIT_MS` and `ORKNUX_DB_POOL_LEAK_MS` size the database connection pool, and can log whoever holds a connection too long.
- A GraphQL request slower than `ORKNUX_GRAPHQL_LOG_SLOW_MS` (3 s) is logged by its name, time and size, never its content - so a page that takes the server down says which of its requests did it. `ORKNUX_GRAPHQL_LOG_LARGE_KB` does the same for size, off by default.

### 🔧 Changed

- The bulkheads are on by default: four agent turns at once, a turn stopped above 85% of the heap after a collection, 48 MB of tool results per turn. Raise or switch them off in Admin → Settings → Bulkheads if a busy installation needs more.
- The images exit when the JVM runs out of heap, so the container restarts instead of limping on half-broken. `ORKNUX_EXIT_ON_OOM=false` keeps the old behaviour, for taking a heap dump.

### 🐛 Fixed

- Resolving an agent's skills - on every briefing and every `skill_load` - read every installed plugin whole, source and icons, to look at the skills they declare. An agent with several skills set to Always did that over and over in one turn. It reads only the declarations now, and only when a plugin has changed.
- An agent's settings page could run the server out of memory on an installation with a large plugin - PlantUML's bundle is about 4 MB. Its tool, action and connection pickers read every plugin whole, source and icons, to look at what each declares; they read only the declarations now.

## 0.9.9.15

### 🐛 Fixed

- Duplicating or importing a workspace did nothing on an installation opened over plain `http://`: the page made its progress key with a browser call that exists only on HTTPS or localhost, failed before the request was sent, and left the copy looking stuck with nothing in the server's log. The server now also logs a copy the moment it is asked for.
- Screens and timers that read a whole growing table to answer something small read only what they need: the audit page's user filter, editing or removing an issue comment, the nightly scratchpad sweep, and offering an agent its plugin tools, which loaded every plugin's bundle. A deleted Slack connection and a model nobody calls any more are no longer remembered in memory for good.

## 0.9.9.14

### 🔧 Changed

- `agent_asks` counts an ask as still working only while its transcript has an open line. A finished ask no longer shows as working for a while after it answered.

### 🐛 Fixed

- Every request an agent's HTTP tools, a web search or a function's `orknux.http` made built an HTTP client of its own, each keeping a thread and native buffers until a full collection - a long run of requests held hundreds. They share one client now. The Slack SDK's own record of every call it makes, kept for rate-limit numbers nothing reads, is switched off too.
- Two providers at the same address - two workspaces on api.openai.com, say - shared one client, so the second was called with the first one's key, and a rotated key was not picked up until a restart. Each provider has its own client now, rebuilt when its key changes.
- More memory a long-running server kept for good: every agent's answer to `ask_agent`, a lock per conversation, a seven-day timer each time a task parked, a Slack client each time a Socket Mode connection reopened, and model clients replaced without being closed.

## 0.9.9.13

Tagged and never published: its build stopped on a test that expected a call the release had removed. Everything it held is in 0.9.9.14.

## 0.9.9.12

### ✨ Added

- Watchers: an agent can ask the server to call one of its own tools on an interval and wake it, in the same conversation, when the result matches a JSONPath or a regular expression - with what matched and the whole result in the message. New built-ins `watcher_set`, `watcher_list` (the agent's own) and `watcher_finish`, on for every agent. `watcher_set` takes a required `tool_result_path` naming which part of the result the condition is held against (`$` for all of it, `$.body` for an `http_get` body), and a condition already true when it is set sets nothing and shows the agent what matched. A watcher fires once; its agent is told when it times out, is stopped or loses its tool. Watchers live in the database and survive a restart.
- AI -> Watchers lists a workspace's watchers - session, tool call, condition and the part of the result it watches, interval, timeout, when it last looked (with what the tool returned, on hover) and when it looks next - with Stop, and the ended ones under Finished.
- Admin -> Settings -> Watchers: the longest a watcher may run (a week), the shortest interval (15 seconds) and how many one agent may have running (10; 0 switches watchers off), defaulting from `ORKNUX_WATCHER_MAX_SECONDS`, `ORKNUX_WATCHER_MIN_INTERVAL_SECONDS` and `ORKNUX_WATCHER_MAX_PER_AGENT`.
- Preferences -> Access Tokens: anybody with an account on this installation makes, sees and revokes their own `orkx_` tokens. Directory and single sign-on accounts get no section. Making and revoking a token is written to the admin audit log.
- Several server replicas can share one Postgres: a lease (`ORKNUX_CLUSTER_LEASE_SECONDS`, 30, with an Admin Settings field) decides which one runs the sweeps, the model, connection and MCP checks and the Slack sockets, and another takes over within one lease when it dies. The Doctor says which holds it. Attachments still need one shared volume and the load balancer cookie affinity; `docs/horizontal-scaling.md` lists what is covered.
- Admin -> Settings -> Server updates: how many broken attempts a jar download gets (5) and the wait before it resumes (2 seconds, doubling to 60).
- Admin -> Settings -> Agents: how long to wait out a rate limit that arrives inside a streaming answer and names no time (5 seconds, doubled on the next attempt, 1 to 60).

### 🔧 Changed

- The Watchers skill is Always for every agent, new and existing, except one that hid it: each conversation starts with the agent loading it, one extra tool call.
- Agents are told to wait for something a tool can see with a watcher rather than by ending the turn with `finish_answer` and `wake_after_ms`: the briefing, `finish_answer`, the woken note and `timer_set` name `watcher_set` first. A workflow agent step that finishes with a watcher running parks and is woken when it fires.
- Update and Fetch on Admin -> Updates answer at once and the jar downloads in the background, with a progress bar, each step and the reason if one fails; a broken download resumes where it stopped. `installServerRelease` and `installServerReleaseFromUrl` now answer a `ServerReleaseDownload`: a script calling them must poll `serverReleaseDownload` until `DONE` or `FAILED`. The download time limit now means how long a connection may go without a byte (60 seconds); a changed value is kept with the new meaning.
- On the inline engine (`ORKNUX_TEMPORAL_ENABLED=false`, as in `orknux-one`) a second server started on the same database refuses to start, and a server restarted after a crash waits up to forty seconds for the old lease to run out.
- A new agent starts with every enabled plugin's skill catalog granted. Existing agents, and agents when a plugin is installed later, are left alone: grant those catalogs by hand where they belong.
- Admin -> Updates: every offered release's changes start closed; one opened stays open.
- Clicking a label on the issues page shows exactly the issues carrying it, no longer those that mention it; `workspaceIssues` takes a `labels` argument.
- A new token's secret is shown with a copy button instead of a Done link.
- An agent's Tools count no longer counts `http_allowList` beside the HTTP tool that brought it.

### 🐛 Fixed

- A chat page now draws an answer the server starts by itself - a watcher firing, an agent it asked answering, a reminder coming due - as it is written; it used to appear only after a reload. A chat left while the agent was thinking and opened again picks the answer up live too, and Stop still stops it.
- Copy buttons copied nothing over plain http at a LAN address; they fall back to the older copy command.
- Importing components: leaving out a tool no longer leaves out every agent holding it, and an action calling a plugin's function is no longer refused as missing where the plugin is installed.
- A model's usage was lost or under-counted when two answers landed at the same moment (on Postgres, with `HHH000099` in the log).
- An update through a proxy that cuts long requests failed with a bare "Failed to fetch"; the interface now says the server could not be reached.
- The install key sent with an official jar download followed redirects to other hosts, and the download had no time limit.
- A Slack connection seen from a replica without the lease says another server listens to it, instead of "not connected yet".

## 0.9.9.11

### ✨ Added

- Admin -> Settings -> HTTP tools: switch the agents' `http_get`, `http_request` and `http_download` off (grants are kept and come back as they were), or limit them to an allow list of URL patterns and methods, with a tester that answers from the same matcher the tools use. Agents holding one of them also get `http_allowList`, which says what the policy allows. Functions, JavaScript tools and plugins calling `orknux.http` are not affected; on an existing installation the tools stay on and allow any URL until somebody changes it.
- `orknux.connections.query({ type, name })` lets a function, JavaScript tool or plugin list its workspace's connections - id, name, type, address without its query string, auth kind, status and header names, never a credential. A plugin needs the new `CONNECTIONS_QUERY` grant, accepted by an administrator.
- A model's page links to its provider, beside the Provider field.

### 🔧 Changed

- An agent calling a plugin function or tool may pass only a connection it has been granted, by id or name; the tool's description and any refusal list only those of the kind, and an agent holding none is told to grant one in its Connections setting. Any connection of the right kind in the workspace used to do. Workflow nodes are unchanged. On upgrade, grant each agent that uses the Prometheus or Jenkins plugins the connections it should reach.
- `find_connections` is offered to every agent that has not switched it off on its Tools list, whatever the number of connections it holds; it used to appear only past six. An agent holding none is told so, and where connections are granted, instead of not having the tool. Every existing agent now holds it as Always, except one under a tool ceiling in a workspace that allows demoting built-ins. The briefing still lists a short grant up front.
- The bootstrap administrator from `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` is created before the server opens its port, so a script that signs in the moment a new container answers is no longer refused for the first half second.

### 🐛 Fixed

- An Azure rate limit that arrives inside a streaming answer ("200: Your requests to ... have exceeded token rate limit") failed the agent step on the spot. It is a rate limit: waited out - for the time the provider names, or a short doubling backoff when it names none - and the call is asked again.
- A run whose agent step was waiting on its model when the server died now carries on and answers the message, without Rerun. On Temporal the step heartbeats, so a dead server's step is retried on a live one within `ORKNUX_TEMPORAL_STEP_HEARTBEAT_SECONDS` (default 30) rather than after the whole five-minute step timeout; on the inline engine (`orknux-one`) the step is asked again instead of failing as interrupted, up to `ORKNUX_INLINE_RESTART_ATTEMPTS` goes (default 3). Both are on Admin -> Settings under Workflow runs, as Step heartbeat and Agent step goes after a restart; the variables are where a fresh installation starts, and a run already going takes a heartbeat change up from its next step. Other kinds of step a restart cut short still fail as interrupted, and the retried agent's question is no longer written into its session twice.
- A plugin update whose download did not match what the marketplace published showed only INTERNAL_ERROR and an id; it now says what happened and to try again in a few minutes.
- A URL typed into a field is drawn as typed: the monospace font joined `://` into one glyph, so `http://` read as `http: /`.

## 0.9.9.10

### 🔧 Changed

- Admin -> Updates shows only the newest offered release's changes open; the others fold to their version line, and clicking a version opens or closes its changes.

### 🐛 Fixed

- A release stored on Admin -> Updates could not be started on Kubernetes: with the manifest's `emptyDir` and `fsGroup` the launcher was refused (`/tmp/orknux-release: Operation not permitted`) and marked the release failed. The launcher runs from the image, so the fix arrives with the new image; activate the release again after upgrading.
- An agent calling a plugin function or tool may pass only a connection it has been granted, by id or name; the tool's description and any refusal list only those of the kind, and an agent holding none is told to grant one in its Connections setting. Any connection of the right kind in the workspace used to do. Workflow nodes are unchanged. On upgrade, grant each agent that uses the Prometheus or Jenkins plugins the connections it should reach.
- `find_connections` is offered to every agent that has not switched it off on its Tools list, whatever the number of connections it holds; it used to appear only past six. An agent holding none is told so, and where connections are granted, instead of not having the tool. Every existing agent now holds it as Always, except one under a tool ceiling in a workspace that allows demoting built-ins. The briefing still lists a short grant up front.

## 0.9.9.9

### ✨ Added

- `ORKNUX_RELEASE_PIN` names the server version an installation runs, over whatever Admin -> Updates chose: a kept release that is signed and not below the schema floor, or the image's own version. A pin that cannot be honoured runs the image's own jar and says why, in the log and on Admin -> Updates; while it is set, the page cannot choose.
- `ORKNUX_HEAP_PERCENT`, `ORKNUX_NATIVE_MEMORY_MB`, `ORKNUX_MALLOC_ARENAS` and `ORKNUX_NIO_BUFFER_CACHE_KB` set the server's memory budget inside its container; see Changed for what the defaults do.
- `scripts/load/teams.py` and `scripts/load/watch.sh` are a load test of three teams' traffic against a container on one core and 2 GB, recording its memory and every restart and why.

### 🔧 Changed

- The server's heap is now the smaller of 75% of the container's memory limit and the limit less 1 GB, so a 2 GB pod runs a 1 GB heap instead of 1.5 GB. Under load the JVM needs about 650 MB beside its heap, so the 1.5 GB heap grew into memory the pod did not have and the kernel killed it at the limit (exit 137): the restarts production saw. glibc's malloc arenas are capped at 2, where it used to keep eight per host core and the memory they had freed, and the JDK keeps no I/O buffer over 256 KB per thread. An installation that sets `-Xmx` or `MaxRAMPercentage` in `JAVA_OPTS` keeps its own heap; the image's default `JAVA_OPTS` is now empty. Nothing to do on upgrade unless you had raised the memory limit to work around restarts, which is no longer needed for 2 GB.
- A container given less than about 1.3 GB now runs a 256 MB heap and says so in its log at every start, which is likely too small: give it at least 2 GB, or lower `ORKNUX_NATIVE_MEMORY_MB`.
- A run's page stops refreshing once the run has ended, and no page refreshes on its timer while its tab is hidden; it catches up when shown again. Every open tab used to ask once a second for as long as it stayed open.
- A stored release that stops starting ends on the image's own jar instead of in a crash loop, however it was chosen. The start loop now runs the launcher again when a stored release exits rather than leaving it to Docker or Kubernetes, and the release is marked failed, with the reason on Admin -> Updates, after the allowed boot attempts. A release that has run before counts only the starts that failed, so replicas restarting together never fail a healthy one.
- The plugin catalog lists Marketplace first and opens on it; Local, for a file of your own, is the second shelf. Opening the Catalog tab now asks the marketplace straight away.

### 🐛 Fixed

- A plugin function or tool that takes a connection as an argument is handed the connection itself - its address, auth kind and headers for a host of the plugin's own kind, as a connection setting is - instead of the id the agent or the workflow node wrote. A model may pass the connection's id or its name; one from another workspace, or of a kind the plugin does not use, is refused with the workspace's connections that would do, and the tool's description lists them.

## 0.9.9.8

### ✨ Added

- A kept server release can be removed from Admin -> Updates, its jar with it; the release running, the one chosen and the one it falls back to cannot.
- Every node in the workflow editor and on a run's page shows an icon: the one it was given, or else its kind's own (the picture the Add menu uses, and a speaker for text to speech). The node's Icon field shows that default greyed as Default.
- An image or decision node's model opens in the drawer beside the graph, the way an agent node opens its agent: the model page's own settings, saved in place, following the node's model picker. A ctrl-click still opens the model's page.
- An image or decision node has New beside its model, as an agent node has beside its agent: the drawer makes a model of the kind the node needs on one of the workspace's providers, the node points at it, and the drawer stays open on its settings.
- A run's page and a session's log refresh every second until somebody chooses another interval; lists still start at Off.
- Admin Settings has a Workflow runs heading with Steps running at once (default 4, 1 to 32), the most steps of one run that work at the same time.
- An agent's MCP server can be left out of an import, like one of its tools, and the agent arrives without it; a server the file names and this workspace lacks no longer blocks the import.
- Admin Settings has a Workspace copies heading with how long a copy may wait for a lock (default 60 seconds). A copy that waits longer stops and says which step it stopped at, rather than waiting for ever.
- The field picker for references and variables works from the keyboard: the arrow keys, Home and End move through the list, Enter picks and Escape closes.
- Admin Settings has a Logging heading that changes the server's log levels without a restart: the root level and any logger by name, applied on every server within 30 seconds, kept across restarts, audited, and reset to the configuration's in one press. A root set below INFO goes back on its own after 60 minutes, so a forgotten DEBUG does not fill a disk.
- Admin -> Workspaces exports a workspace to one file from its row, and Import beside Create Workspace brings such a file in as a new workspace, here or on another installation: its settings, connections, MCP servers, model providers, models and every component, without credentials or variable values, which the result lists for setting. Duplicate now also carries the workspace's timeouts, repeated-call limits and voice settings.
- A run's page folds its summary, graph and log, and a step's input and output, by a chevron at each heading; what is folded stays folded on every run's page.
- Admin -> Updates updates the server in place: Update on a release the official server lists, with its changelog, or upload a jar. Every jar must be signed with the Orknux release key the image carries and is checked again on every start, and one older than 0.9.9.8 or without the update launcher is refused from every source, since the server could not update back from it; the database keeps the last few (Admin Settings, default 3) and any of them can be rolled back to, as can the image's own jar, unless the schema has moved past what it can run on. Every server restarts on the chosen release, a release that will not start is given up on after three starts, and each update and rollback is in the audit log.
- Admin -> Updates also fetches a release from a URL, such as a company Artifactory the platform team fills: a jar's address, or a directory whose `releases.json` lists newer versions to fetch, prefilled from `ORKNUX_RELEASE_SOURCE_URL`. An optional token or `user:password` goes to that host only and is stored nowhere; the download goes through the proxy rules, is bounded by the jar size limit and Admin Settings' download time, and is verified exactly as an upload. Each source has its own switch under `ORKNUX_SELF_UPDATE`, all on by default: `ORKNUX_SELF_UPDATE_OFFICIAL`, `ORKNUX_SELF_UPDATE_UPLOAD` and `ORKNUX_SELF_UPDATE_URL`.
- A Slack connection's page shows its socket on one line - connected since when and when the last event arrived, or why it would not open - with a Reconnect button that closes the socket and opens it again on every server, and is recorded in the audit log. When another server is listening with the same Slack app the page says so ("Slack splits this app's events between N connections"), since Slack then sends that server a share of the events, and the log warns once.

### 🔧 Changed

- In-place updates are **on by default**: an administrator can run a signed release other than the image's own, without a change to the image tag. Where every version that runs must be approved, set `ORKNUX_SELF_UPDATE=false` before upgrading and the image's jar always runs. Both images now start through a small loop that chooses the jar, so PID 1 is a shell that hands `docker stop` on to the JVM; on a read-only root filesystem, mount an `emptyDir` or tmpfs at `/tmp/orknux-release` (`ORKNUX_RELEASE_DIR`).
- A release jar is about 350 MB. A reverse proxy or Ingress in front of the server - the old `orknux-ui` image included - refuses bodies that large by default, so to upload one on Admin -> Updates raise its body limit for `/api/server-releases`, or update from the official server or a URL instead.
- A node that is not a condition or a decision, drawn with lines to several nodes, now runs those paths at the same time rather than one after another. A failure on one path no longer stops the others: they finish, and the run ends FAILED. A node where paths meet still waits for all of them. A graph with no such fan-out runs exactly as before.
- The `orknux/orknux-server` image now serves the interface itself, on its own port 8080, so it is the whole product and `orknux/orknux-ui` is no longer needed; `orknux-one` has no nginx either. An installation still running `orknux-ui` keeps working, since the server answers every path it forwarded, but to move off it point the browser, published port, reverse proxy or Ingress at `orknux-server` on 8080 and remove the `orknux-ui` container (and `ORKNUX_UI_TAG`) - `deploy/README.md` and `deploy/kubernetes/README.md` have the steps.

### 🐛 Fixed

- A zoomed picture's close button sits just outside the picture's top right corner, not in the window's corner, which on a wide screen was far from it.
- Talking over a spoken answer in voice mode stops it again. The hold restarted at every gap between syllables, so ordinary speech never held long enough; a short noise such as a cough still does not interrupt.
- A Slack socket that died without the client noticing no longer stays "open" and deaf until a restart: one that has heard nothing for ten minutes (`ORKNUX_SLACK_QUIET_PERIOD`) and does not answer a ping is reopened by itself, with a log line saying why.
- Duplicating a workspace shows its progress from the first connection on, says when progress cannot be read instead of sitting on the first line, and logs every step, so a copy that stops can be found from the log.
- A model that cannot be reached says where it was called and why, such as "Could not reach http://localhost:11434/v1: connection refused", instead of "Request failed". This applies to agent steps, chats, provider checks, speech, transcription, pictures and decisions. A provider check no longer reports "No model list" for a server that is not there.
- A run started by a Slack mention, message or reply says so on the executions list and the run page ("Slack mention") instead of "Webhook", runs from before the upgrade included. The label comes from the trigger that started the run, so a run whose trigger has been removed still reads Webhook; what is stored is unchanged, so a rollback still reads every run.

## 0.9.9.7

### 🐛 Fixed

- A workflow agent on an Azure OpenAI provider retries again when Azure fails an answer it had already started, with a server error or a rate limit, up to the node's retry attempts. Since 0.9.9.5 these failures ended the step on the first try. A failure about the request itself is still not retried.

## 0.9.9.6

### ✨ Added

- A plugin that declares its own kind of connection is handed that connection's address, credential and headers, so it can call the service itself over `orknux.http`. Only for a connection in the workspace the plugin runs for; Slack and mail connections stay handles, because the server still speaks those on the plugin's behalf.

## 0.9.9.5

### ✨ Added

- An Azure OpenAI provider's page has an API select: Responses, or Chat completions (legacy). Every chat through the provider is sent through the one it names.

### 🔧 Changed

- Azure OpenAI providers send their chats through the Responses API, and every existing one is moved onto it on upgrade. A model with a reasoning effort shows its thinking there, from the summary the model writes. To go back, open the provider under Models and set API to Chat completions (legacy); nothing else needs changing.
- A model page's optional choices - Tool Calls Per Reply and Reasoning effort - name their empty state the same way, Unset: nothing set, nothing sent.

### 🐛 Fixed

- An Azure OpenAI model with a reasoning effort and tools failed on chat completions ("Function tools with reasoning_effort are not supported"); it is asked through the Responses API, and a tool loop carries the model's reasoning from one round to the next.

## 0.9.9.4

### ✨ Added

- Each `orknux_*` tool can be switched off on its own while an agent has Orknux access, so an agent can read runs without being able to start one. A hidden one is neither offered nor run, in chats, workflows and tasks; turning the grant off still takes them all.

- An Azure OpenAI chat model's page offers a Reasoning effort - default, minimal, low, medium or high - sent as `reasoning_effort` to its o-series or GPT-5 deployment; default sends nothing.
- A task's page lists the scratchpads it wrote into, opens one read-only, and keeps up as the task writes more; changing one is a link away, on its session.
- A Decision node for workflows: it asks typed questions - a choice, a score, a yes or no - about what the run carries, hands on the answers with their probabilities, and can send the run down one line per option of a choice, or down Yes or No on a yes-or-no question, with an Unsure line for an answer under the node's threshold. It runs on a decision model - Jev, TypeSafe's hosted one, or a Laya on your own hardware, added under Models as a Decision model provider whose key is optional - or on any chat model, which answers in the same shape with its own estimate of the probabilities.
- The manual shows drawing: pictures drawn in a chat and opened in the viewer, a task that drew two, one that laid its pictures out as a saved PDF, an image node and an agent that drew in a run, and the Artifacts page holding them all - photographed from a demonstration that really draws, as the screenshot installation now runs a stand-in drawing model.

### 🔧 Changed

- A chat model's page shows only the sampling settings its provider reads: Azure OpenAI and OpenAI's own API temperature and top-p, Ollama the same, Anthropic those and top-k, and a llama.cpp-style server under the OpenAI type all five. A setting the provider does not read is refused on save and no longer sent; a value already stored stays in the database until the model is next saved from its page, which clears it; an API client sending one is now refused rather than ignored.
- `finish_answer` is marked Always on every agent while the workspace keeps its built-ins fixed, and stays so whatever a save sends; only the unsafe built-in switch lets an agent hold it at Offer. Existing agents are marked on upgrade. An agent under a tool limit held it at Offer, so the one tool that brings it back was not in front of it.

- Where an agent node may wait, `finish_answer` requires `wake_after_ms`: `-1` finishes for good, a number waits. Left out, the model is asked to choose and its turn goes on; an agent had promised build checks every 10 minutes and ended with the wake-up simply left out.

### 🐛 Fixed

- Duplicating a workspace that already has a copy works: the copy takes the first free of "<name> copy", "<name> copy 2" and so on, and a copy of a copy counts on from the original. It used to be refused because "<name> copy" was taken. `duplicateWorkspace` takes its name as optional to match.

- An agent woken from a wait is told that nothing ran while it was stopped and that a repeating job goes round again now, and a wait's note is described as a note to its future self; one read its own "I have started pinging him" as work done.

- On SQLite, and so in `orknux-one`, the workspace copy's progress bar now moves: a page waiting on a long run could not get a word in until it was over, because SQLite lets whoever asks at the right moment have the database rather than whoever asked first, and the server now queues for it in order.

- An agent that asked another and ended its turn no longer sits out its whole wait when the answer lands in the moment before it parks; it is woken and reads the answer at once.

## 0.9.9.3

### ✨ Added

- On an agent's page, a tool or skill's name links to its page where it has one (a built-in does not), and hovering the row shows what it does - built-in tools included, which now say the first sentence of what the model is told.
- In the chat box, a word starting with the workspace's command marker offers the skills the chat's agent can reach, and Tab or Enter completes it in place.

- A model's provider is a select on its page, so a model can move to another of the workspace's providers and keep its id, settings and every agent pointed at it.
- A model provider can be duplicated from its row, with a copy of each of its models; its own key stays behind, and a workspace secret it reads is kept.
- A task always has `save_artifact`, whatever the agent was granted, and is told to save a file it makes there and link it in its summary; `save_artifact` takes a `contentKey`, so a PDF another tool made is saved as it is rather than typed back.

- ⏰ **An agent can set itself a reminder.** `timer_set` returns at once and the
  agent carries on; when the time is up it is told, with the note it left -
  between two steps if it is still working, or by being woken if it finished.
- 📬 **An answer an agent asked for reaches it, whether or not it waited.** An
  agent that asked another and ended its turn is woken when the answer lands: a
  workflow step waits instead of finishing, a chat answers again on its own, and
  a finished task carries on with what it had left.
- 📊 **Duplicating a workspace shows its progress** - the kind being copied,
  how many of how many, and a bar for the whole copy - so a long copy reads as
  working rather than stuck.
- 🪞 **Duplicate one thing from its list**: agents, models, actions, triggers,
  conditions, objects, skills, tools and memories each have a Duplicate button.
  The copy arrives under the next free name and points at the same functions,
  models and tools as the original.
- 🔇 **Every voice skill can be switched off**: `!caveman=off`, and the same for
  Angryman, Niceman, Jokeman, Crazyman and Rimeman.

### 🐛 Fixed

- A block of commands in a chat answer is highlighted: the command names and their flags are coloured, not only keywords and comments, and `shell`, `powershell` and `cmd` blocks are coloured too.

- A link to a saved artifact in a task, a chat or an answer shows a miniature under it: a PDF's first page, a picture itself, and a tile for anything else or a file that will not open.

- A task whose turn uses all its tool rounds takes its next turn instead of stopping as "could not answer"; the task's own turn limit is what ends one that never finishes.

- An agent asked by another now has its thinking written into its session, as a task's and a workflow node's always were.

- 🧩 **Every agent reaches the built-in skills, whatever catalogs it was granted.**
  `skill_list`, `skill_load` and `skill_search` followed the catalog ticks, so an
  agent with none could not load even `!caveman`. They are built-ins like the
  rest now, on unless hidden, and the built-in catalog is always held.
- 🤝 **Asking other agents keeps better books.** `agent_wait` takes its seconds
  written as `"300"` as well as `300` (it waited 30 instead), and `agent_asks`
  names the agent it asked rather than "an agent" and, after a restart, reports
  a failed ask as failed instead of showing its half-finished first line.
- ↔️ **A trigger's row actions are reachable at a laptop's width.** The table's
  columns added up to more than the card, so Export was drawn past its edge.
- ☑️ **"Allow unsafe built-in tool visibility" opens as it was saved.** It was
  stored all along; the settings page read every setting back but that one.

## 0.9.9.2

### 🔧 Changed

- 🕰️ **Every agent is told how its turn works.** The briefing now says, first,
  that an agent acts only during a turn and stops completely when it ends -
  so it does not promise to "check back later" with nothing to bring it back.
  `ask_agent` no longer claims to answer with what the other agent said: it
  starts the agent, and the answer has to be waited for.

### 🐛 Fixed

- 🗃️ **An upgrade to 0.9.9 no longer stops at V313** where the date plugin had
  a function returning an object. The migration left the type as it was, the
  database refused the row and the server did not start. Nothing to do: the
  rows are retyped just before V313 runs, and installations already past it are
  untouched. If you ran the manual fix, that is fine too.

## 0.9.9.1

### 🐛 Fixed

- ☁️ **A streamed answer from Azure OpenAI no longer fails with "`delta` is not
  set".** Azure's content filter can send a piece of the stream that carries only
  its verdict, and reading one failed the whole turn. It is passed over now.
  Nothing to change on the Azure side.

## 0.9.9

### ✨ Added

- 🤝 **An agent can ask another agent.** Its page lists which agents it may
  ask, and `ask_agent` puts a question to one of them in full and answers with
  what it said. The asked agent gets none of its own delegates, so the depth is
  one: a specialist answers, it does not run a project. Every ask is a
  conversation of its own, written under the session that asked and titled
  with the task, and the session page has a Sessions button at the top right
  that lists the main session and each subagent session with a green dot while
  an agent is at work in it - click one to read it. How many an agent may ask
  in one conversation is a setting: Admin → Settings carries the installation's
  number, ten to start (`ORKNUX_CHAT_MAX_SUBAGENTS`), and a workspace may set
  its own; zero takes the tool off the table.
- ⏰ **An agent can end its turn by waiting.** `finish_answer` takes a wake-up
  in seconds; the step parks and the run comes back to it when the time is up.
  Two bounds under Admin → Settings: the longest one wait may be, and how many
  in a row on one step - because the dangerous wait is not the first but the
  twentieth. Zero takes the wake-up off the tool.
- 🔎 **An agent finds the tools it needs rather than carrying all of them.**
  `find_tools` searches what the agent was granted and loads what it needs for
  the session, and a budget on the agent's page says how many it may hold at
  once. A hundred granted tools no longer cost every round a hundred
  descriptions. The same door for connections: an agent granted forty asks
  for the one it means by name or kind.
- 📝 **An agent can leave itself a note part-way through.** `note_to_self`
  writes a line down during a long task, and the session page draws the notes
  above the transcript.
- 🔑 **An agent's answer to `ask_agent` is kept under a key.** A subagent
  that wrote a page answered with the page, and the asker's only handle was
  the text - so it typed ten thousand characters back into `slack_upload`
  and the model's output cap cut them off. The answer is kept in the asker's
  session store now and comes back with a `contentKey` beside the text, to be
  passed to whichever tool takes one, the way a drawn picture is handed on.
  What the subagent's own tools kept - a picture it drew, a file it saved -
  is copied up into the asker's store when its turn ends, so a key the answer
  names works in the conversation that asked; and `save_artifact` answers a
  `contentKey` beside the url, so a saved file can be uploaded by key too.
- 🎯 **An agent node names the skills to load, and a Slack message can name
  them.** Every skill has an **id** now - letters, underscores and hyphens,
  unique in the workspace, derived from the name for the skills you already
  have and edited on the skill's page. An agent node on the graph gets a
  `skillIds` row beside its prompt: written out, or read from another node,
  and the skills those ids name are loaded for the model before it starts,
  whether or not it would have asked. The Slack trigger hands on
  `commands` - every word in the message that starts with the workspace's
  **command marker**, `!` to begin with and a box on Workspace → Settings -
  so `@orknux !review !security PR 12` lands the agent with both skills in
  front of it. The marker has an installation default in Admin -> Settings
  (`ORKNUX_COMMAND_MARKER`) that a workspace overrides. Orknux's own syntax, because Slack intercepts `/` and refuses
  a slash command it does not know. An id written on the graph that names no
  skill is refused at the save; one that arrived with a message is noted in
  the run and costs nothing else. `skill_load` takes an id as well as a name,
  and an agent's briefing lists each skill with its command and tells the
  agent to say so when asked what it can do - and to load the skill itself
  when a message carries the command.
- 🧾 **A variable can be a list, or a type a plugin defines.** Lists of
  strings, numbers or booleans, edited as one. A plugin declares a type with
  its own completion and validation, run in the sandbox: Slack 1.19 defines
  `SlackUser`, so a variable of that type is picked from the workspace's
  people with the same picker the workflow editor has, and a user id that does
  not exist is refused. Which Slack connection to ask is one of the type's own
  parameters.
- 🔀 **A condition can check a value against a list.** A Value condition takes
  no property: the node it sits on picks the value with the ordinary reference
  row, from anything the run carries, and the condition says whether it is in
  the list, equals, contains or matches.
- 🏷️ **A role names the directory groups that grant it, on the screen.** Admin →
  Roles takes the group names as they are, spaces and dots included. The only
  door used to be a mapping in the configuration file, keyed on a name the
  server had already rewritten, which an administrator could neither see nor
  change.
- 🐙 **Signing in with GitHub.** A third door beside the internal accounts and
  the directory.
- 🎙️ **Talking over an answer stops it.** Speaking while the model is speaking
  interrupts it; a workspace sets how long a voice has to carry before it
  counts, beside the other turn-taking bounds.
- 🔊 **A workflow can say something out loud.** A speech node turns a mapping
  into audio with the workspace's text-to-speech model, filed against the run
  the way a picture is.
- ⌨️ **Chat commands.** A line starting with `/` is a command the installation
  defines, listed as you type - what can be typed in a chat instead of said.
- 🔃 **Every table is ordered by whichever column somebody presses.** Twenty-odd
  lists, the workspace's and the administrator's, with the server doing the
  ordering where the list is paged. A sorted heading says the column's name
  and which way it is going.
- 🧭 **"Go to" reaches a part of a page.** The quick-actions box offers a page's
  sections, not only the page.
- 👁️ **A plugin's secret parameter can show what is being typed.** An eye on the
  box, only while something is typed - never over a stored secret, which the
  server never hands back.
- 📅 **A model's usage takes a date range.** Yesterday, last month, any window;
  thirty days was fixed.
- 🧩 **Custom objects in the editor say what their fields hold.** A field a node
  names for itself has a type - string, number, boolean, list, object - and a
  shape's fields are edited in the panel beside its node, like a trigger's.
- 🕰️ **A trigger's own page holds its history.** Every firing, with what it
  carried and what it started.
- 🏷️ **The labels offered first are the ones lately used.**
- 🗄️ **An installation can stop conversations being thrown away.** A switch
  under Admin → Settings: on some installations the session is the only record
  of a decision.
- 🌲 **Groups filed below their base can be found.**
  `ORKNUX_LDAP_GROUP_SEARCH_SUBTREE=true` searches the whole subtree under the
  group base; the default stays one level, which is what it always was.

- 📦 **An import can leave out a tool an agent points at, and name what it
  carries.** The import dialog offers Leave out on a "Not here" tool row - the
  agent arrives without that grant - beside everything the file carries, and
  Rename on every carried row: the name typed is the name the thing lands
  under, and everything in the file that pointed at it follows. A name that is
  taken is refused on the row rather than moved along, because somebody typed
  it. Templates take the same choices.

- 🗒️ **Scratchpads.** An agent keeps working files for the whole session -
  a page, a report, notes - and edits them in place instead of retyping them.
  `scratchpad_search` takes a regular expression, one pad and lines of context.
  Admin → Settings sets how much text and how much in files a session keeps.
- 🎨 **The product draws.** `diagram_render` (mermaid with subgraphs, or
  PlantUML) and `charts_render` (bar, column, line, area, pie, donut, scatter)
  answer a PNG for a chat or an SVG for a page, as a key. `pdf_fromHtml` draws
  diagram and chart blocks into a PDF, reads a linked stylesheet from its
  scratchpad, and `pdf_fromHtmlZip` lays out a report zipped with its pictures.
- 📦 **Archives both ways.** `zip_files` packs pads and keys into one archive;
  `zip_extract` unpacks one into pads.
- 👀 **An agent can look at a picture on demand.** `picture_view` shows the model
  the picture behind a key - an attachment it read, a chart it drew.
- 🧭 **Finding a tool is three tools.** `tool_find` searches by words,
  `tool_describe` shows one in full with its parameters, `tool_load` takes exact
  names; the briefing marks every tool *loaded* or *load it first*.
- 🤝 **Asking another agent does not wait.** `ask_agent` returns at once;
  `agent_wait`, `agent_asks` and `agent_list` - what each agent it may ask can
  do - go with it. A built-in *Delegating* skill says how to write the question.
- 🧠 **Memories can be corrected.** `memory_update` and `memory_delete` beside
  `memory_search` and `memory_save`.
- 📚 **Built-in skills for making things.** *Diagrams and charts*, *Complex HTML*
  and *Making a PDF*, found by `skill_search`, which looks inside skills' pages.
  And voices: *Niceman*, *Jokeman*, *Crazyman* and *Rimeman* beside *Angryman*.
- 🎛️ **A model's page sets tool calls per reply and sampling** - temperature,
  top-p, top-k, min-p, repeat penalty - each sent only when set.
- 📜 **Long sessions are summarised.** Past a size (40,000 tokens by default) the
  older turns become one summary the model reads instead; nothing is deleted.
- 🔎 **An agent can read a conversation's log** with `orknux_session` and find
  one with `orknux_sessions`, over MCP too.
- 🗂️ **Admin → Settings is in sections** - Agents, Tool calls, Tool list,
  Sessions, Scratchpads, Drawing, Commands - each reachable from Quick actions.

### 🔧 Changed

- 🔒 **The directory connection trusts what Admin → Networking trusts.** The
  certificate authorities pasted in there covered every outbound call except
  LDAPS, so a directory signed by an internal authority could not be reached
  at all and the sign-in said "invalid username or password". It is covered
  now. An installation whose directory already worked is unaffected.
- 👤 **A role given to somebody on the Users screen actually grants it.** For a
  directory user the box was saved, drawn under the name, and ignored: access
  was read from the provider's groups alone. It counts now, on top of whatever
  their groups give - which is how the first administrator of a fresh
  installation gets in, and how somebody gets one workspace for a fortnight
  without a group being made for them. A role ticked before this release and
  left there will be held after it; worth a look at Admin → Users first.
- 🔤 **A role name is matched as written and as rewritten.** A role named
  `platform.backend` now matches that directory group, as well as the
  `PLATFORM_BACKEND` the server used to turn it into.
- 🧮 **Asking other agents is bounded.** Ten per conversation where nothing was
  bounded; an agent that has spent them is told to answer with what it has.
  Raise it under Admin → Settings or per workspace.
- 📄 **PDF and charts ship as part of the product, not as plugins.** They
  used to be written into the Plugins page as built-in plugin rows: something
  the release already contained, offered for installing and removing, with an
  Uninstall button that always failed. `pdf_fromHtml`, `pdf_read`,
  `pdf_preview` and `charts_render` are now tools every agent holds by default
  the way it holds the clock, switched on its Tools list like any other
  built-in, and there is nothing on the Plugins page to install or unload. A
  workflow that calls one of them is unaffected: the function keeps its name
  and its id, so existing graphs go on running. There is no `built_in` flag on
  a plugin any more.

- 🧩 **PDF, charts, dates and markdown-to-text are the server's own code.** The
  plugins go; migrations V312–V316 re-point every workflow action that used
  them, so they keep working with nothing to do.
- 🪶 **Skills are no longer written into the system prompt.** An Always skill
  is named and loaded once per conversation, offered skills are not listed, and
  prompts are much shorter. An agent that relied on a page being inlined now
  loads it.
- 🔁 **`find_tools` is `tool_find` and `tool_load`.** Nothing stored names it,
  and the old name is still answered for sessions that remember it.
- 🖼️ **An SVG drawing answers a key, not its markup.** A workflow, which has no
  session, still gets the markup.
- 🧾 **The session store records what each key holds** - its type and whether it
  is bytes - so an upload never guesses. The Slack plugin's upload for agents
  takes only a key from Slack 1.28.0, which needs this release.
- 💾 **A session keeps 10 MB of text in scratchpads by default,** up from 1 MB,
  and files are held to their own budget. An installation that set its own
  number keeps it.
- 🧬 **Duplicating a workspace copies its connections, model providers and MCP
  servers** without their credentials, and lists which need setting. Nothing to
  do on upgrade.
- 🗃️ **55 migrations run on first start** (V272–V327), none of them applied by
  hand.

### 🐛 Fixed

- 🗜️ **`zip_files` takes the list of files however it is written.** A model
  that wrote the list out as a string - which is a shape they land on often -
  was told "files is a list", which is the one thing it believed it had sent,
  and it tried again the same way. The string is read now, and both the tool's
  description and its refusal carry a whole call written out rather than a
  sentence describing the shape.
- 📈 **A mermaid diagram this cannot draw says what can.** `pie` is real
  mermaid and gets written sooner or later; the PDF draws five kinds -
  flowchart/graph, sequenceDiagram, stateDiagram-v2, classDiagram, erDiagram -
  and used to answer by naming the headers that work, which reads as "try
  another spelling". It now says those five are the whole set and points at
  `charts_render` for a pie, donut, bar, column, line or area chart.
- 🚫 **A duplicate variable name says so where you are looking.** The server
  refused it and the page drew the refusal - as one red line at the top of the
  panel, above the catalog's name and off the screen from the add row at the
  foot of any real list, so adding a duplicate looked like a press that did
  nothing. The refused row now says so directly under itself, with what was
  typed still in it.
- 📋 **A refused sign-in says why in the log.** One WARN line naming the
  variables to check - the bind, the search bases, the group filter - where
  there was nothing at all. A directory or a database that stops answering is
  said once when it breaks and once when it mends, not on every probe.
- 🔌 **Copying an agent between workspaces is no longer refused over a plugin's
  tools.** A tool a plugin brings is in the agent's grant list like any other,
  and the importer read it as a workspace tool the target lacked - so an agent
  granted `github_pullRequest` could not be imported anywhere, and the plan
  said to create a tool that exists in every workspace. A plugin's tool is
  neither carried nor missing now: the row says which plugin brings it and
  the grant arrives intact. A plugin's skill catalog is treated the same way,
  instead of a folder of that name being made beside the plugin's own.
- 🔑 **The sign-in page offers a password reset only where the installation
  keeps the passwords.** Under a directory sign-in the Reset link led to a
  page that could do nothing about a directory's password.
- 🧯 **A group search that cannot run answers the sign-in instead of a 500.**
  An absolute DN in the group base, or a filter without `{0}`, used to escape
  as a stack trace; it is a refusal with the reason in the log now.

- 🖼️ **Pictures reach a PDF.** The layout refused every image it was given, so
  no picture was ever drawn into one, and nothing said so.
- 🧬 **Duplicating a workspace keeps what can be copied.** One component that
  could not come threw the whole copy away; now each is copied on its own, in an
  order that retries what depends on something later, and what is left behind
  is counted with a reason.
- 🔁 **A local model no longer loops on tool calls.** Its own reasoning goes back
  to it, repeated calls are answered with a pointer, a call cut off at the output
  limit is sent back whole, and an empty answer is asked again.
- 🗄️ **A fresh SQLite installation starts.** The baseline was missing a table.
- 🔒 **A long chat is compacted on SQLite without stalling.** The summary was
  asked for inside the transaction that records the message, so recording its
  usage waited thirty seconds for the lock and failed.
- 📎 **A screenshot on a Slack message reaches the agent** whichever workspace's
  connection heard the event.
- 🧜 **Mermaid subgraphs, database nodes and quoted labels draw,** and a fence or
  a `mermaid` line on top is taken off.

## 0.9.8.3

### 🐛 Fixed

- 🧠 **An agent that said it would remember something now does.** `memory_save`
  wrote its audit line as the signed-in user and failed where there is none -
  an agent answering a Slack message, running as a workflow node or working a
  task - and the memory was rolled back with it. It worked from a chat, which
  is why it looked intermittent. And a workspace could have nowhere to write at
  all: every workspace now has a memory catalog of its own, seeded for those
  that exist and made with those made since, which can be renamed but not
  deleted. The search that reads them back looks for every word of the
  question on its own and ranks by how many it carries, so "what does OPS stand
  for" finds the line that says.

## 0.9.8.2

### 🐛 Fixed

- 🔒 **A trusted certificate is trusted by everything that goes out, not only by
  MCP.** The list under Admin → Networking was read by exactly one client:
  `McpClient` fetched the context and set it itself, while every other outbound
  call - a plugin's HTTP request, a model, an action, a webhook - was built from
  `ProxyRouter.builder()` and got the JVM's roots alone. So an administrator who
  pasted their internal authority in found their MCP servers reachable and their
  Confluence plugin still failing to build a chain, with nothing on any screen to
  explain the difference. The builder attaches the context now, so everything
  built from it inherits both the proxy rules and the trust - and a plugin's
  client is rebuilt when the list changes, so an authority added while the server
  is running is picked up without a restart. Installations that have added no
  certificate are unaffected: no context is attached where there is nothing to
  attach, which is the path they were already on.

## 0.9.8.1

### 🔧 Changed

- ⏱️ **How many rounds of tool calls an agent gets is a setting.** It was eight,
  written into the code, and an agent holding twenty tools spent three of them
  listing and loading before the work began: what came back was "kept looking
  things up without reaching an answer", with everything it had gathered thrown
  away and nothing anybody could change about it. Admin → Settings now carries
  the number every agent follows, and an agent whose work is longer carries its
  own on its page - empty there means "follow the installation". Between 2 and
  100 at both doors; `ORKNUX_CHAT_MAX_ROUNDS` sets what a fresh installation
  starts on, and the quick chat panel follows the installation's number too.
  Nothing changes for an installation that leaves it alone: eight is still eight.

## 0.9.8

### ✨ Added

- 🛒 **Plugins install from a marketplace.** The Plugins screen gained a catalog
  beside the list of what is installed: a shelf of listings with icons,
  descriptions, tags and versions, browsable and searchable, installed with one
  press. A listing is fetched through the server rather than the browser, so an
  installation behind a proxy reaches it under the same rules as everything
  else, and the bytes are checked against the digest the catalog published
  before anything is loaded. The catalog outranks the zip's own manifest where
  the two disagree, an install key travels on both doors, and a release carries
  the notes its author wrote — read under a Changelog tab on the listing.

- 📦 **The plugins and the SDK moved out to `orknux-extension`.** They were
  folders in this repository, released when the server was released. They are
  now their own repository and their own package, `@orknux/plugin`, which is
  what a plugin is written against — so a plugin ships when its author ships it,
  and an installation takes a new plugin without taking a new server.

- 🧩 **A plugin brings more than functions.** Its own libraries, allowed by
  whoever loads it; skills, granted to an agent as a catalog of their own;
  object shapes its functions can return and a workflow can hold an answer to;
  and tools declared for agents apart from its functions, which appear in the
  Tools list beside the workspace's own and on the agent form's grant list. A
  plugin's function can be edited in place, and the edit survives a reload, a
  restart and a re-install.

- 🎨 **An agent can draw, and can see.** `chat_draw_picture` in a chat and
  `draw_picture` in a run, both behind a grant that can be seen in the Tools
  list and switched off; a picture already on the conversation is handed to the
  model as an image rather than described to it; and an image node draws one as
  a step of a workflow, keeping its prompt and passing the picture on beside
  whatever reached it. A drawn PNG says how big it came out, and what a picture
  tool answers is a key into the session's store — the thing another tool can
  take to *deliver* it — rather than a link into this installation.

- 🗂️ **Everything a workspace has made, in one place.** An Artifacts page, and
  `save_artifact` for an agent to add to it. A picture opens in the viewer; a
  document an agent wrote — HTML, markdown, text, PDF — opens to be read, served
  inline under a sandbox that allows it no script, no cookies and no network. An
  open artifact is in the address bar, so a link to one opens it.

- 🧾 **An AI session carries a store of its own.** A key and a JSON value per
  session, which is how one tool hands bytes to another: a plugin that makes a
  PDF, a tool that draws a picture, and whatever uploads them all speak the same
  key. It is what makes a picture deliverable without a link.

- 🔍 **Nine paged listings are searched by the database.** Tools, functions,
  conditions, actions, triggers, objects, agents, workflows and libraries: what
  is typed narrows the whole list rather than the page that happens to be on
  screen, so a match on page four is a match.

- 💬 **A chat is answered whether its reader stays or not.** A text turn is a
  record kept for whoever comes back, so closing the tab no longer loses the
  answer being written; Stop still stops it. Chat messages say when they were
  sent, a compaction carries the summary it made rather than only a count, and a
  run's transcript says what the model is thinking while it is thinking it.

- 🧰 **The sandbox learned a few things.** `orknux.encoding` for base64 both
  ways, `orknux.crypto` because the engine has none, `orknux.render` to turn a
  page or a PDF into a picture, and three more Slack doors — follow a message
  link, name a mention, write one.

- 🛑 **An agent can say the work is done and stop.** `finish_answer`, offered to
  an agent inside a run. A round ends when the model writes prose instead of
  asking for another tool, which assumes the answer *is* the prose — and an
  agent that posted its own reply to Slack has nothing left to write. Asked for
  an answer anyway it either repeated the message or answered with nothing,
  which reads as a failure, which is retried, which posts the whole thing twice.
  The step now finishes rather than failing, with whatever the agent passed —
  usually nothing, because the thing it made went somewhere the graph is not.

- 🔗 **A picture has an address when an agent asks for one.** `picture_link` in
  a run and `task_picture_link` in a task: pass the key a drawing answered with
  and get markdown back, for putting the picture at a particular point in what
  is being written. The drawing itself still answers with a key and never a
  link, because a key is what a tool that uploads a file takes; this is the
  other case, asked for on purpose. Only that run's or that task's own pictures
  resolve.

- ⏱️ **How long a plugin may run is a setting.** A plugin call is bounded by the
  number on the Settings screen rather than the one the server was started with:
  1–300 seconds, 30 by default. That page now has one Save at the top for every
  number on it, rather than one beside each.

### 🔧 Changed

- ⚠️ **A listing carries tags, not one category.** A marketplace still answering
  with a single category word has it read as a tag, so nothing breaks — but the
  shelf narrows by tag now, and a plugin can be in more than one place. Both
  catalog queries climb down a ladder of fields, asking for the newest shape
  first and falling back a rung at a time, because GraphQL fails a whole query
  over one field a server has not heard of.

- 🔌 **A capability a plugin did not ask for is a question, not a 500.** Loading
  one that reaches further than its manifest declared asks whoever is loading it
  to allow the difference, in the same modal that allows its libraries.

- 🖼️ **A picture handed to a model carries its host.** The links were relative,
  so a model that pasted one pasted a path with no host behind it. They are
  absolute now, built from the installation's own address — and what the picture
  tools answer is a key rather than a link at all, because a link pasted into a
  chat is punctuation. `save_artifact` is the one tool that still answers with
  one, and composes the markdown itself: an artifact *is* a thing at an address.

- 🔎 **The tools list is searched by name.** It was name or description, and a
  description here is a paragraph written for a model to read — so "date"
  returned every GitHub tool, because their descriptions mention a commit's
  date. Both halves of that list, the workspace's own and the plugins', narrow
  by the same rule.

- 🧹 **Run history is swept, and a deleted workspace takes its runs with it.** A
  retention the Settings screen sets, and a delete that no longer leaves rows
  nobody can reach.

- 📡 **MCP servers are checked on a timer**, not only when somebody presses the
  button — so a server that went away is marked as away before somebody's run
  finds out.

- 🧱 **A workflow name is taken only in its own workspace.** It was installation
  wide, which meant two teams could not both have a workflow called
  "Onboarding".

### 🐛 Fixed

- 📡 **A provider that ignores `stream: true` is still read.** `stream: true` is
  a request, not a guarantee, and a local server or a proxy in front of one may
  answer with the whole completion in one ordinary body. Read for frames that
  were not there, that came back as "the provider answered with no message" — a
  silence invented by the reader — and the step failed. A stream that carried
  nothing at all is now asked once more without streaming, and what that says is
  the answer.

- 📎 **A file shared in Slack reaches the agent.** It arrives with the message
  and is carried on the thread's other messages too, so "look at this" works
  when the picture came a turn earlier. A mention with no files at all is a
  mention rather than a crash.

- 🔁 **Slack sending the same event twice does not run it twice.** A retried
  delivery is recognised and dropped, so one message starts one workflow.

- 🧭 **Two nodes in one workflow may share a definition name**, and a workflow's
  own definition is named among its own rather than competing with every
  workspace's. Deleting a condition no longer raises about a workflow nobody
  named.

- 🪟 **A new node lands in view.** It was placed where the canvas was rather than
  where somebody was looking, which on a large graph meant adding a node and
  seeing nothing happen.

- 🖱️ **A picture drawn in a chat opens when clicked**, and is in the chat's
  files at once rather than after a reload — the one place in the application
  where a picture is made was the one place it was neither.

- 📛 **A plugin's tools are not named by the core.** The note on a drawn picture
  told a model to call `slack_uploadBinary`, which exists only where that plugin
  is loaded; it now says what kind of tool to look for and lets the agent read
  its own list.

## 0.9.7

### ✨ Added

- ⏱️ **How long a tool or function may run is now a setting, at three scopes.**
  Every script ran under the installation's one bound — five seconds unless the
  server was started differently — and changing it meant an environment variable
  and a restart. A tool or function can now carry a timeout of its own, in its
  editor; a workspace can set a default on its settings page; and the
  installation's bound is what remains where neither has said anything. The
  nearest number wins, it is read per call so a change decides the next run and
  never one in flight, and 1–600 seconds is the range everywhere — a timeout
  typed in milliseconds by mistake is refused rather than holding a thread for a
  fortnight.

- 🔐 **A tool can be handed the workspace's variables, the way a function is.**
  External parameters, the same arrangement functions have had: chosen by
  variable in the editor, appended by the sandbox after the parameters the tool
  declares, and never shown to the model — so a tool reaches a credential
  without the credential passing through a conversation. The signature marks
  them `(external)`, and the agent is never told they exist.

- ✏️ **Renaming a function follows into the scripts that call it by its name.**
  An import is held by id, so a rename never broke a caller — but every importer
  whose alias *was* the function's name kept spelling the old one, and the next
  person reading that code went looking for a function that does not exist. The
  alias and the `imports.name` written in the importer's code now move with the
  rename, in both languages, with a version recorded per rewritten importer. An
  alias the importer chose for itself, or one the new name would collide with,
  is left exactly as chosen.

- 🧠 **An agent can write a memory, not only read them.** `memory_save`, beside
  `memory_search` and under the same catalog grant — an agent that could only
  read was an agent whose lessons died with the conversation. A repeated title
  updates the memory rather than doubling it, the catalog may be left unsaid
  only while the agent holds exactly one, and the author is the agent's name, on
  the card and in the audit.

### 🔧 Changed

- ⚠️ **A tool's code is checked against its declared parameters when it is
  saved** — the rule functions were always held to, and the gap behind the
  strangest bug in this release: a tool whose code took three parameters while
  its details held the one legacy `input` map saved fine, and then the model
  filled `input`, whose whole object landed in the code's first argument. Code
  whose arity disagrees with the declared parameters plus externals, or that has
  no default export to call, is refused at save with both counts named. **A
  tool already carrying such a mismatch keeps running as it did, but its next
  save is refused until the code and the parameter list agree.**

- 📏 **A description has room for a worked example.** Tool and function
  descriptions were capped at 500 characters with nothing checking — a longer
  paste failed as a raw database error surfaced as INTERNAL_ERROR. Both columns
  now hold 4000, and going over is a refusal naming the count.

- 🔑 **An empty value on a variable update now clears what is stored.** It used
  to be read as "nothing was sent" and dropped, so a secret could never be set
  back to empty. Null still means "leave the stored value alone", which is what
  a form that cannot show a secret sends.

### 🐛 Fixed

- 🧵 **A condition's function gets its externals where the declaration puts
  them.** The legacy whole-payload call appended the workspace's variables right
  after one argument whatever the function declared, so a function declaring two
  parameters and an external read the payload as its first parameter and the
  external as its second. The argument list is squared to the declared count
  before the variables are appended, so a secret can never slide into a declared
  parameter's place.

- 👁 **A secret saved empty can be edited again.** It never rendered its reveal
  eye, and without the eye its box stayed read-only forever, with a tooltip
  pointing at a control that was not there. A box holding nothing has nothing to
  hide, so it stays open; the eye is drawn only where there is something to
  reveal, and a revealed secret can now be cleared by saving it empty.

## 0.9.6

### ✨ Added

- 🌐 **A function can make an HTTP request.** A workspace's JavaScript could not
  reach an outside service at all, so anything that needed one meant an action or
  an MCP server - a heavier thing to build and keep for a single call.
  `orknux.http.get`, `.post` and `.request` are made **by the server** on the
  function's behalf: the script hands over a URL and gets an answer back as data,
  so it never holds a socket. **Where it may get to is the installation's proxy
  rules**, the same ones a Slack call and an MCP call obey. An object body is
  sent as JSON and given the content type; a JSON reply arrives parsed as `json`
  beside the `body` it came from; a failure is a value with `error` on it rather
  than an exception, so a condition that could not reach a service still decides.
  Put a credential in a workspace variable, which arrives as a parameter - not in
  source that is revision-tracked and exportable.

- 🪵 **A function or a plugin can say what it is doing.** There is no `console` in
  the sandbox and `print` is turned off by name, so until now a script that
  misbehaved left nothing behind but its answer. `orknux.log.debug`, `.info`,
  `.warn` and `.error` write to the server's log under loggers of their own, so
  what scripts say can be turned up without the server getting louder with them.
  **The level is decided inside the sandbox** - `ORKNUX_SCRIPT_LOG_LEVEL` and
  `ORKNUX_PLUGIN_LOG_LEVEL`, either of them `off` - so tracing left in a function
  costs one comparison while it is turned off. A line names the workspace, the
  function, and the workflow and execution where there are any; anything that is
  not a string is logged as JSON.

- 📝 **A new function's code says what it may call.** The stub opened with an
  empty body and a sandbox whose whole surface is one global nobody has heard of,
  so the first thing anybody wrote was `fetch` and the second was a refusal.
  Four commented lines name what is there, and are deleted in one keystroke by
  anybody who already knows.

- 🧩 **An Object node with fields of its own now says so.** That mode has been
  there all along - a node with no saved shape holds whatever fields you name on
  it - but the Shape picker listed the workspace's saved objects and nothing
  else, so the mode had no name on screen: an untouched node read *Choose a
  shape…*, which is what a control somebody forgot to fill in looks like, and
  choosing a saved shape was one-way because there was no row to go back to.
  **Custom** is now a row in that list, marked apart from the saved shapes, and
  choosing it brings the fields editor back. Nothing about what is stored
  changed, so a node drawn before this reads as Custom because that is what it
  is.

- 🔁 **A workflow can be duplicated.** Trying a change to a workflow that is
  already in use meant either redrawing it node by node or editing the one that
  works. The copy button on a workflow's row takes the graph whole - every node
  with its settings, its mappings and where it sits, every edge with the branch
  it leaves by - and opens the copy's canvas. **The copy is always a draft**,
  whatever the original was, which is what makes this safe to press on a
  workflow with triggers on it: an event runs the published copy, so nothing
  starts the duplicate until somebody publishes it. The triggers are pointed at
  rather than copied, since a duplicated trigger would be a second webhook path
  nobody asked for. It is named for what it came from, numbered if that is
  taken, so pressing it twice makes a second copy instead of a refusal.

- ⚠️ **A signature edited in the code now moves the panel beside it.** The two
  are one thing with two controls, and only one direction was wired: editing a
  parameter in the panel rewrote the declaration, editing the declaration left
  the panel showing the old parameters - and it is the panel that is saved. So
  typing the change into the code, which is what anybody writing code reaches
  for, produced a function whose stored signature was whatever the panel still
  believed. It is read back after a pause rather than on each keystroke, and a
  declaration that is mid-edit leaves the panel alone rather than emptying it.

- 🔐 **A server behind a private or self-signed certificate can be reached.** It
  could not be: the failure was *unable to find certification path to requested
  target*, eight words of Java naming no server, no certificate and no remedy —
  and the only fix was the JVM's own trust store, which an administrator running
  a container image cannot reach without rebuilding it. **Admin → Networking**
  now holds the list of certificate authorities this installation trusts: give
  one a name, paste its certificate in PEM, and it is trusted **as well as** the
  roots already trusted, never instead of them, so nothing that worked before
  stops. The hostname is still checked and the chain still has to build; there is
  no trust-everything switch, deliberately. An authority that has expired says so
  on its row rather than quietly not working, and removing one takes effect on
  the next connection rather than at the next restart. A paste that is not a
  certificate is refused in words that say what to do rather than as Java's own
  *No certificate data found*.

- 🔌 **An MCP server can be asked whether it is there, and says what went
  wrong.** There was no way to check one: an address that was wrong or a token
  that had expired showed up as an agent quietly having fewer tools than its
  screen listed, with nothing anywhere saying so. Check on the server's page
  opens the same handshake and asks for the same tool list an agent does, and
  reports what it found. Every failure used to arrive as the same eight words —
  *the server did not complete the MCP handshake* — for a wrong address, a
  refused credential, a rejected protocol version and a proxy answering instead;
  each now says which, quoting what the server itself said. A handshake refused
  inside a 200, which JSON-RPC allows and which used to read as success, is
  reported as the refusal it is.

- 🔌 **An MCP server's page says which agents hold it.** It was the one
  registered thing with no *Used by* panel, because an MCP server was counted
  among the things nothing points at - and an agent names one in its grants.
  Removing a server takes that capability off every agent holding it and does
  not ask, so the page most likely to be looked at before a Remove was the page
  that said nothing about what a Remove would cost. The panel sits directly
  above the Danger Zone, and each row opens the agent that holds it. A server
  nobody was granted says so in words.

- 🔊 **A speech model can be told not to read the blank lines.** Readers do not
  agree on what an empty line is: some pause on one for far longer than the
  sentence deserves, some take it for the end of the utterance and clip what
  follows, and an answer written in paragraphs is full of them. Skip empty lines
  when reading, on the model's own card, hands the reader the lines with the
  blank ones taken out and the rest in their own order — the line breaks between
  them stay, because that is where a reader draws breath. It is a fact about the
  reader rather than about the answer, so it sits on the model and two speech
  models in one workspace can differ. Off unless it is turned on, so nothing
  reads differently until somebody asks for it.

- 🧩 **A plugin can ask the server to read a Slack thread.** Slack's message
  event carries no reply count, so *is this the first reply* — the commonest
  thing anybody gates a workflow on — cannot be answered from what arrives; the
  thread has to be read, and reading it needs the network a plugin deliberately
  does not have. So the server makes the call, under a **capability** the plugin
  declares and somebody accepts. Capabilities are a second list beside the
  permissions and are accepted separately, because the two differ in kind: a
  permission turns on a language feature and reaches nothing, while a capability
  is this server acting on the plugin's behalf. Nothing about the sandbox is
  relaxed to do it. `orknux.slack.thread(connection, channel, threadTs)` is
  declared in the plugin template, so any editor completes it — and refuses a
  connection of the wrong kind, because a `SlackConnection` carries its kind in
  its type.

- 🌐 **A plugin an administrator agrees to can make requests.** A plugin knows one
  outside service, and until now it could only ask the server about Slack. It can
  now ask for **`NETWORK_REQUEST`** — the widest thing on the capability list, and
  the summary says so where somebody reads it before accepting: *make requests to
  any address this server can reach*. Off until it is granted, granted per plugin,
  and every request goes out through the proxy rules, so an installation's own
  rules govern where it gets to — the same rules an MCP call and a Slack call obey.

  **The sandbox did not change.** There is still no socket, no `fetch` and no way
  to ask for one: a capability is a function the server implements, and what
  crosses is a url in and a status, some headers and a string back. So a package
  that calls `require('net')` is exactly as uninstallable as it was, which is
  worth knowing before reaching for this to fix one. `file:` and anything else
  that is not http is refused, and a plugin cannot set the headers that would
  make this server's request look like somebody else's.

  A workspace's **functions** do not get this one. They get the Slack call,
  because that can only reach connections belonging to the workspace the run is
  in — but a function is written by whoever can write one and runs the moment it
  is saved, and there is nobody in that story to agree to anything.

- 📦 **A package that is more than one file can now be installed.** Most
  published packages are: an entry that requires a file beside it, or a second
  package, was answered with *build a bundle and upload it* — and the person
  reading that had to go and install Node to do something the server was already
  holding the archive for. **Libraries** now offers to bundle instead. It says
  what would go in, with every package at the version its range resolved to, and
  makes them into the one file a library has to be if you say so. Choosing
  several files at once does the same thing, and asks which of them it is
  entered by rather than guessing.

  A CommonJS file goes in exactly as it arrived — nothing minified, no specifier
  rewritten — and what went in is listed on the row afterwards, because a bundle
  is an artefact **this installation assembled**: no registry published it, and
  nobody outside can hash it to the same thing. That is why it is offered rather
  than done quietly.

  **A package published only as an ES module works too.** `import` is syntax and
  a bundle has nothing to hand it, so those files are rewritten as CommonJS on
  the way in — by Babel, which does that for a living, vendored so an
  installation with no network still installs libraries. The bundle's header
  names every file that happened to, so what is the published file and what is
  not can still be told apart. The first library that needs the compiler waits
  about two and a half seconds for it to load, once, ever.

  A file reaching for Node's `fs` is still refused by name, and so is one the
  compiler cannot parse; two packages needing different versions of a third are
  refused with both versions, since one file cannot hold two. Where a package
  publishes a `browser` map — the field saying what to do with a file where there
  is no Node — it is honoured, because that is exactly the environment a library
  runs in. Nothing about the sandbox changed: a bundle is
  CommonJS and is wrapped on the way in exactly as any other CommonJS library
  is.

- 💬 **A long chat can be summarised instead of failing.** A conversation that
  outgrew its model's window stopped working, and it stopped with a number —
  *maximum context length is 128000 tokens* — which is true and useless: the
  only thing anybody could do about it was start again and lose everything.
  **Workspace → Settings → Chat Compaction** now holds a threshold: above it,
  everything but the last few turns is replaced by one summary of itself and the
  chat carries on. **Those messages are gone**, which is what compacting means —
  so it is off until somebody sets a threshold, and every workspace starts off.
  The summary's length is a setting too, and so is the model that writes it:
  summarising is a cheaper job than answering and happens once in a while, so a
  workspace talking to an expensive model can summarise with a small one.
  Compaction happens before a turn is sent rather than after one fails, and a
  summariser that will not answer leaves the conversation alone — a chat that is
  still too long is a better outcome than one whose older half was thrown away
  by a network error. The chat says so both ways round: a line while the summary
  is being written, and a note in the transcript saying how many messages were
  replaced by it - compaction that happened silently read as a turn that had
  stalled, and left a summary in the conversation nobody had been told was
  written.

- 🔗 **A Slack trigger says which connection its event arrived on, and a function
  condition can be handed arguments.** Both are what the above needs to be
  correct rather than merely possible: a workspace with two Slack connections has
  two Slacks, and a thread read through the wrong one is somebody else's. A
  condition now takes one argument per parameter its function declares — a
  written value, or a reference to a field the run carries, such as the trigger's
  thread and the connection it came in on. A condition with none behaves exactly
  as it did, so nothing written before this changes.

- 🧵 **A function can read a Slack thread, and be handed the connection to
  read it through.** The plugin could; a function written in the workspace could
  not, and a function is what most of this product is made of. `orknux.slack.thread`
  is now declared in the function editor and answered by the server on the
  function's behalf - the sandbox still has no network, so what crosses is a
  request and a page of JSON, never a client. A parameter may now be typed
  **connection**, which is what makes the call sayable: a function declaring
  `string` worked and told nobody reading it what the string was for. Both
  screens that ask for one - the Test Run window and an action's parameter
  mapping - offer the workspace's connections by name rather than asking for an
  id off another page. A thread on a connection belonging to another workspace is
  refused as though it were not there.

- ▶️ **A finished task can be told to carry on.** The only way back into one
  that had ended was to start it again from nothing, which threw away everything
  it had worked out and paid for the same reasoning twice. The box that was
  already at the foot of a finished task now sends what you write into the same
  task, with its history behind it, and the task picks up where it stopped.

### 🔧 Changed

- ⚠️ **The quick chat panel now writes down what it did.** It ran a tool loop of
  its own that nothing recorded, so a panel given permission to write could
  start a run and leave no account of having started one — the one loop here
  whose authority is a switch was the one loop with no transcript. Each round
  now goes into an LLM session, one per person per workspace, filed under
  `quick-chat`: the question, every tool it called with what it was passed and
  what came back, and the answer. It records through the same recorder every
  other loop uses, so the same redaction applies to it — a credential in a
  tool's arguments is starred out on the way in rather than by a second pass
  that could reach a different answer. The session is written and never read
  back, so the panel still has no memory of its own.

- ⚠️ **Leaving an issue now returns to the list you were on, filters and
  all.** The filters live in the address so that a narrowed tracker is a link
  somebody can send - but every way out of an issue named the list by its bare
  address, so filtering down to the handful that matter and opening one of them
  put you back at Open, newest first with the filtering to do again. The arrow,
  the leave guard and the way out after a delete all return to the list that was
  left. The issue's own address is unchanged: it carries no filters, because a
  link to an issue is what people paste to each other and two that differ only
  by somebody else's filtering would look like two different pages.

- ⚠️ **Asking for the answer again, in voice mode, is now read aloud.** It was
  not. The composer and the voice panel are two doors into the model and only
  one of them was wired to the reader, so a conversation being held out loud
  went silent at exactly the press that says the last answer was not good
  enough — the panel had never been told a turn was happening, and sat there
  listening while an answer nobody would hear was written behind it. The press
  now goes through the panel, which reads the second answer the way it reads
  every other, and cuts short the reading of the one being replaced rather than
  finishing it first.

- ⚠️ **A workflow with two triggers now runs only the branch belonging to the
  trigger that fired.** It used to run both. A trigger node has nothing pointing
  at it, so every one of them counted as a beginning: one message arrived, both
  branches went, both agents were charged, and the send that succeeded belonged
  to the trigger that had not fired — with no way to tell which message would be
  the one that went out. **Republish the workflow to get the new behaviour**: a
  published copy is what an event runs and is never rewritten, so one published
  before this release goes on running both branches until it is published again.
  Runs nobody triggered — a person pressing Run, an API asking for the workflow
  itself — still begin at every trigger, since nothing says which half to prefer.
  Repeating a run repeats the half the original ran.

- 💾 **The workspace settings page has one Save, at the foot of it.** Some
  cards had a Save of their own and some did not, so whether a change was kept
  depended on which card it was in - and there was no way to tell by looking
  which kind you were in front of. Every control on the page is now a draft until
  the one button at the bottom is pressed. It writes only the fields that were
  actually touched, which matters more than it sounds: saving everything on the
  page would have quietly put back whatever somebody else had changed elsewhere
  since it was opened.

### 🐛 Fixed

- 🧩 **Looking at a saved shape no longer throws away an Object node's own
  fields.** Naming fields under **Custom**, glancing at a saved shape and coming
  back left the node holding the shape's fields and yours gone. They are set
  aside while a shape has the panel and restored when Custom is chosen again.

- ⚖️ **A condition's parameters are filled in on the node, with the ordinary
  pickers.** They were on the condition's own settings page, which has no graph
  behind it - so a reference had to be typed from memory into a plain box - and
  they were one shared list, so two nodes asking the same question about
  different threads had to be two conditions. They belong to the node now, drawn
  with the same Value/Reference switch and field picker every other parameter
  uses. **A condition that already carries arguments keeps them**, so no graph
  changed meaning.

- 🔎 **The field picker's search stopped keeping whole groups.** It matched the
  group's heading as well as the field, so a graph with a *Slack reply received*
  trigger and an *Azure* agent answered `re` with every field of both - a search
  box that appeared to do nothing.

- 📄 **A list goes back to the first page when the workspace changes.** Standing
  on page 3 of Triggers and switching workspace asked for page 3 of the next one,
  which answered truthfully with nothing: an empty list on a workspace that has
  triggers. Every paged list on a workspace had it.

- 🔌 **An agent's MCP servers are chosen from a list.** They were chips and a box
  to type a name into, so granting one meant knowing its name by heart and
  nothing on screen said which existed. They are the same list as Tools now, with
  a search and a count; **a grant naming a server this workspace does not have is
  still shown**, marked, and can be revoked.

- ✏️ **Renaming a function in its code moves the panel beside it.** The Name
  field rewrote the declaration and the declaration did not rewrite the Name
  field, so a rename typed where a programmer types one left the panel - and what
  is saved - on the old name.

- 🔔 **A trigger firing says which workflows it started.** The line carried a
  count, so working out what a message set off meant opening Executions and
  matching on the clock, and a firing that started one of three said nothing
  about the one. It names them, with their ids, and says why each of the others
  did not run.

- 💬 **The quick chat panel knows what the sandbox offers.** Its briefing listed
  every prohibition and never mentioned `orknux`, so asked how to count the
  messages in a Slack thread it answered `messages.length` - the length of the
  page that was fetched, which also counts the parent - instead of `replies`.

- ✅ **A validation line the interface could finish.** *Saved, and valid — the
  code compiles and the sandbox's parser ac* now reads **Saved and valid**.

- ⌨️ **The function editor no longer loses what you typed.** A save landing
  while somebody was mid-word put the stored version back over the top of it, so
  a rewritten declaration reverted to what it had been a second earlier - about
  one edit in two, with nothing on screen to say it had happened. The editor's
  loader now stops caring about an answer it no longer needs, so a reply to a
  request that has been overtaken is dropped rather than drawn.

- 💬 **A chat opened by its own address is about its own workspace.** The chat
  screen never asked which workspace the open conversation belonged to: it took
  whichever workspace the browser had last looked at, and the first one in the
  list when it had looked at none. So a chat reached from a link, a bookmark or
  a reload was drawn under somebody else's workspace - the agents offered in the
  picker, the models voice mode needs, where an attachment goes and every link
  out of the page, including one naming a stranger's agent as the one answering.
  Switching workspace from a chat still moves you, and now waits for the move to
  land: the list of conversations no longer flickers back to the workspace you
  just left.

- 📝 **Twelve editors and settings pages stopped losing what you typed.** The
  same fault #324 fixed in the function editor was everywhere a page fills its
  form from a load and never cancels that load: the answer arrives after you
  have started, puts the stored version back over the state behind the boxes,
  and nothing on screen says so - the fields still show what you typed, because
  it is the state that was replaced and the state is what Save sends. Three of
  them were reported separately. A model's **Context Window** could be typed,
  saved, and stored as empty. **Renaming the parameter a tool arrives with**
  reverted, and the save stored the signature the panel had been put back to. A
  provider's **Checked every few minutes** switch could be turned off and saved
  with nothing changing. Rather than wait for the fourth report the rest were
  looked for: providers, connections, objects, MCP servers, skills, memories,
  workflow settings, admin settings, certificate authorities and both workspace
  settings pages. It also fixes the quieter half - opening one record and then
  another no longer draws the first one's values under the second one's name.

## 0.9.5

### ✨ Added

- 🔌 **A local model server no longer answers every other call with "unexpected
  end of stream".** llama.cpp and most self-hosted servers close an idle
  connection after five seconds; the HTTP client held one for five minutes and
  wrote its next request into a socket the server had already closed. Chats,
  titles and provider checks all failed on it, and the second attempt worked,
  which made it look intermittent. Connections are now dropped before any
  server drops them.

- 🔌 **A provider is asked for its models where it actually keeps them.** The
  check behind "Test Connection" built its own address, and for an Azure
  endpoint written through to `/openai/v1` that came out one path too deep -
  so a provider that answered every message reported that it could not be
  reached, and the card sent you to check the one field that was right. The
  listing goes through the model SDK now, which knows where each of Azure's two
  surfaces keeps it; a server answering in a shape the SDK cannot read is still
  read the old way.

- 🧪 **A model becomes an agent in one press.** Beside every chat model on
  Models there is now a "Make an agent on this model" action: it creates an agent
  on that model, named after it, and takes you to its settings page. The agent is
  granted **nothing** — no tools, no skills, no MCP servers, no catalogues, no
  shell — because granting is a deliberate act; it is a bare agent to dress, and
  it is what makes "I have just added a model, does it work" a short path again.
  Pressing it twice makes a second agent with a number after the name rather than
  failing on the one that is taken.

- 🐙 **GitHub, as a plugin rather than as a connection type.**
  `plugins/github/github.js` guards a webhook trigger with GitHub's HMAC
  signature and names what arrived, so pull requests, review comments and
  pushes start workflows. Load it, accept `TEXT_ENCODING`, point its
  `webhookSecret` at one of the workspace's variables, and add the trigger's URL
  to the repository. Nothing about GitHub is in the server: a host that changes
  its signature scheme is a new version of that file, not a release.

- 💬 **A working task can be told something.** A box on its page takes a message
  while the agent is mid-turn, and the agent picks it up **between the tools it
  is running** — so a correction reaches it within seconds rather than at the end
  of a turn, and a report can become a table without the task being stopped and
  started again with a better prompt. A task cannot finish while something said
  to it is still unread: `task_done` with a message above it earns another turn
  instead of ending. Until it has been read the page says so, and a message that
  was never read — on a task that failed or was stopped — stays on the page
  saying that, rather than disappearing with the box.

- 💼 **Microsoft Teams, as a plugin** — `plugins/teams/teams.js`, loaded on the
  Plugins screen. A message in Teams starts a workflow by way of a Teams outgoing
  webhook pointed at a webhook trigger, with the plugin checking the signature
  Teams sends; a workflow answers with an HTTP request action against Graph. No
  new connection type, and nothing to upgrade the server for. Teams has no
  equivalent of Slack's socket, so the receiving half needs this installation to
  be reachable from Microsoft, and the Graph token lives in a workspace variable
  that has to be refreshed about hourly. The README has the setup.

- 🖼 **A task can draw.** Where the workspace has chosen a text-to-image model,
  an agent working a task is offered a tool that draws from a description, and
  every picture it drew is shown under the task's outcome — including on a task
  that ran out of turns before it finished. The picture opens larger when it is
  clicked.

- 🎨 **A chat agent can draw.** Ask for a diagram in the conversation and the
  agent draws one, where the workspace has chosen a text-to-image model. The
  picture is filed as an attachment on the chat and appears in the thread, so it
  is still there when the chat is reopened. A workspace with no image model
  chosen offers no such tool, and no agent is told it could have drawn.

- 🧹 **A task that was never picked up is picked up.** Something now looks every
  few minutes for tasks left sitting at Queued and hands them over again, so a
  hand-over lost to a restart at the wrong moment — or, on Temporal, to a
  workflow that started and could not run — no longer leaves a task nothing will
  ever look at. A task a worker already has is never handed over twice. How long
  a task may sit is a field on Admin → Settings for an installation carrying its
  own tasks, five minutes by default; one running Temporal takes it from
  `ORKNUX_TASK_SWEEP_MINUTES` and is shown no field.

- 🔕 **A provider can be told not to be checked on a timer.** The sweep asks
  every configured provider every few minutes so that "Connected" means today,
  which is right for a provider an installation pays for and wrong for the one
  somebody keeps configured against a box that is only sometimes running — a
  laptop's model server, an endpoint started for an afternoon. There it produced
  a failed row and a page of connection-refused in the log every five minutes
  about a state nobody thought was wrong. **Automatic checks** on the provider's
  own page turns that off; every provider that exists today has it on. It stops
  the timer and nothing else — Test Connection still runs, and so does every
  chat and task the provider serves.

- ⏱ **A task page can be told to catch up on a timer.** The page follows a task
  on a stream, so nothing here waits on a refresh — but a stream can stop
  without saying so, and from the reader's side that looks exactly like a model
  thinking for four minutes. The same interval control the Executions and Audit
  screens carry is now beside the word that says whether the stream is up, Off
  by default and sharing the one setting those screens share. A task that has
  finished is not offered it, having nothing left to change.

- ↩️ **A finished task can be told to carry on.** "Make the third stanza
  shorter" is not a new piece of work, it is this one continued — and the only
  thing that knows how is the agent that did it. The box on a task's page stays
  after it ends, reading **Carry on**: what is typed there sets the same task
  working again on the same session, so the agent has the poem it wrote, the
  pictures it drew and the files it read, and does not have to be told the whole
  job by somebody who just watched it be done. Starting a fresh task was the
  only route before, and a fresh task opens on nothing. The turn and time
  allowances are taken again from the settings as they stand and the counts
  start from zero, so a task that stopped *because* it ran out can still be
  asked for more; the outcome it wrote is cleared and the next one replaces it,
  with what it said still in the log.

### 🔧 Changed

- 🪵 **A provider that cannot be reached is one line in the log rather than a
  page of it.** The stack trace behind a connection refused was printed at WARN
  every time, twice per attempt since the listing tries the SDK and then a
  hand-built request — sixty frames of okhttp and Spring proxies for the
  ordinary answer that a box is switched off. The sentence is kept at WARN and
  the stack moves to DEBUG; what the check found is still written on the
  provider's own row, which is where somebody reads it.

- ⚠️ **A new chat or task can no longer be started on a bare model.** Both
  used to offer a choice between an agent and a model, and it was never a choice:
  a bare model is an agent with the tools, the skills, the grants, the memory and
  the system prompt taken off, and it was sitting beside them as though it were a
  peer. **An existing installation loses the ability to start a chat or a task on
  a bare model.** The Models half of the picker above a chat is gone and so are
  the two calls that moved a chat back onto one; the task form asks for an agent;
  and `startChat` and `startTask` refuse a model on their own. The chats and
  tasks that were started that way are untouched — they open, they render, they
  answer, and a chat can be handed to an agent whenever somebody wants it to be.
  What is new is a workspace with no agent at all: chat and tasks now say so and
  offer the way to add one, where before a bare model quietly filled the gap. A
  workspace nobody had chatted in used to open its first chat on a bare model
  whatever the interface offered; it now opens on the first agent that can answer.

- 🔌 **Custom is no longer a provider type; those providers are OpenAI now.** The
  type named who was at the other end where every other one names what the
  endpoint speaks, so it promised that any wire format would be handled and sent
  an OpenAI request every time — it branched on nothing anywhere in the server.
  **On upgrade** every Custom provider becomes an OpenAI provider with its
  endpoint, its key, its authentication and its models untouched, and the calls
  it makes are the ones it was already making. Google's OpenAI-compatible
  endpoint, a local server or a gateway is an **OpenAI** provider pointed at its
  own address, which is what it was doing under the old name. If you script
  against the API, `CUSTOM` is gone from `ProviderType`.

- 🏁 **A task started on an installation without Temporal now actually runs.**
  Every task started from the Tasks page or from an issue sat at Queued doing
  nothing until the server was restarted: the work was handed to a worker a
  moment before the task itself was written down, so the worker went looking for
  it and found nothing. Nothing is handed over now until the task is there to be
  read — which also covers an approval, an answer or a message, all of which
  reached a task the same way.

- 🖌 **The picture button is gone from the chat composer**, replaced by the
  agent's own drawing tool above. Asking for a picture is now something said in
  the conversation rather than a mode the composer is switched into and the
  description retyped in. Pictures drawn before it went are untouched: they are
  attachments with a line in the thread, and they still show. A chat on a bare
  model rather than an agent can no longer draw at all — a bare model is offered
  no tools.

- 📨 **A webhook run is handed the request's headers**, under `webhook`, beside
  what the body brought — because several senders say which event a delivery is
  in a header and not in the JSON, and a workflow given only the body could not
  tell one apart from another. The body still wins where both name a field, and
  the headers HTTP has names for carrying a credential are left out of the row,
  so nothing changes for a workflow already written against a webhook.

- ⏱ **A shell command is no longer stopped after a minute, and its output no
  longer loses the end.** The command timeout ships at ten minutes and the kept
  output at 256 KiB, and both can be set per machine on Admin → Shell, where
  leaving a box empty means the installation's own default. Output over the
  allowance keeps **both ends** now rather than the first bytes only, with a line
  between them saying how much went — so a build that fails after a long
  download shows the error rather than the download. An installation that was
  relying on the old minute will find commands running longer before they are
  stopped.

- 🔒 **A credential on a shell command line is no longer audited in the clear.**
  A password in a git remote, a `curl -u`, an `Authorization` header, a
  `--token=` or an exported `…_TOKEN` is replaced by `***` before the row is
  written — rows written before this are left as they are, and any credential
  already in them should be treated as disclosed.

- 🔒 **A credential in a tool call is no longer kept in the session
  transcript.** What an agent passed a tool is redacted the same way an audit
  line is, so a password in a `git push` URL is `***` on the chat and task
  pages and in the table behind them; what a tool *returned* is stripped only of
  the things that are a credential on sight — GitHub, Slack, AWS and similar
  tokens, and private keys — because replacing every `password` and `--token` in
  a build log would cost the agent the output it works from. A secret in command
  output with no recognisable shape is still stored, and rows written before
  this are left as they are.

- 🔒 **A chat watched live now shows the same redacted lookup a reload does.**
  The stored copy of a tool call was stripped of its credentials and the frames
  streamed to the chat window were not, so one `git push` read `alice:***@host`
  after a reload and `alice:s3cr3t@host` while it was running; both are now the
  one redacted string, under the same two strengths, and the agent goes on being
  handed the command as it was written.

- 💬 **What an agent says on its way to a lookup is kept.** A model may answer
  with a message and tool calls in the same reply — "let me read the skill
  first" — and that message was thrown away: off the task and chat pages, and
  gone from the agent's own memory by its next turn, so a task whose progress
  was reported that way lost the only copy of it. It is now written into the
  session above the calls it came with, under the agent's name. A round that
  said nothing writes nothing, which is nearly all of them.

- 🔌 **An Anthropic model no longer refuses a turn that put two of a role
  together.** That API takes its messages strictly alternating, and a round that
  called several tools threaded a result back per message — so the request was
  refused outright and the agent got a provider error rather than an answer.
  Consecutive turns are now joined into one message, as separate parts, when the
  Anthropic request is built; every other provider is sent exactly what it was
  sent before.

- 🧠 **A task's thinking closes when the model starts answering**, rather than
  staying open until the turn ends — a long answer no longer leaves the block
  counting up with the reasoning stopped mid-sentence.

- 🛑 **Interrupting a chat now stops the model, rather than only stopping
  listening to it.** Pressing the big circle in voice mode put the panel back to
  Listening and left the request exactly where it was: the model went on writing
  an answer nobody would ever hear, every word of it was charged for, and the
  next thing said raced a turn that had never ended. The interruption now aborts
  the request, and the server hangs up on the provider when the reader goes —
  which it could not previously notice at all while the model was thinking,
  because nothing was being written to find out on. The composer has a **Stop**
  beside the send button that does the same thing for a typed turn. An answer
  that was stopped is not written to the history: the chat keeps the question and
  no answer, rather than half a sentence attributed to the model.

## 0.9.4

### ✨ Added

- 🤖 **Tasks: an agent given a problem, working at it until it is done.** Its own
  section, its own page, and a log that fills in as the model works — what it is
  thinking, what it called, what came back.
- 🚀 **Start by AI on an issue**, which hands the issue's own agent the title, the
  kind, the labels, the description and the thread, picks the issue up, and says
  so in the thread with a link to the task.
- ❓ **A task can stop and ask** — a question, or permission for a capability —
  and waits for an answer rather than guessing.
- 🎛 **A workspace decides how many turns a task may take.** Empty means the
  installation's own number.
- 🏷 **Issue types, decided per workspace**, rather than a fixed list.
- 💬 **Slack triggers on a message and on a reply to one of our own bots** —
  a reply is matched by the author of the thread it hangs under.
- 📦 **A library can be installed from npm**, fetched once into the database and
  served from there; CommonJS packages included.
- ▶ **A function can be test-run from its editor**, down the path a workflow runs
  it on, with a field per parameter — and the workspace's variables can be given
  by hand for the run.
- 🔍 **Where a component is used, asked in one place** — every function, tool,
  skill, agent, action and condition says what depends on it before you remove it.
- 🎨 **A fourth kind of model, one that draws**, and a picture button in the chat.
- 🇵🇱 **Polish**, chosen per person — including what the server refuses and why.
- 💰 **What a chat has spent**, as a running total kept on the chat itself.
- 🧠 **What the model thought before it answered**, in a chat and on a task,
  arriving while it is being thought rather than after.
- 🗑 **A comment can be taken off an issue**, and off everywhere it was copied to.
- 📄 **An action is edited on a page of its own**, the way a condition is.
- 🔔 **A bell that rings for a task**, and news about one.
- 🧭 **The top bar remembers where you have been.**

### 🔧 Changed

- 📡 **A Slack trigger belongs to the app, not to one connection row.** One app
  opens one socket per row and Slack delivers each event to exactly one of them,
  so a trigger bound to a single row fired on a fraction of what it was set up
  for. Every row of that app now hears it — *including rows in other workspaces*,
  each still answering for its own token and its own scopes.
- 🛡 **One trigger failing no longer stops the others** that were waiting on the
  same event.
- 🔑 **A bot token that gains a scope is noticed at once**, rather than after ten
  minutes of the old answer.
- 🧰 **A function's wiring is a page of its own**, off the editor's column.
- ⌨ **A Slack connection trigger needs the event subscribed in the Slack app**,
  not only the scope granted — the manual and the Action picker now say which
  events and where.

### 🐛 Fixed

- 📜 **A chat opens on its newest turn** rather than at the top of the thread.
- ⏳ **A task's turn streams.** It was one blocking call per round, so nothing
  reached the page between one turn and the next.
- 🧵 **A page joining mid-thought is told the rest of it**, instead of watching a
  line that never finishes.
- 👯 **A line on a task's page is drawn once**, not twice.
- 🏁 **A task's stream drains before it says it has ended**, so nothing is lost
  at the last frame.
- ⚙ **A task's worker is given the activity its workflow calls** — Tasks did not
  run at all without it.
- 📝 **Switching workspace while filing an issue keeps you on the form**, rather
  than dropping you on the next workspace's issue list.
- 🔇 **A reply trigger says once that something reached it** and was not what it
  asked for, so silence can be told from nothing arriving.
- 🖼 **The Test Run window no longer flashes its own explanation** as it opens.

## 0.9.3

### ✨ Added

- 🔓 **Authentication can be turned off**, `ORKNUX_AUTH_METHOD=NONE`, for an
  installation already behind a gate of its own — every request then administers
  it, and the interface says so on every page.
- ☸ **Orknux runs on Kubernetes from a manifest kept in this repository.**
  `deploy/kubernetes/orknux.yaml` is `deploy/compose.yaml`'s five services
  written as Kubernetes objects, with a README for what differs.
- 🔑 **Every stored credential can reference a workspace secret**, per field, so
  a connection can hold one token and reference another.
- 🧩 **A function or a tool can call another function**, declared in the editor
  and resolved by the host rather than by the sandbox.
- 📚 **Libraries: JavaScript an installation loads once and its scripts import**,
  administered centrally and refusing removal while something depends on it.
- 🔒 **A plugin says which JavaScript it needs, and loading it asks you to
  agree.** Five named builtins; there is no name for a file, a socket or a Java
  class, so none can be asked for.
- ⏻ **A trigger can be switched on and off from the two screens that define
  one**, not only from the list.
- 🎙 **Voice mode keeps listening while it is thinking and while it is talking**,
  and holds what you said until the turn comes round.
- ⌨ **You can type as well as speak in voice mode**, and Send queues rather than
  drops while the model is busy.
- 🔊 **Where an answer is cut for the speech model is a workspace setting** —
  None, Sentence or Paragraph, on the Voice card.
- 🔁 **A chat's last answer can be asked for again**, and the one it replaces is
  kept as a take you can step back to.
- 📏 **A model's context window can be set** on the model's own page, which is
  the screen the refusal about it already named.

### 🔧 Changed

- 🗣 **An answer read aloud is the answer as it is drawn**, not the markdown
  behind it; a code block is announced rather than read.
- ⏩ **Reading an answer aloud starts on its first sentences** instead of waiting
  for the whole answer to be synthesised.
- 🔒 **A plugin no longer gets `console` or `Intl` for free.** GraalJS turns both
  on by default; a plugin that uses them must now declare them and be re-loaded.
- 🤖 **The picker above a chat offers agents before models.**
- 💬 **Switching workspace while in a chat leaves you in the chat**, rather than
  sending you to the Flow section.

- ✂ **The Create Trigger dialog dropped the sentence explaining what a trigger
  is**, above a form whose first field is called Trigger Name.
- 🏷 **A trigger of the connection kind is called Connection**, not Incoming
  Connection; there is no outgoing one to tell it apart from.
- 🔗 **The node panel opens a definition by the same mark every other form
  uses**, rather than by the words "Open definition".

### 🐛 Fixed

- 🔑 **A function reached through `imports` is handed its own workspace
  variables.** It read them as `undefined` - a wrong answer rather than a
  failure - while the editor promised the sandbox would supply them.
- ⏳ **The editor knows `imports.f(...)` gives back a promise.** It was annotated
  as the bare return type, so a call without `await` type-checked and then
  handed back a promise at run time.
- 📚 **A library whose export is a class instance lists what it offers.** Only
  its own fields were read, so a bundle keeping its API on a prototype showed
  its internals and none of its methods. Running one was never affected.
- 🕸 **A run's graph drawn as boxes with nothing between them.** Every rebuild
  threw away the handle positions every line is drawn from.
- 🎙 **Voice mode no longer says it is speaking before anything has been
  spoken**; the caption follows the audio.
- ⏳ **A conversation held by voice says the model is working**, in the
  transcript and not only in the voice panel.
- 🔗 **An agent's settings point at everything they name** — the model, the
  catalogs, the tools and the MCP servers, in both frames.
- 💾 **The trigger settings page can be saved more than once per visit.** Save
  stayed disabled after the first press until the page was reloaded.

## 0.9.2

### ✨ Added

- 🎚 **A workspace decides when voice mode has heard enough.** Three settings on
  a Voice card: how long a pause ends your turn, how far above the room's own
  noise a sound must stand, and how long an open microphone stays open.
- 🔑 **A model provider's key can be a reference to a workspace secret.**
  **Value** or **Reference** beside the field itself, held by id, so renaming
  the variable or moving it disturbs nothing.
- 💬 **A Slack action's target can be picked from what the connection can see,
  and is judged when it is typed.** Users and channels filtered as you type, in
  both places a target is set, and still free text.
- ⌨ **Publishing a workflow has a keystroke.** Ctrl+Enter, rebindable in
  Preferences, refusing exactly where the button refuses.

### 🐛 Fixed

- 🎙 **Voice mode stops when you have finished, not when you go quiet.** The
  threshold is measured from the room now, the 1.2-second pause is 2.5, and the
  thirty-second cap is ten minutes.
- 🏷 **A Slack action's target no longer asks which kind it is.** Nothing read it
  when sending - and removing it turned up that `@alice` reached nobody, before
  this change as much as after.
- 🦙 **A provider of type Ollama is spoken to in Ollama's dialect.** It was asked
  for `/models`, which Ollama does not serve, so a correct address answered *"No
  model list - check the endpoint"*.
- 📇 **Somebody who signs in through the directory is written down again.** The
  event that puts an external user on the Users page was never published for the
  directory door.
- 🗄 **Monitoring names the database the installation actually stores in.** It
  said Postgres to everybody, including every `orknux-one`, which stores in
  SQLite.
- 📝 **A new issue is written on a blank form.** Opening Create issue from an
  issue carried that issue's title, description and labels into it.
- ✍ **Switching workspace no longer throws away a half-written issue.** It still
  leaves, but it asks, and offers to file the issue where it was written.
- 🕸 **A run's graph is drawn even when its size arrives a moment late.** Nodes
  declare their size now, rather than being drawn invisible until something took
  a fresh measurement.
- 🧾 **An audit feed written in one moment comes back in one order.** Both audit
  queries sorted on the timestamp alone.

## 0.9.1

### ✨ Added

- 🧠 **A workspace sets what its agents default to.** The same memory share
  slider on an **Agents** card, used by every agent that sets none of its own.
- ⏰ **A cron expression says what it does, under the field it is typed in.** It
  follows what is typed rather than what was saved, and one that can never come
  round says so in those words.
- 🧠 **How much conversation an agent carries is now a setting, and it is one
  number.** A **Session Memory** share of the model's context window replaces
  five constants in the source; an agent that sets nothing carries what it did.
- 📖 **A picture in the manual opens at the size it was taken.** Clicking one
  opens it over the page, with Escape, click-away and the focus kept inside.
- 🔍 **"Go to" offers things to do, as well as everywhere to go.** Create issue,
  function and condition, marked with a plus, in a box now called **Quick
  actions**.
- 🕸 **The lines in a workflow point where the run goes.** An edge a run travels
  ends in an arrowhead at the target, red on the failure branch; the dashed
  dependency lines deliberately keep none.
- 🤖 **An agent can put a name on an issue.** `orknux_update_issue` takes an
  `assignee` by the name somebody would say, `"nobody"` hands it back, and a
  name matching nobody is refused rather than ignored.

### 🔧 Changed

- 🧰 **Removing an MCP server now takes the grant off the agents that held it.**
  It used to leave the name behind, so registering that name again handed every
  agent still holding the grant whatever now answered at the new address.

### 🐛 Fixed

- 💬 **The text in the chat composer sits in the middle of its box.** The row
  aligned its contents to the floor, which put the whole difference between a
  line of text and a 32px button above the text and none of it below.
- 🕸 **The control in a node's corner wears a rotate arrow, and no longer crowds
  the node.** It was the two-arrow glyph that means refresh everywhere else, and
  it sat inside the corner among the resize handles.
- 🧪 **CI runs both databases now, side by side.** The build tested one engine,
  and the untested one is what `orknux-one` ships with; both legs run in parallel
  and both must pass before an image is built.
- 💬 **An installation with chat turned off stops offering a chat's settings.**
  The workspace's Chat card did not honour the switch; the Quick Chat model stays,
  in a card of its own.
- 📖 **A screenshot in the manual is no longer dimmed in the light theme.** The
  rule that darkens icon files was catching every picture in the documentation.
- 🗄 **An agent's tools work in a chat on SQLite.** Every tool call came back as
  `Unable to commit against JDBC Connection`; the chat now writes what was said,
  asks the model outside any transaction of its own, and writes the answer after.
- 🧰 **Renaming an MCP server carries the grants with it.** They are held by
  name, so a rename left every agent holding a grant that matched nothing and was
  dropped in silence.
- ⏰ **A cron of seconds is now a schedule this actually keeps.** The tick ran
  once a minute; it now runs on `ORKNUX_SCHEDULER_TICK_INTERVAL` and starts every
  occurrence that came due, with catching up bounded to a minute.
- ⏰ **A schedule that can never come round is refused when it is saved.**
  `0 0 30 2 *` is well-formed, saved, and was skipped on every tick for ever.
- ⌨ **Shift + Enter grows the message box, and it grows all the way to the top.**
  The two-hundred-pixel cap is measured rather than chosen now: the composer
  takes what the conversation above it can spare.
- 💬 **The send button no longer says "Sending" once the message has been sent.**
  It reads *Waiting…* and then *Answering…*, off the same condition the
  conversation's own row uses.
- 💬 **The chat selector is no longer cropped, and links to what it names.** A
  `max-width` beat its `width`, so a panel meant to be 560 pixels wide drew its
  tabs outside a ninety-pixel box; it opens leftwards now.
- 🤖 **The status that says somebody has started is no longer hidden from the
  agents.** `orknux_set_issue_status` accepted `IN_PROGRESS` all along and
  described itself as taking two values.
- 🧾 **An issue somebody picked up is no longer audited as reopened.** Both doors
  wrote two answers to a question with three; the wording lives beside the enum
  now, decided once for all three.
- 🧠 **An agent no longer answers a later question out of its own summary of a
  lookup.** A call and what it returned are one line of the session now, with a
  budget of their own, and a chat with an agent keeps a session to hold them.
- 📝 **The issue tools no longer answer about the first two hundred issues as
  though it were the tracker.** `orknux_issue_labels` counted a page rather than
  the tracker, and `labels` and `assignee` filtered after fetching one.

## 0.9.0

### ✨ Added

- 🔁 **A node's retry policy is a policy, not a checkbox.** Attempts and a single
  wait with a "double it" tick have become attempts, an initial wait, a
  multiplier, a ceiling, jitter and a total budget; a graph you already have does
  exactly what it did.
- 🌐 **An HTTP action's headers are built as rows, and a value may name a
  variable.** They were a JSON blob typed by hand, so a bearer token was pasted
  in as a literal, stored unencrypted, into a field that is not a credential
  field.
- 🎨 **Three times as many icons to label a node with**, 94 to 291, chosen by
  what the nodes in this product actually reach - tickets, source control,
  infrastructure, storage, security, money, documents, logistics.
- 📋 **The workflow list can be sorted, and shows as many rows as you ask for.**
  Name, last run or switched on, in either direction, ordered by the server, with
  the order kept in the address.
- 🤖 **An agent's definition opens beside the graph** rather than instead of it,
  the way every other kind already did.
- 💾 **The four editors ask before unsaved work is walked away from.** Function,
  tool, object and skill, and only where there is a change to lose.
- 🧾 **Creating and closing an issue is written to the audit log** whichever door
  it came through; the tools an agent uses recorded nothing.
- 🛟 **A node can be told what to do when it fails.** A retry policy and a second
  way out, drawn as a red **If fails** line beside the green one - and every
  attempt is another billed call.
- 🔍 **The chat shows the lookups an agent made**, not only the words it said.
  What the model is sent is unchanged.
- 💬 **A turn carried into a chat says who said it**, which matters once a
  session holds both an agent's turns and a person's.
- 🕸 **Turning a node is offered on the node**, above the selected one beside its
  resize handles.
- 🔗 **The object a function parameter names is one link away**, beside the
  selector.
- 🕰 **Functions, tools, skills and agents keep what they were.** Every save keeps
  the version it replaced, with a **History** panel that puts one back; nothing
  from before the upgrade is recovered.
- ♻ **A workflow's publications are kept, and one can be put back into service.**
  They used to be one row per workflow, overwritten on every Publish; restoring
  leaves the draft alone, and variables are deliberately not versioned.
- ⏳ **How long that history is kept is an Admin setting**, fourteen days by
  default (`ORKNUX_REVISION_RETENTION_DAYS`).
- 🔑 **A Slack connection's app-level token can be read back.** It could be typed
  and never seen again.

### 🔧 Changed

- 🔌 **Google AI is no longer a provider type; use Custom.** It never worked;
  stored providers become Custom with their endpoint and key untouched, and
  `GOOGLE_AI` is gone from `ProviderType`.
- 🧭 **The Ollama endpoint hint points at `/v1`.** The form suggested
  `http://localhost:11434`, and anyone who took the hint at its word got a 404.
- 🧩 **There is one Slack connection type, not two.** Outgoing only and Socket
  Mode were one integration under two names; connections carry across, and
  `SLACK_SOCKET_MODE` is gone from `ConnectionType`.
- 🛑 **Deleting an issue asks first.** It used to delete on the click that
  reached it.
- ⏱ **The loader waits three seconds before it appears**, everywhere it is used.
  It was five, which no screen ever reached.
- ✏ **A line in the workflow editor takes as many bend points as you put on it.**
  Double-click the line to add one, double-click a point or press Delete to take
  it off; arrangements already made are read and written unchanged.
- 🚫 **Deleting something that is still in use is refused, and says what is using
  it.** Actions, agents, conditions, triggers, tools, skill catalogs and memory
  catalogs; objects are the deliberate exception.
- 🏷 **Toggling a tool's or a skill's Active badge no longer discards the draft.**
  It applied the whole stored copy over the form.
- 🪝 **"Webhook" no longer names two opposite things.** A trigger of that kind is
  incoming; the outgoing connection kind is now **HTTP**, with existing
  connections carried across.
- 🧹 **Jira and GitHub are no longer offered as connection kinds.** Nothing
  implemented either; both become HTTP connections, which is what they were.
- 👁 **A secret is revealed by the same control everywhere.** Every one is now the
  eye, it toggles, and it says which state it is in.
- 📖 **The readmes name the other three addresses** the site answers to.

### 🐛 Fixed

- 🔍 **The magnifier above a conversation searches the conversation.** It searched
  the list of chat titles, which is the sidebar's question.
- 🔢 **The footer says which version of Orknux this is.** It named the product,
  the copyright holder, the licence and the source, and not the one thing anybody
  is asked for first.
- 🗑 **Deleting a chat asks first.** The trash button removed the chat and every
  message in it on one press, with nothing said.
- 🔦 **The chat's search button searches.** It used to focus a hidden read-only
  input that existed only to be focused.
- 🖱 **The whole message box takes a click.** Only the narrow line of text did, so
  a press on the padding went nowhere.
- 📐 **The chat's title bar is one row.** The model or agent answering sat in a
  bar of its own below the title, carrying one word.
- 👆 **Every icon control in the product answers the pointer**, not only the ones
  on the four pages this was first noticed on.
- 📋 **The Add menu in the workflow editor keeps the two LLM kinds together.** Its
  order came from the order somebody had declared the labels in.
- 💡 **The small square at the end of a row lights up under the pointer, and does
  it the same way on every page.** It had been declared sixteen times in sixteen
  stylesheets, four ways.
- ❓ **A dialog explains itself behind the (?) beside its title**, rather than in
  a paragraph above the form.
- ⌨ **Typing quickly in a code editor no longer scrambles what you typed.** The
  editor wrote stale text back into the document a keystroke later, and what was
  saved was the scrambled text.
- 💾 **An editor no longer asks about unsaved work on a function nobody touched.**
  The guard fired by counting renders and the rewrite happened on a later one; it
  is asked by value now.
- 🔤 **The workflow list's first column no longer says "Template name"** on a list
  of workflows.
- ✏ **A line in the workflow editor accepts extra bend points even when it
  carries a label.** The label sat on the first point as its only handle,
  covering the part of the line you would aim at.
- 〰 **The line from a session node is drawn as a dependency**, dashed, like every
  other line that says "this uses that" rather than "this runs next".
- ✖ **The editor's side panel can be closed from its top-right corner.** The only
  way out was at the bottom, past the whole form.
- 🗄 **A workspace can be deleted on SQLite, which is what the one-container image
  runs.** Any workspace holding an action that calls a function could not be
  deleted at all; Postgres was never affected.
- 🖌 **Twenty-seven backgrounds across the interface were painted with a colour
  that does not exist.** They were transparent, showing whatever happened to be
  behind them.
- 📡 **A list that could not be fetched no longer says the workspace is empty.**
  Pickers and grant lists turned a failed request into "there are none yet".
- 🖼 **Pictures sent to an Anthropic model now arrive.** They did not: a turn
  carrying a screenshot reached the model as words alone, and one that genuinely
  cannot be carried now fails naming the format.
- 🚢 **The one-container image works on whatever port you publish it on.** nginx
  forwarded the Host header with the port removed, so every ordinary call was
  treated as cross-origin and refused.
- 🛡 **The proxy rules now cover the calls that were going round them.** A host
  was resolved here before the rules were consulted, and OIDC sign-in and mail
  went round them too; LDAP still cannot be routed, and the page says so.
- 🧵 **A chat turn no longer disappears because a tool call failed.** The tracker
  tools ran inside the chat's transaction; they open one of their own now, so a
  tool call that succeeded is kept even if the turn is not.
- ✂ **An issue title too long for the tracker is refused in words.** A model now
  hears which field was too long and what the limit is - 200 characters for a
  title, 60 for a label.
- ⏰ **One unpublished workflow stopped every scheduled trigger.** The round that
  fires due triggers ran in one transaction, so a draft rolled back every
  trigger's run and its "last fired"; each fires in its own now.
- 🔒 **Slack went round the proxy rules entirely.** Its SDK's own HTTP client and
  websocket stack dialled out directly; an `HTTPS_PROXY` in the environment no
  longer reaches Slack, so write it as a rule.
- 🩺 **The diagnostics page could not say anything on SQLite** - every check
  answered with an error, because one query asked for a table SQLite does not
  have and was the one part not wrapped in a catch.
- 🩺 **The stored-secrets check could never fail.** It compared a decryption
  against itself, so it reported everything readable whatever the truth was.
- ⛔ **An agent's failures were retried three times by the platform underneath
  it**, including the ones that could only fail the same way.
- 📐 **The foot of the preferences page fell outside what scrolled** when the
  frame was held to the window, taking the last card's clearance with it.
- 👤 **Choosing "No one" for an issue's assignee** now clears it.
- 🧰 **A condition, an action and a webhook can call a plugin's function.** The
  pickers offered them and the save refused them.
- 📂 **Runs of a workflow you removed can be found, and no longer offer a link
  into an error.** The Workflow filter is built from the workflows the runs name,
  with removed ones marked **(removed)**.

## 0.8.0

### ✨ Added

- 📤 **A whole component travels, not only the self-contained ones.** Agents,
  workflows, actions and triggers join the five kinds that could already be
  exported and imported, and what a component points at comes with it.
- 🧠 **An LLM session: a conversation that outlives the run that started it.** An
  agent node given a session key records its turn against it, two runs computing
  the same key share one history, and a session can be continued in chat.
- 🔑 **What cannot travel is asked for on arrival.** A model, a connection and an
  MCP server sit beside a credential, so the import plan reports each one it
  cannot satisfy and asks which row is meant rather than guessing.
- 🔌 **Nothing arrives switched on.** A trigger is created disabled, a workflow
  arrives as a draft, and an agent granted shell or Orknux access says so in the
  plan before anything is written.
- 🔗 **An issue can be linked to another** - relates to, blocks, duplicates -
  showing on both ends, recorded in both histories, and being blocked is news.
- 🗂 **A field of an object can say what it means**, and the sentence reaches the
  model as well as the reader.
- 📊 **A Prometheus endpoint**, at `/actuator/prometheus`, carrying the JVM's own
  measures and three counters worth alerting on: runs started, finished, failed.

### 🐛 Fixed

- 📐 **A page that is still reading says so.** Fifteen settings and editor screens
  drew a whole blank form while the record was still on its way, and anything
  typed into one was overwritten when the real values landed.
- 📜 **A workspace script that ran out of memory is told that, rather than being
  told it ran too many statements.** The two arrive as the same flag and mean
  opposite things to whoever has to fix the script.
- 🧰 **An action or a webhook may call a function a plugin declared.** Both
  refused a choice their own picker had just offered.
- 🎙 **Interrupting voice mode no longer silences it for the rest of the
  session.** Cutting in stopped that sentence and every one after it, with
  nothing said.
- 🖥 **A shell's account is optional, the way it is at `ssh`.** Leaving it out
  means the account the server runs as, and the Shell page names which.
- 🚢 **The all-in-one image stops offering single sign-on it does not run.** It
  claimed LDAP, so it advertised a directory that was never there and reported
  itself degraded for failing to reach one.
- 📜 **A script that threw is not run twice more to watch it throw again.** Every
  script failure was retryable, so a runaway burned its whole budget three times
  over.
- 🔒 **The script sandbox denies the one thing `HostAccess.NONE` leaves open.** It
  still permitted mutable target mappings; no exploit was found.
- 📡 **A workspace that cannot be read says why.** Its settings page kept the
  message inside a form that a failed load never reached.

## 0.7.0

### ✨ Added

- 🧭 **The top bar carries four sections** - AI, Workflow, Workspace and Chat -
  each with its own menu, rather than one Workspace section holding nineteen
  pages. No page changed address.
- 📧 **Issue news is sent by email as well as shown in the bell**, to anybody
  whose account has an address, with a switch in Preferences for turning it off.
- 📤 **Templates: a component published once, for every workspace to take.** An
  Admin page holds exported components and every catalogue page gains **Use
  template**; publishing is an administrator's, using one is anybody's, and a
  template holds a copy and follows nothing.
- 📧 **The tracker can write to you.** The same news from the same desk, sending
  only where `ORKNUX_MAIL_HOST` and `ORKNUX_MAIL_FROM` are set, with the subject
  naming who did what to which issue and never a word of what was written.
- 🔐 **A role can now administer one workspace without administering the
  installation.** An *Administers* tick beside a role on the workspace's settings
  form; nothing installation-wide comes with it.
- 🚢 **`orknux/orknux-one`: the whole thing in one container.** The interface, the
  server and a SQLite file under `/var/lib/orknux`; not a deployment, and its
  Docker Hub page says why rather than leaving it to be discovered.
- 🤖 **The assistant can read and rewrite a workspace tool.** There was no
  tool-reading tool at all, so on a tool's address it looked for a function with
  that id and listed the functions.
- ✏ **A condition is edited on a page of its own**, at `/conditions/new` and
  `/conditions/<id>`, rather than in a modal.
- 📝 **An issue has a History tab**, holding what has happened to it with changes
  made through the MCP tools in the same list; a label changing and an issue
  changing hands were recorded nowhere before.
- 🕸 **A node can be duplicated in the workflow editor**, with `Ctrl+D`, carrying
  its definition, icon and facing and a deep copy of its mappings; edges are not
  copied.
- 🕸 **An edge can be dragged by either end onto another handle.** Reconnecting,
  not bending - dropping onto nothing snaps back, and onto existing wiring is
  refused.
- 🕸 **Open definition opens in the editor's left panel** for triggers, actions
  and conditions, rather than leaving the graph you were editing.
- 🧭 **The browser tab says what is open** - the workflow, the issue, the agent -
  with the product name last.
- 🔗 **A link from a function's external parameters to the workspace's
  variables**, which the hint there described without offering.
- 🔔 **An issue being opened is news.** Filing one told the assignee and nobody
  else, so a finding filed with nobody named wrote into an empty room.

### 🔧 Changed

- 🧭 **The top bar carries the workspace selector, Docs and Admin**, on the right
  beside the account; the selector is on more screens than it was, not fewer.
- 🎨 **Every select is drawn by this application rather than by the operating
  system.** A transparent control is what makes a white menu open over a dark
  page.
- 📐 **The menu's collapse control sits on its first row**, at the column's edge,
  instead of down in the attribution strip below the fold on a laptop screen.
- 🔒 **A workspace id you cannot see now reads as one that does not exist.** The
  last of what 0.5.0 closed; the cost is real, since a mistyped id now reads as
  absent rather than as somebody else's.
- 🎙 **Voice mode moved into the message composer**, beside the microphone, drawn
  as a waveform rather than a speaker.
- 🧭 **Switching workspace keeps your place where that means anything.** A list
  page stays a list page; a page about one particular thing falls back to its
  list.
- 🔗 **The Orknux logo goes to orknux.ai**, in a new tab. It led nowhere before.
- 🖱 **Clicking a model provider row opens it**, rather than only the settings
  icon; the models on that page open the same way.
- 🗂 **A new variable stays where it was added** until the page is opened again,
  rather than sorting itself away from under the cursor.
- 📖 **The Docker Hub description is published by CI**, from `DOCKERHUB.md`, and
  the build fails if the file passes the 25,000 bytes Docker Hub accepts.

### 🐛 Fixed

- 🎙 **Voice mode releases the microphone on every way out.** It held one open
  stream per entry, and the composer's own button only ever released on stop.
- 💬 **The chat's composer sits at the bottom of the frame again.** The room every
  page was given for the floating assistant launcher was room the one page whose
  last element touches the bottom did not want.
- 📐 **The two collapse controls are the same control.** They collapsed by
  different gestures, with different icons, and the catalogue column was 80
  pixels shorter than the menu.
- 📐 **The last button on a page is no longer under the floating assistant
  launcher.** On a settings page that is the Danger Zone's delete button, which
  it overlapped by 31 by 19 pixels.
- 🔐 **The Admin button is offered only to administrators.** The flag that decides
  defaulted to on, and eleven pages never set it.
- 📐 **The preferences page could not reach its own end.** 611 pixels of it were
  unreachable at 900 tall, so every shortcut below Turn Node could not be seen or
  rebound.
- 🕸 **The trigger picker's list was clipped to 68 pixels** in a 240 pixel field,
  cutting option names to `Se` and `Sla`.
- 🤖 **Accepting a suggested function change dropped half of it.** The accept sent
  only the source, so a new parameter in the declaration was lost and the save
  was refused; the parameter list is read from the code being accepted now.
- 🗂 **A variable created while a page was open is now offered by it**, rather
  than after a reload.
- 🕸 **The node panel could not be scrolled.** A dialog is `height: fit-content`
  in the browser's own stylesheet, which over-constrained a panel that sets both
  top and bottom.

## 0.6.0

### ✨ Added

- 🗄 **Orknux runs on SQLite**, as well as Postgres, decided by `ORKNUX_DB_URL`
  and nothing else - no second container and a backup that is one file. Postgres
  is still what a deployment should use, and the README lists what differs.
- 🖥 **An agent can run commands on a machine.** An Admin → Shell page holds SSH
  targets; a granted agent opens a session, gets a working directory of its own
  and loses it on close, and what contains this is the machine rather than
  anything in the application.
- 🛡 **One address can be reached through a proxy without sending everything
  through it.** An Admin → Networking page holds ordered rules matched against
  the request URL, covering every outbound call except mail.
- 🔐 **An installation can be entered without a directory.**
  `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` and `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` create
  one internal administrator at startup; it only ever creates, and a password in
  a variable is a way in to be undone rather than a credential to keep.

### 🔧 Changed

- 🗄 **The migrations moved into a directory per database**, `db/migration/postgresql`
  with the SQLite baseline alongside. Nothing changes for an existing
  installation; it matters to anyone writing one, since a schema change is now
  written twice.
- 🔒 **An id that is not yours reads as one that is not real, whether you read it
  or change it.** Sixty-five mutations now throw exactly what the absent case
  threw, so a client that treated the refusal as "ask an administrator" will see
  "not found" instead.

### 🐛 Fixed

- 🔒 **The Entra ID token endpoint is checked before the client secret is posted
  to it.** It was the last outbound call not asked where it was going, and it is
  a POST carrying the application's client secret.

## 0.5.0

### ✨ Added

- 📝 **An issue can be moved to another workspace**, administrators only, taking
  its comments, labels, links, observers and files but not its number - so the
  old address stops working, and the move is written into the issue, both
  workspaces' activity and everybody following it.
- 🔔 **Observers on an issue**, below its labels: anybody in a workspace can watch
  one in a press, an administrator can put somebody else on the list, and a model
  cannot observe because it has nowhere to read its news.
- 🤖 **`orknux_open_issue` takes observers**, so an assistant can put a finding in
  front of somebody without assigning them the work.
- 📧 **Forgotten passwords can be reset by mail.** A link that works once, stops
  after an hour and signs the account out everywhere; internal accounts only, and
  it needs `ORKNUX_MAIL_HOST`, `ORKNUX_MAIL_FROM` and `ORKNUX_BASE_URL`.
- 🔗 **Links can be added while an issue is being written**, rather than only
  after it exists.
- 📋 **Sorting a list of issues by last comment**, which is not the same as by
  last change: closing, relabelling and assigning all move the change time.
- 👤 **An address on a user**, taken from the directory or the OIDC provider at
  sign-in and refreshed from there until somebody types their own.
- ▶ **A run says which run it was started from**, for both kinds of re-run, and
  links back to it.
- ▶ **Run, in the workflow editor**, which starts the graph in front of you and
  takes you to the run it made; it uses the draft, deliberately.

### 🔧 Changed

- ▶ **A workflow switched off now stays off.** Nothing that starts a run ever read
  the switch, so on upgrade a workflow left off some time ago and quietly running
  anyway will stop the moment this is installed.
- 🔐 **An OIDC bearer token is now checked against who it was issued for, and this
  one can lock people out.** Only the issuer was checked; a token must now name
  this installation in `aud`, or `ORKNUX_OIDC_AUDIENCES` must say what the tokens
  actually carry.

### 🐛 Fixed

- 🔔 **Waiting for tracker news no longer costs a thread.** `orknux_news` sat on
  the request thread for up to five minutes, so a couple of hundred such calls
  took the server off the air; `ORKNUX_ASYNC_REQUEST_TIMEOUT` bounds how long a
  request answered this way may stay open.
- 🔒 **A webhook body has a size limit.** An anonymous caller chose the size of
  five copies of it; `ORKNUX_WEBHOOK_MAX_BODY_SIZE` defaults to `1MB` and refuses
  anything larger with 413 before it is read.
- 🔒 **Signing in can no longer be tried without limit.** Wrong passwords are
  counted per username and per address, with a pause that doubles and then stops;
  nothing locks, and a successful sign-in clears the count.
- 📎 **The files sent into a chat are as private as the chat.** Attachments were
  checked against the workspace, so anybody who could see it could list and
  download somebody else's.
- 🔒 **A refusal no longer names the workspace it is protecting.** "You do not
  have access to workspace "frontend"" answered a question nobody may ask, and
  GraphQL reports errors with a 200, so trying every id was a script.
- 💬 **The `@` mention list appeared at the bottom of the whole editor box**
  rather than at the mention, which in the comment box put it off the window with
  only two names reachable.
- 📋 **The sort control's options did not name the field they sorted on** -
  "Newest" sorted by number and read as a date, so a correct order looked wrong
  against the times beside it.

## 0.4.0

### ✨ Added

- 🔗 **Links on an issue.** An address gets a row of its own rather than a
  sentence in the description, a GitHub one shown as `owner/repo#123` worked out
  from the address alone; only `http` and `https` are kept.
- ▶ **A run can be started again from one of its steps**, rather than only from
  the beginning: the steps ahead appear as what they were, marked carried over,
  and it refuses where that cannot honestly be done.
- 📦 **A compose file that brings up a whole Orknux**, at `deploy/compose.yaml` -
  the published images, the database, a directory and Temporal on one published
  port.

### 🐛 Fixed

- 📝 **An issue in a list read "opened by alice - 18 minutes ago" beside the time
  it last changed**, so down a correctly ordered list the times ran in no order
  at all. It shows when the issue was opened.
- 🔒 **Spring Boot 4.0.7**, which closes an unauthenticated LDAP bind on exactly
  the mechanism this product signs people in with, and a Tomcat request smuggling
  hole across the boundary people authenticate over.
- 📎 **The volume example for the server image mounted a path the image does not
  contain**, so it was created owned by root and no attachment could be written;
  move the volume to `/home/orknux` and set `ORKNUX_ATTACHMENTS_LOCATION`.

## 0.3.0

### ✨ Added

- 📝 **An issue tracker in every workspace.** Open, In progress and Closed, one
  search across titles, descriptions and labels, filters that live in the
  address, and issues carrying comments, `@` mentions and pasted screenshots.
- 🔔 **A notification bell**, beside the account menu, reporting issues you filed
  changing state, comments on issues that concern you, and your name written in
  one, across every workspace you can see.
- 📧 **Mail.** An SMTP connection holds the server details, its password
  encrypted like every other credential, and a Send Email action takes a
  recipient, subject, body, cc and reply-to.
- 🧰 **The tracker over MCP**: an assistant can list, read, open, comment on,
  label and close issues, and wait on a feed of what has happened.
- 🔐 **Internal users with passwords, and `orkx_` access tokens** for reaching the
  API and the MCP endpoint as a named person.
- 🕸 **A workflow editor that can be undone.** Undo and redo, rebindable
  keystrokes, pickers you type into, components created beside the canvas rather
  than over it, and nodes that can be turned.
- 🎙 **Voice mode says what it is doing** - listening, thinking or speaking - and
  can be interrupted mid-answer.

### 🔧 Changed

- ▶ **Publishing means something now, and this is the one to read twice.** A
  trigger, a schedule or the API runs the published copy; a workflow that was
  never published, and was running because nothing checked, will stop - publish
  it once.
- ▶ **Re-running a run repeats the graph that ran**, rather than whatever is being
  edited now.
- 🔗 **A link into the interface no longer opens in a new tab**; a modified click
  still does, because these are real links now.

### 🐛 Fixed

- 🔐 **Deleting a role a workspace depended on removed it silently**, taking
  access with it. It is refused, and names the workspaces in the way.
- 🔐 **The admin settings page was offered to anybody signed in.** Administrators
  only, as every other admin page already was.
- ✏ **"No errors", "Formatting valid" and "Schema compile healthy" were what those
  editors opened with**, before anything had been checked, and survived every edit
  after; they start as "not checked yet" now.
- 📎 **An oversized upload, or one to an installation with attachments switched
  off, answered 500 and "Internal Server Error".** Both now say what happened.
- 🕸 **The workflow editor's mapping labels could not be dragged** - the node
  beneath took the press - and a label that did move left its line behind.
- 🤖 **An agent node's output was named `reply` by a placeholder and by nothing
  else**, so the node declared nothing and nothing downstream could point at it.
- 📐 **Long pages grew instead of scrolling**, pushing the attribution bar off the
  bottom.
- 📋 **Sorting a list of issues by title failed outright**, because the query
  joined the labels and Postgres will not order a distinct select by an
  expression outside its select list.

## 0.2.0 and earlier

Not written down. The changelog starts here, which is the honest place to start
it: reconstructing releases from their commits afterwards produces something
that reads like a changelog and is nobody's account of what happened.

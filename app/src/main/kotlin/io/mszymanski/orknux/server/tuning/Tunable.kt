package io.mszymanski.orknux.server.tuning

import io.mszymanski.orknux.connector.ConnectionKnob
import io.mszymanski.orknux.workflow.ExecutionKnob

/**
 * Where on Admin -> Settings a [Tunable] is drawn, in the order the page draws them.
 */
enum class TuningSection(val title: String) {
    MODELS("Model calls"),
    AGENTS("Agents"),
    CODE("Functions and HTTP"),
    COMMANDS("Commands from the chat"),
    MEDIA("Pictures and speech"),
    WORKFLOWS("Workflows and triggers"),
    PLUGINS("Plugins and libraries"),
}

/** What a [Tunable]'s number counts, as the box beside it says. */
enum class TuningUnit(val said: String) {
    SECONDS("seconds"),
    MINUTES("minutes"),
    HOURS("hours"),
    TIMES("times"),
    ITEMS("items"),
    CHARACTERS("characters"),
    LINES("lines"),
    MB("MB"),
    PIXELS("pixels"),
    PERCENT("percent"),
    TOKENS("tokens"),
    HERTZ("Hz"),
    KILOBITS_PER_SECOND("kbit/s"),
    MILLISECONDS("ms"),
}

/**
 * Every behavioural number the server has that is not already a setting of its
 * own, as one list. Issue #453.
 *
 * ---------------------------------------------------------------------------
 * Why one list and not a setting each
 *
 * Every setting before this one was written out by hand: a name in
 * `SettingNames`, a getter and a setter in `InstallationSettings`, two fields
 * and a mutation in the API, two fields in the schema, a box on the page, an
 * exception for out of range and a line in its resolver. Nine places for one
 * number, which is why numbers kept being written into the code instead - the
 * cost of doing it right was a morning, and the cost of `const val` was a line.
 *
 * Here it is one entry. The key is what the row in `installation_setting` is
 * called; [fallback] is where a fresh installation starts, which the
 * environment variable named in `application.yml` may move; [least] and [most]
 * are what an administrator may choose; and the label and hint are what the
 * page says. `TunablesTest` holds the list to its other halves: an
 * `application.yml` line per entry, a README row per variable, and every knob a
 * module declares appearing here exactly once.
 *
 * A number that is a format rather than a behaviour - a column's length, a
 * protocol's constant, how many bytes a key is - is not a tunable, and is left
 * in the code where it belongs.
 *
 * ---------------------------------------------------------------------------
 * Labels are one line
 *
 * The page draws [label] beside the box and [hint] behind the question mark,
 * and both are English: the same sentence reaches the audit log, which stays
 * English for ever.
 */
enum class Tunable(
    val key: String,
    val fallback: Int,
    val least: Int,
    val most: Int,
    val section: TuningSection,
    val unit: TuningUnit,
    val label: String,
    val hint: String,
) {

    /* ---- Model calls (the connection module's knobs) ---- */

    MODEL_RETRY_AFTER_LONGEST_SECONDS(
        ConnectionKnob.RETRY_AFTER_LONGEST_SECONDS, TuningSection.MODELS, TuningUnit.SECONDS,
        "The longest Retry-After a model call waits out",
        "A provider answering 429 with a Retry-After is waited out on the spot, below any node's own retry policy, when the model accepts it. A longer wait than this is handed back to the node instead of holding a worker. Zero never waits.",
    ),
    MODEL_RETRY_AFTER_ATTEMPTS(
        ConnectionKnob.RETRY_AFTER_ATTEMPTS, TuningSection.MODELS, TuningUnit.TIMES,
        "How many Retry-Afters one model call waits out",
        "After this many, the call fails and the node's retry policy decides what happens next.",
    ),
    MODEL_THROTTLE_LONGEST_DELAY_SECONDS(
        ConnectionKnob.THROTTLE_LONGEST_DELAY_SECONDS, TuningSection.MODELS, TuningUnit.SECONDS,
        "The longest a throttled model call is held",
        "A model or provider with a token rate holds calls that would overshoot it. The hold grows with how far over the rate the window is, and stops growing here, so an overshoot is a delay and not an outage.",
    ),
    MODEL_IMAGE_FETCH_SECONDS(
        ConnectionKnob.IMAGE_FETCH_SECONDS, TuningSection.MODELS, TuningUnit.SECONDS,
        "How long fetching a drawn picture may take",
        "Some image models answer with a link rather than the picture; this is how long collecting it from that link may take.",
    ),
    MODEL_SPEED_CALLS(
        "model.speed.calls", 50, 1, 1000, TuningSection.MODELS, TuningUnit.ITEMS,
        "How many recent calls a model's speed is averaged over",
        "A model's page averages the speeds its provider reported over this many of its newest calls, and a session's page lists this many of its newest calls with theirs. Only providers that report timings, such as llama.cpp, have any.",
    ),

    /* ---- Agents ---- */

    AGENT_WAIT_DEFAULT_SECONDS(
        "agent.wait.default.seconds", 30, 1, 3600, TuningSection.AGENTS, TuningUnit.SECONDS,
        "How long agent_wait waits when not told",
        "An agent that has asked other agents may wait for them with agent_wait; this is how long, when it does not say. It returns as soon as one of them finishes.",
    ),
    AGENT_WAIT_LONGEST_SECONDS(
        "agent.wait.longest.seconds", 300, 1, 3600, TuningSection.AGENTS, TuningUnit.SECONDS,
        "The longest agent_wait may wait",
        "A model that may sleep for an hour will. An agent asking for longer is given this.",
    ),
    TOOLS_LOADED_AT_ONCE(
        "tools.loaded.at.once", 8, 1, 50, TuningSection.AGENTS, TuningUnit.ITEMS,
        "How many findable tools one search loads",
        "An agent with more tools than its briefing names finds the rest with tool_find. This is how many of the matches one search hands it at once; the rest are counted and a narrower search finds them.",
    ),
    TOOL_SEARCHES_AT_ONCE(
        "tools.searches.at.once", 8, 1, 50, TuningSection.AGENTS, TuningUnit.ITEMS,
        "How many names or searches one tool_find call may carry",
        "What an agent asks for beyond this in one call is ignored.",
    ),
    /*
     * A share of the model's window rather than a size, because the windows an
     * installation runs differ by an order of magnitude. Chat 981: one svg_read
     * answer of 523,768 characters against a 262,144-token window. TurnWindow.
     */
    TOOL_RESULT_WINDOW_PERCENT(
        "tool.result.window.percent", 10, 1, 90, TuningSection.AGENTS, TuningUnit.PERCENT,
        "How much of the model's window one tool result may take",
        "A longer answer is cut to this share of the model's context window, counted at the characters a token below, and the model is told how long it was and how to read the rest. Applies to models whose window is known.",
    ),
    TURN_WINDOW_CHARS_PER_TOKEN(
        "turn.window.chars.per.token", 1, 1, 8, TuningSection.AGENTS, TuningUnit.CHARACTERS,
        "How many characters a token is counted as against the window",
        "What turns a share of the model's window into a length to cut at. One never lets a result past its share; markup and numbers come to barely more than one a token, prose to about four.",
    ),
    TURN_WINDOW_RESERVE_PERCENT(
        "turn.window.reserve.percent", 25, 5, 75, TuningSection.AGENTS, TuningUnit.PERCENT,
        "How much of the model's window a shrunk turn leaves free",
        "A turn the provider refuses as too long is summarised and cut until it fits the window less this share, which is left for the tool list, the instructions and the answer.",
    ),
    SCRATCHPADS_MOST(
        "scratchpad.pads", 50, 1, 1000, TuningSection.AGENTS, TuningUnit.ITEMS,
        "How many scratchpads one session may open",
        "Scratchpads are an agent's working files; what they may hold in bytes is set under Scratchpads.",
    ),
    SHEET_ROWS_MOST(
        "sheet.rows.most", 200, 1, 10_000, TuningSection.AGENTS, TuningUnit.ITEMS,
        "How many rows one read of a CSV or a workbook answers",
        "An agent reading an attached sheet is handed this many rows at a time and told where the rest begin, so a large file is read in pages rather than all at once.",
    ),
    SHEET_ANSWER_CHARACTERS(
        "sheet.answer.characters", 60_000, 1_000, 10_000_000, TuningSection.AGENTS, TuningUnit.CHARACTERS,
        "How much of a sheet one read hands a model",
        "A page of rows stops early once its cells come to this many characters. A model pays for every character it is handed.",
    ),
    SHEET_UNPACKED_MB(
        "sheet.unpacked.mb", 100, 1, 2_000, TuningSection.AGENTS, TuningUnit.MB,
        "The most one part of a workbook may unpack to",
        "An .xlsx or an .ods is a zip, and a small one can unpack to gigabytes. A workbook with a part larger than this is refused rather than read.",
    ),
    /*
     * xml_query and svg_measure: questions about a large XML or SVG file whose
     * answers go into the turn instead of the file. Chat 981, a KiCad copper
     * layer read whole. See XmlCapability and SvgGeometry.
     */
    XML_MATCHES_MOST(
        "xml.matches.most", 50, 1, 5_000, TuningSection.AGENTS, TuningUnit.ITEMS,
        "How many matches one XML or SVG question lists",
        "An agent querying an XML file or measuring an SVG is handed this many nodes or rows at a time, with the total counted, and pages through the rest.",
    ),
    XML_FILE_MB(
        "xml.file.mb", 20, 1, 500, TuningSection.AGENTS, TuningUnit.MB,
        "The largest XML or SVG file an agent may query or measure",
        "A larger file is refused rather than parsed. Parsing holds the whole document in memory, several times its size.",
    ),
    XML_ATTRIBUTE_CHARACTERS(
        "xml.attribute.characters", 200, 20, 100_000, TuningSection.AGENTS, TuningUnit.CHARACTERS,
        "How much of a long attribute or text an XML query shows",
        "A longer value, such as an SVG path's d, is cut to this with its length said, unless the agent asks for it in full.",
    ),
    SVG_GROUP_GAP_PERCENT(
        "svg.group.gap.percent", 100, 1, 10_000, TuningSection.AGENTS, TuningUnit.PERCENT,
        "How close SVG shapes must be to be grouped, when the agent gives no gap",
        "Grouping shapes by proximity, such as pads into footprints, joins two whose edges are within this share of the shapes' median nearest-neighbour pitch.",
    ),
    PDF_SNIPPET_CHARACTERS(
        "pdf.snippet.characters", 200, 20, 5_000, TuningSection.AGENTS, TuningUnit.CHARACTERS,
        "How much text around each PDF search match is shown",
        "A search of a PDF answers the page of each match and this much of the text around it.",
    ),
    PDF_MATCHES_MOST(
        "pdf.matches.most", 20, 1, 1_000, TuningSection.AGENTS, TuningUnit.ITEMS,
        "How many matches one PDF search lists",
        "The total is always counted; past this the agent is told how many more there are.",
    ),
    PDF_TEXT_DOCUMENTS(
        "pdf.text.documents", 16, 0, 1_000, TuningSection.AGENTS, TuningUnit.ITEMS,
        "How many PDFs' text is kept for searching again",
        "The text of a searched PDF is kept in memory by its content, per session, so a second search does not read the file again. The least recently searched goes first; 0 keeps none.",
    ),
    /*
     * A time and not a length, because the patterns that never finish are
     * short: (a|a?){60}b is eleven characters. See AuthoredRegex.
     */
    REGEX_MATCH_SECONDS(
        "regex.match.seconds", 2, 1, 60, TuningSection.AGENTS, TuningUnit.SECONDS,
        "How long one regular expression somebody wrote may take to match",
        "Watcher conditions, scratchpad searches, workflow conditions and HTTP tool rules run patterns this server did not write. One still matching after this is stopped and counts as no match; a watcher it belongs to ends and tells its agent why.",
    ),
    WATCHER_MATCH_CHARACTERS(
        "watcher.match.characters", 1_000_000, 10_000, 10_000_000, TuningSection.AGENTS, TuningUnit.CHARACTERS,
        "The longest result a watcher's regular expression is held against",
        "A watcher whose tool answers with more than this ends, and tells its agent to point tool_result_path at the part that matters.",
    ),
    WATCHER_RESULT_CHARACTERS(
        "watcher.result.characters", 10_000, 500, 1_000_000, TuningSection.AGENTS, TuningUnit.CHARACTERS,
        "How much of a watcher's result is kept and shown to its agent",
        "Each check keeps the beginning of what the tool returned, and the message that wakes the agent carries the same; the rest is cut, and the message says how much. The condition is still held against all of it.",
    ),

    /* ---- Functions and HTTP ---- */

    HTTP_TIMEOUT_SECONDS(
        "http.timeout.seconds", 30, 1, 600, TuningSection.CODE, TuningUnit.SECONDS,
        "How long one HTTP request from a tool may take",
        "The http_request and download tools an agent calls. A function's own orknux.http is bounded by the function's timeout instead.",
    ),
    HTTP_BODY_CHARACTERS(
        "http.body.characters", 200_000, 1_000, 10_000_000, TuningSection.CODE, TuningUnit.CHARACTERS,
        "How much of an HTTP answer a tool hands a model",
        "The rest is cut. A model pays for every character it is handed, so this is a cost as much as a size.",
    ),
    HTTP_DOWNLOAD_MB(
        "http.download.mb", 5, 1, 500, TuningSection.CODE, TuningUnit.MB,
        "The largest file a tool may download",
        "What the download tool fetches is kept in the session's store; anything larger is refused before it is kept.",
    ),
    SEARCH_RESULTS_MOST(
        "search.results.most", 20, 1, 100, TuningSection.CODE, TuningUnit.ITEMS,
        "The most results one web search may ask for",
        "An agent asking for more is given this many.",
    ),
    SEARCH_TIMEOUT_SECONDS(
        "search.timeout.seconds", 20, 1, 300, TuningSection.CODE, TuningUnit.SECONDS,
        "How long one web search may take",
        "Counted for the search provider's answer, not for reading the pages it names.",
    ),
    SCRIPT_LOG_LINES(
        ExecutionKnob.SCRIPT_LOG_LINES, TuningSection.CODE, TuningUnit.LINES,
        "How many lines one function call may log back",
        "The lines are handed back with the answer and shown on the run; past this many they still reach the server's log but no longer travel with the result.",
    ),

    /* ---- Commands from the chat ---- */

    COMMAND_TIMEOUT_SECONDS(
        "command.timeout.seconds", 600, 1, 86_400, TuningSection.COMMANDS, TuningUnit.SECONDS,
        "How long a command run from the chat may take",
        "A command run with the Run button on a chat answer is stopped after this long. An elevated one counts the time the administrator prompt waits for an answer. Its output stays readable for as long again after it ends.",
    ),
    COMMAND_OUTPUT_CHARACTERS(
        "command.output.characters", 200_000, 1_000, 10_000_000, TuningSection.COMMANDS, TuningUnit.CHARACTERS,
        "How much of a command's output is kept",
        "What a command run from the chat prints past this is dropped, and the page says it was cut. The command runs on regardless.",
    ),
    /* ---- Pictures and speech ---- */

    STEP_PICTURES_MOST(
        "pictures.per.run", 20, 0, 1000, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many pictures one workflow run or task may draw",
        "Drawing is the most expensive call there is. Zero refuses every drawing.",
    ),
    STEP_SPEECHES_MOST(
        "speeches.per.run", 20, 0, 1000, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many times one workflow run may speak",
        "Each is a call to a speech model. Zero refuses every one.",
    ),
    /*
     * Its own limit beside the attachment cap because the attachment is kept
     * once and this is sent: as base64, a third larger, on every round of the
     * turn it came with. Twenty is under the attachment cap's default of
     * twenty-five, so a fresh installation refuses no sound it accepted as a
     * file for any reason but this one.
     */
    CHAT_AUDIO_MAX_MB(
        "chat.audio.max.mb", 20, 1, 1024, TuningSection.MEDIA, TuningUnit.MB,
        "The largest sound a chat message may send to a model",
        "An mp3, wav or flac goes to a model that takes audio as itself, in base64 on every round of its turn; a larger one is refused before the model is asked.",
    ),
    /*
     * Counted in notes rather than bytes because the notes are what the answer
     * is made of: a minute of piano is a thousand of them, and a model pays for
     * every line.
     */
    MIDI_READ_NOTES(
        "midi.read.notes", 2000, 10, 100_000, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many notes midi_read lists from one file",
        "Past this many the answer stops listing them and says how many more there were; the tracks, tempo and time signature are still given whole.",
    ),
    /*
     * Counted in the length of the music rather than the time it takes,
     * because the length is known before anything is synthesised: a file
     * longer than this is refused up front, not after a minute of CPU.
     */
    MIDI_RENDER_LONGEST_SECONDS(
        "midi.render.longest.seconds", 300, 1, 3600, TuningSection.MEDIA, TuningUnit.SECONDS,
        "The longest piece midi_render turns into sound",
        "A MIDI file that plays for longer is refused before anything is synthesised; the sound it would make is kept whole in the session, so this also bounds its size.",
    ),
    /*
     * At most 48 kHz because the MP3 the same render may be asked for carries
     * nothing higher, and a rate one format refuses is a setting that works
     * for FLAC and fails for MP3.
     */
    MIDI_RENDER_SAMPLE_RATE(
        "midi.render.sample.rate", 44100, 8000, 48000, TuningSection.MEDIA, TuningUnit.HERTZ,
        "The sample rate midi_render synthesises at",
        "Every format is rendered at this rate, in stereo: 44100 is a CD's, 48000 a video's, and lower makes smaller files that sound duller.",
    ),
    MIDI_RENDER_MP3_KBITS(
        "midi.render.mp3.kbits", 192, 32, 320, TuningSection.MEDIA, TuningUnit.KILOBITS_PER_SECOND,
        "The bitrate of an MP3 midi_render encodes",
        "Higher is closer to the FLAC and larger; FLAC and WAV are lossless and take no bitrate.",
    ),
    /*
     * Counted in elements because that is what a model drawing one adds, and
     * what the editor draws one by one: a wireframe the browser can no longer
     * move around in is lost work, however few bytes it is.
     */
    WIREFRAME_ELEMENTS_MOST(
        "wireframe.elements.most", 2000, 10, 100_000, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many elements one wireframe may hold",
        "A save, from the editor or from a model drawing in it, that would leave more is refused and says how many there were.",
    ),
    /*
     * Counted in characters because that is what a model pays for: the page
     * orknux_wireframe_html hands back is read in full, and a drawing of
     * thousands of elements is a page of hundreds of thousands. Past this the
     * answer carries the link to the page and not the page.
     */
    WIREFRAME_HTML_INLINE_CHARACTERS(
        "wireframe.html.inline.characters", 100_000, 1_000, 5_000_000, TuningSection.MEDIA, TuningUnit.CHARACTERS,
        "How long a wireframe's HTML may be and still be handed to a model",
        "A longer page is answered with the link to download it instead of the page itself.",
    ),
    /*
     * Counted in pixels because that is what a chat has room for: the picture
     * of a wireframe an agent drew is put in the conversation beside its link,
     * drawn at twice its own size for sharpness and never wider than this.
     */
    WIREFRAME_PICTURE_PIXELS(
        "wireframe.picture.pixels", 1600, 200, 4096, TuningSection.MEDIA, TuningUnit.PIXELS,
        "How wide the picture of a wireframe an agent drew may be",
        "The picture put in a chat beside the wireframe's link; a wider drawing is scaled down to this.",
    ),
    /*
     * Diagrams, in Explore. The notes are where a diagram keeps what its boxes
     * cannot say, and are what an agent reads a node for - long enough for a
     * paragraph or two by default, held to a number so one node cannot hold a
     * manual. Checked on every save, from the editor and the tools alike.
     */
    DIAGRAM_NOTE_CHARACTERS(
        "diagram.note.characters", 5_000, 100, 100_000, TuningSection.MEDIA, TuningUnit.CHARACTERS,
        "How long a diagram node's notes may be",
        "A save, from the editor or from an agent, with longer notes on a node is refused and names the node.",
    ),
    DIAGRAM_DESCRIPTION_CHARACTERS(
        "diagram.description.characters", 2_000, 100, 20_000, TuningSection.MEDIA, TuningUnit.CHARACTERS,
        "How long a diagram's description may be",
        "A longer one is refused, from the editor and from an agent alike.",
    ),
    DIAGRAM_ITEMS_MOST(
        "diagram.items.most", 2_000, 10, 100_000, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many nodes and connections one diagram may hold",
        "A save that would leave more is refused and says how many there were.",
    ),
    DIAGRAM_SEARCH_HITS(
        "diagram.search.hits", 20, 1, 500, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many hits a diagram search answers with",
        "The best matches first; an agent searching for more narrows its words.",
    ),
    /*
     * What orknux_diagram shows of each node's notes. The whole diagram is read
     * at once, and a hundred nodes with full notes is a book; the node tool
     * gives one node's notes whole.
     */
    DIAGRAM_NOTES_SHOWN_CHARACTERS(
        "diagram.notes.shown.characters", 200, 0, 100_000, TuningSection.MEDIA, TuningUnit.CHARACTERS,
        "How much of each node's notes a whole diagram read shows",
        "Notes past this are cut and marked; reading one node gives its notes whole.",
    ),
    DIAGRAM_PICTURE_PIXELS(
        "diagram.picture.pixels", 1600, 200, 4096, TuningSection.MEDIA, TuningUnit.PIXELS,
        "How wide the picture of a diagram may be",
        "The picture put in a chat beside the diagram's link, and the PNG and PDF an agent exports; a wider drawing is scaled down to this.",
    ),

    /*
     * Design's Documents page. Counted in the characters of the stored content
     * because that is what is sent whole on every save and read whole by every
     * export: a document past this is one the editor and a PDF take too long over.
     */
    DOCUMENT_CHARACTERS_MOST(
        "document.characters.most", 2_000_000, 10_000, 50_000_000, TuningSection.MEDIA, TuningUnit.CHARACTERS,
        "How long one document's stored content may be",
        "A save, from the editor or from a model writing in it, that would make it longer is refused and says how long it was.",
    ),
    /*
     * Counted in characters because that is what a model pays for: the
     * Markdown orknux_document hands back is read in full. Past this the
     * answer carries the first part and says the rest is there.
     */
    DOCUMENT_MARKDOWN_INLINE_CHARACTERS(
        "document.markdown.inline.characters", 100_000, 1_000, 5_000_000, TuningSection.MEDIA, TuningUnit.CHARACTERS,
        "How much of a document's Markdown is handed to a model",
        "A longer document is handed over cut at this length, with a note saying how long it is.",
    ),
    /*
     * Counted in pixels because a drawing in SVG has no size of its own worth
     * keeping: it is laid out as pixels this wide to be a picture in a page.
     */
    DOCUMENT_SVG_PIXELS(
        "document.svg.pixels", 1600, 200, 4096, TuningSection.MEDIA, TuningUnit.PIXELS,
        "How wide an SVG put in a document is drawn",
        "A drawing an agent hands over as SVG is kept as a picture this many pixels wide.",
    ),
    /*
     * Counted in characters because that is what the completion model reads on
     * every pause in the typing: the text before the caret, cut to this, and a
     * quarter as much after it.
     */
    DOCUMENT_COMPLETION_CONTEXT_CHARACTERS(
        "document.completion.context.characters", 2_000, 100, 50_000, TuningSection.MEDIA, TuningUnit.CHARACTERS,
        "How much of a document the inline suggestion reads",
        "The text before the caret is cut to this many characters, and the text after it to a quarter as many.",
    ),
    /*
     * The document editor's grammar check. Asked of the paragraphs changed
     * once the typing has paused this long, and never of more text than the
     * second at once: LanguageTool's time grows with the text.
     */
    GRAMMAR_PAUSE_MILLIS(
        "grammar.pause.millis", 800, 100, 10_000, TuningSection.MEDIA, TuningUnit.MILLISECONDS,
        "How long the typing pauses before grammar is checked",
        "Only the paragraphs changed since the last check are sent.",
    ),
    GRAMMAR_TEXT_MOST(
        "grammar.text.most", 20_000, 1_000, 200_000, TuningSection.MEDIA, TuningUnit.CHARACTERS,
        "How much text one grammar check takes",
        "A longer paragraph is not checked; the editor sends paragraphs one request each.",
    ),
    DOCUMENT_SEARCH_RESULTS(
        "document.search.results", 20, 1, 200, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many documents a search answers",
        "Quick actions and orknux_search_documents list at most this many, names that match first.",
    ),
    DOCUMENT_SNIPPET_CHARACTERS(
        "document.snippet.characters", 160, 40, 1_000, TuningSection.MEDIA, TuningUnit.CHARACTERS,
        "How much of a document a search shows around a match",
        "Also how much of a document's start the Documents list shows under its name.",
    ),

    /*
     * Design's Videos page. Issue #658. A video is a job its backend runs for
     * minutes, so how many run at once, how often each is asked about, how
     * long it may take and how large a file is kept are the four numbers that
     * decide what it costs.
     */
    VIDEOS_AT_ONCE(
        "videos.at.once", 1, 1, 16, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many videos are made at once",
        "Videos asked for in Design wait their turn past this many. A local video server makes one at a time however many it is sent.",
    ),
    VIDEO_POLL_SECONDS(
        "video.poll.seconds", 5, 1, 300, TuningSection.MEDIA, TuningUnit.SECONDS,
        "How often a video being made is asked about",
        "Each ask is one request to the video's server; the page's progress moves this often.",
    ),
    VIDEO_LONGEST_MINUTES(
        "video.longest.minutes", 120, 1, 1440, TuningSection.MEDIA, TuningUnit.MINUTES,
        "How long one video may take",
        "A video still not made after this is given up on, and said to have failed.",
    ),
    VIDEO_MAX_MB(
        "video.max.mb", 200, 1, 4096, TuningSection.MEDIA, TuningUnit.MB,
        "The largest video kept",
        "A video larger than this is not collected from its server, and is said to have failed.",
    ),

    /*
     * A video made larger: its frames go to an upscale model's WebUI a batch at
     * a time, and the result may be no larger than this on its longer side -
     * four times a 1280-wide video is 5120, which no browser plays smoothly and
     * H.264 as written here does not take.
     */
    VIDEO_UPSCALE_BATCH_FRAMES(
        "video.upscale.batch.frames", 8, 1, 64, TuningSection.MEDIA, TuningUnit.ITEMS,
        "Most frames sent in one upscale call",
        "A video's frames go to the upscale model's server this many at a time. More is fewer calls and a larger request each; the progress moves once a batch.",
    ),
    VIDEO_UPSCALE_MAX_PIXELS(
        "video.upscale.max.pixels", 3840, 64, 16384, TuningSection.MEDIA, TuningUnit.PIXELS,
        "The longest side of an upscaled video",
        "An upscale that would make a video longer than this on either side is refused before it starts.",
    ),

    /*
     * Where the image editor's brush starts, in the picture's own pixels -
     * somebody painting chooses their own, from 1 to the most here. Issue #637.
     */
    IMAGE_BRUSH_PIXELS(
        "images.brush.pixels", 12, 1, 500, TuningSection.MEDIA, TuningUnit.PIXELS,
        "How wide the image editor's brush starts",
        "In the picture's own pixels, so zooming does not change it. Whoever paints can choose another width, up to the most this may be set to.",
    ),
    /*
     * Where the image editor's brush's opacity starts, and how hard a soft
     * tip's edge is - whoever paints moves both on the brush's bar. The marker
     * starts at an opacity of its own, since a marker is see-through by what
     * it is: at the brush's 100 it would be a square tip by another name.
     */
    IMAGE_BRUSH_OPACITY_PERCENT(
        "images.brush.opacity.percent", 100, 1, 100, TuningSection.MEDIA, TuningUnit.PERCENT,
        "How opaque the image editor's brush starts",
        "Every tip but the marker starts here. Whoever paints can choose another.",
    ),
    IMAGE_BRUSH_HARDNESS_PERCENT(
        "images.brush.hardness.percent", 50, 0, 100, TuningSection.MEDIA, TuningUnit.PERCENT,
        "How hard the soft brush's edge starts",
        "The soft tip and the eraser fade across what is left of their width; zero fades across half of it. Whoever paints can choose another.",
    ),
    IMAGE_MARKER_OPACITY_PERCENT(
        "images.marker.opacity.percent", 50, 1, 100, TuningSection.MEDIA, TuningUnit.PERCENT,
        "How opaque the image editor's marker starts",
        "A marker's strokes are see-through, and darken what they cross. Whoever paints can choose another.",
    ),
    /*
     * Where the image editor's selective blur starts, in the picture's own
     * pixels; whoever blurs chooses their own up to the most here. 254 is the
     * most the stack blur it draws with takes. Issue #636.
     */
    IMAGE_BLUR_PIXELS(
        "images.blur.pixels", 12, 1, 254, TuningSection.MEDIA, TuningUnit.PIXELS,
        "How strongly the image editor's blur starts",
        "The blur's radius in the picture's own pixels. Whoever blurs can choose another, up to the most this may be set to.",
    ),
    /*
     * How far a selective blur fades out inside its selection's edge, in the
     * picture's own pixels, to start with: 0 stops the blur hard at the edge,
     * as it always did, so an installation that changes nothing blurs as before.
     */
    IMAGE_BLUR_FALLOFF_PIXELS(
        "images.blur.falloff.pixels", 0, 0, 500, TuningSection.MEDIA, TuningUnit.PIXELS,
        "How far the image editor's blur fades at its edge",
        "Inside a rectangle or lasso, the blur weakens towards the edge across this many of the picture's own pixels. Zero is a hard edge. Whoever blurs can choose another.",
    ),
    /*
     * How far the edge of a Magic edit's selection is softened when the change
     * is laid over the picture, in its own pixels. The right width depends on
     * the picture and the change, so this is where the form starts and not a
     * rule. Issue #638.
     */
    IMAGE_FEATHER_PIXELS(
        "images.feather.pixels", 8, 0, 256, TuningSection.MEDIA, TuningUnit.PIXELS,
        "How far a Magic edit's edge is feathered",
        "The redrawn part blends into the picture across about this many of its own pixels either side of the selection's edge. Zero is a hard edge. Whoever edits can choose another.",
    ),
    /*
     * How many layers a picture keeps over it in the image editor. Each is a
     * PNG the picture's size, kept with every version of it until Save or
     * Discard, so this is what bounds the disk one picture can take.
     */
    IMAGE_LAYERS_MOST(
        "images.layers.most", 32, 1, 256, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many layers a picture may keep",
        "Over the picture itself, in the image editor. Each is a picture the same size, kept with every unsaved version, so more layers take more disk.",
    ),
    /*
     * How long a picture being drawn has left, in the image editor. A Stable
     * Diffusion WebUI says how far it is and how long it reckons is left, and is
     * asked this often while it draws; every other server says nothing, and the
     * time left is then measured off the same model's newest pictures of the
     * same kind - or not said at all, where there are too few of them.
     */
    IMAGE_PROGRESS_POLL_SECONDS(
        "images.progress.poll.seconds", 2, 1, 60, TuningSection.MEDIA, TuningUnit.SECONDS,
        "How often a picture being drawn is asked about",
        "Only a Stable Diffusion WebUI says how far it is. Each ask is one request to it, and the image editor's time left moves this often.",
    ),
    IMAGE_ESTIMATE_JOBS(
        "images.estimate.jobs", 10, 1, 200, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many of a model's newest pictures a time left looks at",
        "Where a model's server says nothing of its progress, the time left is the middle of how long these took, less the time taken so far.",
    ),
    IMAGE_ESTIMATE_LEAST(
        "images.estimate.least", 3, 1, 50, TuningSection.MEDIA, TuningUnit.ITEMS,
        "How many timed pictures a time left needs",
        "Fewer drawn by the same model in the same way, and the image editor says only how long it has taken.",
    ),

    /* ---- Workflows and triggers ---- */

    ACTION_RETRY_SECONDS(
        "action.retry.seconds", 30, 1, 86_400, TuningSection.WORKFLOWS, TuningUnit.SECONDS,
        "How often a waiting action asks again",
        "An action that waits for something - a reply, a condition - asks again this often when it does not say for itself.",
    ),
    ACTION_WAIT_SECONDS(
        "action.wait.seconds", 3600, 1, 2_592_000, TuningSection.WORKFLOWS, TuningUnit.SECONDS,
        "How long a waiting action waits when it does not say",
        "Written onto a condition action when it is saved without a timeout of its own; an action saved earlier keeps what it was given.",
    ),
    TRIGGER_REMEMBER_MINUTES(
        "trigger.remember.minutes", 10, 1, 1440, TuningSection.WORKFLOWS, TuningUnit.MINUTES,
        "How long a trigger remembers an event it ran on",
        "The same message arriving twice - a retry, a second listener - starts one run, not two, within this time.",
    ),
    TRIGGER_REMEMBERED(
        "trigger.remembered", 5_000, 100, 1_000_000, TuningSection.WORKFLOWS, TuningUnit.ITEMS,
        "How many events a trigger remembers",
        "The oldest is forgotten first once this many are held.",
    ),
    SCHEDULES_PER_TICK(
        "schedules.per.tick", 60, 1, 10_000, TuningSection.WORKFLOWS, TuningUnit.ITEMS,
        "How many runs one schedule may start in one tick",
        "A schedule finer than the tick starts every occurrence that came due since the last one; past this many in one round the rest are dropped and a warning is logged.",
    ),
    RESEARCH_DOCUMENT_CHARACTERS(
        "research.document.characters", 200_000, 1_000, 5_000_000, TuningSection.WORKFLOWS, TuningUnit.CHARACTERS,
        "How much of the chosen documents a research is handed",
        "Counted across every document included in one research; the text past it is left out and the research is told so.",
    ),
    RESEARCH_SOURCES_MOST(
        "research.sources.most", 100, 1, 1_000, TuningSection.WORKFLOWS, TuningUnit.ITEMS,
        "The most sources one research may read",
        "Whatever a mode's workflow asks for; each source read is a model call, so this bounds what one research costs.",
    ),
    RESEARCH_PAGE_CHARACTERS(
        "research.page.characters", 20_000, 1_000, 500_000, TuningSection.WORKFLOWS, TuningUnit.CHARACTERS,
        "How much of one page a research reads",
        "One web page, or one page of a document; the rest is not read. The research's own pages-per-source decides how many.",
    ),
    RESEARCH_STEP_RETRIES(
        "research.step.retries", 2, 0, 9, TuningSection.WORKFLOWS, TuningUnit.TIMES,
        "How many times a research mode's step is tried again",
        "Written on every step of the research modes' workflows when they are made, as each node's retry policy; a step that failed in a way that could come out differently is tried again this many times. A workflow already made keeps what its nodes say.",
    ),
    RESEARCH_STEP_RETRY_SECONDS(
        "research.step.retry.seconds", 10, 0, 3600, TuningSection.WORKFLOWS, TuningUnit.SECONDS,
        "How long a research mode's step waits before trying again",
        "Written on the research modes' steps with the number of tries, as the wait before each one.",
    ),
    RESEARCH_IMAGES_MOST(
        "research.images.most", 6, 1, 50, TuningSection.WORKFLOWS, TuningUnit.ITEMS,
        "The most images one research keeps",
        "Only for a research started with Include images; it stops looking once it has this many, and each one is kept on the Artifacts page.",
    ),
    RESEARCH_IMAGE_MB(
        "research.image.mb", 2, 1, 20, TuningSection.WORKFLOWS, TuningUnit.MB,
        "The largest image a research keeps",
        "An image larger than this is skipped rather than kept or laid out in the report; never more than the largest file a tool may download.",
    ),
    RESEARCH_RESUME_AFTER_RESTART(
        "research.resume.after.restart", 2, 0, 9, TuningSection.WORKFLOWS, TuningUnit.TIMES,
        "How many times a research step a restart cut short is run again",
        "Plan, Gather, Deepen and Write change nothing outside the research, so one a server restart cut short is run again when the server is back; 0 fails it, to be restarted from its page. Save is never run again.",
    ),
    RESEARCH_REVIEW_ROUNDS(
        "research.review.rounds", 1, 1, 10, TuningSection.WORKFLOWS, TuningUnit.TIMES,
        "How many rounds of review a research gets",
        "For a research started with Review: the reviewer reads the report and the writer revises it where asked, at most this many times, before it is saved. A workspace may set its own number.",
    ),
    RESEARCH_FOLLOW_UPS(
        "research.followups", 5, 0, 10, TuningSection.WORKFLOWS, TuningUnit.ITEMS,
        "How many follow-up questions a finished research suggests",
        "Each one starts a research of its own when clicked; 0 suggests none and asks the model nothing. A workspace, and the start form, may say otherwise.",
    ),
    RESEARCH_FOLLOW_UPS_TOKENS(
        "research.followups.tokens", 400, 50, 4_000, TuningSection.WORKFLOWS, TuningUnit.TOKENS,
        "How long a research's follow-up questions may be in all",
        "The most the model may write when it is asked for them, with its thinking off.",
    ),
    RESEARCH_ESTIMATE_RUNS(
        "research.estimate.runs", 3, 1, 50, TuningSection.WORKFLOWS, TuningUnit.ITEMS,
        "How many finished research a time left is measured from",
        "A running research says about how long it has left once the workspace has finished this many in the same mode; fewer, and it says nothing.",
    ),
    RESEARCH_ESTIMATE_LOOKBACK(
        "research.estimate.lookback", 20, 1, 500, TuningSection.WORKFLOWS, TuningUnit.ITEMS,
        "How many of the newest finished research a time left looks at",
        "The newest in the same mode; older ones ran on whatever the models and the workflow were then.",
    ),
    RESEARCH_IMAGE_PIXELS(
        "research.image.pixels", 120, 0, 4_000, TuningSection.WORKFLOWS, TuningUnit.PIXELS,
        "The shortest side an image a research keeps may have",
        "Icons, logos and spacers are smaller than a picture worth placing in a report; 0 keeps images of any size.",
    ),

    /* ---- Plugins and libraries ---- */

    PLUGIN_HTTP_BODY_MB(
        "plugin.http.body.mb", 2, 1, 100, TuningSection.PLUGINS, TuningUnit.MB,
        "The largest text answer a plugin's request may read",
        "What a plugin reads as text through orknux.http; a larger answer is refused.",
    ),
    PLUGIN_DOWNLOAD_MB(
        "plugin.download.mb", 5, 1, 500, TuningSection.PLUGINS, TuningUnit.MB,
        "The largest binary answer a plugin's request may read",
        "Counted on the bytes rather than the base64 the sandbox is handed, which is a third again larger.",
    ),
    PLUGIN_UPLOAD_MB(
        "plugin.upload.mb", 10, 1, 500, TuningSection.PLUGINS, TuningUnit.MB,
        "The largest file a plugin may upload",
        "A file a plugin sends on from the session's store, as a picture to Slack.",
    ),
    LIBRARY_MB(
        "library.mb", 4, 1, 100, TuningSection.PLUGINS, TuningUnit.MB,
        "The largest script library a workspace may add",
        "A library is JavaScript every function in the workspace may import.",
    ),
    NPM_ARCHIVE_MB(
        "npm.archive.mb", 32, 1, 500, TuningSection.PLUGINS, TuningUnit.MB,
        "The largest npm package fetched for a library",
        "Counted for one package's archive, as the registry serves it.",
    ),
    NPM_PACKAGES_MOST(
        "npm.packages.most", 60, 1, 1000, TuningSection.PLUGINS, TuningUnit.ITEMS,
        "The most npm packages one library may pull in",
        "A package and everything it depends on, counted together.",
    ),
    MARKETPLACE_ICON_HOURS(
        "marketplace.icon.hours", 6, 1, 720, TuningSection.PLUGINS, TuningUnit.HOURS,
        "How long a marketplace icon is kept before it is fetched again",
        "Icons are fetched once and kept, so the plugin list does not reach the marketplace on every view.",
    ),
    ;

    constructor(
        knob: ConnectionKnob,
        section: TuningSection,
        unit: TuningUnit,
        label: String,
        hint: String,
    ) : this(knob.key, knob.fallback, knob.least, knob.most, section, unit, label, hint)

    constructor(
        knob: ExecutionKnob,
        section: TuningSection,
        unit: TuningUnit,
        label: String,
        hint: String,
    ) : this(knob.key, knob.fallback, knob.least, knob.most, section, unit, label, hint)

    /** What `application.yml` calls it: `orknux.tuning.<key>`. */
    val property: String get() = "orknux.tuning.$key"

    /** The environment variable that moves where a fresh installation starts. */
    val variable: String get() = "ORKNUX_TUNING_" + key.uppercase().replace('.', '_')

    companion object {
        fun byKey(key: String): Tunable? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What the installation says each [Tunable] is.
 *
 * [Tunables] is the one implementation that reads Admin -> Settings; a class
 * takes this rather than that so a test building it by hand gets [DEFAULTS],
 * which answers every number's fallback, without a database behind it.
 */
fun interface Tuning {

    operator fun get(tunable: Tunable): Int

    companion object {
        val DEFAULTS = Tuning { it.fallback }
    }
}

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
        "An .xlsx is a zip, and a small one can unpack to gigabytes. A workbook with a part larger than this is refused rather than read.",
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

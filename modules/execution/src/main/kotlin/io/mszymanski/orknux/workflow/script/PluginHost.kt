package io.mszymanski.orknux.workflow.script

/**
 * What a plugin may ask the *server* to do on its behalf.
 *
 * Distinct from [PluginPermission] on purpose, and the distinction is the whole
 * point of having two lists. A permission turns on a language builtin and
 * nothing else — that file says so, and says that its vocabulary cannot express
 * "give me a socket". This one can, so it is written down somewhere else, granted
 * separately, and shown separately to whoever accepts a plugin.
 *
 * **Nothing here opens the sandbox.** A capability is a function the server
 * implements and hands in as a callable; the plugin never gets a client, a
 * credential or a socket, and `allowIO`, `allowHostAccess` and the rest stay the
 * denials they are. What crosses is an argument and an answer, both as data.
 *
 * The reason it exists at all: a plugin's whole job is to know one outside
 * service well, and there are questions about that service — what is in this
 * Slack thread — that cannot be answered from the payload alone. The choice is
 * between the server making that call under a named grant and the feature not
 * existing. Issue #316.
 *
 * **A workspace's functions have the same door**, and that is what made the
 * scoping below necessary. A plugin is loaded once for the installation and
 * pointed at a connection by somebody with a plugin screen in front of them; a
 * function is written by anybody who can write one, in any workspace. Handing
 * every function a call that takes a bare connection id would have been a way
 * to read another workspace's Slack by guessing a number.
 */
enum class PluginCapability(
    /** What it gives, as a person deciding whether to accept a plugin reads it. */
    val summary: String,
    /**
     * Whether a workspace's own functions get this without anybody accepting it.
     *
     * **The difference is that a plugin has somebody to ask and a function does
     * not.** A plugin is loaded once for the installation and an administrator
     * accepts what it declares, in the words above; a function is written by
     * whoever can write one and runs the moment it is saved. So a capability is
     * offered to functions only where it is safe *without* a grant - which
     * means bounded by something other than somebody's judgement.
     *
     * Reading a thread qualifies: it can only reach connections belonging to the
     * workspace the run is in, which the runner takes from the run.
     *
     * Making a request was withheld on the same reasoning - its bound is the
     * grant, and a function has nobody to give one - and that was overruled on
     * 2026-09-06: a function that cannot call an HTTP service cannot do most of
     * what people write functions for, and every other way to make the call
     * (an action, an MCP server) is a heavier thing to build and maintain for
     * a single request. What holds it in is the installation's proxy rules,
     * which is where an administrator decides where this server may get to, and
     * whoever can write a function can already run code in this sandbox.
     */
    val forScripts: Boolean,
) {

    /**
     * Read a thread from one of the workspace's Slack connections.
     *
     * Read-only, and only through a connection the plugin was pointed at: the
     * handle comes from a `connection` parameter somebody filled in, so a plugin
     * granted this can still only reach the Slack a workspace handed it.
     */
    SLACK_READ_THREAD("Read a Slack thread, through a connection it was given", forScripts = true),

    /**
     * Post a message through one of the workspace's Slack connections.
     *
     * A write, and still `forScripts` - narrower than the HTTP a function
     * already has, which can post to Slack's own API directly. This can only
     * reach a connection the workspace was given, scoped by the run the way
     * reading a thread is, so what bounds it is the same boundary and not a
     * grant. The connection comes from a parameter somebody filled in.
     */
    SLACK_POST_MESSAGE("Post a Slack message, through a connection it was given", forScripts = true),

    /**
     * Add an emoji reaction to a Slack message.
     *
     * The same reasoning as posting: a write, scoped to a connection the
     * workspace was given, narrower than the request capability a function
     * already carries.
     */
    SLACK_ADD_REACTION("Add a Slack reaction, through a connection it was given", forScripts = true),

    /**
     * Follow a Slack permalink to the one message it points at.
     *
     * The same reasoning as reading a thread: read-only, through a connection
     * the workspace was given, bounded by the run's workspace. A message pasted
     * into another message travels as its permalink, and until this nothing
     * could read what the link says.
     */
    SLACK_READ_MESSAGE("Read the Slack message a link points at, through a connection it was given", forScripts = true),

    /**
     * Say who a Slack user id belongs to.
     *
     * A mention arrives as `<@U…>`, which names nobody until it is looked up.
     * Read-only, through a connection the workspace was given, bounded by the
     * run's workspace like the thread read.
     */
    SLACK_READ_USER("Look up who a Slack user id is, through a connection it was given", forScripts = true),

    /**
     * Turn a name into the notation Slack renders as a mention.
     *
     * Read-only - what comes back is text to put in a message, and posting it
     * still takes the posting capability. Bounded the same way the lookups
     * above are.
     */
    SLACK_MENTION("Resolve a name to a Slack mention, through a connection it was given", forScripts = true),

    /**
     * Search Slack's messages by query.
     *
     * Read-only, through a connection the workspace was given, bounded by the
     * run's workspace like the other reads. One honesty note carried in the
     * dispatch rather than here: Slack answers `search.messages` only for a
     * user token, so a connection holding a bot token gets Slack's own refusal
     * back as the error - which is the true answer, not this server's.
     */
    SLACK_SEARCH("Search Slack messages, through a connection it was given", forScripts = true),

    /**
     * Offer Slack members and channels for what somebody typed - the same
     * ranking the workflow editor's target box draws. Issue #377.
     *
     * A plugin defining a `SlackUser` type completes values with this rather
     * than listing the directory itself: one ranking, one set of rules about
     * what a partial handle matches, and no bot token in the plugin's hands.
     * `forScripts` for the reason reading a user is - it reaches only what
     * the connection already reaches.
     */
    SLACK_SUGGEST("Offer Slack members and channels for what was typed, through a connection it was given", forScripts = true),

    /**
     * Make an HTTP request, to an address of the plugin's choosing.
     *
     * **The widest thing on this list, and the summary says so** — because the
     * summary is what somebody reads in the moment they decide. Everything else
     * here is narrow by construction: reading a thread reaches Slack, through a
     * connection a workspace pointed at, and nowhere else. This reaches whatever
     * the plugin asks for, which is the point of it and also the whole of its
     * risk — a plugin granted this can address anything the *server* can, which
     * on most installations includes things the person accepting it cannot.
     *
     * Three things hold it in, and none of them is the sandbox:
     *
     *   the grant   an administrator accepts it, in these words, per plugin. It
     *               is off until somebody says otherwise - for a plugin. A
     *               workspace's own functions have it without asking, which is
     *               a deliberate decision and not an oversight: see `forScripts`
     *   the rules   every request goes out through `ProxyRouter`, so an
     *               installation's proxy rules govern where it gets to — the
     *               same rules an MCP call and a Slack call obey
     *   the shape   a request is a call the server makes and an answer that
     *               comes back as data. The plugin never holds a socket, so
     *               there is nothing to keep open, to listen on, or to hand about
     *
     * What this deliberately is **not** is a network for the sandbox. A package
     * calling `require('net')` still cannot be installed and never will be: it
     * wants Node's socket API, and this is a function.
     */
    NETWORK_REQUEST("Make requests to any address this server can reach", forScripts = true),

    /**
     * Turn an SVG into a PNG.
     *
     * The narrowest thing on this list. It reaches nothing: what goes in is a
     * string the caller already had, what comes back is bytes computed from it,
     * and no connection, address or credential is involved at any point. It is
     * a capability rather than a permission only because the work is the
     * server's to do - the sandbox has no WebAssembly and no rasteriser, which
     * was checked rather than assumed.
     *
     * `forScripts` for the same reason reading a thread is: its bound is not
     * somebody's judgement. Size and time bound it, and they bound everybody
     * equally, so there is nothing a grant would be deciding.
     *
     * What it costs is a renderer, and a renderer that reads SVG is a program
     * that reads an untrusted document format with a scripting model and an
     * external-reference model of its own. Both are switched off explicitly
     * where it is built; see the implementation. A plugin handing it a diagram
     * it just drew is the whole of the intended use, and the guards are there
     * for the day something hands it a document from outside.
     */
    RENDER_PNG("Turn an SVG into a PNG, here on the server", forScripts = true),

    /**
     * And a page of a PDF into one, which is how something that made a
     * document gets to look at it.
     *
     * Its own grant rather than part of [RENDER_PNG], which `RENDERING.md`
     * argued against: the reach is identical - nothing - and a second name
     * makes an administrator weigh a distinction that does not exist.
     *
     * What that argument leaves out is the parser. These are two different
     * libraries reading two different untrusted formats, and a PDF is the
     * larger of the two by a distance: an embedded-file model, an encryption
     * model, a font stack it will load from inside the document. An operator
     * who is happy to draw markup and not to hand documents to PDFBox has a
     * real position, and one grant would take it away from them.
     *
     * `forScripts` for the same reason as the other: bytes in, a picture out,
     * no connection and no credential anywhere in it.
     */
    RENDER_PDF("Turn a page of a PDF into a PNG, here on the server", forScripts = true),

    /**
     * List the workspace's connections - which ones there are, of what kind,
     * and where they point - so a script can find the one it wants by name or
     * kind instead of being handed its id. Issue #597.
     *
     * Never a credential. What crosses is built field by field from a list of
     * what may, so a field somebody adds to a connection later stays on this
     * side until somebody decides otherwise; a token, a password, a key and a
     * reference to the variable holding one are not on that list.
     *
     * `forScripts` for the reason reading a thread is: what it reaches is the
     * connections of the workspace the run belongs to, which the runner takes
     * from the run and the script cannot name. Nothing it answers is more than
     * the workspace's connection page already shows whoever can write a
     * function there, and a handle it answers is still only usable through the
     * doors above, each scoped the same way.
     */
    CONNECTIONS_QUERY("List the workspace's connections - names, kinds and addresses, never a credential", forScripts = true),

    ;

    companion object {

        /** The one named that, or null. Refused on upload rather than half-granted. */
        fun named(what: String): PluginCapability? =
            entries.firstOrNull { it.name.equals(what.trim(), ignoreCase = true) }
    }
}

/**
 * The server, as a plugin may ask things of it.
 *
 * One method per capability, and the argument and the answer are both JSON text
 * — which is what keeps this a door rather than a hole: nothing on either side
 * of it is a host object, so there is no reflection to reach through and no type
 * to walk from.
 *
 * Implemented in the app, where the connections and their credentials are, and
 * handed to [PluginRunner] so the execution module goes on knowing nothing about
 * Slack.
 */
fun interface PluginHost {

    /**
     * @param capability what is being asked for, already checked against what the
     *   caller was granted.
     * @param argument the call's arguments, as a JSON array.
     * @param on which workspace is asking, or null for a caller that belongs to
     *   no one workspace. **This is a boundary and not a hint**: what it names is
     *   the only workspace whose connections the answer may come from, and the
     *   implementation refuses anything else. The script never sees it and
     *   cannot set it — the runner takes it from the run, not from the call.
     * @return the answer as JSON, or a JSON object with an `error` on it. A
     *   refusal is data rather than an exception because the caller has to be
     *   able to say something useful about it: "that connection is gone" is a
     *   sentence a workflow can act on.
     */
    fun ask(capability: PluginCapability, argument: String, on: Long?): String
}

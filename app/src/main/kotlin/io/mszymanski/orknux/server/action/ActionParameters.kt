package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.workflow.MappingMode
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * What an action needs and what it produces.
 *
 * These are read off the action's settings rather than stored — the arguments
 * of the function it calls, the two halves of the message it sends — so this is
 * the one place that rule lives. Both the action API, which shows them on the
 * form, and the graph validator, which checks an edge can satisfy them, ask
 * here.
 */
@Service
class ActionParameters(private val functions: WorkflowFunctionRepository) {

    /**
     * What the action has to be given.
     *
     * Which is the same list a node has to fill in, because that is what
     * filling it in is for; only a delay differs, answering its own parameter
     * from its settings.
     */
    fun inputsOf(action: WorkflowAction): List<ActionParamView> = when (action.subtype) {
        ActionSubtype.TIME -> listOf(ActionParamView("duration", ValueType.NUMBER))

        // What a saved condition needs is the condition's business, not the
        // action's; the action needs whatever the run is already carrying.
        ActionSubtype.CONDITION -> emptyList()

        else -> defaultsFor(action).map { ActionParamView(it.name, it.type) }
    }

    /**
     * What the action still has to be given by whatever comes before it.
     *
     * A delay's duration is one of its inputs on the form, because that is what
     * the action is about — but it is answered by the action's own settings, so
     * nothing upstream has to produce it. Only what is still open is what a
     * graph can be wrong about.
     */
    fun requiredInputsOf(action: WorkflowAction): List<ActionParamView> = when (action.subtype) {
        ActionSubtype.TIME -> emptyList()
        else -> inputsOf(action)
    }

    /** What the next node is handed, which follows from what the action did. */
    fun outputsOf(action: WorkflowAction): List<ActionParamView> = when (action.subtype) {
        // What a send hands on: where it landed, and the timestamp a later reply
        // would thread onto.
        ActionSubtype.OUTGOING_CONNECTION -> listOf(
            ActionParamView("channel", ValueType.STRING),
            ActionParamView("ts", ValueType.STRING),
        )
        /*
         * What a mail hands on: the address the server gave it, which is what a
         * mail log is searched by afterwards, and the list it went to. Not the
         * body - the next node wrote it, and handing it back would only carry it
         * through the run's history a second time.
         */
        ActionSubtype.SEND_EMAIL -> listOf(
            ActionParamView("messageId", ValueType.STRING),
            ActionParamView("recipients", ValueType.ARRAY),
        )

        // Whatever the service answered with. Nothing here defines that shape, so
        // it is a map: a later node can read fields off it, unchecked, as before.
        ActionSubtype.HTTP_REQUEST -> listOf(ActionParamView("response", ValueType.MAP))
        ActionSubtype.FUNCTION -> listOf(
            ActionParamView(
                "result",
                // The function's declared return type, or a map when there is no
                // function to ask — an unanswered question, not a defined shape.
                action.functionId?.let { functions.findByIdOrNull(it) }?.returnType ?: ValueType.MAP,
            ),
        )

        // A wait answers with what it was waiting on.
        ActionSubtype.INLINE_CONDITION ->
            references(action.conditionExpression).map { ActionParamView(it, ValueType.BOOLEAN) }

        ActionSubtype.CONDITION -> listOf(ActionParamView("held", ValueType.BOOLEAN))
        ActionSubtype.TIME -> emptyList()
        /*
         * What a spoken step hands on: where the audio is, so the next node can
         * send it, and how long it runs for, which is the one thing about a
         * sound file a condition might reasonably branch on.
         */
        ActionSubtype.SPEAK -> listOf(
            ActionParamView("url", ValueType.STRING),
            ActionParamView("filename", ValueType.STRING),
            ActionParamView("seconds", ValueType.NUMBER),
        )
    }

    /**
     * The parameters a node pointing at this action has to supply, and what the
     * action itself suggests for each.
     *
     * Only what applies to this subtype: a function's arguments, what a message
     * says and who it goes to, a wait's duration. A node panel that listed every
     * parameter any action could have would be asking about things this one has
     * no use for.
     *
     * The expression beside each is a starting point taken from the action —
     * the seed a node copies once. After that the node owns them, and this is
     * not consulted again.
     */
    fun defaultsFor(action: WorkflowAction): List<ActionParamDefault> = when (action.subtype) {
        // The function decides the arguments; the action may already suggest
        // what to pass for some of them.
        ActionSubtype.FUNCTION -> {
            val suggested = action.mappings.associate { it.argument to it.expression }
            val declared = action.functionId?.let { functions.findByIdOrNull(it) }?.params.orEmpty()
            // Nothing suggested means take it from upstream under its own name,
            // which is the reference somebody would otherwise pick by hand.
            declared.map { param -> seed(param.name, param.type, suggested[param.name]) }
        }

        /*
         * The two things a send is made of, as the node's own to change.
         *
         * They used to be only whatever placeholders the definition happened to
         * contain, so an action written with a fixed message offered a node
         * nothing to override — and the one thing anybody wants to vary per node
         * is who it goes to and what it says. Seeded from the definition, so a
         * node left alone sends exactly what the action says.
         */
        ActionSubtype.OUTGOING_CONNECTION -> listOf(
            seed(TARGET, ValueType.STRING, action.targetName),
            seed(CONTENT, ValueType.STRING, action.content),
            // Empty posts to the channel. A reply in a thread reads the
            // timestamp of the message that asked, which is what puts the
            // answer under it.
            if (action.connectionAction == ConnectionAction.REPLY_IN_THREAD) {
                ActionParamDefault(THREAD_TS, ValueType.STRING, "trigger.threadTs", MappingMode.REFERENCE)
            } else {
                ActionParamDefault(THREAD_TS, ValueType.STRING, "")
            },
        )

        /*
         * Everything about one mail that is worth varying per node, which is all
         * of it: two nodes sharing a "Notify by mail" action are usually telling
         * different people about different things. Seeded from the definition, so
         * an action written with a fixed recipient and subject sends exactly that
         * from a node nobody edited.
         */
        ActionSubtype.SEND_EMAIL -> listOf(
            seed(TO, ValueType.STRING, action.emailTo),
            seed(SUBJECT, ValueType.STRING, action.emailSubject),
            seed(BODY, ValueType.STRING, action.content),
            seed(CC, ValueType.STRING, action.emailCc),
            seed(REPLY_TO, ValueType.STRING, action.emailReplyTo),
        )

        /*
         * What one node may call, and what it may say — the same argument as a
         * send's target and content. Method and headers stay the definition's: they
         * say what kind of call this is, which belongs to the action rather than to
         * one use of it.
         */
        ActionSubtype.HTTP_REQUEST -> listOf(
            seed(URL, ValueType.STRING, action.url),
            seed(BODY, ValueType.STRING, action.content),
        )

        ActionSubtype.INLINE_CONDITION ->
            references(action.conditionExpression).map { defaultReference(it, ValueType.BOOLEAN) }

        // A delay is told how long by its own settings, so the node supplies
        // nothing; a saved condition needs what the run already carries.
        ActionSubtype.TIME, ActionSubtype.CONDITION -> emptyList()
        /*
         * What it says, seeded from the action. The one thing anybody varies
         * per node is the words - "read this out" is the action, and which
         * summary is the node's - which is the same argument the message
         * actions above make about their content.
         */
        ActionSubtype.SPEAK -> listOf(seed(SPEECH, ValueType.STRING, action.speechText))
    }

    /**
     * A parameter the definition expects to be filled from upstream, offered as
     * a reference to the field of that name — which is what it always meant.
     * It used to be handed over as the text `{{input.name}}`, which a node then
     * held as a value and sent verbatim if nobody noticed.
     */
    private fun defaultReference(name: String, type: ValueType) =
        ActionParamDefault(name = name, type = type, expression = name, mode = MappingMode.REFERENCE)

    /**
     * A parameter seeded from what the definition says, as a value or a
     * reference depending on what that turns out to be.
     *
     * Definitions written before parameters had a mode say `{{input.text}}`
     * where they mean "whatever came in as text". That is read as the reference
     * it describes, so an old definition seeds a node that works; anything else
     * is the text it looks like, and an empty setting leaves the node asking
     * upstream for the field of that name.
     */
    private fun seed(name: String, type: ValueType, said: String?): ActionParamDefault {
        val text = said?.trim().orEmpty()
        val referenced = referenceIn(text)
        return when {
            referenced != null -> ActionParamDefault(name, type, referenced, MappingMode.REFERENCE)
            text.isEmpty() -> defaultReference(name, type)
            else -> ActionParamDefault(name, type, text, MappingMode.VALUE)
        }
    }

    /**
     * The field a setting names, if naming one is all it does.
     *
     * `{{input.text}}`, `{{text}}` and `input.text` all name `text`; the
     * `trigger.` prefix survives, being where the field is read from. Text with
     * a placeholder inside a sentence names nothing — there is no way to say
     * half a value, and pretending otherwise would drop the rest of the
     * sentence.
     */
    private fun referenceIn(setting: String): String? {
        val named = when {
            setting.startsWith("{{") && setting.endsWith("}}") ->
                setting.removeSurrounding("{{", "}}").trim()

            setting.startsWith("input.") -> setting
            else -> return null
        }
        if (!FIELD_PATH.matches(named)) return null
        return named.removePrefix("input.")
    }

    /**
     * Whether the run's input survives the action.
     *
     * A wait hands on what it was given, so everything before it stays
     * available; something that performs work answers with its own output and
     * that is all the next node sees.
     */
    /**
     * Whether what reached this action reaches the step after it too.
     *
     * All of them, now. A WAIT produces nothing and always passed its payload
     * on; an EXECUTE used to hand on only what it returned, so a function that
     * turned an answer into Slack markup left the reply after it with a bare
     * string and no channel, no thread and nothing to read. A step's result is
     * something a run gains on its way past, not a thing that replaces the
     * run - which is the rule the image node was corrected to in #333.
     */
    fun passesThrough(action: WorkflowAction): Boolean = true

    /** Every `input.x` an expression reads. */
    private fun references(expression: String?): List<String> = expression
        ?.let { REFERENCE.findAll(it).map { match -> match.groupValues[1] }.toList() }
        .orEmpty()
        .distinct()

    companion object {
        /** Where a send goes: a channel, a recipient, whatever the service calls it. */
        const val TARGET = "target"

        /** What it says. */
        const val CONTENT = "content"

        /** What a [ActionSubtype.SPEAK] node says, overriding the action's own. */
        const val SPEECH = "speech"

        /** The message a reply threads onto; blank posts to the channel instead. */
        const val THREAD_TS = "threadTs"

        /** What an HTTP node calls, and what it sends. */
        const val URL = "url"

        /** What an HTTP request sends, and what a mail says. */
        const val BODY = "body"

        /** A mail's recipients and copy list, comma-separated, and its subject. */
        const val TO = "to"
        const val CC = "cc"
        const val SUBJECT = "subject"

        /** Where an answer to the mail should go. */
        const val REPLY_TO = "replyTo"

        /**
         * A name and nothing else, dots included. Whole-string on purpose: this
         * decides whether a seed is a reference, and a sentence with a name in
         * it is a sentence.
         */
        private val FIELD_PATH = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
        private val REFERENCE = Regex("""\binput\.([A-Za-z_][A-Za-z0-9_]*)""")
    }
}

/** A parameter of an action, and the expression the action suggests for it. */
data class ActionParamDefault(
    val name: String,
    val type: ValueType,
    val expression: String,
    /** Whether the expression is the value itself, or the field to read. */
    val mode: MappingMode = MappingMode.VALUE,
)

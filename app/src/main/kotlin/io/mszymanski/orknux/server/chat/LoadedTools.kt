package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.model.ToolLoading

/**
 * How a tool loaded with `tool_load` is called for the rest of a conversation,
 * and what the model is told about it - in the briefing's paragraph over its
 * list of tools, in `tool_load`'s description and answer, and in what
 * `tool_find` and `tool_describe` answer. One place, so the four cannot say
 * different things. Read off the provider at every turn; see [ToolCeilings.loading].
 *
 * Every tool named in these sentences is read off its declaration - [ToolRun],
 * [ToolSearchTools] - never written out.
 */
enum class LoadedTools {
    /**
     * It joins the tool array at the next turn ([ConversationFrame.joined]):
     * through [ToolRun] for the rest of the turn that loaded it, by its own
     * name from the next. The provider's setting is on.
     */
    JOIN,

    /** It never joins mid-conversation: through [ToolRun], for the rest of it. The setting is off. */
    RUN,

    /**
     * [RUN], on llama.cpp, said as a rule rather than as advice.
     *
     * llama.cpp builds a grammar for the tool call from the request's tool
     * array, so a model can only write the names the array declares. One that
     * believes a loaded tool is called by its own name writes that name, and
     * the grammar turns it into the nearest declared one: session 900's Qwen
     * called a loaded scratchpad_write and got scratchpad_keep, every time.
     * The guard cannot catch what never arrives, so the belief is what is fixed:
     * the model is told plainly that [ToolRun] is the only way, with the call's
     * exact shape. Only where nothing is up front either - with every tool in
     * the array, nothing is marked to load.
     */
    RUN_ONLY,
    ;

    /**
     * The sentence of the briefing's paragraph that says what a load holds,
     * [example] being one of the agent's own tools.
     */
    fun held(example: String): String = when (this) {
        JOIN -> "Once " + ToolSearchTools.FIND + " says one is loaded it stays yours for the rest of this " +
            "conversation: in this turn call it with " + ToolRun.NAME + " rather than loading it again, and from " +
            "the next it is marked (" + BuiltInTools.LOADED + ") here and called by its own name."
        RUN -> "Once " + ToolSearchTools.FIND + " says one is loaded it stays yours for the rest of this " +
            "conversation, still marked here as it was: call it with " + ToolRun.NAME + " rather than loading it again."
        RUN_ONLY -> "Once " + ToolSearchTools.FIND + " says one is loaded it stays yours for the rest of this " +
            "conversation, still marked here as it was, and you do not load it again. " + rule(example)
    }

    /** What [ToolSearchTools.FIND]'s own description says of how a loaded tool is called. */
    val described: String
        get() = when (this) {
            JOIN -> "Call it with " + ToolRun.NAME + " in this turn; from your next turn it is in your list of " +
                "tools and called by its own name."
            RUN -> "You call it with " + ToolRun.NAME + " from then on."
            RUN_ONLY -> "You call it with " + ToolRun.NAME + " from then on, and only so: never by its own name."
        }

    /** How [ToolSearchTools.FIND]'s answer introduces the tools it loaded, [example] being one of them. */
    fun loaded(example: String): String = when (this) {
        JOIN -> "In this turn call them with " + ToolRun.NAME + ", giving the name and the arguments, whatever " +
            "your list of tools still marks them. From your next turn they are in your list of tools, and you " +
            "call them by their own names:\n"
        RUN -> "Call them with " + ToolRun.NAME + ", giving the name and the arguments, for the rest of this " +
            "conversation, whatever your list of tools still marks them:\n"
        RUN_ONLY -> rule(example) + "\n"
    }

    /**
     * What an answer that lists tools to load - [ToolSearchTools.SEARCH]'s and
     * [ToolSearchTools.DESCRIBE]'s - adds about calling them once loaded, or
     * null where the answer needs nothing more than it said before.
     */
    fun listing(example: String): String? = if (this == RUN_ONLY) rule(example) else null

    companion object {
        /** Which applies on a provider [loading] so. */
        fun of(loading: ToolLoading): LoadedTools = when {
            loading.joinsLoaded -> JOIN
            loading.type == ProviderType.LLAMA_CPP && !loading.offersAll -> RUN_ONLY
            else -> RUN
        }

        /**
         * The rule, said whole: a tool marked to load is called through
         * [ToolRun] and nothing else, now and later, why, and the call's exact
         * shape with [example] as the tool.
         */
        fun rule(example: String): String =
            "A tool marked (" + BuiltInTools.TO_LOAD + ") is not in your list of tools and never will be in this " +
                "conversation, even once loaded, so it can only ever be called through " + ToolRun.NAME + " - " +
                "never by its own name, in this turn or any later one: a call by a name that is not in your list " +
                "of tools reaches a different tool. Call it as " + ToolRun.NAME + " with {\"" + ToolRun.TOOL +
                "\": \"" + example + "\", \"" + ToolRun.ARGUMENTS + "\": {...its arguments...}}."
    }
}

package io.mszymanski.orknux.server.dependency

/**
 * What a dependency question is asked about, and what an answer may name.
 *
 * One vocabulary for both ends of the arrow. A function is a subject — "what
 * uses this?" — and it is also an answer, because a function imports another
 * one; so a single enum says both, and a kind that is only ever one end says so
 * in its comment rather than in a second enum.
 *
 * **Not [ComponentKind][io.mszymanski.orknux.server.transfer.ComponentKind].**
 * That one is the catalogue of what an envelope can carry, and it is short for a
 * reason: a variable, a library and a connection all hold something that cannot
 * travel. This one is the catalogue of what can point at what, and those three
 * are exactly the interesting cases — a variable is the credential of a
 * connection, a library is imported by a tool in a workspace the reader may not
 * be able to open. Widening the transfer enum to fit them would offer
 * `exportComponent(kind: VARIABLE)`, which is a promise the exporter cannot
 * keep.
 */
enum class DependencyKind {
    OBJECT,
    FUNCTION,
    CONDITION,
    TOOL,

    /** The folder, because the folder is what an agent is granted. */
    SKILL_CATALOG,

    /** The folder, for the same reason. */
    MEMORY_CATALOG,
    ACTION,
    TRIGGER,
    AGENT,

    /** Only ever an answer: nothing points at a workflow. */
    WORKFLOW,
    VARIABLE,

    /** Installation-wide, so its answers can be in workspaces the reader cannot open. */
    LIBRARY,

    /** Only ever an answer, and one held beside a credential. */
    CONNECTION,

    /**
     * A server an agent is granted, and one that is asked what uses it.
     *
     * The one of these four with a dependant of its own: an agent names it, by
     * name, and removing the server takes the capability away from every agent
     * holding it. That was silent - see `McpServerAPI.removeMcpServer`, which
     * un-grants without asking - so the page had to be able to say who would
     * lose something first. Issue #318.
     */
    MCP_SERVER,

    /** Asked what it holds: the models reached through it. */
    MODEL_PROVIDER,

    /**
     * A model, asked what uses it: the agents thinking with it, the
     * workspace's settings that name it, the actions that speak with it and the
     * workflow nodes that draw or decide with it.
     */
    MODEL,

    /**
     * Only ever an answer: a workspace's own setting naming the thing asked
     * about - its companion model, say. [Dependant.section] says which, so a
     * screen can open the settings page there.
     */
    WORKSPACE,
    ;

    /** "skill catalog", for a sentence somebody reads. */
    val label: String get() = name.lowercase().replace('_', ' ')

    /**
     * Whether "what uses this?" is a question this kind has an answer to.
     *
     * False for the three that are only ever the far end of an arrow. Asked before
     * the component is looked up, so that a workflow id nothing points at is
     * refused for the reason it is refused for and not as a missing row.
     */
    val askable: Boolean
        get() = this !in setOf(CONNECTION, WORKSPACE)
}

/**
 * One thing that depends on a component, said once and read two ways.
 *
 * This is the whole of #258 and #268 in one class. The delete guards each grew
 * their own answer to "what uses this", each assembled a sentence out of it, and
 * a sentence is where a name stops being something anybody can follow: being
 * told *"That library is imported by slugify in Backend"* leaves the reader to go
 * and find `slugify` by hand. So the answer is a row with an id on it, and the
 * sentence is one of the two things that row can become.
 *
 * [phrase] is that sentence's clause, and it is carried on the entry rather than
 * derived from [kind] because the wording is the *subject's* and not the
 * dependant's. A function that calls another function is named bare, because the
 * reader is looking at a list of functions; the same function importing a library
 * is "slugify in Backend", because the reader is an administrator looking across
 * workspaces; a trigger is "the webhook Nightly", because a webhook is not in any
 * list a bare name would send somebody to. Those wordings were each argued for
 * where they were written and none of them is wrong — what was wrong was
 * computing the set twice.
 *
 * @param id always present, because a row without one is a row nothing can link
 *   to, and a link is the point.
 * @param workspaceId null for a function the organisation owns, which belongs to
 *   no workspace.
 * @param published whether this names the frozen copy rather than the drawn one.
 *   Only a [DependencyKind.WORKFLOW] ever sets it, and it changes what the reader
 *   has to do about it: a draft is redrawn, a publication is republished.
 */
data class Dependant(
    val kind: DependencyKind,
    val id: Long,
    val name: String,
    val workspaceId: Long?,
    val workspaceName: String?,
    val published: Boolean,
    val phrase: String,
    /** Where on its page the dependant is, for one that is a part of a page - a workspace setting's anchor. */
    val section: String? = null,
)

/**
 * The answer to "where is this used", as far as this reader is allowed to have it.
 *
 * [hidden] is the part that is not a lie and not a leak. A library is
 * installation-wide and the functions importing it live in workspaces the reader
 * may not be able to open; naming one of those is telling somebody that a
 * workspace exists and what is in it, and dropping it silently is answering "what
 * uses this?" with a list that is missing rows. So it is counted and not named,
 * and the screen says so in a line of its own.
 *
 * Every workspace-scoped subject answers `hidden = 0` — the reader had to be able
 * to see the workspace to ask at all, and every answer is in it.
 */
data class Dependants(
    val entries: List<Dependant>,
    val hidden: Int,
)

/**
 * A kind that is only ever the far end of an arrow.
 *
 * `componentDependants(kind: WORKFLOW)` is a question with no answer rather than
 * an empty one: nothing in this product points at a workflow, so an empty list
 * would read as "nothing uses it yet" when the truth is that the question does
 * not apply.
 */
class DependencyKindNotAskableException(kind: DependencyKind) :
    RuntimeException("Nothing points at ${indefinite(kind.label)}, so there is nothing to list")

private fun indefinite(label: String): String =
    if (label.first() in "aeiou") "an $label" else "a $label"

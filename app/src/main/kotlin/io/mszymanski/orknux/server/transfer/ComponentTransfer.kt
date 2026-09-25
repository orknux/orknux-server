package io.mszymanski.orknux.server.transfer

/**
 * Moving a workspace's components between installations as JSON.
 *
 * The file this package writes and reads is a *contract*, not a dump: it is
 * written by one installation and read by another, possibly a release or two
 * apart, and the two have nothing else in common. Three rules follow from that
 * and are worth stating once, here, rather than being rediscovered:
 *
 * 1. **No ids travel.** An id is a number this database printed and it means
 *    nothing anywhere else. Everything the envelope points at, it points at by
 *    name — and because every reference in it is single-kinded (a property can
 *    only name an object, a condition's member can only name a condition) a
 *    reference is a bare name rather than a kind-and-name pair.
 * 2. **No secrets travel.** The line is the one the database already draws: a
 *    column this installation encrypts at rest is not ours to put in a file
 *    somebody emails, and nothing that is encrypted is written here. A
 *    variable's value, a connection's token, a model provider's key and an MCP
 *    server's header are all on that side of it, so each of those is exported
 *    as a name and a type — enough for the target to say what it means here,
 *    and nothing that would work anywhere on its own. A column the workspace
 *    typed in the clear — a function's source, an action's headers — is the
 *    workspace's own text and travels; the day one of those becomes a place
 *    credentials are kept it has to be encrypted first, and it stops travelling
 *    by this same rule rather than by a second one.
 * 3. **A version the reader does not know is refused, by name.** Reading what
 *    can be read out of a newer envelope is how half a workflow gets created
 *    and nobody is told which half.
 *
 * A kind added to the catalogue is not a version bump, and deliberately: an
 * older reader given a component it has never heard of refuses the whole file
 * and names the kind, which is the same whole-file refusal rule 3 exists to
 * get. So an export of the kinds that travelled before still claims version 1
 * and can still be read by the installation that wrote it a release ago, and
 * only a file that actually holds a new kind is refused there.
 *
 * The envelope is written and read by hand, as `WorkflowSnapshot` writes the
 * published graph, and for the same reason: a shape in a file outlives the class
 * it came from, and binding it by reflection makes every field rename a silent
 * format change.
 */

/**
 * The contract this installation writes, and the newest one it will read.
 *
 * An integer, and only ever compared with `>` — an envelope claiming a version
 * this does not know is refused rather than partially read. Bump it when the
 * *meaning* of something in the envelope changes; adding a field an older
 * reader can ignore is not a bump.
 */
const val COMPONENT_FORMAT_VERSION: Int = 1

/**
 * What can be exported and imported.
 *
 * The whole catalogue. The first five reach only each other and travel whole;
 * the last four reach outward as well — an agent names a model, an action and a
 * trigger name a connection — and what they reach out to holds credentials, so
 * it cannot be carried. That is what [ExternalKind] is, and it is the whole of
 * the difference between the two halves: everything in an envelope is still
 * written by name, and the import still creates all of it or none of it.
 *
 * The order is the order things are written in, which is the order they depend
 * on each other in: nothing here reaches anything below it.
 */
enum class ComponentKind {
    OBJECT,
    FUNCTION,
    CONDITION,
    TOOL,
    SKILL,
    ACTION,
    TRIGGER,
    AGENT,
    WORKFLOW,
    ;

    /** "function", for a message somebody reads. */
    val label: String get() = name.lowercase()

    /**
     * "an object", "a function".
     *
     * Here rather than at each message, because every sentence that names a kind
     * has to get it right and three of them take "an".
     */
    val indefinite: String get() = indefiniteFor(label)
}

/**
 * What an envelope points at and can never carry.
 *
 * Each of these is a row with an encrypted column in it — a key, a token, a
 * header — so what travels is the name and the type, and the import will not
 * create anything until it has been told what each one means in the target
 * workspace. A connection invented from a name would be a connection to
 * nowhere; one invented from a name and somebody else's token would be worse.
 *
 * Not a [ComponentKind] on purpose. A kind is something the file holds; these
 * are things it asks for, and the two are answered differently — one by
 * creating, the other by binding.
 */
enum class ExternalKind {

    /** One model, reached through one provider; both are named. */
    MODEL,

    /** One of the workspace's connections to a service. */
    CONNECTION,

    /** One MCP server the workspace has registered. */
    MCP_SERVER,
    ;

    /** "mcp server", for a message somebody reads. */
    val label: String get() = name.lowercase().replace('_', ' ')

    val indefinite: String get() = indefiniteFor(label)
}

/** "an object", "a connection" — one rule, so no message has to remember it. */
private fun indefiniteFor(label: String): String =
    if (label.first() in "aeiou") "an $label" else "a $label"

/**
 * One thing an envelope points at that it could not carry.
 *
 * [name] is what the source workspace called it and [type] is what it was —
 * `SLACK`, `OPENAI` — which together are the whole of what a target needs in
 * order to say what to point it at. A model has a provider as well, because a
 * model is only ever reached through one and two providers may well offer a
 * model of the same name.
 */
data class ExternalReference(
    val kind: ExternalKind,
    val name: String,
    /** The provider a model is reached through; null for everything else. */
    val provider: String? = null,
    /** The type it was there, when it has one. Never a setting, never a secret. */
    val type: String? = null,
) {

    /**
     * What the plan calls it, and what a binding names it by.
     *
     * Unique within a workspace, which a model's name alone is not. Built here
     * and compared as it stands — nothing ever takes it apart again, because
     * what resolves a reference to a row is the structured [provider] and
     * [name] beside it.
     */
    val label: String get() = provider?.let { "$it / $name" } ?: name
}

/**
 * How much of what a component reaches travels with it.
 *
 * [DEEP] is the useful default: a function exported without the objects it is
 * typed against lands somewhere it cannot be opened. [SHALLOW] is for moving one
 * thing into a workspace that already has the rest, and the difference is
 * entirely in what the envelope carries — the import behaves the same either
 * way, resolving whatever is not in the file against the target workspace.
 */
enum class ExportDepth {
    SHALLOW,
    DEEP,
}

/** What the import will do about one thing the envelope mentions. */
enum class ImportDisposition {

    /** Created here under the name the envelope gave it. */
    CREATE,

    /**
     * Created here under a different name, because that one was taken.
     *
     * Never a replacement: somebody else's work under the same name is theirs.
     * Everything else in the same envelope that pointed at this points at the
     * renamed one, so the import is internally consistent even when half of it
     * has been renamed.
     */
    RENAME,

    /**
     * Not created: the workspace already has this one, and the imported things
     * that pointed at it will point at it here.
     *
     * A shallow export's dependencies and every variable land here, matched by
     * name. So does an external the import was told what to point at — a
     * binding is the same answer given by hand rather than found, and it would
     * be a distinction without a difference to call it something else.
     */
    REUSE,

    /**
     * Pointed at, not carried, and not here. The import is refused while any of
     * these remain, because creating the rest would leave a function typed
     * against an object that does not exist — or an agent naming a model that
     * is nobody's.
     */
    MISSING,

    /**
     * Carried by the file and not created, because the caller said to leave it out.
     *
     * Only ever a component the envelope actually holds. Everything else a plan
     * lists is a *reference* — something the file points at and does not carry —
     * and there is nothing there to leave out: the fix for one of those is to
     * bind it, to make it here, or to export again with more of what it needs.
     *
     * Not the same as absent from the plan. A left-out component is still listed,
     * still named, and still says why — including when it was left out because
     * something else was, which is the only way somebody finds out that
     * unticking one row took three with it.
     */
    EXCLUDE,
}

/** The envelope is not JSON, or not this format at all. */
class EnvelopeUnreadableException(reason: String) :
    RuntimeException("This file is not an Orknux export: $reason")

/**
 * The envelope was written by a newer Orknux than this one.
 *
 * Names both versions and refuses. The alternative — reading the fields it
 * recognises — creates a component that is missing whatever the new version
 * added, with nothing to say which parts arrived.
 */
class EnvelopeVersionUnknownException(found: Int, producedBy: String?) : RuntimeException(
    "This export is format version $found and this installation reads version " +
        "$COMPONENT_FORMAT_VERSION" +
        (producedBy?.let { ". It was produced by $it" } ?: "") +
        ". Upgrade this installation, or export again from an installation of this version.",
)

/** The envelope is well-formed but says something impossible. */
class EnvelopeInvalidException(says: String) : RuntimeException(says)

/**
 * The import was asked to go ahead while something it needs is not here.
 *
 * The preview says the same thing first, so this is the answer to somebody who
 * confirmed anyway — or to a client that never asked. Nothing is written.
 */
class ImportNotPossibleException(problems: List<String>) : RuntimeException(
    "Nothing was imported. " + problems.joinToString(" "),
)

/**
 * The import was told to point a reference at something that is not there.
 *
 * A binding names a row in the target workspace by id, so this is a client's
 * mistake rather than a file's: an id from another workspace, one that has been
 * deleted since the form was drawn, or one of the wrong kind altogether.
 * Refused rather than ignored — an ignored binding is an import that quietly
 * did something other than what it was asked to.
 */
class ImportBindingInvalidException(kind: ExternalKind, name: String, targetId: Long) : RuntimeException(
    "This workspace has no ${kind.label} with id $targetId, so there is nothing for $name to point at. " +
        "Nothing was imported.",
)

/**
 * The import was told to leave out something the file does not carry.
 *
 * Refused rather than ignored, for the reason a bad binding is: a request the
 * server silently drops is a client showing one import and getting another. It
 * is also the answer to the mistake worth naming — a plan lists what the file
 * points at beside what it holds, and only what it holds can be left out.
 */
class ImportExclusionUnknownException(kind: ComponentKind, name: String) : RuntimeException(
    "This file carries no ${kind.label} called $name, so there is none to leave out. What can be left out is " +
        "what the file carries, and a tool an agent in it points at; anything else it points at has to " +
        "exist here instead. Nothing was imported.",
)

/**
 * The import was told to rename something the file does not carry.
 *
 * Refused for the reason an unknown exclusion is: a client showing one file and
 * asking about another. Issue #383.
 */
class ImportRenameUnknownException(kind: ComponentKind, name: String) : RuntimeException(
    "This file carries no ${kind.label} called $name, so there is none to rename. Nothing was imported.",
)

package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.action.FunctionExternal
import io.mszymanski.orknux.server.action.ScriptImport
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * One argument a tool takes, in the order it takes them.
 *
 * The same shape a function's parameter has, and deliberately so: both are
 * arguments to a script in the same sandbox, and a workspace that has learnt
 * what a parameter is in one editor should not have to learn it again in the
 * other. [ValueType] is borrowed from the workflow side rather than copied for
 * the same reason — two enumerations of the same six shapes would drift.
 *
 * What it is *not* is a schema the model is held to. Everything a provider is
 * told about a tool's arguments is a string, so the type here is what the
 * editor annotates the code with and what the agent is told the argument means;
 * it is not a promise the argument arrives already shaped.
 */
@Embeddable
class AgentToolParam(
    @Column(name = "name", nullable = false, length = 64)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: ValueType = ValueType.STRING,

    /**
     * Which object, when the type is one. Null for everything else.
     *
     * Spelled the way a function's parameter spells it — a column holding an id,
     * no foreign key — so a deleted object leaves a dangling id that is reported
     * rather than a delete that is refused.
     */
    @Column(name = "object_id")
    var objectId: Long? = null,
)

/**
 * A named piece of JavaScript an agent may call while it runs.
 *
 * The difference from a workflow function is who calls it. A function is called
 * by an action node, at a point the graph fixed in advance; a tool is offered to
 * an agent, which calls it if it judges that it should. The sandbox is the same
 * one — `ScriptRunner` — so what is stored here is only ever text.
 */
@Entity
@Table(name = "agent_tool")
class AgentTool(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    /**
     * What the tool is for, in a sentence or a worked example. This is not
     * decoration: it is what an agent reads to decide whether to call it, and a
     * description good enough to quote a sample call needs room for one.
     */
    @Column(length = 4000)
    var description: String? = null,

    /**
     * The one line that goes in the system prompt, at most
     * [MOST_TOOL_SUMMARY_CHARS] characters. Issue #481.
     *
     * Apart from [description], which is read at the moment of calling by a
     * model that has already decided to call something. This is read before
     * that: every tool the agent holds is listed in its briefing, so it knows
     * what it has rather than searching for words it hopes exist. A list like
     * that is only affordable if each line is short, which is what the bound is
     * for - and why the front of the line matters most, since a long list is
     * trimmed from the end.
     *
     * Null where nobody wrote one, and the description's own first words stand
     * in: a tool that says nothing about itself in the list is worse than one
     * described badly.
     */
    @Column(length = 50)
    var summary: String? = null,

    /** What runs: the JavaScript the editor compiled from [typescript]. */
    @Column(nullable = false, columnDefinition = "text")
    var source: String,

    /**
     * What was written, kept beside what runs.
     *
     * Reopening a tool has to show the author their own code rather than the
     * compiler's output, and the sandbox has to be handed JavaScript — so both
     * are stored, and they are only ever written together.
     */
    @Column(nullable = false, columnDefinition = "text")
    var typescript: String,

    /**
     * What it takes, in the order the sandbox passes it.
     *
     * A tool used to take exactly one argument called `input`, hard-coded in two
     * places: the schema the model was shown and the single-element list the
     * sandbox was handed. That was not a signature anybody could read or change
     * — the only account of what a tool wanted was a sentence in its
     * description, and the model had to guess the rest.
     *
     * So it is stored, like a function's. Existing tools were given the one
     * parameter they always had, which is why nothing about them changed.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_tool_param", joinColumns = [JoinColumn(name = "tool_id")])
    @OrderColumn(name = "position")
    var params: MutableList<AgentToolParam> = mutableListOf(),

    /**
     * The workspace's functions this tool calls, under the names it calls them.
     *
     * The same [ScriptImport] a function's list holds, and pointing at the same
     * table: a tool and a function are the same JavaScript in the same sandbox, and
     * a workspace that has worked out how importing goes in one editor should not
     * have to work it out again in the other.
     *
     * One direction only. A tool may import a function; nothing imports a tool,
     * because a tool is what an agent decides to call and not a piece anybody
     * builds out of.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_tool_import", joinColumns = [JoinColumn(name = "tool_id")])
    @OrderColumn(name = "position")
    var imports: MutableList<ScriptImport> = mutableListOf(),

    /**
     * The installation's libraries this tool imports, under the names it uses.
     *
     * A separate list from the functions it imports, pointing at a separate table.
     * They arrive in the same `imports` object and are written the same way in the
     * code, because from inside a script there is no difference worth spelling —
     * but a single column that could hold either kind of id is a column that will
     * one day hold the wrong one, and for an import that is a call into whatever
     * happened to have that number.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_tool_library", joinColumns = [JoinColumn(name = "tool_id")])
    @OrderColumn(name = "position")
    var libraries: MutableList<ScriptImport> = mutableListOf(),

    /**
     * The workspace's variables this tool is handed, after the parameters it
     * declares — the same arrangement a function has, held in the same
     * embeddable, because a tool is the same JavaScript in the same sandbox.
     *
     * The agent is never told they exist: the model fills the declared
     * parameters and the sandbox appends these, which is how a tool reaches a
     * credential without the credential passing through a conversation.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_tool_external", joinColumns = [JoinColumn(name = "tool_id")])
    @OrderColumn(name = "position")
    var externals: MutableList<FunctionExternal> = mutableListOf(),

    /**
     * How long one call of this tool may run, in seconds.
     *
     * Null means the tool has decided nothing: the workspace's default is used,
     * and the installation's bound where the workspace has none. Read per call,
     * so changing it changes the next call rather than one in flight.
     */
    @Column(name = "timeout_seconds")
    var timeoutSeconds: Int? = null,

    /** Off leaves it defined but out of reach, which a delete would not. */
    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
) {

    /** "(city: string, days: number)", as the entity can say it without names. */
    val signature: String
        get() = params.joinToString(", ", "(", ")") { "${it.name}: ${it.type.name.lowercase()}" }
}

interface AgentToolRepository : JpaRepository<AgentTool, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<AgentTool>

    /**
     * The same, narrowed to the tools whose name carries a word.
     *
     * The name only. It was the name or the description, and a tool's
     * description here is a paragraph written for a model to read - so
     * searching "date" returned every github tool, because their descriptions
     * mention a commit's date. Any ordinary word drags in half the list, which
     * is a search that narrows nothing.
     *
     * Case-insensitive and a substring rather than a prefix: what people recall
     * is a phrase from the middle of a name, not how it opened.
     *
     * Asked of the database rather than sieved in the browser because the list
     * is paged - narrowing what arrived on page one would hide matches sitting
     * on page four and quietly call that "no results".
     */
    @Query(
        """
        SELECT e FROM AgentTool e
        WHERE e.workspaceId = :workspaceId
          AND LOWER(e.name) LIKE LOWER(CONCAT('%', :looking, '%'))
        """,
    )
    fun searching(
        @Param("workspaceId") workspaceId: Long,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<AgentTool>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): AgentTool?

    /** Every tool that imports the function with this id, asked before a delete. */
    @Query("select t from AgentTool t join t.imports i where i.importedId = :functionId")
    fun findByImportedFunctionId(functionId: Long): List<AgentTool>

    /** Every tool that imports the library with this id. */
    @Query("select t from AgentTool t join t.libraries l where l.importedId = :libraryId")
    fun findByImportedLibraryId(libraryId: Long): List<AgentTool>
}

class ToolNotFoundException(val id: Long) : RuntimeException("No tool with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class ToolNameTakenException(val name: String) :
    RuntimeException("A tool named \"$name\" already exists in this workspace"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ToolNameInvalidException(val name: String) :
    RuntimeException("\"$name\" is not a name a script can be called by"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ToolSourceInvalidException(val reason: String) : RuntimeException(reason), Refusal {

    override val arguments get() = mapOf("reason" to reason)
}

/**
 * A description longer than the column it lives in.
 *
 * Said in characters, because the person pasted text and can count it — before
 * this the database refused it and the editor showed an internal error, which
 * told them nothing about what to shorten.
 */
class ToolDescriptionTooLongException(val length: Int, val limit: Int) : RuntimeException(
    "The description is $length characters and at most $limit fit",
), Refusal {

    override val arguments get() = mapOf("length" to length, "limit" to limit)
}

/**
 * How long a tool's line in the system prompt may be. Issue #481.
 *
 * Fifty characters is a phrase, not a sentence, and that is the point: every
 * tool an agent holds is listed in its briefing, so the list has to stay
 * affordable at a hundred tools as well as at five. A long list is trimmed from
 * the end, which is why what goes at the front of the phrase matters most.
 */
const val MOST_TOOL_SUMMARY_CHARS = 50

/** The one line a tool gets in the system prompt is fifty characters. Issue #481. */
class ToolSummaryTooLongException(val length: Int, val limit: Int) : RuntimeException(
    "The summary is $length characters and at most $limit fit",
), Refusal {

    override val arguments get() = mapOf("length" to length, "limit" to limit)
}

class ToolParamInvalidException(val name: String) :
    RuntimeException("\"$name\" is not a name a parameter can have"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * The code and the declared parameters disagree about how many arguments there are.
 *
 * The same rule a function is saved under, and it was missing here for no reason
 * anybody chose: a tool whose code took three arguments while its details
 * declared one saved fine — and then the model filled the one declared
 * parameter, whose whole object landed in the code's first argument. The
 * mismatch is only diagnosable at save time, so that is where it is refused.
 */
class ToolSignatureMismatchException(val found: Int, val params: Int, val externals: Int) : RuntimeException(
    "The code takes $found ${if (found == 1) "argument" else "arguments"}, but this tool is handed " +
        "${params + externals}: $params declared" +
        (if (externals > 0) " and $externals from the workspace" else "") +
        ". The agent fills the declared parameters and the sandbox passes them in that order, " +
        "so the code has to take all of them.",
), Refusal {

    override val arguments get() = mapOf("found" to found, "params" to params, "externals" to externals)
}

/**
 * Two of a tool's parameters answer to the same name.
 *
 * Refused rather than kept, because the model addresses them by name: two
 * called `query` are one the agent can fill and one it cannot reach, and which
 * is which is decided by whatever the provider's JSON does with a repeated key.
 */
class ToolParamDuplicateException(val name: String) :
    RuntimeException("This tool already takes a parameter called \"$name\""), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * A parameter says it takes an object without saying which.
 *
 * OBJECT is a reference to something the workspace defined; on its own it is not
 * a type at all. MAP is what to use for a shape nobody has written down — and the
 * message says so, because that is the choice being made.
 */
class ToolObjectRequiredException(val name: String) : RuntimeException(
    "\"$name\" is declared as an object but no object is chosen. Pick one of this " +
        "workspace's objects, or use map for a structure without a defined shape.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * One half of a tool's code arrived without the other.
 *
 * Refused rather than guessed at: compiling TypeScript is the editor's job and
 * nothing on this side can strip types or put them back, so a save that carried
 * only one half would leave the two permanently out of step.
 */
class ToolCodeIncompleteException(val missing: String) : RuntimeException(
    "The $missing is missing. A tool's TypeScript and the JavaScript compiled " +
        "from it are saved together, so that what runs is always what was written.",
), Refusal {

    override val arguments get() = mapOf("missing" to missing)
}

/**
 * A tool an agent may call is not one to delete.
 *
 * Named agents rather than a count, because the way out is to go and take the
 * grant off each of them and "2 agents" does not say which. The agents are said
 * as "the agent Answerer" for the reason a workflow is: the sentence is read on
 * the tool list, where nothing else on the screen is an agent.
 */
class ToolInUseException(val name: String, val agents: List<String>) : RuntimeException(
    "$name is granted to ${agents.joinToString(", ")}, so it cannot be deleted",
), Refusal {

    override val arguments get() = mapOf("name" to name, "agents" to agents)
}


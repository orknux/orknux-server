package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A reusable instruction set that guides how an agent goes about something.
 *
 * The content is markdown opening with a frontmatter block — a `---` fence
 * naming and describing the skill — which is what makes a skill something an
 * agent can be handed rather than a note somebody left. [SkillFormat] is where
 * that shape is checked.
 */
@Entity
@Table(name = "agent_skill")
class AgentSkill(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The folder it lives in; every skill is in one, the way a memory is. */
    @Column(name = "catalog_id", nullable = false)
    var catalogId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    /**
     * What a graph or a command names it by: letters, underscores and hyphens,
     * unique in the workspace. Derived from the name unless somebody typed
     * one; see [SkillKeys]. Issue #381.
     */
    @Column(name = "skill_key", nullable = false, length = 120)
    var key: String = "",

    @Column(length = 500)
    var description: String? = null,

    @Column(nullable = false, columnDefinition = "text")
    var content: String,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)

interface AgentSkillRepository : JpaRepository<AgentSkill, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<AgentSkill>

    fun findByCatalogId(catalogId: Long, pageable: Pageable): Page<AgentSkill>

    fun findByCatalogId(catalogId: Long): List<AgentSkill>

    fun countByCatalogId(catalogId: Long): Long

    fun deleteByCatalogId(catalogId: Long)

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): AgentSkill?

    /** The one this id names here, whatever case it was typed in. Issue #381. */
    fun findByWorkspaceIdAndKeyIgnoreCase(workspaceId: Long, key: String): AgentSkill?

    /** Every skill the workspace can still use, for a graph naming them by id. */
    fun findByWorkspaceIdAndEnabledTrue(workspaceId: Long): List<AgentSkill>
}

/**
 * A skill's id: what a workflow graph, a Slack command or a plugin names it by.
 *
 * Letters, underscores and hyphens, and nothing else - so that a command
 * marker followed by a word is always a whole id, and a word out of a
 * message never half-matches one. Unique in a workspace, the way a name is;
 * a plugin's skill gets one derived from its name the same way. Issue #381.
 */
object SkillKeys {

    val RULE = Regex("[A-Za-z_-]+")

    private val UNWANTED = Regex("[^A-Za-z_-]+")

    /** Everything but letters, for telling two spellings of one id apart from two ids. */
    private val NOT_A_LETTER = Regex("[^a-z]")

    /** Whether this is a usable id: only the characters the rule allows, and at least one. */
    fun usable(key: String): Boolean = key.length in 1..KEY_LENGTH && RULE.matches(key)

    /**
     * The id a name becomes: a hyphen where the rule refuses a run of
     * characters, so the words stay words - "Answering in a thread" is
     * `answering-in-a-thread`, not `answeringinathread`. A name with nothing
     * left - "2024", say - is given a stand-in.
     *
     * The words matter because this id is what a model types back at
     * `skill_load` and what a person types after the command marker. Run
     * together it reads as one long word nobody can spell from memory, which is
     * how a model ends up guessing at shapes like `plugin::skill`.
     */
    fun derive(name: String): String = UNWANTED.replace(name.trim(), "-")
        .trim('-')
        .lowercase()
        .take(KEY_LENGTH)
        .trim('-')
        .ifEmpty { "skill" }

    /**
     * Whether two ids are the same id said differently.
     *
     * Only the letters are compared, so `answering-in-a-thread`, the
     * `Answeringinathread` an older rule derived, and `Answering in a thread`
     * are one id. It is what lets a name typed from memory find its skill, and
     * what lets an id written on a graph before the rule changed go on working.
     */
    fun same(one: String, other: String): Boolean = bare(one).isNotEmpty() && bare(one) == bare(other)

    /** An id with everything but its letters taken off, for [same]. */
    fun bare(key: String): String = NOT_A_LETTER.replace(key.trim().lowercase(), "")

    /**
     * The first of this id and its lettered variants that nothing holds:
     * `review`, `review-b`, `review-c`. Letters rather than digits because
     * the rule allows no digits.
     */
    fun free(wanted: String, taken: (String) -> Boolean): String {
        if (!taken(wanted)) return wanted
        for (letter in 'b'..'z') {
            val candidate = "${wanted.take(KEY_LENGTH - 2)}-$letter"
            if (!taken(candidate)) return candidate
        }
        throw SkillKeyTakenException(wanted)
    }

    const val KEY_LENGTH = 120
}

/**
 * A folder of skills.
 *
 * Its own table rather than a label, for the reason a memory catalog is one: the
 * screen lists catalogs beside the skills of the one selected, so a catalog is a
 * thing that exists, and has a count worth showing, before anything is in it.
 * It is also the unit an agent is granted.
 */
@Entity
@Table(name = "skill_catalog")
class SkillCatalog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_by", nullable = false, length = 120)
    val createdBy: String = "",
)

interface SkillCatalogRepository : JpaRepository<SkillCatalog, Long> {

    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long): List<SkillCatalog>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): SkillCatalog?
}

/** Whether a piece of content is shaped like a skill, and where it is not. */
data class FormatCheck(val valid: Boolean, val message: String? = null, val line: Int? = null)

/**
 * The frontmatter a skill opens with.
 *
 * A skill is handed to an agent, so it has to say what it is called and what it
 * is for in a place that can be read without reading the whole of it. That is
 * the fenced block at the top; everything after it is the skill itself.
 */
object SkillFormat {

    private const val FENCE = "---"

    fun check(content: String): FormatCheck {
        val lines = content.lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first == -1) return FormatCheck(false, "A skill needs a frontmatter block and a body", 1)
        if (lines[first].trim() != FENCE) {
            return FormatCheck(false, "A skill opens with a $FENCE frontmatter fence", first + 1)
        }

        val closing = lines.drop(first + 1).indexOfFirst { it.trim() == FENCE }
        if (closing == -1) return FormatCheck(false, "The frontmatter fence is never closed", first + 1)

        val frontmatter = lines.subList(first + 1, first + 1 + closing)
        for (field in listOf("name", "description")) {
            val entry = frontmatter.firstOrNull { it.trimStart().startsWith("$field:") }
                ?: return FormatCheck(false, "The frontmatter has no $field", first + 1)
            if (entry.substringAfter("$field:").isBlank()) {
                return FormatCheck(false, "The frontmatter $field is empty", first + 2 + frontmatter.indexOf(entry))
            }
        }

        val body = lines.drop(first + closing + 2)
        if (body.all { it.isBlank() }) {
            return FormatCheck(false, "The skill has frontmatter but no body", first + closing + 2)
        }
        return FormatCheck(true)
    }

    /** What a new skill starts as: the shape, with the parts named. */
    fun starter(name: String, description: String?): String = """
        $FENCE
        name: $name
        description: ${description ?: "What this skill is for."}
        $FENCE

        # $name

        ## Objective

        What an agent following this is trying to achieve.

        ## Steps

        - The first thing to do
    """.trimIndent()
}

class SkillNotFoundException(val id: Long) : RuntimeException("No skill with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class SkillCatalogNotFoundException(val id: Long) : RuntimeException("No skill catalog with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class SkillCatalogNameTakenException(val name: String) :
    RuntimeException("This workspace already has a skill catalog called $name"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class SkillCatalogNameInvalidException : RuntimeException("A skill catalog needs a name")

class SkillNameTakenException(val name: String) :
    RuntimeException("A skill named \"$name\" already exists in this workspace"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class SkillNameInvalidException : RuntimeException("A skill name is required")

class SkillKeyInvalidException(val key: String) : RuntimeException(
    "\"$key\" cannot be a skill id: letters, underscores and hyphens only, up to ${SkillKeys.KEY_LENGTH} of them",
), Refusal {

    override val arguments get() = mapOf("key" to key)
}

class SkillKeyTakenException(val key: String) :
    RuntimeException("A skill with the id \"$key\" already exists in this workspace"), Refusal {

    override val arguments get() = mapOf("key" to key)
}

class SkillContentInvalidException(val reason: String) : RuntimeException(reason), Refusal {

    override val arguments get() = mapOf("reason" to reason)
}

/**
 * A skill catalog an agent draws on is not one to delete.
 *
 * Said of the catalog and not of the skills inside it, because the catalog is
 * what an agent is granted: the skills go with it, so the loss is every page in
 * the folder at once.
 */
class SkillCatalogInUseException(val name: String, val agents: List<String>) : RuntimeException(
    "$name is granted to ${agents.joinToString(", ")}, so it cannot be deleted",
), Refusal {

    override val arguments get() = mapOf("name" to name, "agents" to agents)
}


package io.mszymanski.orknux.server.agent

import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service

/**
 * The skills Orknux brings itself. Issue #468.
 *
 * A skill is a page of markdown saying how to go about something, and a few of
 * them are not about any one workspace: how to answer briefly, and whatever
 * follows it. Every installation that wanted one wrote it again, which is the
 * same argument that made the to-do list and the clock built-in tools rather
 * than a plugin to install.
 *
 * ### Read from files, and read once
 *
 * The text lives in markdown files under `resources/skills` rather than in a
 * string here: a skill is a document somebody edits, and a document held in a
 * Kotlin string is one nobody will. Loaded at startup, because these change when the server is
 * replaced and never between two calls.
 *
 * ### Offered as a catalog, hidden from the Skills page
 *
 * Granted the way a plugin's skills are - a catalog name on the agent's list -
 * so nothing downstream learns a third kind of skill exists: the briefing
 * lists them, `skill_load` loads them, a graph names them by id. What they are
 * deliberately not is rows on the Skills page. That page is where a workspace
 * writes its own, and a row nobody can rename, edit or delete is clutter on the
 * one screen people go to to do exactly those things.
 */
@Service
class BuiltInSkills {

    /**
     * The one catalog, or none where the files are missing - a build without
     * them should offer an empty picker rather than a folder with nothing in
     * it, which is a thing somebody grants and then wonders about.
     */
    fun catalogs(): List<PluginSkillCatalog> = held.takeIf { it.isNotEmpty() }?.let {
        listOf(PluginSkillCatalog(name = CATALOG, key = KEY, plugin = "Orknux", skills = it))
    }.orEmpty()

    private val held: List<GrantedSkill> by lazy { read() }

    private fun read(): List<GrantedSkill> {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val files = runCatching { resolver.getResources("classpath*:skills/*.md") }.getOrDefault(emptyArray())
        return files.mapNotNull { file ->
            val content = runCatching { file.inputStream.bufferedReader().use { it.readText() } }.getOrNull()
                ?: return@mapNotNull null
            val name = nameOf(content) ?: file.filename?.removeSuffix(".md") ?: return@mapNotNull null
            GrantedSkill(
                name = name,
                key = SkillKeys.derive(name),
                description = describedIn(content),
                catalog = CATALOG,
                content = content,
            )
        }.sortedBy { it.name }
    }

    /** What the frontmatter calls it; the file's name where it says nothing. */
    private fun nameOf(content: String): String? = frontmatter(content, "name")

    private fun describedIn(content: String): String? = frontmatter(content, "description")

    /**
     * One field out of the `---` block a skill opens with - the same block
     * [SkillFormat] checks, read here rather than parsed by a YAML library
     * because two keys on their own lines is the whole of what is being read.
     */
    private fun frontmatter(content: String, field: String): String? = content.lineSequence()
        .takeWhile { it.trim() != "---" || content.startsWith("---") }
        .take(FRONTMATTER_LINES)
        .firstOrNull { it.trimStart().startsWith("$field:") }
        ?.substringAfter("$field:")
        ?.trim()
        ?.ifEmpty { null }

    companion object {

        /**
         * `orknux_skills` - what goes on an agent's grant list.
         *
         * Suffixed like a plugin's for the same reason: the name is a grant
         * string and has to be this one's alone, where a bare `orknux` could be
         * a folder a workspace already has.
         */
        const val CATALOG = "orknux_skills"

        const val KEY = "orknux"

        /** How far into a file the frontmatter can be; it is the first thing in one. */
        private const val FRONTMATTER_LINES = 20
    }
}

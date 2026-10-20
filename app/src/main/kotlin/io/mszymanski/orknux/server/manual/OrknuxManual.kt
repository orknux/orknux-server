package io.mszymanski.orknux.server.manual

import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The manual the interface shows under /docs, as the server holds it.
 *
 * The chapters are the interface's markdown, `orknux-ui/src/docs`, put on the
 * classpath at `manual/` by every build - see `app/pom.xml`. One source rather
 * than a copy, so what an agent is told about a screen is what a person reading
 * the manual beside it is told, for the version actually running.
 *
 * Split at its `##` headings, because a chapter runs to sixty thousand
 * characters and a section to fifteen: the section is the unit a model can read
 * in one go and the unit a question is usually about.
 */
class OrknuxManual(val chapters: List<ManualChapter>) {

    fun chapter(slug: String): ManualChapter? = chapters.firstOrNull { it.slug == slug }

    /** A section by `chapter/section`, or a chapter's opening by its slug alone. */
    fun section(id: String): ManualSection? {
        val chapter = chapter(id.substringBefore('/')) ?: return null
        return if ('/' in id) chapter.sections.firstOrNull { it.id == id } else chapter.opening
    }

    /**
     * The sections holding every one of the words, the most mentions first.
     *
     * Plain word matching, which is what the interface's own search does: the
     * manual is a quarter of a megabyte and fits in memory many times over, and
     * a model searching it knows the product's words better than any ranking
     * would guess them.
     */
    fun search(query: String, limit: Int): List<ManualHit> {
        val words = query.lowercase().split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return chapters.asSequence()
            .flatMap { listOf(it.opening) + it.sections }
            .mapNotNull { section ->
                val text = (section.title + "\n" + section.text).lowercase()
                if (words.any { it !in text }) return@mapNotNull null
                val mentions = words.sumOf { word -> Regex(Regex.escape(word)).findAll(text).count() }
                val lines = section.text.lines()
                    .filter { line -> words.any { it in line.lowercase() } }
                    .sortedByDescending { line -> words.count { it in line.lowercase() } }
                    .take(SNIPPETS)
                    .map { it.trim().take(SNIPPET) }
                ManualHit(section, mentions, lines)
            }
            .sortedByDescending { it.mentions }
            .take(limit)
            .toList()
    }

    companion object {
        private const val SNIPPETS = 2
        private const val SNIPPET = 240

        /** For a test, which hands the chapters in rather than reading the jar. */
        fun of(files: Map<String, String>) = OrknuxManual(parse(files))

        /** The manual this build carries; empty where it was built without one. */
        fun fromClasspath(): OrknuxManual {
            val found: Array<Resource> = PathMatchingResourcePatternResolver().getResources("classpath*:manual/*.md")
            return of(
                found.mapNotNull { resource ->
                    resource.filename?.let { name -> name to resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
                }.toMap(),
            )
        }

        /**
         * Chapters ordered by their file's leading number and named by their
         * first heading, as the interface does it; `02-workflows.md` is the
         * chapter `workflows`. A translated file, `02-workflows.pl.md`, is left
         * out - these tools answer in English.
         */
        fun parse(files: Map<String, String>): List<ManualChapter> = files.entries
            .filter { (name, _) -> name.endsWith(".md") && !Regex("\\.[a-z]{2}\\.md$").containsMatchIn(name) }
            .sortedBy { it.key }
            .map { (name, body) -> chapterOf(name.removeSuffix(".md").replace(Regex("^\\d+-"), ""), body.replace("\r\n", "\n")) }

        private fun chapterOf(slug: String, body: String): ManualChapter {
            val lines = body.lines()
            var title = slug
            val opening = StringBuilder()
            val sections = mutableListOf<Pair<String, StringBuilder>>()
            var fenced = false
            for (line in lines) {
                // A `# comment` inside a shell example is not a heading.
                if (line.trimStart().startsWith("```")) fenced = !fenced
                when {
                    !fenced && line.startsWith("# ") && title == slug && sections.isEmpty() && opening.isBlank() ->
                        title = line.removePrefix("# ").trim()
                    !fenced && line.startsWith("## ") -> sections += line.removePrefix("## ").trim() to StringBuilder()
                    sections.isEmpty() -> opening.appendLine(line)
                    else -> sections.last().second.appendLine(line)
                }
            }
            val taken = mutableSetOf<String>()
            return ManualChapter(
                slug = slug,
                title = title,
                opening = ManualSection(slug, slug, title, opening.toString().trim(), emptyList()),
                sections = sections.map { (heading, text) ->
                    var id = "$slug/${slugOf(heading)}"
                    var n = 2
                    while (!taken.add(id)) id = "$slug/${slugOf(heading)}-${n++}"
                    val content = text.toString().trim()
                    ManualSection(id, slug, heading, content, subheadings(content))
                },
            )
        }

        private fun subheadings(text: String): List<String> {
            var fenced = false
            return text.lines().mapNotNull { line ->
                if (line.trimStart().startsWith("```")) fenced = !fenced
                line.takeIf { !fenced && it.startsWith("### ") }?.removePrefix("### ")?.trim()
            }
        }

        fun slugOf(heading: String): String =
            heading.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "section" }
    }
}

data class ManualChapter(
    /** From the file name with its ordering prefix dropped, and the interface's address for it: `/docs/<slug>`. */
    val slug: String,
    val title: String,
    /** What the chapter says before its first section, addressed by the chapter's slug alone. */
    val opening: ManualSection,
    val sections: List<ManualSection>,
)

data class ManualSection(
    /** `chapter/section`, like `workflows/the-editor`; the chapter's slug alone for its opening. */
    val id: String,
    val chapter: String,
    val title: String,
    val text: String,
    /** The `###` headings inside it, so a contents page says what a long section covers. */
    val subheadings: List<String>,
)

data class ManualHit(val section: ManualSection, val mentions: Int, val lines: List<String>)

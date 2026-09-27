package io.mszymanski.orknux.server.embedded

/**
 * Mermaid, as PlantUML reads it. Issue #487.
 *
 * ## Why translate rather than refuse
 *
 * Models write mermaid. It is what the training data is full of, it is what
 * every markdown renderer they have seen supports, and a tool that says "write
 * PlantUML instead" loses that argument every time - the old renderer's own
 * failure was a model trying four spellings of a mermaid pie chart and then
 * telling somebody the feature was unavailable.
 *
 * So mermaid goes in. What comes out the other side is PlantUML, which draws
 * it properly and draws the kinds mermaid's own five-kind parser refused.
 *
 * ## What this is not
 *
 * Not a mermaid implementation. It reads the header, maps the kinds that have a
 * direct PlantUML equivalent, and rewrites the handful of shapes that differ.
 * Anything already written as PlantUML - anything opening with `@start` - is
 * passed through untouched, which is the escape hatch for a diagram mermaid
 * cannot express.
 *
 * Where a kind has no clean mapping the source is handed to PlantUML as it
 * stands. That is deliberate: PlantUML's own refusal names the line it could
 * not read, which is more use than ours guessing.
 */
object Mermaid {

    /**
     * The source as PlantUML, wrapped in `@startuml` where it needs to be.
     *
     * Left exactly as it is when it already opens with `@start`: a model that
     * wrote PlantUML meant PlantUML, and a translator that touched it would be
     * corrupting the one input that needed no help.
     */
    fun asPlantUml(source: String): String {
        val written = source.trim()
        if (written.startsWith("@start")) return written

        /*
         * Kept both ways round. Every kind but one reads better with the
         * indentation gone, and a mindmap *is* its indentation - mermaid nests
         * by how far in a line starts, so trimming first would flatten every
         * mindmap to one level.
         */
        val kept = written.lines().filter { it.isBlank().not() && !it.trim().startsWith("%%") }
        val lines = kept.map { it.trim() }
        val header = lines.firstOrNull().orEmpty()

        return when {
            FLOW.containsMatchIn(header) -> flowchart(lines)
            header.startsWith("sequenceDiagram") -> wrapped(sequence(lines.drop(1)))
            header.startsWith("classDiagram") -> wrapped(lines.drop(1).joinToString(LINE))
            header.startsWith("erDiagram") -> wrapped("!pragma layout smetana" + LINE + lines.joinToString(LINE))
            STATE.containsMatchIn(header) -> wrapped(state(lines.drop(1)))
            header.startsWith("gantt") -> wrapped("!theme plain" + LINE + lines.joinToString(LINE))
            header.startsWith("mindmap") -> mindmap(kept.drop(1))
            header.startsWith("journey") -> wrapped(lines.drop(1).joinToString(LINE))
            /*
             * Not a header this knows. Handed over whole rather than refused
             * here, because PlantUML reads a great deal more than mermaid does
             * and its refusal, when it does refuse, names the line.
             */
            else -> wrapped(written)
        }
    }

    /**
     * The mermaid headers that are charts rather than diagrams. Issues #487 and #507.
     *
     * PlantUML has no pie chart - there is no `@startpie`, and asking for one
     * comes back as a picture of a syntax error rather than as a refusal. Which
     * is right, because a pie is not a diagram: it plots numbers, and plotting
     * numbers is the chart renderer's work.
     *
     * So they are named here and refused by name, pointing at the tool that
     * does draw them. Guessing a translation would put a complaint in
     * somebody's report and call it a drawing - which is the fault this whole
     * change is about.
     */
    val CHARTS = setOf("pie", "xychart", "quadrantchart", "sankey", "radar", "treemap")

    /** Whether this source is one of those, read off its first word. */
    fun isChart(source: String): Boolean {
        val header = source.trim().lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("%%") }
            .orEmpty()
        return header.substringBefore(' ').substringBefore('-').lowercase() in CHARTS
    }

    /** `@startuml` … `@enduml`, which is how every PlantUML source is delimited. */
    private fun wrapped(body: String): String =
        "@startuml" + LINE + body + LINE + "@enduml"

    /**
     * A flowchart, which is the one every model reaches for first.
     *
     * PlantUML's activity syntax is a different language, so this becomes a
     * component diagram: `A --> B` means the same thing in both, and the
     * direction on the header maps onto PlantUML's own. Left to right is the
     * one worth getting right, because that is what a wide diagram needs and
     * what mermaid's `LR` says.
     *
     * **The nodes are declared, not written inline.** Mermaid gives a node its
     * label where it first appears - `A[Webhook] --> B{Verified?}` - and
     * PlantUML has no such form: writing `A [Webhook] --> B [Verified?]` is a
     * syntax error, which is exactly what the first cut of this produced. So
     * the labels are collected in one pass and emitted as `rectangle "Webhook"
     * as A` declarations, and the edges that follow carry bare aliases.
     *
     * The shape is kept where mermaid gave one: `{...}` is a decision and
     * becomes a hexagon, and everything else a plain box.
     */
    private fun flowchart(lines: List<String>): String {
        val header = lines.firstOrNull().orEmpty()
        val direction = FLOW.find(header)?.groupValues?.getOrNull(2)?.uppercase().orEmpty()
        val laid = when (direction) {
            "LR" -> "left to right direction"
            "RL" -> "right to left direction"
            "BT" -> "bottom to top direction"
            else -> "top to bottom direction"
        }

        /*
         * Declaration order is the order the nodes are first mentioned, which
         * is the order somebody wrote them and reads better than sorting would.
         */
        val labelled = LinkedHashMap<String, String>()
        val shaped = LinkedHashMap<String, String>()
        val edges = mutableListOf<String>()

        lines.drop(1).forEach { line ->
            val bare = NODE.replace(line) { found ->
                val alias = found.groupValues[1]
                val opened = found.groupValues[2]
                val said = found.groupValues[3].trim()
                if (said.isNotEmpty()) {
                    labelled[alias] = said
                    /*
                     * `hexagon` rather than `diamond` for a decision: PlantUML
                     * has no diamond among its component shapes, and writing
                     * one is a syntax error that comes back as a picture of a
                     * complaint rather than as a failure. Hexagon is the shape
                     * it does have that reads as a branch.
                     */
                    shaped[alias] = if (opened == "{") "hexagon" else "rectangle"
                }
                alias
            }
            /*
             * Arrows first, then the label, and the order matters: mermaid
             * writes the label *inside* the arrow - `B -->|yes| C` - and
             * PlantUML writes it after the target, `B --> C : yes`. Replacing
             * it in place produced `B --> : yes C`, which is a syntax error,
             * and a syntax error here comes back as a picture of one.
             */
            /*
             * The other way mermaid labels an edge: the text inside the arrow
             * itself, `B -- Yes --> C`, and its dotted and thick spellings. It
             * is at least as common as the pipes, and it came through untouched
             * - a line PlantUML cannot read, in a diagram that then drew as a
             * picture of the error. Issue #525.
             */
            val inline = INLINE_LABEL.replace(bare) { found ->
                val arrow = when (found.groupValues[2]) {
                    "-." -> "..>"
                    else -> "-->"
                }
                found.groupValues[1] + " " + arrow + " " + found.groupValues[4] +
                    " : " + found.groupValues[3].trim()
            }
            val arrows = inline.replace("-.->", "..>").replace("==>", "-->")
            val edge = LABELLED_EDGE.replace(arrows) { found ->
                found.groupValues[1] + " " + found.groupValues[2] + " " + found.groupValues[4] +
                    " : " + found.groupValues[3].trim()
            }.trim()
            if (edge.isNotEmpty()) edges += edge
        }

        val declared = labelled.map { (alias, said) ->
            (shaped[alias] ?: "rectangle") + " \"" + said + "\" as " + alias
        }
        return wrapped((listOf(laid) + declared + edges).joinToString(LINE))
    }

    /**
     * A sequence diagram, which is nearly the same language in both.
     *
     * `participant`, `->>`, `-->>`, `note over` and `loop`/`end` all mean what
     * they look like. The arrows differ by a character and the activation marks
     * differ by a word.
     */
    private fun sequence(lines: List<String>): String = lines.joinToString(LINE) { line ->
        line
            .replace("->>+", "->")
            .replace("-->>-", "-->")
            .replace("->>", "->")
            .replace("-->>", "-->")
            .replace(Regex("^\\s*participant\\s+(\\w+)\\s+as\\s+(.+)$")) {
                "participant \"" + it.groupValues[2].trim() + "\" as " + it.groupValues[1]
            }
            .replace(Regex("^\\s*Note\\s+(over|left of|right of)\\s+", RegexOption.IGNORE_CASE)) {
                "note " + it.groupValues[1].lowercase() + " "
            }
    }

    /**
     * A state diagram. `[*]` means the same start and end marker in both, which
     * is most of it; the rest is the label separator.
     */
    private fun state(lines: List<String>): String = lines.joinToString(LINE) { line ->
        line.replace(Regex("\\s*:\\s*"), " : ")
    }

    /**
     * A mindmap. Mermaid indents to nest; PlantUML counts asterisks. So the
     * indent is measured and turned into that many stars, which is the whole
     * difference between the two.
     */
    private fun mindmap(lines: List<String>): String {
        val depths = lines.map { line -> line.takeWhile { it == ' ' }.length to line.trim() }
        val steps = depths.map { it.first }.distinct().sorted()
        val body = depths.joinToString(LINE) { (indent, said) ->
            "*".repeat(steps.indexOf(indent) + 1) + " " + said
        }
        return "@startmindmap" + LINE + body + LINE + "@endmindmap"
    }

    /** A newline, built rather than typed: a `\n` in a Kotlin string is fine, in a heredoc it is not. */
    private val LINE = 10.toChar().toString()

    private val FLOW = Regex("^(graph|flowchart)\\s+(TD|TB|LR|BT|RL)\\s*$", RegexOption.IGNORE_CASE)
    private val STATE = Regex("^stateDiagram(-v2)?\\s*$", RegexOption.IGNORE_CASE)
    /**
     * `A -- yes --> B`, `A == yes ==> B` and `A -. yes .-> B`: the label written
     * inside the arrow, which becomes `A --> B : yes`. The text may not hold an
     * arrow's own characters, which is what stops this reading `A --> B` as a
     * label of nothing.
     */
    private val INLINE_LABEL =
        Regex("(\\w+)\\s*(--|==|-\\.)\\s+([^\\-=.>|][^>|]*?)\\s+(?:-->|==>|\\.->)\\s*(\\w+)")

    /** `A -->|yes| B`, whose four parts become `A --> B : yes`. */
    private val LABELLED_EDGE = Regex("(\\w+)\\s*(-->|->|\\.\\.>)\\s*\\|([^|]*)\\|\\s*(\\w+)")
    private val NODE = Regex("(\\w+)([\\[({])([^\\])}]*)[\\])}]")
    private val SLICE = Regex("\"([^\"]+)\"\\s*:\\s*([0-9.]+)")
}

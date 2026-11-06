package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.chat.CodeRunTools
import io.mszymanski.orknux.server.chat.ScratchpadTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * `code_run` over a session's files, and a subagent reading the keys of the
 * conversation that asked it.
 *
 * The agent this came from had a 1,900-row orders CSV to mark from a list of
 * cancellations, no way to compute over it, and a subagent that was told the
 * attachment key it had been handed held nothing.
 */
@SpringBootTest
class CodeRunTest(
    @Autowired val pads: SessionScratchpadService,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val tools: ScratchpadTools,
    @Autowired val store: LlmSessionStore,
    @Autowired val mapper: ObjectMapper,
    @Autowired val agents: io.mszymanski.orknux.server.agent.AgentRepository,
    @Autowired val tuning: io.mszymanski.orknux.server.tuning.Tuning,
) {

    private var session: Long = 0
    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull((workspaces.findByName("code-run") ?: workspaces.save(Workspace(name = "code-run"))).id)
        session = recorder.open(workspaceId, "test", "code-run-${System.nanoTime()}")
    }

    private fun call(
        on: Long,
        name: String,
        arguments: Map<String, Any?>,
        agent: io.mszymanski.orknux.server.agent.Agent? = null,
    ): JsonNode = mapper.readTree(
        requireNotNull(tools.shed(on, agent)).run(ToolCall("c1", name, mapper.writeValueAsString(arguments))),
    )

    private fun run(
        code: String,
        language: String? = null,
        on: Long = session,
        agent: io.mszymanski.orknux.server.agent.Agent? = null,
    ): JsonNode = call(on, CodeRunTools.RUN, mapOf("code" to code, "language" to language), agent)

    /**
     * The half that would have finished the orders file: the statuses came from
     * one of the agent's own tools, a page of ids per call. A program calls it
     * in its loop, through the agent's own dispatch - so what the agent may not
     * call, the program may not either.
     */
    @Test
    fun `a program calls the agent's own tools in a loop, within its grants and its bound`() {
        val agent = agents.save(
            io.mszymanski.orknux.server.agent.Agent(
                workspaceId = workspaceId, name = "coder-${System.nanoTime()}", type = io.mszymanski.orknux.server.agent.AgentType.LLM,
            ),
        )
        val looped = run(
            """
            def main():
                days = [orknux.tools.call('date_today', {}) for _ in range(3)]
                return {'answers': len(days), 'first': days[0]}
            """.trimIndent(),
            agent = agent,
        )
        assertThat(looped.path("error").isMissingNode).describedAs(looped.toString()).isTrue()
        assertThat(looped.path("returned").path("answers").asInt()).isEqualTo(3)
        assertThat(looped.path("returned").path("first").toString()).describedAs(looped.toString()).doesNotContain("error")
        assertThat(looped.path("toolCalls").path("date_today").asInt()).isEqualTo(3)

        // Not from a loop: asking another agent.
        val asked = run("def main():\n    return orknux.tools.call('ask_agent', {'agent': 'x', 'question': 'y'})\n", agent = agent)
        assertThat(asked.path("returned").path("error").stringValue()).contains("cannot be called from a program")

        // What the agent's list hides, the program cannot reach either.
        agent.hiddenTools = (agent.hiddenTools + "date_today").toMutableList()
        val hidden = agents.save(agent)
        assertThat(run("def main():\n    return orknux.tools.call('date_today', {})\n", agent = hidden)
            .path("returned").path("error").stringValue()).contains("There is no tool called date_today")

        // Without an agent - a function, a tool, a plugin - there is nobody's tools to call.
        assertThat(run("def main():\n    return orknux.tools.call('date_today', {})\n").path("error").stringValue())
            .contains("only a program an agent runs with code_run")
    }

    /**
     * A function gets the web and the workspace's Slack connections without
     * asking, because a person wrote it. A program an agent wrote must not, or
     * `code_run` is every agent's way round its own grants.
     */
    @Test
    fun `a program reaches no web and no Slack of its own, and the agent is told its limits`() {
        val agent = agents.save(
            io.mszymanski.orknux.server.agent.Agent(
                workspaceId = workspaceId, name = "confined-${System.nanoTime()}", type = io.mszymanski.orknux.server.agent.AgentType.LLM,
            ),
        )
        val reached = run(
            """
            def main():
                return {
                    'http': orknux.http.request({'url': 'http://orknux.invalid/'}),
                    'slack': orknux.slack.post('any', 'C1', 'hello', None),
                }
            """.trimIndent(),
            agent = agent,
        )
        assertThat(reached.path("returned").path("http").path("error").stringValue()).describedAs(reached.toString())
            .contains("reaches only this conversation")
        assertThat(reached.path("returned").path("slack").toString()).contains("reaches only this conversation")

        val described = requireNotNull(tools.shed(session, agent)).specs().single { it.name == CodeRunTools.RUN }.description
        assertThat(described)
            .contains("${tuning[io.mszymanski.orknux.server.tuning.Tunable.CODE_RUN_SECONDS]} seconds")
            .contains("at most ${tuning[io.mszymanski.orknux.server.tuning.Tunable.CODE_RUN_TOOL_CALLS]} calls of your tools")
    }

    @Test
    fun `a Python program joins a CSV pad with a key and writes the result into a pad`() {
        pads.create(session, "orders.csv", null, "id,status\n1,NEW\n2,CANCELED\n3,SHIPPED\n4,CANCELED\n")
        store.put(session, "cancelled", mapper.writeValueAsString(listOf(2, 3)))

        val answer = run(
            """
            import csv, io

            def main():
                cancelled = set(orknux.session.store.get('cancelled'))
                rows = list(csv.DictReader(io.StringIO(orknux.scratchpad.text('orders.csv'))))
                out = io.StringIO()
                writer = csv.writer(out, lineterminator='\n')
                writer.writerow(['id', 'status', 'cancelled'])
                for row in rows:
                    writer.writerow([row['id'], row['status'], 'Y' if int(row['id']) in cancelled else 'N'])
                orknux.scratchpad.write('orders-marked.csv', out.getvalue())
                print('marked', len(cancelled))
                return {'rows': len(rows)}
            """.trimIndent(),
        )

        assertThat(answer.path("error").isMissingNode).describedAs(answer.toString()).isTrue()
        assertThat(answer.path("returned").path("rows").asInt()).isEqualTo(4)
        assertThat(answer.path("printed").toString()).contains("marked 2")
        assertThat(pads.find(session, "orders-marked.csv")?.content)
            .isEqualTo("id,status,cancelled\n1,NEW,N\n2,CANCELED,Y\n3,SHIPPED,Y\n4,CANCELED,N\n")
    }

    /** A spreadsheet export starts with a byte-order mark, and a program reading it by column name must not see it. */
    @Test
    fun `a CSV with a byte-order mark reads by its column names`() {
        store.put(session, "attachment.bom", mapper.writeValueAsString("\uFEFFid,status\n1,NEW\n"))
        pads.create(session, "bom.csv", null, "\uFEFFid,status\n2,OLD\n")
        val answer = run(
            """
            import csv, io

            def main():
                kept = list(csv.DictReader(io.StringIO(orknux.session.store.get('attachment.bom'))))
                pad = list(csv.DictReader(io.StringIO(orknux.scratchpad.text('bom.csv'))))
                return [kept[0]['id'], pad[0]['id']]
            """.trimIndent(),
        )
        assertThat(answer.path("returned").toString()).describedAs(answer.toString()).isEqualTo("[\"1\",\"2\"]")
    }

    @Test
    fun `JavaScript runs too, and a failure is said with what was printed before it`() {
        pads.create(session, "n.txt", null, "1\n2\n3\n")
        val summed = run(
            "export default function main() { return orknux.scratchpad.text('n.txt').trim().split('\\n').map(Number).reduce((a, b) => a + b, 0); }",
            language = "javascript",
        )
        assertThat(summed.path("returned").asInt()).describedAs(summed.toString()).isEqualTo(6)

        val failed = run("def main():\n    print('before')\n    return orknux.scratchpad.text('missing.txt')\n")
        assertThat(failed.path("error").stringValue()).contains("missing.txt")
        assertThat(failed.path("printed").toString()).contains("before")

        // A plain script, the way a model writes one first: its prints come back, and `result` is the answer.
        val script = run("import csv, io\nrows = list(csv.reader(io.StringIO(orknux.scratchpad.text('n.txt'))))\nprint('rows', len(rows))\nresult = len(rows)\n")
        assertThat(script.path("error").isMissingNode).describedAs(script.toString()).isTrue()
        assertThat(script.path("returned").asInt()).isEqualTo(3)
        assertThat(script.path("printed").toString()).contains("rows 3")
        assertThat(run("print(1)").path("printed").toString()).contains("1")
        // What json cannot write by itself is written the way anybody would: a set as a sorted list, a date as ISO text.
        val plain = run("import datetime, decimal\nresult = {'s': {3, 1, 2}, 'd': datetime.date(2024, 1, 2), 'n': decimal.Decimal('1.5')}\n")
        assertThat(plain.path("returned").toString()).describedAs(plain.toString()).isEqualTo("{\"s\":[1,2,3],\"d\":\"2024-01-02\",\"n\":1.5}")
    }

    @Test
    fun `the sandbox holds a program that tries to leave it`() {
        val answer = run("import os\n\ndef main():\n    return os.listdir('/')\n")
        assertThat(answer.path("returned").isMissingNode).describedAs(answer.toString()).isTrue()
        assertThat(answer.path("error").isString).isTrue()
    }

    @Test
    fun `a subagent reads the keys of the conversation that asked it, and writes only its own`() {
        store.put(session, "slack.1m50370", mapper.writeValueAsString("id,status\n1,NEW\n"))
        val child = recorder.openUnder(session, "Look into the orders")
        val grandchild = recorder.openUnder(child, "And deeper")

        assertThat(store.get(child, "slack.1m50370")).isEqualTo(mapper.writeValueAsString("id,status\n1,NEW\n"))
        assertThat(store.get(grandchild, "slack.1m50370")).describedAs("through every ancestor").isNotNull()
        assertThat(store.has(child, "slack.1m50370")).isTrue()

        // The asked agent's tools reach it the same way: a key into a pad, and a program reading it.
        val appended = call(child, ScratchpadTools.APPEND, mapOf("name" to "copy.csv", "key" to "slack.1m50370"))
        assertThat(appended.path("error").isMissingNode).describedAs(appended.toString()).isTrue()
        assertThat(run("def main():\n    return orknux.session.store.get('slack.1m50370')\n", on = child)
            .path("returned").stringValue()).startsWith("id,status")

        // Its own value under the same key shadows the asker's, and leaves the asker's alone.
        store.put(child, "slack.1m50370", mapper.writeValueAsString("changed"))
        assertThat(store.get(child, "slack.1m50370")).isEqualTo("\"changed\"")
        assertThat(store.get(session, "slack.1m50370")).isEqualTo(mapper.writeValueAsString("id,status\n1,NEW\n"))

        // Only up the line: a sibling's key is not the asker's.
        val sibling = recorder.openUnder(session, "Something else")
        store.put(sibling, "theirs", "1")
        assertThat(store.get(child, "theirs")).isNull()
    }

    @Test
    fun `a pad's answer no longer claims it is unshared`() {
        val written = call(session, ScratchpadTools.WRITE, mapOf("name" to "a.md", "content" to "x"))
        assertThat(written.path("written").asBoolean()).isTrue()
        assertThat(written.has("shared")).isFalse()
        assertThat(call(session, ScratchpadTools.LIST, emptyMap()).path("scratchpads").first().has("shared")).isFalse()
    }
}

package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `orknux.scratchpad`, from both sides of the sandbox. Issue #411.
 *
 * What is pinned here is the contract's shape rather than any storage: the
 * named methods reach the one door with the right operation and arguments, the
 * answer crosses back as parsed JSON, the door is keyed by the session the call
 * was made inside, and a call made inside no session is told so - because
 * "this wrote into nowhere" is the failure the design exists to rule out. What
 * the operations actually do to a file is ScratchpadTest's business, against
 * the real service.
 */
class SessionScratchpadsScriptTest {

    private class Recording : SessionScratchpads {
        val requests = mutableListOf<Pair<Long, String>>()
        var answer = """{"ok":true}"""

        override fun act(sessionId: Long, request: String): String {
            requests += sessionId to request
            return answer
        }
    }

    private val pads = Recording()
    private val runner = ScriptRunner(
        ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000),
        host = null,
        scratchpads = pads,
    )

    @Test
    fun `a named method reaches the door with its operation and arguments, and the answer crosses back`() {
        pads.answer = """{"ok":{"name":"draft.html","bytes":4,"shared":false}}"""

        val wrote = runner.call(
            "export default function draft() {\n  return orknux.scratchpad.write('draft.html', 'body', 'The page');\n}",
            "draft",
            emptyList(),
            on = 12,
            sessionId = 41,
        )

        // The answer came back parsed.
        assertThat((wrote as ScriptResult.Returned).json).contains("\"name\":\"draft.html\"")
        // The door was reached, keyed by the session, with the write operation and its arguments.
        assertThat(pads.requests).hasSize(1)
        val (session, request) = pads.requests.single()
        assertThat(session).isEqualTo(41L)
        assertThat(request).contains("\"op\":\"write\"")
        assertThat(request).contains("\"name\":\"draft.html\"")
        assertThat(request).contains("\"content\":\"body\"")
        assertThat(request).contains("\"description\":\"The page\"")
    }

    @Test
    fun `each method names the operation the server expects`() {
        runner.call(
            """
            export default function all() {
              orknux.scratchpad.list();
              orknux.scratchpad.read('a', 0, 10);
              orknux.scratchpad.append('a', 'x');
              orknux.scratchpad.replace('a', 'x', 'y');
              orknux.scratchpad.describe('a', 'what it is');
              orknux.scratchpad.share('a', true);
              orknux.scratchpad.search('needle');
              orknux.scratchpad.remove('a');
              return 'done';
            }
            """.trimIndent(),
            "all",
            emptyList(),
            on = 12,
            sessionId = 41,
        )

        val ops = pads.requests.map { Regex("\"op\":\"(\\w+)\"").find(it.second)?.groupValues?.get(1) }
        assertThat(ops).containsExactly("list", "read", "append", "replace", "describe", "share", "search", "delete")
    }

    @Test
    fun `a call made inside no session is told there are no scratchpads, in a sentence`() {
        val answer = runner.call(
            "export default function tryIt() {\n  return orknux.scratchpad.write('a', 'b');\n}",
            "tryIt",
            emptyList(),
            on = 12,
        )

        assertThat((answer as ScriptResult.Returned).json).contains("there are no scratchpads here")
        assertThat(pads.requests).isEmpty()
    }

    @Test
    fun `a refusal from the service comes back as the error`() {
        pads.answer = """{"error":"That would put this session's scratchpads over the budget."}"""

        val answer = runner.call(
            "export default function big() {\n  return orknux.scratchpad.append('a', 'lots');\n}",
            "big",
            emptyList(),
            on = 12,
            sessionId = 41,
        )

        assertThat((answer as ScriptResult.Returned).json).contains("over the budget")
    }

    /** The same door from a plugin's sandbox, which binds it separately. */
    @Test
    fun `a plugin's tool reaches the same scratchpads`() {
        val plugins = PluginRunner(
            PluginProperties(timeoutMillis = 10_000, statementLimit = 2_000_000),
            host = null,
            scratchpads = pads,
        )
        pads.answer = """{"ok":{"name":"log","bytes":3,"shared":false}}"""

        val answer = plugins.call(
            """
            export default class Scratchy extends OrknuxPlugin {
              id() { return 'scratchy'; }
              apiVersion() { return 1; }
              functions() {
                return [
                  new OrknuxFunction({
                    name: 'note',
                    returnType: 'string',
                    run: () => {
                      const said = orknux.scratchpad.append('log', 'one');
                      return said.ok.name;
                    },
                  }),
                ];
              }
            }
            """.trimIndent(),
            "note",
            emptyList(),
            on = 12,
            sessionId = 41,
        )

        assertThat((answer as ScriptResult.Returned).json).isEqualTo("\"log\"")
        assertThat(pads.requests.single().second).contains("\"op\":\"append\"")
    }
}

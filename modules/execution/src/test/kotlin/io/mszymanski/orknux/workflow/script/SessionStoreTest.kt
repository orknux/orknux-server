package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * `orknux.session.store`, from both sides of the sandbox.
 *
 * What is pinned is the contract's shape rather than any storage: the value
 * crosses as JSON and comes back parsed, the store is keyed by the session the
 * call was made inside, and a call made inside no session is told so in a
 * sentence - because "this wrote into nowhere" is the failure the design
 * exists to rule out.
 */
class SessionStoreTest {

    private class Remembering : SessionScratch {
        val held = ConcurrentHashMap<Pair<Long, String>, String>()

        override fun put(sessionId: Long, key: String, json: String): String? {
            held[sessionId to key] = json
            return null
        }

        override fun get(sessionId: Long, key: String): String? = held[sessionId to key]

        override fun remove(sessionId: Long, key: String) {
            held.remove(sessionId to key)
        }
    }

    private val scratch = Remembering()
    private val runner = ScriptRunner(
        ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000),
        host = null,
        scratch = scratch,
    )

    @Test
    fun `a value put in one call is there for the next, keyed by the session`() {
        val write = runner.call(
            """
            export default function remember(seen) {
              return orknux.session.store.put('cursor', { page: 3, seen });
            }
            """.trimIndent(),
            "remember",
            listOf("7"),
            on = 12,
            sessionId = 41,
        )
        assertThat((write as ScriptResult.Returned).json).contains("\"ok\":true")
        assertThat(scratch.held[41L to "cursor"]).isEqualTo("""{"page":3,"seen":7}""")

        val read = runner.call(
            "export default function recall() {\n  return orknux.session.store.get('cursor');\n}",
            "recall",
            emptyList(),
            on = 12,
            sessionId = 41,
        )
        assertThat((read as ScriptResult.Returned).json).isEqualTo("""{"page":3,"seen":7}""")
    }

    @Test
    fun `a key can be unset, and is gone for the next call`() {
        val write = runner.call(
            "export default function put() {\n  return orknux.session.store.put('cursor', { page: 3 });\n}",
            "put",
            emptyList(),
            on = 12,
            sessionId = 41,
        )
        assertThat((write as ScriptResult.Returned).json).contains("\"ok\":true")
        assertThat(scratch.held[41L to "cursor"]).isEqualTo("""{"page":3}""")

        val unset = runner.call(
            "export default function drop() {\n  return orknux.session.store.unset('cursor');\n}",
            "drop",
            emptyList(),
            on = 12,
            sessionId = 41,
        )
        assertThat((unset as ScriptResult.Returned).json).contains("\"ok\":true")
        assertThat(scratch.held).doesNotContainKey(41L to "cursor")

        val read = runner.call(
            "export default function recall() {\n  return orknux.session.store.get('cursor');\n}",
            "recall",
            emptyList(),
            on = 12,
            sessionId = 41,
        )
        assertThat((read as ScriptResult.Returned).json).isEqualTo("null")
    }

    @Test
    fun `unsetting inside no session is told there is no store, in a sentence`() {
        val answer = runner.call(
            "export default function tryIt() {\n  return orknux.session.store.unset('k');\n}",
            "tryIt",
            emptyList(),
            on = 12,
        )
        assertThat((answer as ScriptResult.Returned).json).contains("there is no session store here")
    }

    @Test
    fun `a call made inside no session is told there is no store, in a sentence`() {
        val answer = runner.call(
            """
            export default function tryIt() {
              return {
                put: orknux.session.store.put('k', 1),
                got: orknux.session.store.get('k'),
              };
            }
            """.trimIndent(),
            "tryIt",
            emptyList(),
            on = 12,
        )

        assertThat((answer as ScriptResult.Returned).json).contains("there is no session store here")
        assertThat(answer.json).contains("\"got\":null")
        assertThat(scratch.held).isEmpty()
    }

    @Test
    fun `a refusal from the store comes back as the error`() {
        val refusing = object : SessionScratch {
            override fun put(sessionId: Long, key: String, json: String) = "this session's store already holds 200 keys"
            override fun get(sessionId: Long, key: String): String? = null
            override fun remove(sessionId: Long, key: String) = Unit
        }
        val bounded = ScriptRunner(
            ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000),
            host = null,
            scratch = refusing,
        )

        val answer = bounded.call(
            "export default function fill() {\n  return orknux.session.store.put('k', 1);\n}",
            "fill",
            emptyList(),
            on = 12,
            sessionId = 41,
        )

        assertThat((answer as ScriptResult.Returned).json).contains("already holds 200 keys")
    }

    /** The same doors from a plugin's sandbox, which binds them separately. */
    @Test
    fun `a plugin's tool reaches the same store`() {
        val plugins = PluginRunner(
            PluginProperties(timeoutMillis = 10_000, statementLimit = 2_000_000),
            host = null,
            scratch = scratch,
        )

        val answer = plugins.call(
            """
            export default class Scratchy extends OrknuxPlugin {
              id() { return 'scratchy'; }
              apiVersion() { return 1; }
              functions() {
                return [
                  new OrknuxFunction({
                    name: 'count',
                    returnType: 'number',
                    run: () => {
                      const so_far = orknux.session.store.get('count') ?? 0;
                      orknux.session.store.put('count', so_far + 1);
                      return so_far + 1;
                    },
                  }),
                ];
              }
            }
            """.trimIndent(),
            "count",
            emptyList(),
            on = 12,
            sessionId = 41,
        )

        assertThat((answer as ScriptResult.Returned).json).isEqualTo("1")
        assertThat(scratch.held[41L to "count"]).isEqualTo("1")
    }
}

package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The one door a plugin has out of its sandbox.
 *
 * A plugin has no network and no way to ask for one — `IOAccess.NONE`, and a
 * permission list whose vocabulary cannot express a socket. But a plugin's whole
 * job is to know one outside service well, and there are questions about that
 * service which cannot be answered from a payload: what is in this Slack thread
 * is the one this was built for. So the server makes the call, under a named
 * grant, and what crosses is data. Issue #316.
 *
 * Five things this pins, and the first two are the security property:
 *
 *   ungranted   a plugin that was granted nothing is told so, in words, and the
 *               server is never asked. The helper exists and refuses; what is
 *               absent is the host behind it, which is the half that matters
 *   unaccepted  declaring is not being granted: what is bound is what was
 *               *granted*, never what the plugin asked for
 *   granted     one that was granted it can call it, and gets the answer back
 *   as data     what crosses is JSON both ways, so there is no host object on
 *               either side to walk from
 *   refusals    a refusal comes back as data rather than as a thrown error, so a
 *               plugin can say something useful about it
 */
class PluginCapabilityTest {

    private val asked = mutableListOf<Pair<PluginCapability, String>>()

    /** A server that answers, and writes down what it was asked. */
    private val host = PluginHost { capability, argument, _ ->
        asked += capability to argument
        """{"messages":[{"ts":"1.1","text":"hello"}],"replies":1}"""
    }

    private val runner = PluginRunner(
        PluginProperties(timeoutMillis = 5_000, statementLimit = 2_000_000),
        host,
    )

    /** A plugin that calls the capability and hands back whatever it got. */
    private val source = """
        export default class Reader extends OrknuxPlugin {
          id() { return 'reader'; }
          apiVersion() { return 1; }
          capabilities() { return ['SLACK_READ_THREAD']; }
          functions() {
            return [new OrknuxFunction({
              name: 'read',
              params: [{ name: 'channel', type: 'string' }],
              returnType: 'map',
              run: (channel) => orknux.slack.thread({ id: 7, type: 'SLACK' }, channel, '1.0'),
            })];
          }
        }
    """.trimIndent()

    @Test
    fun `a plugin granted nothing has no way to reach the server`() {
        val answer = runner.call(source, "read", listOf("\"#general\""), capabilities = emptySet())

        /*
         * Refused in words rather than by throwing whatever a call on undefined
         * throws - but the property that matters is the second assertion: the
         * host was never reached, so there was nothing to refuse *with*.
         */
        assertThat(answer).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((answer as ScriptResult.Returned).json).contains("was not granted SLACK_READ_THREAD")
        assertThat(asked).describedAs("the server was never asked").isEmpty()
    }

    /**
     * The half that matters most: what is bound is what was granted.
     *
     * A plugin declaring a capability is a plugin asking. Binding on the strength
     * of the declaration would make the acceptance decorative — which is the one
     * failure this whole arrangement exists to prevent.
     */
    @Test
    fun `declaring a capability is not being granted it`() {
        val answer = runner.call(source, "read", listOf("\"#general\""), capabilities = emptySet())

        assertThat((answer as ScriptResult.Returned).json).contains("was not granted SLACK_READ_THREAD")
        assertThat(asked).describedAs("the host was never asked").isEmpty()
    }

    @Test
    fun `a plugin granted one can call it, and gets the answer`() {
        val answer = runner.call(
            source,
            "read",
            listOf("\"#general\""),
            capabilities = setOf(PluginCapability.SLACK_READ_THREAD),
        )

        assertThat((answer as ScriptResult.Returned).json).contains("\"replies\":1")
        assertThat(asked).singleElement().satisfies({ (capability, argument) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_READ_THREAD)
            // The connection is handed over as its id, and everything crosses as
            // JSON: no host object on either side to walk from.
            assertThat(argument).isEqualTo("""[7,"#general","1.0",null]""")
        })
    }

    /**
     * A plugin's `orknux.http` is a function's, member for member.
     *
     * They were written out twice - once in each runner - and the copies had
     * drifted the moment functions were given HTTP: the plugin side kept an
     * older shape with no `post` and no parsed `json`, so the same call written
     * in the two places answered differently. Two copies of an API is two APIs,
     * so there is one now, and this is what says so.
     */
    @Test
    fun `a plugin gets the same http helper a function does`() {
        val answering = PluginRunner(
            PluginProperties(timeoutMillis = 5_000, statementLimit = 2_000_000),
            PluginHost { capability, argument, _ ->
                asked += capability to argument
                """{"status":201,"headers":{},"body":"{\"id\":9}"}"""
            },
        )

        val poster = """
            export default class Poster extends OrknuxPlugin {
              id() { return 'poster'; }
              apiVersion() { return 1; }
              capabilities() { return ['NETWORK_REQUEST']; }
              functions() {
                return [new OrknuxFunction({
                  name: 'make',
                  params: [{ name: 'url', type: 'string' }],
                  returnType: 'map',
                  run: (url) => {
                    const r = orknux.http.post(url, { title: 'hello' });
                    return { id: r.json.id, status: r.status };
                  },
                })];
              }
            }
        """.trimIndent()

        val answer = answering.call(
            poster,
            "make",
            listOf("\"https://api.example.com/tickets\""),
            capabilities = setOf(PluginCapability.NETWORK_REQUEST),
        )

        // `post` exists, an object body went out as JSON with the header, and
        // the JSON reply came back parsed.
        assertThat((answer as ScriptResult.Returned).json).contains("\"id\":9")
        assertThat(answer.json).contains("\"status\":201")
        assertThat(asked.single().second).contains("application/json")
        assertThat(asked.single().second).contains("title")
    }

    /**
     * A refusal is data.
     *
     * A plugin has to be able to say something useful about "that connection is
     * gone", and an exception here would surface as a plugin that failed for
     * reasons nobody can read.
     */
    @Test
    fun `a refusal from the server arrives as an answer rather than as a failure`() {
        val refusing = PluginRunner(
            PluginProperties(timeoutMillis = 5_000, statementLimit = 2_000_000),
            { _, _, _ -> """{"error":"that connection has been deleted"}""" },
        )

        val answer = refusing.call(
            source,
            "read",
            listOf("\"#general\""),
            capabilities = setOf(PluginCapability.SLACK_READ_THREAD),
        )

        assertThat(answer).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((answer as ScriptResult.Returned).json).contains("that connection has been deleted")
    }

    /**
     * `orknux.connections.query` is one helper in both sandboxes. Issue #597.
     *
     * The same call written in a function and in a plugin granted it reaches
     * the server as the same capability with the same argument and the run's
     * workspace, and comes back as the same answer - only the two keys the
     * filter knows cross, whatever else the guest put on the object.
     */
    @Test
    fun `a plugin gets the same connections helper a function does`() {
        val seen = mutableListOf<Triple<PluginCapability, String, Long?>>()
        val answering = PluginHost { capability, argument, on ->
            seen += Triple(capability, argument, on)
            """{"connections":[{"id":7,"name":"Support Slack","type":"SLACK"}]}"""
        }
        val call = "orknux.connections.query({ type: 'SLACK', name: 'support slack', secret: 'x', get extra() { return 1; } })"

        val function = ScriptRunner(ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000), answering)
            .call("export default function find() { return $call; }", "find", emptyList(), on = 12)
        val plugin = PluginRunner(PluginProperties(timeoutMillis = 5_000, statementLimit = 2_000_000), answering).call(
            """
                export default class Finder extends OrknuxPlugin {
                  id() { return 'finder'; }
                  apiVersion() { return 1; }
                  capabilities() { return ['CONNECTIONS_QUERY']; }
                  functions() {
                    return [new OrknuxFunction({ name: 'find', params: [], returnType: 'map', run: () => $call })];
                  }
                }
            """.trimIndent(),
            "find",
            emptyList(),
            capabilities = setOf(PluginCapability.CONNECTIONS_QUERY),
            on = 12,
        )

        assertThat((function as ScriptResult.Returned).json).contains("\"name\":\"Support Slack\"")
        assertThat((plugin as ScriptResult.Returned).json).isEqualTo(function.json)
        assertThat(seen).hasSize(2).allSatisfy({ (capability, argument, on) ->
            assertThat(capability).isEqualTo(PluginCapability.CONNECTIONS_QUERY)
            assertThat(argument).isEqualTo("""{"type":"SLACK","name":"support slack"}""")
            assertThat(on).describedAs("the run's workspace, not the script's").isEqualTo(12L)
        })
    }

    /** No filter, or an empty one, asks for everything - and a filter that is not an object is refused in the guest. */
    @Test
    fun `an absent filter asks for every connection, and a wrong one is refused before the server`() {
        val seen = mutableListOf<String>()
        val answering = ScriptRunner(
            ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000),
            PluginHost { _, argument, _ -> seen += argument; """{"connections":[]}""" },
        )

        answering.call("export default function a() { return orknux.connections.query(); }", "a", emptyList(), on = 12)
        answering.call("export default function b() { return orknux.connections.query({}); }", "b", emptyList(), on = 12)
        val refused = answering.call("export default function c() { return orknux.connections.query('SLACK'); }", "c", emptyList(), on = 12)

        assertThat(seen).containsExactly("{}", "{}")
        assertThat((refused as ScriptResult.Returned).json).contains("takes an object")
    }

    @Test
    fun `a plugin not granted the connections query is refused without the server being asked`() {
        val lister = """
            export default class Lister extends OrknuxPlugin {
              id() { return 'lister'; }
              apiVersion() { return 1; }
              capabilities() { return ['CONNECTIONS_QUERY']; }
              functions() {
                return [new OrknuxFunction({ name: 'list', params: [], returnType: 'map', run: () => orknux.connections.query() })];
              }
            }
        """.trimIndent()

        // Declared and not accepted, and granted something else entirely: refused either way.
        listOf(emptySet(), setOf(PluginCapability.SLACK_READ_THREAD)).forEach { granted ->
            val answer = runner.call(lister, "list", emptyList(), capabilities = granted, on = 12)
            assertThat((answer as ScriptResult.Returned).json).contains("was not granted CONNECTIONS_QUERY")
        }
        assertThat(asked).describedAs("the server was never asked").isEmpty()
    }

    /** What it declares is readable, so a person can be shown it before granting. */
    @Test
    fun `what a plugin asks the server for is read off it`() {
        val read = runner.inspect(source)

        assertThat(read).isInstanceOf(PluginInspection.Read::class.java)
        assertThat((read as PluginInspection.Read).capabilities).containsExactly("SLACK_READ_THREAD")
    }
}

package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A workflow function reaching the one door out, exactly as a plugin does.
 *
 * The capability was a plugin's alone at first, and that was the wrong line to
 * have drawn: a function condition is what people actually write - #316's own
 * example, *is this the first reply in the thread*, is a condition - and getting
 * at it meant packaging a plugin and having somebody accept it.
 *
 * Three things, and the third is the one that had to be built before the other
 * two were safe:
 *
 *   the helper    a script calls `orknux.slack.thread(...)` and gets the answer
 *   as data       what crosses is JSON both ways, so there is no host object on
 *                 either side to walk from
 *   the boundary  the workspace the run belongs to reaches the host, and the
 *                 script neither sees it nor can set it. Without that, a
 *                 function could read another workspace's Slack by guessing a
 *                 connection id - which is why the plugin's own host is scoped
 *                 the same way now.
 */
class ScriptHostTest {

    private val asked = mutableListOf<Triple<PluginCapability, String, Long?>>()

    private val host = PluginHost { capability, argument, on ->
        asked += Triple(capability, argument, on)
        """{"messages":[{"ts":"1.1","text":"hello"}],"replies":1}"""
    }

    private val runner = ScriptRunner(ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000), host)

    private val source = """
        export default function read(channel) {
          return orknux.slack.thread({ id: 7, type: 'SLACK' }, channel, '1.0');
        }
    """.trimIndent()

    @Test
    fun `a function can read a thread through the server`() {
        val answer = runner.call(source, "read", listOf("\"#general\""), on = 12)

        assertThat(answer).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((answer as ScriptResult.Returned).json).contains("\"replies\":1")
    }

    /**
     * The boundary, and it is the whole reason this is safe to offer at all.
     *
     * The workspace comes from the run. A script that wants to read somewhere
     * else has nothing to write: there is no argument for it and no global
     * holding it.
     */
    @Test
    fun `the workspace the run belongs to is what reaches the server`() {
        runner.call(source, "read", listOf("\"#general\""), on = 12)

        assertThat(asked).singleElement().satisfies({ (capability, argument, on) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_READ_THREAD)
            assertThat(on).describedAs("the run's workspace, not the script's idea of one").isEqualTo(12L)
            // Everything crosses as JSON: no host object on either side.
            assertThat(argument).isEqualTo("""[7,"#general","1.0",null]""")
        })
    }

    @Test
    fun `a function can post a message through the server`() {
        val poster = """
            export default function post(channel) {
              return orknux.slack.post({ id: 7, type: 'SLACK' }, channel, 'hi', '1.0');
            }
        """.trimIndent()

        runner.call(poster, "post", listOf("\"#general\""), on = 12)

        assertThat(asked).singleElement().satisfies({ (capability, argument, on) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_POST_MESSAGE)
            assertThat(on).isEqualTo(12L)
            assertThat(argument).isEqualTo("""[7,"#general","hi","1.0"]""")
        })
    }

    @Test
    fun `a function can add a reaction through the server`() {
        val reactor = """
            export default function react(channel) {
              return orknux.slack.react({ id: 7, type: 'SLACK' }, channel, '1.0', 'thumbsup');
            }
        """.trimIndent()

        runner.call(reactor, "react", listOf("\"#general\""), on = 12)

        assertThat(asked).singleElement().satisfies({ (capability, argument, on) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_ADD_REACTION)
            assertThat(on).isEqualTo(12L)
            assertThat(argument).isEqualTo("""[7,"#general","1.0","thumbsup"]""")
        })
    }

    @Test
    fun `a function can follow a message link through the server`() {
        val follower = """
            export default function follow(link) {
              return orknux.slack.message({ id: 7, type: 'SLACK' }, link);
            }
        """.trimIndent()

        runner.call(follower, "follow", listOf("\"https://x.slack.com/archives/C1/p1\""), on = 12)

        assertThat(asked).singleElement().satisfies({ (capability, argument, on) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_READ_MESSAGE)
            assertThat(on).isEqualTo(12L)
            assertThat(argument).isEqualTo("""[7,"https://x.slack.com/archives/C1/p1"]""")
        })
    }

    @Test
    fun `a function can ask who a user id is through the server`() {
        val who = """
            export default function who(id) {
              return orknux.slack.user({ id: 7, type: 'SLACK' }, id);
            }
        """.trimIndent()

        runner.call(who, "who", listOf("\"<@U0123ABCD>\""), on = 12)

        assertThat(asked).singleElement().satisfies({ (capability, argument, on) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_READ_USER)
            assertThat(on).isEqualTo(12L)
            assertThat(argument).isEqualTo("""[7,"<@U0123ABCD>"]""")
        })
    }

    /** The door a plugin's SlackUser type completes values through. Issue #377. */
    @Test
    fun `a function can ask for Slack suggestions through the server`() {
        val picker = """
            export default function pick(typed) {
              return orknux.slack.suggest({ id: 7, type: 'SLACK' }, typed, 'USER', 10);
            }
        """.trimIndent()

        runner.call(picker, "pick", listOf("\"mich\""), on = 12)

        assertThat(asked).singleElement().satisfies({ (capability, argument, on) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_SUGGEST)
            assertThat(on).isEqualTo(12L)
            assertThat(argument).isEqualTo("""[7,"mich","USER",10]""")
        })
    }

    @Test
    fun `a function can resolve a mention through the server`() {
        val resolver = """
            export default function ping(name) {
              return orknux.slack.mention({ id: 7, type: 'SLACK' }, name);
            }
        """.trimIndent()

        runner.call(resolver, "ping", listOf("\"dana\""), on = 12)

        assertThat(asked).singleElement().satisfies({ (capability, argument, on) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_MENTION)
            assertThat(on).isEqualTo(12L)
            assertThat(argument).isEqualTo("""[7,"dana"]""")
        })
    }

    @Test
    fun `a function can search Slack through the server`() {
        val seeker = """
            export default function seek(query) {
              return orknux.slack.search({ id: 7, type: 'SLACK' }, query, 5);
            }
        """.trimIndent()

        runner.call(seeker, "seek", listOf("\"in:#support deploy\""), on = 12)

        assertThat(asked).singleElement().satisfies({ (capability, argument, on) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_SEARCH)
            assertThat(on).isEqualTo(12L)
            assertThat(argument).isEqualTo("""[7,"in:#support deploy",5]""")
        })
    }

    /** With no host wired, the helper is still there and says so in a sentence. */
    @Test
    fun `a script where the door is not wired is told, rather than thrown at`() {
        val alone = ScriptRunner(ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000))

        val answer = alone.call(source, "read", listOf("\"#general\""), on = 12)

        assertThat(answer).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((answer as ScriptResult.Returned).json).contains("cannot read Slack threads")
    }
}

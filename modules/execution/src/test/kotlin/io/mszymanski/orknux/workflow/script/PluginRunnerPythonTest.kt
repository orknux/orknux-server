package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A plugin written in Python, loaded and called through the same doors a
 * JavaScript plugin is: what it declares is read by the same reader and held to
 * the same contract, and what it runs is called through the same harness.
 */
class PluginRunnerPythonTest {

    private val asked = mutableListOf<Pair<PluginCapability, String>>()

    private val runner = PluginRunner(
        PluginProperties(timeoutMillis = 15_000, statementLimit = 2_000_000),
        PluginHost { capability, argument, _ ->
            asked += capability to argument
            """{"status":200,"headers":{},"body":"pong"}"""
        },
    )

    private val plugin = """
        from orknux import OrknuxPlugin, OrknuxFunction, OrknuxTool, OrknuxFunctionTool, OrknuxParameter, OrknuxSkill, OrknuxType

        def shout(text, times=1):
            return (text.upper() + '!') * times

        class Echo(OrknuxPlugin):
            def id(self):
                return 'echo'

            def api_version(self):
                return 1

            def parameters(self):
                return [OrknuxParameter('greeting', 'string', description='What to say first', required=False)]

            def capabilities(self):
                return ['NETWORK_REQUEST']

            def functions(self):
                return [
                    OrknuxFunction('shout', shout, 'string', description='Says it louder',
                                   params=[{'name': 'text', 'type': 'string'}, {'name': 'times', 'type': 'number', 'default': 1}]),
                    OrknuxFunction('greet', self.greet, 'string', params=[{'name': 'who', 'type': 'string'}]),
                    OrknuxFunction('ping', self.ping, 'object', params=[{'name': 'url', 'type': 'string'}]),
                ]

            def tools(self):
                return [
                    OrknuxFunctionTool('shout', description='Use it to shout'),
                    OrknuxTool('count', lambda words: len(words), 'number', params=[{'name': 'words', 'type': 'array'}]),
                ]

            def skills(self):
                return [OrknuxSkill('Echoing', '# Echoing\nShout when asked.', id='echoing')]

            def types(self):
                return [OrknuxType('loudness', 'string', suggest=lambda prefix, settings: ['quiet', 'loud'])]

            def actions(self):
                return [{'name': 'echo', 'label': 'Echo', 'run': lambda given, context: {'said': given['text'] + context['settings'].get('greeting', '')},
                         'parameters': [{'name': 'text', 'type': 'string'}], 'outputs': [{'name': 'said', 'type': 'string'}]}]

            def greet(self, who):
                print('greeting', who)
                return self.settings.get('greeting', 'Hello') + ', ' + who

            def ping(self, url):
                return orknux.http.get(url)
    """.trimIndent()

    private fun returned(result: ScriptResult): String? {
        assertThat(result).describedAs(result.toString()).isInstanceOf(ScriptResult.Returned::class.java)
        return (result as ScriptResult.Returned).json
    }

    @Test
    fun `it is read like any plugin`() {
        val read = runner.inspect(plugin)

        assertThat(read).describedAs(read.toString()).isInstanceOf(PluginInspection.Read::class.java)
        read as PluginInspection.Read
        assertThat(read.id).isEqualTo("echo")
        assertThat(read.apiVersion).isEqualTo(1)
        assertThat(read.functions.map { it.name }).containsExactly("shout", "greet", "ping")
        assertThat(read.functions.first().params.map { it.name to it.required }).containsExactly("text" to true, "times" to false)
        assertThat(read.functions.first().params.last().default).isEqualTo("1")
        assertThat(read.functions.first().source).isNull()
        assertThat(read.tools.map { it.name to it.proxyOf }).containsExactly("shout" to "shout", "count" to null)
        assertThat(read.tools.first().description).isEqualTo("Use it to shout")
        assertThat(read.parameters.single().name).isEqualTo("greeting")
        assertThat(read.capabilities).containsExactly("NETWORK_REQUEST")
        assertThat(read.skills.single().id).isEqualTo("echoing")
        assertThat(read.types.single().suggests).isTrue()
        assertThat(read.actions.single().name).isEqualTo("echo")
    }

    @Test
    fun `a declared function runs, with the workspace's settings on self`() {
        val shouted = runner.call(plugin, "shout", listOf("\"hey\"", "2"))
        val greeted = runner.call(plugin, "greet", listOf("\"Ada\""), settings = """{"greeting":"Hi"}""")

        assertThat(returned(shouted)).isEqualTo("\"HEY!HEY!\"")
        assertThat(returned(greeted)).isEqualTo("\"Hi, Ada\"")
    }

    @Test
    fun `a tool, a type and an action run through the same harness`() {
        val counted = runner.call(plugin, "count", listOf("""["a","b","c"]"""), surface = "tools")
        val suggested = runner.call(plugin, "loudness", listOf("\"\"", "{}"), surface = "types:suggest")
        val acted = runner.call(plugin, "echo", listOf("""{"text":"hi"}""", "{}"), settings = """{"greeting":"!"}""", surface = "actions")

        assertThat(returned(counted)).isEqualTo("3")
        assertThat(returned(suggested)).isEqualTo("""["quiet","loud"]""")
        assertThat(returned(acted)).isEqualTo("""{"said":"hi!"}""")
    }

    @Test
    fun `a capability reaches the server only where it was granted`() {
        val granted = runner.call(plugin, "ping", listOf("\"https://example.com\""), capabilities = setOf(PluginCapability.NETWORK_REQUEST))
        val refused = runner.call(plugin, "ping", listOf("\"https://example.com\""))

        assertThat(returned(granted)).contains("\"body\":\"pong\"")
        assertThat(asked.single().first).isEqualTo(PluginCapability.NETWORK_REQUEST)
        assertThat(returned(refused)).contains("this plugin was not granted NETWORK_REQUEST")
    }

    @Test
    fun `what a run raises comes back as a failure in its words`() {
        val failed = runner.call(plugin, "shout", listOf("42"))

        assertThat((failed as ScriptResult.Failed).reason).isEqualTo("AttributeError: 'int' object has no attribute 'upper'")
    }

    @Test
    fun `a file that is not a plugin is told why`() {
        val none = runner.inspect("from orknux import OrknuxPlugin\n\ndef helper():\n    return 1\n")
        val two = runner.inspect(
            "from orknux import OrknuxPlugin\nclass A(OrknuxPlugin):\n    pass\nclass B(OrknuxPlugin):\n    pass\n",
        )
        val unfinished = runner.inspect("from orknux import OrknuxPlugin\nclass A(OrknuxPlugin):\n    pass\n")

        assertThat((none as PluginInspection.Unreadable).reason).isEqualTo("the plugin defines no class that extends OrknuxPlugin")
        assertThat((two as PluginInspection.Unreadable).reason).contains("2 classes that extend OrknuxPlugin")
        assertThat((unfinished as PluginInspection.Unreadable).reason).contains("a plugin must implement id()")
    }

    @Test
    fun `a declaration the contract refuses is refused for Python too`() {
        val read = runner.inspect(
            """
            from orknux import OrknuxPlugin, OrknuxFunction
            class Bad(OrknuxPlugin):
                def id(self): return 'bad'
                def api_version(self): return 1
                def functions(self): return [OrknuxFunction('', lambda: 1, 'string')]
            """.trimIndent(),
        )

        assertThat((read as PluginInspection.Unreadable).reason).contains("an OrknuxFunction needs a name")
    }

    @Test
    fun `a JavaScript plugin that imports something called orknux is still JavaScript`() {
        assertThat(PythonGuest.isPython("import orknux from './orknux.js';\nexport default class X {}")).isFalse()
        assertThat(PythonGuest.isPython("import orknux\n")).isTrue()
        assertThat(PythonGuest.isPython("from orknux import OrknuxPlugin\n")).isTrue()
    }
}

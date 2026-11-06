package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A script written in Python: that it runs, that it reaches the server only
 * through the `orknux` helpers JavaScript has, and that every way out the
 * JavaScript sandbox closes is closed for Python as well.
 */
class ScriptRunnerPythonTest {

    private val asked = mutableListOf<Pair<PluginCapability, String>>()

    private val runner = ScriptRunner(
        ScriptProperties(timeoutMillis = 15_000, statementLimit = 2_000_000),
        PluginHost { capability, argument, _ ->
            asked += capability to argument
            """{"status":200,"headers":{},"body":"{\"open\":3}"}"""
        },
    )

    private fun run(source: String, vararg arguments: String, context: String = "{}"): ScriptResult =
        runner.call(source.trimIndent(), "tool", arguments.toList(), context = context, on = 7, language = ScriptLanguage.PYTHON)

    private fun json(result: ScriptResult): String? {
        assertThat(result).describedAs(result.toString()).isInstanceOf(ScriptResult.Returned::class.java)
        return (result as ScriptResult.Returned).json
    }

    private fun failure(result: ScriptResult): String {
        assertThat(result).describedAs(result.toString()).isInstanceOf(ScriptResult.Failed::class.java)
        return (result as ScriptResult.Failed).reason
    }

    @Test
    fun `main is called with the JSON arguments and answers JSON`() {
        val answer = run(
            """
            def main(ticket, extra):
                return {'id': ticket['id'], 'words': extra.split(), 'done': True}
            """,
            """{"id":7}""",
            "\"a b\"",
        )

        assertThat(json(answer)).isEqualTo("""{"id": 7, "words": ["a", "b"], "done": true}""")
    }

    @Test
    fun `returning None answers nothing`() {
        assertThat(json(run("def main():\n    pass"))).isNull()
    }

    @Test
    fun `the standard library is there`() {
        val answer = run(
            """
            import hashlib, base64, re, datetime, statistics
            def main(text):
                return [hashlib.sha256(text.encode()).hexdigest()[:8], base64.b64encode(b'ok').decode(),
                        re.sub(r'\d', '#', 'a1b2'), datetime.date(2026, 1, 2).isoformat(), statistics.mean([1, 2, 3])]
            """,
            "\"x\"",
        )

        assertThat(json(answer)).isEqualTo("""["2d711642", "b2s=", "a#b#", "2026-01-02", 2]""")
    }

    @Test
    fun `context is readable and cannot be changed`() {
        val answer = run(
            """
            def main():
                try:
                    context['run'] = 2
                    return 'changed'
                except TypeError:
                    return context['run']
            """,
            context = """{"run":1}""",
        )

        assertThat(json(answer)).isEqualTo("1")
    }

    @Test
    fun `what main raises is said with its type`() {
        assertThat(failure(run("def main():\n    raise ValueError('no thanks')"))).isEqualTo("ValueError: no thanks")
    }

    @Test
    fun `a script with no main is told so`() {
        assertThat(failure(run("def helper():\n    return 1"))).isEqualTo("tool defines no main function to call")
    }

    @Test
    fun `an answer that is not JSON is refused in words`() {
        assertThat(failure(run("def main():\n    return object()"))).contains("not JSON serializable")
    }

    @Test
    fun `a set, a date and a Decimal are answered as JSON anybody would write for them`() {
        val answered = run("import datetime, decimal\ndef main():\n    return [{2, 1}, datetime.date(2024, 1, 2), decimal.Decimal('1.5')]")
        assertThat((answered as ScriptResult.Returned).json).isEqualTo("[[1, 2], \"2024-01-02\", 1.5]")
    }

    @Test
    fun `the helpers are the JavaScript ones, reached as orknux`() {
        val answer = run(
            """
            def main(url):
                reply = orknux.http.get(url)
                return reply['json']['open']
            """,
            "\"https://api.example.com/tickets\"",
        )

        assertThat(json(answer)).isEqualTo("3")
        assertThat(asked.single().first).isEqualTo(PluginCapability.NETWORK_REQUEST)
        assertThat(asked.single().second).contains("https://api.example.com/tickets")
    }

    @Test
    fun `keyword arguments are the options a helper takes last, and import orknux works too`() {
        val answer = run(
            """
            import orknux as o
            def main():
                return o.http.request(url='https://api.example.com/x', method='delete')['status']
            """,
        )

        assertThat(json(answer)).isEqualTo("200")
        assertThat(asked.single().second).contains("\"DELETE\"")
    }

    @Test
    fun `a helper that is not there raises OrknuxError`() {
        val answer = run(
            """
            from orknux import OrknuxError
            def main():
                try:
                    orknux.nothing.here()
                except OrknuxError as refused:
                    return str(refused)
            """,
        )

        assertThat(json(answer)).isEqualTo("\"orknux.nothing does not exist\"")
    }

    @Test
    fun `print and orknux log are the call's log lines`() {
        val answer = run(
            """
            def main():
                print('hello', 3)
                orknux.log.warn('careful')
                return 1
            """,
        )

        assertThat(answer).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((answer as ScriptResult.Returned).logs).containsExactly("info hello 3", "warn careful")
    }

    /**
     * Each way out, tried on its own. Some are refused with a Python exception
     * and some by the sandbox stopping the call outright; either is closed, and
     * the one thing that must never come back is the probe's own answer.
     */
    @Test
    fun `files, processes, sockets, threads and Java are all closed`() {
        val probes = mapOf(
            "open" to "open('/etc/passwd').read()",
            "listdir" to "__import__('os').listdir('/')",
            "subprocess" to "__import__('subprocess').run(['id'])",
            "system" to "__import__('os').system('id')",
            "socket" to "__import__('socket').create_connection(('example.com', 80), timeout=2)",
            "thread" to "__import__('threading').Thread(target=lambda: 1).start()",
            "java" to "__import__('java').type('java.lang.System')",
            "polyglot" to "__import__('polyglot').eval(language='js', string='1')",
            "ctypes" to "__import__('ctypes').CDLL(None)",
        )

        val opened = probes.filter { (_, probe) ->
            val answer = run("def main():\n    try:\n        $probe\n    except Exception:\n        return 'refused'\n    return 'reached'")
            answer is ScriptResult.Returned && answer.json == "\"reached\""
        }

        assertThat(opened.keys).isEmpty()
    }

    @Test
    fun `a loop that never ends is stopped by the clock`() {
        val stuck = ScriptRunner(ScriptProperties(timeoutMillis = 2_000, statementLimit = 200_000))

        val loops = listOf(
            "def main():\n    x = 0\n    while True:\n        x += 1\n        x -= 1",
            "def main():\n    while True: pass",
        ).map { stuck.call(it, "spin", emptyList(), language = ScriptLanguage.PYTHON) as ScriptResult.Failed }

        assertThat(loops).allSatisfy {
            assertThat(it.reason).isEqualTo("took longer than 2000 ms and was stopped")
            assertThat(it.settled).isFalse()
        }
    }

    @Test
    fun `importing JavaScript is refused`() {
        val answer = runner.call(
            "def main():\n    return 1",
            "tool",
            emptyList(),
            imports = mapOf("helper" to "f1"),
            language = ScriptLanguage.PYTHON,
        )

        assertThat(failure(answer)).isEqualTo("tool is Python, and a Python script cannot import JavaScript functions or libraries")
    }

    @Test
    fun `parsing says where a syntax error is`() {
        val checked = runner.validate("def main(:\n    return 1", ScriptLanguage.PYTHON)

        assertThat(checked.valid).isFalse()
        assertThat(checked.line).isEqualTo(1)
        assertThat(runner.validate("def main():\n    return 1", ScriptLanguage.PYTHON).valid).isTrue()
    }

    @Test
    fun `arity counts what main takes positionally, defaults included`() {
        assertThat(runner.arity("def main(a, b=2, *, c=3):\n    pass", ScriptLanguage.PYTHON)).isEqualTo(ScriptArity.Counted(2))
        assertThat(runner.arity("def main(*rest):\n    pass", ScriptLanguage.PYTHON)).isInstanceOf(ScriptArity.Unreadable::class.java)
        assertThat(runner.arity("x = 1", ScriptLanguage.PYTHON)).isEqualTo(ScriptArity.Unreadable("it defines no main function to call"))
    }
}

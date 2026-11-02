package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A function making an HTTP request, which it could not do at all before.
 *
 * `NETWORK_REQUEST` was a plugin's alone, withheld from functions on the
 * reasoning that its bound is the grant and a function has nobody to give one.
 * That was overruled on 2026-09-06: a function that cannot call an HTTP service
 * cannot do most of what people write functions for, and the alternatives - an
 * action, an MCP server - are heavier things to build and keep for one request.
 *
 * What is asserted here is the shape, because the shape is what makes it safe
 * to offer and pleasant to use:
 *
 *   as data       the URL goes over as JSON and the answer comes back as JSON.
 *                 The script never holds a socket, so there is nothing to keep
 *                 open, to listen on, or to reflect from
 *   the proxy     the request is the *server's* to make, so an installation's
 *                 proxy rules govern it - the script cannot see them and cannot
 *                 argue with them
 *   JSON both ways an object body is sent as JSON and says so; a JSON reply
 *                 arrives parsed, beside the text it was parsed from
 *   refusal       a failure is a value with an error on it, so a condition can
 *                 still decide rather than become undecidable
 */
class ScriptHttpTest {

    private val asked = mutableListOf<Triple<PluginCapability, String, Long?>>()

    private fun runnerAnswering(answer: String): ScriptRunner {
        val host = PluginHost { capability, argument, on ->
            asked += Triple(capability, argument, on)
            answer
        }
        return ScriptRunner(ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000), host)
    }

    @Test
    fun `a function can make a request and read the answer`() {
        val runner = runnerAnswering("""{"status":200,"headers":{},"body":"{\"open\":3}"}""")

        val answer = runner.call(
            """
            export default function count(url) {
              const r = orknux.http.get(url);
              if (r.error) return -1;
              return r.json.open;
            }
            """.trimIndent(),
            "count",
            listOf("\"https://api.example.com/tickets\""),
            on = 12,
        )

        assertThat((answer as ScriptResult.Returned).json).isEqualTo("3")
    }

    /** The one that matters for safety: the server makes the call, not the script. */
    @Test
    fun `the request crosses as JSON, for the server to make`() {
        val runner = runnerAnswering("""{"status":204,"headers":{},"body":""}""")

        runner.call(
            """
            export default function ping(url) {
              return orknux.http.request({ url, method: 'delete', headers: { 'x-a': '1' } }).status;
            }
            """.trimIndent(),
            "ping",
            listOf("\"https://api.example.com/thing/1\""),
            on = 12,
        )

        val (capability, argument, on) = asked.single()
        assertThat(capability).isEqualTo(PluginCapability.NETWORK_REQUEST)
        assertThat(on).isEqualTo(12)
        // The method is normalised, and everything the server needs is in one
        // JSON array: url, method, headers, body.
        assertThat(argument).contains("https://api.example.com/thing/1")
        assertThat(argument).contains("DELETE")
        assertThat(argument).contains("x-a")
    }

    @Test
    fun `an object body is sent as JSON and says so`() {
        val runner = runnerAnswering("""{"status":201,"headers":{},"body":"{}"}""")

        runner.call(
            """
            export default function make(url) {
              return orknux.http.post(url, { title: 'hello' }).status;
            }
            """.trimIndent(),
            "make",
            listOf("\"https://api.example.com/tickets\""),
            on = 12,
        )

        val argument = asked.single().second
        assertThat(argument).contains("application/json")
        assertThat(argument).contains("title")
    }

    /** A content type the author set is theirs; nothing is written over it. */
    @Test
    fun `a content type the function set is left alone`() {
        val runner = runnerAnswering("""{"status":200,"headers":{},"body":""}""")

        runner.call(
            """
            export default function send(url) {
              return orknux.http.request({
                url, method: 'POST', body: 'a=1', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
              }).status;
            }
            """.trimIndent(),
            "send",
            listOf("\"https://api.example.com/form\""),
            on = 12,
        )

        val argument = asked.single().second
        assertThat(argument).contains("x-www-form-urlencoded")
        assertThat(argument).doesNotContain("application/json")
    }

    /**
     * A reply that is not JSON has no `json`, and still has its `body`.
     *
     * Parsing is an offer, not a promise: a service answering HTML on an error
     * page must not turn into a thrown exception inside somebody's condition.
     */
    @Test
    fun `a reply that is not JSON keeps its body and grows no json`() {
        val runner = runnerAnswering("""{"status":200,"headers":{},"body":"<html>no</html>"}""")

        val answer = runner.call(
            """
            export default function look(url) {
              const r = orknux.http.get(url);
              return { hasJson: r.json !== undefined, body: r.body };
            }
            """.trimIndent(),
            "look",
            listOf("\"https://example.com\""),
            on = 12,
        )

        assertThat((answer as ScriptResult.Returned).json).contains("\"hasJson\":false")
        assertThat(answer.json).contains("<html>no</html>")
    }

    /**
     * A refusal is data.
     *
     * A condition that cannot reach a service has to be able to decide. Throwing
     * would make it undecidable, which stops the run rather than answering it.
     */
    @Test
    fun `a refusal comes back as a value rather than a thrown error`() {
        val runner = runnerAnswering("""{"error":"the proxy refused that address"}""")

        val answer = runner.call(
            """
            export default function ask(url) {
              const r = orknux.http.get(url);
              return r.error ?? 'reached it';
            }
            """.trimIndent(),
            "ask",
            listOf("\"https://blocked.example.com\""),
            on = 12,
        )

        assertThat((answer as ScriptResult.Returned).json).contains("the proxy refused that address")
    }

    /**
     * Binary goes over as base64 and is marked as such, so the server knows to
     * decode it rather than send sixty thousand characters of alphabet soup.
     */
    @Test
    fun `an upload marks its body as base64 and names the content type`() {
        val runner = runnerAnswering("""{"status":201,"headers":{},"body":""}""")

        runner.call(
            """
            export default function put(url) {
              return orknux.http.upload(url, 'aGVsbG8=', 'image/png').status;
            }
            """.trimIndent(),
            "put",
            listOf("\"https://files.example.com/up\""),
            on = 12,
        )

        val argument = asked.single().second
        assertThat(argument).contains("aGVsbG8=")
        assertThat(argument).contains("\"sendBase64\":true")
        assertThat(argument).contains("image/png")
        assertThat(argument).contains("POST")
    }

    /** And a download asks for the bytes back the same way. */
    @Test
    fun `a download asks for bytes and reads them as base64`() {
        val runner =
            runnerAnswering("""{"status":200,"headers":{},"base64":"aGVsbG8=","size":5,"contentType":"image/png"}""")

        val answer = runner.call(
            """
            export default function fetchIt(url) {
              const r = orknux.http.download(url);
              return { size: r.size, kind: r.contentType, held: r.base64 };
            }
            """.trimIndent(),
            "fetchIt",
            listOf("\"https://files.example.com/logo.png\""),
            on = 12,
        )

        assertThat(asked.single().second).contains("\"wantBytes\":true")
        assertThat((answer as ScriptResult.Returned).json).contains("\"size\":5")
        assertThat(answer.json).contains("image/png")
        assertThat(answer.json).contains("aGVsbG8=")
    }

    /** Without a host there is no door, and saying so beats throwing. */
    @Test
    fun `an installation with no host says so rather than failing oddly`() {
        val runner = ScriptRunner(ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000), null)

        val answer = runner.call(
            "export default function ask(url) {\n  return orknux.http.get(url).error;\n}",
            "ask",
            listOf("\"https://example.com\""),
            on = 12,
        )

        assertThat((answer as ScriptResult.Returned).json).contains("cannot make requests from a function")
    }

    /**
     * A WebSocket goes through the same door, as an object that says so, and
     * every message crosses as text - an object as its JSON.
     */
    @Test
    fun `a websocket exchange crosses as one call, and each reply arrives parsed`() {
        val runner = runnerAnswering("""{"replies":["{\"result\":\"pong\"}","not json"]}""")

        val answer = runner.call(
            """
            export default function talk(url) {
              const r = orknux.http.websocket(url, [{ action: 'ping' }, 'raw'], { timeoutMs: 500 });
              return [r.json[0].result, r.json[1], r.replies[1]];
            }
            """.trimIndent(),
            "talk",
            listOf("\"ws://localhost:8765\""),
            on = 12,
        )

        assertThat((answer as ScriptResult.Returned).json).isEqualTo("[\"pong\",null,\"not json\"]")
        val (capability, argument, _) = asked.single()
        assertThat(capability).isEqualTo(PluginCapability.NETWORK_REQUEST)
        assertThat(argument)
            .contains("\"op\":\"websocket\"")
            .contains("\"url\":\"ws://localhost:8765\"")
            .contains("\"messages\":[\"{\\\"action\\\":\\\"ping\\\"}\",\"raw\"]")
            .contains("\"timeoutMs\":500")
    }
}

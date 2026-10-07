package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.GrantedSkill
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

/**
 * Orknux as an MCP server, for agents that live outside it.
 *
 * The same tools the quick chat and a granted agent use — [OrknuxTools] — behind
 * the protocol an outside client speaks. One surface, three doors, and this is
 * the door for something running on somebody's laptop.
 *
 * **One server per workspace**, addressed as `/mcp/{workspaceId}`. The
 * alternative is a single endpoint with a workspace argument on every tool,
 * which makes every call carry a parameter the caller already decided once, and
 * makes it possible to get wrong per call. A client configures the workspace in
 * its URL and the tools are then exactly the tools.
 *
 * **Authentication is the application's own**, which is the whole of what
 * "respects the auth rules" means here: the request goes through the same
 * security chain as everything else, so it needs a session — `POST /api/session`
 * for a directory installation, or a bearer token where OIDC is configured —
 * and then [WorkspaceAccess] decides whether this caller may see this workspace
 * at all. Nothing here grants anything the caller could not already do through
 * the API by hand; it only makes it reachable by an agent.
 *
 * Writing is allowed for the same reason. A person with a session can start a
 * workflow from the interface, so a tool that refused would be protecting
 * nothing while pretending to.
 *
 * JSON-RPC 2.0 over plain HTTP POST. No SSE and no session resumption: this
 * server has no long-lived state to resume and never sends an unsolicited
 * message, so a request and its answer is the whole of the transport.
 *
 * **The answer is a promise**, because one tool waits. `orknux_news` holds its
 * call open until something happens on an issue, for as long as five minutes,
 * and anybody who can sign in can ask for it. Answering on the thread that took
 * the request would have meant a couple of hundred such calls holding every
 * thread Tomcat has and taking the server off the air - through the one tool
 * whose whole purpose is to wait. Everything else here completes before it
 * returns, so nothing is slower for it.
 *
 * A wait this long outlives the container's default timeout for an unanswered
 * request, so `spring.mvc.async.request-timeout` is set past it in
 * `application.yml`; the two belong together and moving one means moving both.
 */
@RestController
class McpAPI(
    private val workspaces: WorkspaceRepository,
    private val tools: OrknuxTools,
    private val access: WorkspaceAccess,
    private val mapper: ObjectMapper,
    private val skills: SkillTool,
) {

    @PostMapping("/mcp/{workspaceId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun rpc(@PathVariable workspaceId: Long, @RequestBody body: JsonNode): CompletableFuture<ResponseEntity<Any>> {
        val method = body.path("method").stringValue().orEmpty()
        val requestId = body.path("id").takeUnless { it.isMissingNode || it.isNull }

        /*
         * A workspace this caller may not have is answered in the protocol
         * rather than as an HTTP fault.
         *
         * A JSON-RPC client is reading JSON-RPC. Handing it a Spring error page
         * — or worse, the 401 the error dispatch used to produce — tells an
         * agent to go and authenticate when the real answer is that the
         * workspace in its URL is wrong. Nothing distinguishes "not there" from
         * "not yours", the way it does not anywhere else here.
         */
        val workspace = workspaces.findByIdOrNull(workspaceId)?.takeIf { access.canSee(it) }
            ?: return done(
                ResponseEntity.ok(
                    error(requestId, INVALID_PARAMS, "There is no workspace $workspaceId that you can see"),
                ),
            )

        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)
        val id = requestId

        /*
         * A notification has no id and takes no answer — `initialized` is the
         * one every client sends. Answering it with a result is a protocol
         * error on our side, so it gets an empty 202 instead.
         */
        if (id == null) {
            log.debug("MCP notification {} for workspace {}", method, workspaceId)
            return done(ResponseEntity.accepted().build())
        }

        return when (method) {
            "initialize" -> done(ok(id, initialize()))
            "tools/list" -> done(ok(id, mapOf("tools" to tools.specs(scope).map(::described))))
            "tools/call" -> call(scope, body.path("params")).thenApply { answered -> ok(id, answered) }
            "ping" -> done(ok(id, emptyMap<String, Any>()))
            "prompts/list" -> done(ok(id, mapOf("prompts" to offered(workspaceId).map { (name, skill) -> prompt(name, skill) })))
            "prompts/get" -> done(promptGot(id, workspaceId, body.path("params")))
            "resources/list" -> done(ok(id, mapOf("resources" to offered(workspaceId).map { (_, skill) -> resource(skill) })))
            // No templates: every skill is listed by its own address.
            "resources/templates/list" -> done(ok(id, mapOf("resourceTemplates" to emptyList<Any>())))
            "resources/read" -> done(resourceRead(id, workspaceId, body.path("params")))
            else -> done(ResponseEntity.ok(error(id, METHOD_NOT_FOUND, "This server does not do $method")))
        }
    }

    /**
     * The workspace's skills, each under the name a client asks for it by.
     * Issue #617.
     *
     * A skill is what both a prompt and a resource are here: a page of
     * instructions a client picks by name - as a slash command, or as a
     * document to attach. The name is the skill's id; where two catalogs hold
     * one id, the first keeps the bare id and the rest are `catalog:id`, which
     * is the spelling `skill_load` already reads.
     */
    private fun offered(workspaceId: Long): List<Pair<String, GrantedSkill>> {
        val taken = mutableSetOf<String>()
        return skills.offeredOutside(workspaceId).map { skill ->
            val bare = skill.key.lowercase()
            val name = if (taken.add(bare)) skill.key else "${skill.catalog}:${skill.key}"
            name to skill
        }
    }

    private fun prompt(name: String, skill: GrantedSkill): Map<String, Any> = buildMap {
        put("name", name)
        put("title", skill.name)
        skill.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        put("arguments", emptyList<Any>())
    }

    private fun resource(skill: GrantedSkill): Map<String, Any> = buildMap {
        put("uri", uriOf(skill))
        put("name", skill.key)
        put("title", skill.name)
        skill.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        put("mimeType", MARKDOWN)
    }

    private fun promptGot(id: JsonNode, workspaceId: Long, params: JsonNode): ResponseEntity<Any> {
        val asked = params.path("name").stringValue(null).orEmpty()
        val skill = offered(workspaceId).firstOrNull { it.first.equals(asked, ignoreCase = true) }?.second
            ?: return ResponseEntity.ok(error(id, INVALID_PARAMS, "There is no prompt called $asked"))
        return ok(
            id,
            buildMap {
                skill.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                put(
                    "messages",
                    listOf(mapOf("role" to "user", "content" to mapOf("type" to "text", "text" to skill.content))),
                )
            },
        )
    }

    private fun resourceRead(id: JsonNode, workspaceId: Long, params: JsonNode): ResponseEntity<Any> {
        val asked = params.path("uri").stringValue(null).orEmpty()
        val skill = offered(workspaceId).firstOrNull { uriOf(it.second) == asked }?.second
            ?: return ResponseEntity.ok(error(id, RESOURCE_NOT_FOUND, "There is no resource at $asked"))
        return ok(id, mapOf("contents" to listOf(mapOf("uri" to asked, "mimeType" to MARKDOWN, "text" to skill.content))))
    }

    /** `orknux://skills/<catalog>/<id>`, each part encoded, since a catalog is whatever somebody named a folder. */
    private fun uriOf(skill: GrantedSkill): String =
        "orknux://skills/" + encode(skill.catalog) + "/" + encode(skill.key)

    private fun encode(part: String): String = URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")

    /** An answer that was ready before the method returned, which is most of them. */
    private fun done(answer: ResponseEntity<Any>): CompletableFuture<ResponseEntity<Any>> =
        CompletableFuture.completedFuture(answer)

    private fun error(id: JsonNode?, code: Int, says: String): Map<String, Any?> =
        mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to says))

    private fun initialize(): Map<String, Any> = mapOf(
        // Answered with the version this was written against. A client asking
        // for a newer one is told what it is talking to and decides for itself.
        "protocolVersion" to PROTOCOL,
        "capabilities" to mapOf(
            "tools" to mapOf("listChanged" to false),
            // The workspace's skills, both ways a client may want them. Issue #617.
            "prompts" to mapOf("listChanged" to false),
            "resources" to mapOf("listChanged" to false, "subscribe" to false),
        ),
        "serverInfo" to mapOf("name" to "orknux", "version" to VERSION),
        "instructions" to
            "Orknux, a workflow and agent platform. These tools read and operate one workspace: its " +
            "workflows, its runs and its agents. Everything that has a page comes back with a `url`; " +
            "use it when you refer to something, and never invent one. The workspace's skills - how it " +
            "goes about things - are offered as prompts and as resources.",
    )

    /**
     * One call, run and wrapped as MCP expects.
     *
     * A tool that failed answers `isError` with the reason as text rather than a
     * JSON-RPC error: the protocol reserves those for the call not happening at
     * all, and a model needs to read what went wrong to try something else.
     */
    private fun call(scope: OrknuxScope, params: JsonNode): CompletableFuture<Map<String, Any>> {
        val name = params.path("name").stringValue(null).orEmpty()
        if (!tools.handles(name)) {
            return CompletableFuture.completedFuture(content("There is no tool called $name", failed = true))
        }

        val arguments = params.path("arguments").takeUnless { it.isMissingNode || it.isNull }
        return tools.runAsync(scope, name, arguments?.let(mapper::writeValueAsString) ?: "{}").thenApply { answer ->
            // The surface reports its own refusals as `{"error": ...}`; those
            // are the tool's answer, not a transport failure, but the client
            // should still see them as an error rather than as a result to act
            // on.
            val refused = runCatching { mapper.readTree(answer).has("error") }.getOrDefault(false)
            content(answer, failed = refused)
        }
    }

    private fun content(text: String, failed: Boolean): Map<String, Any> = mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to text)),
        "isError" to failed,
    )

    /**
     * A tool as MCP wants it: a name, a description, and a JSON Schema.
     *
     * Everything is a string, which is what [ToolSpec] carries and what these
     * tools actually take — an id is as happily read from `"19"` as from `19`,
     * and a schema claiming otherwise would make clients refuse calls that work.
     */
    private fun described(spec: ToolSpec): Map<String, Any> = mapOf(
        "name" to spec.name,
        "description" to spec.description,
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to spec.parameters.associate { parameter ->
                parameter.name to mapOf("type" to "string", "description" to parameter.description)
            },
            "required" to spec.parameters.filter { it.required }.map { it.name },
        ),
    )

    private fun ok(id: JsonNode, result: Any): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.OK).body(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))

    private companion object {
        val log = LoggerFactory.getLogger(McpAPI::class.java)

        /** The revision of the protocol these shapes were written against. */
        const val PROTOCOL = "2025-06-18"
        const val VERSION = "1.0"

        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        /** What the protocol answers a `resources/read` for an address nothing is at. */
        const val RESOURCE_NOT_FOUND = -32002

        const val MARKDOWN = "text/markdown"
    }
}

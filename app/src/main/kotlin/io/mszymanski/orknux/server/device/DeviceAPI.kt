package io.mszymanski.orknux.server.device

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode

/** Who the client is signed in as, for it to say. */
data class DeviceHello(val user: String)

/**
 * The door the Orknux clients come in by: the Chrome extension under
 * `/api/browser`, Orknux for Windows under `/api/desktop`, and the Claude Code
 * channel plugin under `/api/claude`.
 *
 * Long polling rather than a socket: the client holds one request open asking
 * for work, the server answers it with a command or with nothing after
 * [DeviceLinks.next]'s wait, and the client asks again. Every request is an
 * ordinary authenticated one - a bearer token, so the cross-site guard lets it
 * through and a read-only token is refused the answers it would have to post -
 * and nothing new listens anywhere.
 *
 * The device is the person's who signed it in, and only conversations that
 * are that person's reach it; see [DeviceTools].
 */
@RestController
class DeviceAPI(
    private val links: DeviceLinks,
    /** Who uses which client, for Admin > Reports > Clients. */
    private val uses: ClientUses,
) {

    @GetMapping("/api/{device:browser|desktop|claude|terminal}/hello")
    fun hello(@PathVariable device: String): DeviceHello {
        kindOf(device)
        return DeviceHello(currentUser())
    }

    /**
     * The next command for this person's device: 200 with it, or 204 when none
     * came within [wait] seconds - the client asks again either way.
     */
    @GetMapping("/api/{device:browser|desktop|claude|terminal}/next")
    fun next(
        @PathVariable device: String,
        @RequestParam(required = false) wait: Int?,
        @RequestParam(required = false) client: String?,
        @RequestParam(required = false) version: String?,
        /** Which running copy of the client asks: one Shell of a person's, among several on other computers. */
        @RequestParam(required = false) instance: String?,
        /** Read for the Clients report's Computer column: the system a browser runs on. */
        @org.springframework.web.bind.annotation.RequestHeader(name = "User-Agent", required = false) agent: String? = null,
    ): ResponseEntity<DeviceCommand> {
        val seconds = (wait ?: LONGEST_WAIT_SECONDS).coerceIn(0, LONGEST_WAIT_SECONDS)
        // Written down for the Clients report; never the reason a client's poll fails.
        runCatching { uses.seen(currentUser(), kindOf(device), client, version, instance, agent) }
        val command = links.next(currentUser(), kindOf(device), client, version, seconds * 1000L, instance)
            ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(command)
    }

    /**
     * What the device made of one command: `{"result": ...}` or
     * `{"error": "why"}`. 410 where the call has stopped waiting, so the
     * client can say the agent gave up on it.
     */
    @PostMapping("/api/{device:browser|desktop|claude|terminal}/answers/{id}")
    fun answer(@PathVariable device: String, @PathVariable id: String, @RequestBody body: JsonNode): ResponseEntity<Void> {
        val error = body.path("error").takeIf { it.isTextual }?.stringValue()?.take(ERROR_LENGTH)
        val result = body.get("result")
        val taken = links.answer(currentUser(), kindOf(device), id, DeviceAnswer(result, error))
        return ResponseEntity.status(if (taken) HttpStatus.NO_CONTENT else HttpStatus.GONE).build()
    }

    /*
     * Every kind on every server, the Claude Code plugin's included: it is the
     * person's own session asking for the person's own messages, which a
     * desktop app in client mode reaches on a shared server too. Whether My
     * Claude Code is offered is decided where it is chosen, not here.
     */
    private fun kindOf(device: String): DeviceKind =
        DeviceKind.ofPath(device) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun currentUser(): String =
        requireNotNull(SecurityContextHolder.getContext().authentication?.name) { "Nobody is signed in." }

    companion object {
        /** Under the 30 seconds after which proxies start giving up on a quiet request. */
        const val LONGEST_WAIT_SECONDS = 25

        private const val ERROR_LENGTH = 2_000
    }
}

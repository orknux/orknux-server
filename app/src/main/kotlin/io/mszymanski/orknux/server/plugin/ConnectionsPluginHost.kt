package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionView
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * `orknux.connections.query`, on the server's side of the door. Issue #597.
 *
 * Answers which connections the calling run's workspace holds, so a script
 * can find "the Slack one" or "the Prometheus called prod" for itself rather
 * than being handed an id by whoever wired it up.
 *
 * **The answer is built from an allow-list, never by taking secrets out.** A
 * connection carries three credentials and a variable reference beside each,
 * and the next one somebody adds would be a fourth; a view that removed the
 * known ones would hand that one over the day it landed. So each field that
 * crosses is written out below by name, and a field that is not written out
 * does not cross. [CROSSES] is the list, and a test holds the answer to it.
 *
 * Two judgement calls sit in that list, both on the cautious side:
 *
 *   headers   by name only. The connection page shows their values, but a
 *             header is where an API key goes when the auth kinds do not fit,
 *             and a value is a credential often enough that it stays here.
 *   url       scheme, host, port and path only. A URL is where a connection
 *             points and a script wants it; a password written into its user
 *             info, or a token into its query or fragment, is still a secret.
 *
 * Scoped to [on], the run's own workspace, which the runner takes from the
 * run - the script has no way to name another. A call made inside no
 * workspace is refused rather than answered with everything.
 */
@Component
class ConnectionsPluginHost(
    private val connections: WorkspaceConnectionService,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** `{ type?, name? }` in, `{ connections: [...] }` or `{ error }` out. */
    fun query(argument: String, on: Long?): String {
        val workspaceId = on ?: return refusal("there is no workspace here to list the connections of")
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the filter was not JSON")
        if (!given.isObject) return refusal("connections.query takes an object: { type, name }, both optional")

        val type = textOf(given.get("type")) ?: return refusal("type has to be a string")
        val name = textOf(given.get("name")) ?: return refusal("name has to be a string")

        val found = connections.workspaceConnections(workspaceId)
            .filter { it.workspaceId == workspaceId }
            .filter { type.value == null || ofKind(it, type.value) }
            .filter { name.value == null || it.name.equals(name.value, ignoreCase = true) }

        val answer = mapper.createObjectNode()
        val listed = answer.putArray("connections")
        found.forEach { listed.add(handle(it)) }
        log.debug("A script listed {} connection(s) in workspace {}", found.size, workspaceId)
        return mapper.writeValueAsString(answer)
    }

    /**
     * One connection, as a script may see it. Every `put` here is a decision
     * that the field is not a credential; see [CROSSES].
     */
    private fun handle(connection: WorkspaceConnectionView): ObjectNode {
        val one = mapper.createObjectNode()
        one.put("id", connection.id)
        one.put("name", connection.name)
        one.put("type", connection.type.name)
        one.put("pluginType", connection.pluginType)
        one.put("url", withoutUserInfo(connection.effectiveUrl))
        one.put("authType", connection.authType.name)
        one.put("status", connection.status.name)
        val headers = one.putArray("headers")
        connection.headers.forEach { headers.add(it.name) }
        if (connection.type == ConnectionType.SMTP) {
            one.putObject("smtp")
                .put("port", connection.smtpPort)
                .put("username", connection.smtpUsername)
                .put("from", connection.smtpFrom)
                .put("security", connection.smtpSecurity.name)
        }
        return one
    }

    /**
     * A built-in kind by its name, or a plugin's by the id a connection stores
     * (`key/name`) or by the declared name alone - the matching a plugin's
     * connection parameter uses, case aside.
     */
    private fun ofKind(connection: WorkspaceConnectionView, wanted: String): Boolean {
        if (connection.type.name.equals(wanted, ignoreCase = true)) return true
        val pluginType = connection.pluginType ?: return false
        return pluginType.equals(wanted, ignoreCase = true) ||
            pluginType.substringAfter('/').equals(wanted, ignoreCase = true)
    }

    /** Absent and null are both "no filter"; anything but a string is a refusal. */
    private class Asked(val value: String?)

    private fun textOf(node: tools.jackson.databind.JsonNode?): Asked? = when {
        node == null || node.isNull -> Asked(null)
        node.isTextual -> Asked(node.asString())
        else -> null
    }

    private fun refusal(why: String): String =
        mapper.writeValueAsString(mapper.createObjectNode().put("error", why))

    companion object {

        /**
         * Every key a listed connection may carry, and nothing else may. Held
         * here so that the test asserting it and the code writing it read one
         * list - widening it is a decision about what a script may see.
         */
        val CROSSES = setOf("id", "name", "type", "pluginType", "url", "authType", "status", "headers", "smtp")

        /** And inside `smtp`, for a mail connection. The password is not here. */
        val SMTP_CROSSES = setOf("port", "username", "from", "security")

        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

        /**
         * Scheme, host, port and path, and nothing that can carry a credential:
         * `https://user:pass@host:8443/x?token=t#k` as `https://host:8443/x`.
         * The query and the fragment go whole, because a token in one is the
         * shape an incoming-webhook URL or an API key in the address takes, and
         * nothing here can tell such a parameter from a harmless one. A bare
         * host - an SMTP connection's - passes as it is.
         */
        fun withoutUserInfo(url: String): String {
            val scheme = SCHEME.find(url)?.value.orEmpty()
            val rest = url.substring(scheme.length)
            val authorityEnds = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnds).substringAfterLast('@')
            val path = rest.substring(authorityEnds).substringBefore('?').substringBefore('#')
            return scheme + authority + path
        }
    }
}

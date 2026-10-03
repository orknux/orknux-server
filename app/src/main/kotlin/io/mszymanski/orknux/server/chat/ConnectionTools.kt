package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionView
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The connections an agent was granted, asked for rather than recited.
 *
 * ### What it is for
 *
 * A tool that takes a connection wants an id, and the id has to come from
 * somewhere. It came from the briefing: every granted connection listed by
 * name, type and id in the system turn, with the standing instruction that a
 * grant is permission rather than encouragement.
 *
 * That is right for six connections and wrong for sixty. The briefing is paid
 * for on every round of every turn, for a list that most turns never look at -
 * and past a certain length it is not a list a model reads carefully anyway,
 * it is a wall it skims. So beyond [LISTED] the briefing stops reciting and
 * says there is a tool, and the agent asks when the work actually needs an id.
 *
 * The same trade the tool search makes for tools, and for the same reason: a
 * short list is cheaper carried than found, and a long one is the other way
 * round. Which is why the briefing keeps listing them while they are few - an
 * agent that has to spend a round finding the only connection it was given is
 * paying to learn what it could have been told for nothing.
 *
 * ### What it is not
 *
 * Not a way to see the workspace's connections. What can be found is exactly
 * what this agent was granted, which is the same list the briefing would have
 * recited; the tool changes where the agent reads it, never what is in it. An
 * agent granted none is not offered the tool at all.
 *
 * And no credentials, ever. A name, a type, a host and an id: enough to pass
 * the right one to a tool, and nothing that would be worth exfiltrating. The
 * secret lives on the connection and is read by the thing making the call.
 */
@Service
class ConnectionTools(
    private val connections: WorkspaceConnectionService,
    private val mapper: ObjectMapper,
) {

    /** Whether this agent is offered the tool: it holds grants, and enough of them. */
    fun offered(agent: Agent): Boolean = agent.connections.size > LISTED

    /** Whether the briefing should recite them instead, which is the short case. */
    fun recited(agent: Agent): Boolean = agent.connections.isNotEmpty() && !offered(agent)

    fun specFor(agent: Agent): ToolSpec = ToolSpec(
        name = FIND,
        description = "Finds the id of a connection you have been granted. You hold " +
            "${agent.connections.size} of them - too many to be listed here - and a tool that takes " +
            "a connection id needs one of these. Search by the name of the system or of the " +
            "connection: \"slack\", \"production jira\". Only pass an id to a tool when you have been " +
            "explicitly told to use that connection; otherwise leave the tool to its own default.",
        parameters = listOf(
            ToolParameterSpec(
                name = QUERY,
                description = "What you are looking for: a connection's name, or the system it " +
                    "reaches. Left out, every connection you hold is listed.",
                required = false,
            ),
        ),
    )

    fun handles(name: String): Boolean = name == FIND

    /**
     * The connections this agent was granted, as rows: read by id, and kept
     * only where the id still answers and is still the agent's workspace's.
     *
     * The one reading of the grant. The briefing recites it, [run] searches
     * it, and a plugin's connection argument is held to it - so what an agent
     * is told it holds and what it may pass are the same list by construction.
     */
    fun granted(agent: Agent): List<WorkspaceConnectionView> = agent.connections
        .mapNotNull { connections.workspaceConnection(it) }
        .filter { it.workspaceId == agent.workspaceId }

    /**
     * What matches, as JSON, and nothing the agent was not granted.
     *
     * Read by id and dropped where the id no longer answers, which is what the
     * briefing already does with the same list: a deleted connection is not
     * this turn's problem, and the agent's settings page is where a stale grant
     * is reported.
     */
    fun run(agent: Agent, arguments: String): String {
        if (!offered(agent)) {
            return mapper.writeValueAsString(
                mapOf("error" to "This agent has not been granted enough connections to search"),
            )
        }

        val granted = granted(agent)

        val asked = query(arguments)?.trim().orEmpty()
        val matches = if (asked.isEmpty()) granted else granted.filter { matches(it, asked) }

        return mapper.writeValueAsString(
            mapOf(
                "granted" to granted.size,
                "found" to matches.map { found ->
                    mapOf(
                        "id" to found.id,
                        "name" to found.name,
                        "type" to found.type.name,
                        // Where it points, which is how somebody tells a staging
                        // connection from a production one when both are called
                        // after the same system. Never the credential.
                        "host" to host(found.effectiveUrl),
                    )
                },
            ),
        )
    }

    /**
     * Every word of the query found in the name, the type or the host.
     *
     * All of them rather than any, because a query is narrowing: somebody
     * asking for "production jira" wants the production one, and an answer
     * holding every Jira connection and every production connection is the
     * question not being answered.
     */
    private fun matches(connection: WorkspaceConnectionView, asked: String): Boolean {
        val against = "${connection.name} ${connection.type.name} ${connection.effectiveUrl}".lowercase()
        return asked.lowercase().split(NOT_A_WORD).filter { it.isNotEmpty() }.all { against.contains(it) }
    }

    /** The host out of an address, and the address itself where it is not one. */
    private fun host(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    private fun query(arguments: String): String? = runCatching {
        mapper.readTree(arguments).path(QUERY).takeIf { it.isTextual }?.stringValue()
    }.getOrNull()

    companion object {

        const val FIND = "find_connections"
        const val QUERY = "query"

        /**
         * How many are recited in the briefing before the tool takes over.
         *
         * Six lines in a system turn is nothing and a round trip is not free,
         * so a handful stays where it was. Past that the briefing is paying on
         * every round for a list most turns never read.
         */
        const val LISTED = 6

        private val NOT_A_WORD = Regex("[^a-z0-9]+")
    }
}

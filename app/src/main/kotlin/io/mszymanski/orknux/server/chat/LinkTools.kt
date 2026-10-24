package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Where this installation is, and a page of it as a link that works anywhere.
 *
 * A tool answering about something with a page - an issue, a run, a session -
 * hands back its address as a path, `/workspace/9/tasks/28`, because that is
 * what the browser follows. Pasted into Slack or a mail by a model, a path has
 * no host behind it and reads as punctuation. This answers the host the
 * installation is reached at - [io.mszymanski.orknux.server.security.InstallationAddress], the same one its mails
 * link from - and turns a path into the absolute link to put in front of a
 * person.
 *
 * Needs no session: the host is the installation's, not the conversation's.
 */
@Service
class LinkTools(
    private val address: io.mszymanski.orknux.server.security.InstallationAddress,
    private val mapper: ObjectMapper,
) {

    /** The shed for one turn. Always lent: answering needs nothing kept. */
    fun shed(): ToolShed = Shed()

    private inner class Shed : ToolShed {

        override fun specs(): List<ToolSpec> = listOf(
            ToolSpec(
                name = LINK,
                readOnly = true,
                description = "Gives the address this installation is reached at, and turns a path into a full " +
                    "link to it. Tools answering about something with a page give its address as a path " +
                    "(\"/workspace/9/tasks/28\"); a path pasted into a message has no host and does not open. " +
                    "Give it as $PATH to get the link to send; leave $PATH out for the address alone.",
                parameters = listOf(
                    ToolParameterSpec(PATH, "A path in this installation, starting with /. Omit for the address alone.", required = false),
                ),
            ),
        )

        override fun handles(name: String): Boolean = name == LINK

        override fun run(call: ToolCall): String {
            val base = base()
            val asked = runCatching { mapper.readTree(call.arguments).path(PATH) }.getOrNull()
                ?.takeIf { it.isTextual }?.stringValue()?.trim().orEmpty()
            val answer = linkedMapOf<String, Any>("base" to base)
            if (asked.isNotEmpty()) answer["url"] = absolute(base, asked)
            return mapper.writeValueAsString(answer)
        }
    }

    /** The configured host, without a trailing slash; the development address where none is set, as links elsewhere fall back. */
    private fun base(): String = address.base().ifEmpty { "http://localhost:5173" }

    companion object {
        const val LINK = "app_link"
        const val PATH = "path"

        /**
         * A path made absolute on [base]. One already absolute is left as it is -
         * a model handing back a link it was given should not get it mangled - and
         * one without its leading slash is given it.
         */
        fun absolute(base: String, path: String): String = when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> base + path
            else -> "$base/$path"
        }
    }
}

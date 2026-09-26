package io.mszymanski.orknux.server.chat

import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * The server's own tools, for the agent form. Issue #444.
 *
 * Not an administrator's query, for the reason `pluginTools` is not: deciding
 * what an agent may call is workspace work, and the list is the same for every
 * workspace because it is the server's. No argument, because nothing about it
 * depends on who is asking.
 */
@Controller
class BuiltInToolsAPI(private val builtIns: BuiltInTools) {

    @QueryMapping
    fun builtInTools(): List<BuiltInTool> = builtIns.all()
}

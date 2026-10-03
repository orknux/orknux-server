package io.mszymanski.orknux.server.chat

import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
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
class BuiltInToolsAPI(
    private val builtIns: BuiltInTools,
    private val summaries: BuiltInToolSummaries,
    private val http: io.mszymanski.orknux.server.embedded.HttpToolPolicy,
) {

    /** Read once: the built-ins and what the model is told about them are fixed for a release. */
    private val summaryByName: Map<String, String> by lazy { summaries.summaries() }

    @QueryMapping
    fun builtInTools(): List<BuiltInTool> = builtIns.all()

    @SchemaMapping(typeName = "BuiltInTool")
    fun summary(tool: BuiltInTool): String? = summaryByName[tool.name]

    /**
     * Why no agent is offered it now, whatever it was granted. Issue #602: the
     * HTTP tools switched off in Admin Settings leave every grant as it was,
     * and the Tools list says so rather than losing the rows.
     */
    @SchemaMapping(typeName = "BuiltInTool")
    fun unavailable(tool: BuiltInTool): String? =
        if (http.offers(tool.name)) null else "Switched off for this installation in Admin Settings -> HTTP tools."
}

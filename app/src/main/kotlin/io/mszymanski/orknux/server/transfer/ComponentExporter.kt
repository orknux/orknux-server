package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.library.ScriptLibraryRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.obj.WorkflowObject
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.trigger.WorkflowTrigger
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workflow.NodeKind
import io.mszymanski.orknux.server.workflow.Workflow
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNode
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Turns one of a workspace's components into the JSON somebody downloads.
 *
 * Walks outward from the thing asked for, collecting what it reaches, and writes
 * every one of them by name. Nothing here reads a credential: a variable a
 * function is handed appears as a name and a shape under `requires`, and the
 * three things that are kept beside a key — a model, a connection, an MCP
 * server — are read through [ComponentExternals], which hands back a name and a
 * type and nothing else. That is the whole of what the target needs in order to
 * say what to point them at.
 *
 * What an agent, an action and a trigger reach outward to is written on the
 * component that reaches it and nowhere else. There is deliberately no second
 * list of them beside `requires.variables`: a variable is there because it is
 * never a component and the file has nowhere else to describe it, whereas an
 * external is written where the reference is made, and a summary of those
 * kept beside them would be a second thing to keep in step with them.
 */
@Service
class ComponentExporter(
    private val objects: WorkflowObjectRepository,
    private val functions: WorkflowFunctionRepository,
    private val conditions: WorkflowConditionRepository,
    private val tools: AgentToolRepository,
    private val skills: AgentSkillRepository,
    private val catalogs: SkillCatalogRepository,
    private val variables: WorkspaceVariableRepository,
    private val scriptLibraries: ScriptLibraryRepository,
    private val actions: WorkflowActionRepository,
    private val triggers: WorkflowTriggerRepository,
    private val agents: AgentRepository,
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val edges: WorkflowEdgeRepository,
    private val externals: ComponentExternals,
    private val mapper: ObjectMapper,
    @Value("\${orknux.version:unknown}") private val version: String,
) {

    /**
     * The envelope, formatted, for one component of one workspace.
     *
     * The caller has already decided the workspace is the caller's to see; this
     * checks that the component is in it, which is the other half — an id from
     * another workspace would otherwise export another workspace's code.
     */
    fun export(workspaceId: Long, kind: ComponentKind, id: Long, depth: ExportDepth): String {
        val root = Held(kind, id, nameOf(workspaceId, kind, id))
        val reached = if (depth == ExportDepth.DEEP) walk(workspaceId, root) else listOf(root)

        val envelope = mapper.createObjectNode()
        envelope.put("formatVersion", COMPONENT_FORMAT_VERSION)
        // Only ever quoted back in a message. Nothing branches on it, which is
        // why the version that logic reads is a separate integer above.
        envelope.put("producedBy", "Orknux $version")
        envelope.put("exportedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        envelope.put("depth", depth.name)

        val roots = envelope.putArray("roots")
        roots.addObject().apply {
            put("kind", root.kind.name)
            put("name", root.name)
        }

        envelope.set("requires", requirements(workspaceId, reached))

        val components = envelope.putArray("components")
        // Objects before what is typed against them, and functions before the
        // conditions that call them: the file reads top-down the way it is
        // applied, which is the order the catalogue itself is declared in.
        reached.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
            .forEach { components.add(describe(workspaceId, it)) }

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope)
    }

    /** `normaliseOrder.orkx.json` — recognisable, and not mistaken for source. */
    fun fileNameFor(workspaceId: Long, kind: ComponentKind, id: Long): String {
        val name = nameOf(workspaceId, kind, id)
        val safe = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifEmpty { kind.label }
        return "$safe.orkx.json"
    }

    /** One component, and where in the workspace it is. */
    private data class Held(val kind: ComponentKind, val id: Long, val name: String)

    /**
     * Everything the root reaches, the root included, each thing once.
     *
     * Breadth-first with a seen set, because the graph has cycles in it — an
     * object may hold a property of its own type, which is how a tree is
     * described, and a composite condition may name another composite.
     */
    private fun walk(workspaceId: Long, root: Held): List<Held> {
        val found = LinkedHashMap<Pair<ComponentKind, Long>, Held>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val held = queue.removeFirst()
            if (found.put(held.kind to held.id, held) != null) continue
            reachedBy(workspaceId, held).forEach { queue.addLast(it) }
        }
        return found.values.toList()
    }

    /** The edges: what one component points at, within this workspace. */
    private fun reachedBy(workspaceId: Long, held: Held): List<Held> = when (held.kind) {
        ComponentKind.OBJECT -> object_(workspaceId, held.id).properties
            .mapNotNull { it.refObjectId }
            .distinct()
            .map { Held(ComponentKind.OBJECT, it, nameOf(workspaceId, ComponentKind.OBJECT, it)) }

        ComponentKind.FUNCTION -> function(workspaceId, held.id).let { function ->
            (function.params.mapNotNull { it.objectId } + listOfNotNull(function.returnObjectId))
                .distinct()
                .map { Held(ComponentKind.OBJECT, it, nameOf(workspaceId, ComponentKind.OBJECT, it)) } +
                // And what it imports, which is a function that has to travel with
                // it: a function exported without the ones it calls imports as code
                // whose `imports` object is empty.
                function.imports.map { it.importedId }.distinct()
                    .map { Held(ComponentKind.FUNCTION, it, nameOf(workspaceId, ComponentKind.FUNCTION, it)) }
        }

        ComponentKind.CONDITION -> condition(workspaceId, held.id).let { condition ->
            condition.members.distinct()
                .map { Held(ComponentKind.CONDITION, it, nameOf(workspaceId, ComponentKind.CONDITION, it)) } +
                listOfNotNull(condition.functionId)
                    .map { Held(ComponentKind.FUNCTION, it, nameOf(workspaceId, ComponentKind.FUNCTION, it)) }
        }

        // A tool reaches the functions it imports. A skill is markdown and
        // reaches nothing at all.
        ComponentKind.TOOL -> tool(workspaceId, held.id).imports.map { it.importedId }.distinct()
            .map { Held(ComponentKind.FUNCTION, it, nameOf(workspaceId, ComponentKind.FUNCTION, it)) }

        ComponentKind.SKILL -> emptyList()

        ComponentKind.ACTION -> action(workspaceId, held.id).let { action ->
            held(workspaceId, ComponentKind.FUNCTION, action.functionId) +
                held(workspaceId, ComponentKind.CONDITION, action.conditionId)
        }

        ComponentKind.TRIGGER -> trigger(workspaceId, held.id).let { trigger ->
            held(workspaceId, ComponentKind.OBJECT, trigger.objectId) +
                held(workspaceId, ComponentKind.FUNCTION, trigger.authFunctionId) +
                held(workspaceId, ComponentKind.CONDITION, trigger.conditionId)
        }

        /*
         * An agent's tools, and the skills in the catalogs it was granted.
         *
         * The tools are the obvious half: a granted tool that is not there is a
         * name an agent can never call. The skills are the less obvious one, and
         * they are here for the same reason — a grant is per catalog rather than
         * per skill, so carrying the grant and not what is in it would land an
         * agent that reads nothing. What the agent holds is the catalog's name
         * either way; the skills travel because a deep export is the one that
         * lands somewhere it can be used.
         */
        ComponentKind.AGENT -> agent(workspaceId, held.id).let { agent ->
            agent.tools.distinct()
                .mapNotNull { tools.findByWorkspaceIdAndName(workspaceId, it) }
                .map { Held(ComponentKind.TOOL, it.id!!, it.name) } +
                agent.skillCatalogs.distinct()
                    .mapNotNull { catalogs.findByWorkspaceIdAndName(workspaceId, it)?.id }
                    .flatMap { skills.findByCatalogId(it) }
                    .filter { it.workspaceId == workspaceId }
                    .map { Held(ComponentKind.SKILL, it.id!!, it.name) }
        }

        /*
         * Everything the graph points at, which is every kind above.
         *
         * A node names one thing at most, and which field it names it in is
         * decided by the node's kind — so this reads the field the kind uses
         * rather than every field that is set. A node whose kind was changed
         * leaves the old id behind it, and carrying what that pointed at would
         * put things in the file the workflow does not use.
         */
        ComponentKind.WORKFLOW -> nodes.findByWorkflowId(held.id).flatMap { node ->
            when (node.kind) {
                // The shape an agent's answer is held to travels too: without
                // it the import would land a node whose answer no longer has
                // one, which is a different workflow wearing the same name.
                NodeKind.AGENT -> held(workspaceId, ComponentKind.AGENT, node.agentId) +
                    held(workspaceId, ComponentKind.OBJECT, node.outputObjectId)
                NodeKind.TRIGGER -> held(workspaceId, ComponentKind.TRIGGER, node.triggerId)
                NodeKind.ACTION -> held(workspaceId, ComponentKind.ACTION, node.actionId)
                NodeKind.CONDITION -> held(workspaceId, ComponentKind.CONDITION, node.conditionId)
                NodeKind.OBJECT -> held(workspaceId, ComponentKind.OBJECT, node.objectId)
                // A session node points at no catalogue entry: what it names is
                // a key it carries, so it brings nothing with it.
                NodeKind.SESSION -> emptyList()
                // An image node points at a model, which is not a transferable
                // component - no more than an agent node's model is - so it
                // brings nothing with it and the workspace it lands in supplies
                // its own.
                NodeKind.IMAGE -> emptyList()
                // A decision node likewise: its model stays behind. Issue #577.
                NodeKind.DECISION -> emptyList()
            }
        }.distinct()
    }

    /**
     * One edge, or none, for an id that may be null or may be gone.
     *
     * A component points at what it points at with a nullable column, and a
     * column that names something since deleted is a shape the catalogue allows.
     * Neither is worth refusing an export over: what is not there is not
     * carried, and the import says so by name rather than the export failing.
     */
    private fun held(workspaceId: Long, kind: ComponentKind, id: Long?): List<Held> {
        val name = id?.let { nameOrNull(workspaceId, kind, it) } ?: return emptyList()
        return listOf(Held(kind, id, name))
    }

    /**
     * What the target workspace has to have already.
     *
     * Only variables, because only a variable cannot be carried — its value is a
     * secret, and a variable without its value is a name with nothing behind it.
     * Everything else an envelope points at but does not hold is a shallow
     * export's business, and the import works that out from the components it
     * can see rather than from a second list here that could disagree with them.
     */
    private fun requirements(workspaceId: Long, reached: List<Held>): ObjectNode {
        val node = mapper.createObjectNode()
        val listed = node.putArray("variables")
        reached.filter { it.kind == ComponentKind.FUNCTION }
            .flatMap { function(workspaceId, it.id).externals }
            .map { it.variableId }
            .distinct()
            .mapNotNull { variables.findByIdOrNull(it) }
            .filter { it.workspaceId == workspaceId }
            .sortedBy { it.name }
            .forEach { variable ->
                listed.addObject().apply {
                    put("name", variable.name)
                    put("type", variable.type.name)
                    // Says why the value is not here, without carrying anything.
                    put("secret", variable.kind.name == "SECRET")
                }
            }
        return node
    }

    private fun describe(workspaceId: Long, held: Held): ObjectNode = when (held.kind) {
        ComponentKind.OBJECT -> describeObject(object_(workspaceId, held.id))
        ComponentKind.FUNCTION -> describeFunction(workspaceId, function(workspaceId, held.id))
        ComponentKind.CONDITION -> describeCondition(workspaceId, condition(workspaceId, held.id))
        ComponentKind.TOOL -> describeTool(workspaceId, tool(workspaceId, held.id))
        ComponentKind.SKILL -> describeSkill(skill(workspaceId, held.id))
        ComponentKind.ACTION -> describeAction(workspaceId, action(workspaceId, held.id))
        ComponentKind.TRIGGER -> describeTrigger(workspaceId, trigger(workspaceId, held.id))
        ComponentKind.AGENT -> describeAgent(workspaceId, agent(workspaceId, held.id))
        ComponentKind.WORKFLOW -> describeWorkflow(workflow(workspaceId, held.id))
    }

    private fun describeObject(held: WorkflowObject): ObjectNode = start(ComponentKind.OBJECT, held.name).apply {
        put("description", held.description)
        val properties = putArray("properties")
        held.properties.forEach { property ->
            properties.addObject().apply {
                put("name", property.name)
                put("kind", property.kind.name)
                // A property can only ever point at an object, so the reference
                // is a bare name; nothing has to say of what kind.
                put("objectRef", property.refObjectId?.let { objects.findByIdOrNull(it)?.name })
                put("elementKind", property.elementKind?.name)
                put("description", property.description)
            }
        }
    }

    private fun describeFunction(workspaceId: Long, held: WorkflowFunction): ObjectNode =
        start(ComponentKind.FUNCTION, held.name).apply {
            put("description", held.description)
            // Both halves, always: what runs and what was written. Either alone
            // would import as a function whose editor and sandbox disagree.
            put("source", held.source)
            put("typescript", held.typescript)
            put("returnType", held.returnType.name)
            put("returnObjectRef", held.returnObjectId?.let { objects.findByIdOrNull(it)?.name })
            val params = putArray("params")
            held.params.forEach { param ->
                params.addObject().apply {
                    put("name", param.name)
                    put("type", param.type.name)
                    put("objectRef", param.objectId?.let { objects.findByIdOrNull(it)?.name })
                }
            }
            // Names only. The order matters — they are passed after the declared
            // parameters, in this order — so it is an array rather than a set.
            val handed: ArrayNode = putArray("variableRefs")
            held.externals.forEach { external ->
                variables.findByIdOrNull(external.variableId)
                    ?.takeIf { it.workspaceId == workspaceId }
                    ?.let { handed.add(it.name) }
            }
            /*
             * What it imports, as a reference and a local name.
             *
             * The reference is the imported function's name, like every other
             * reference in this format - an envelope crosses installations, where
             * an id means nothing. The local name is not a reference at all: it is
             * the word the source uses, and it travels with the source.
             */
            val imported = putArray("imports")
            held.imports.forEach { one ->
                functions.findByIdOrNull(one.importedId)
                    ?.takeIf { it.workspaceId == workspaceId }
                    ?.let { target ->
                        imported.addObject().apply {
                            put("functionRef", target.name)
                            put("name", one.importName)
                        }
                    }
            }
            /*
             * The libraries it uses, by key and local name.
             *
             * The library itself does not travel. It is the installation's — loaded
             * once, vouched for once, and shared by every workspace — so an
             * envelope that carried a copy would be an envelope that installs
             * software, which is not a thing importing a function should do. The
             * key is the reference, and an installation that has not loaded it says
             * so on the way in.
             */
            val used = putArray("libraries")
            held.libraries.forEach { one ->
                scriptLibraries.findById(one.importedId).orElse(null)?.let { library ->
                    used.addObject().apply {
                        put("libraryRef", library.key)
                        put("name", one.importName)
                    }
                }
            }
        }

    private fun describeCondition(workspaceId: Long, held: WorkflowCondition): ObjectNode =
        start(ComponentKind.CONDITION, held.name).apply {
            put("type", held.type.name)
            put("property", held.property?.name)
            put("check", held.check?.name)
            put("negate", held.negate)
            put("icon", held.icon)
            val values = putArray("values")
            held.values.forEach { values.add(it) }
            put("functionRef", held.functionId?.let { functions.findByIdOrNull(it)?.name })
            val members = putArray("memberRefs")
            held.members.forEach { member ->
                conditions.findByIdOrNull(member)?.takeIf { it.workspaceId == workspaceId }?.let { members.add(it.name) }
            }
        }

    private fun describeTool(workspaceId: Long, held: AgentTool): ObjectNode =
        start(ComponentKind.TOOL, held.name).apply {
            put("description", held.description)
            put("source", held.source)
            put("typescript", held.typescript)
            put("enabled", held.enabled)
            // What it takes. Left out until now, which made every exported tool
            // one the receiving model is told the wrong signature for.
            val params = putArray("params")
            held.params.forEach { param ->
                params.addObject().apply {
                    put("name", param.name)
                    put("type", param.type.name)
                    put("objectRef", param.objectId?.let { objects.findByIdOrNull(it)?.name })
                }
            }
            val imported = putArray("imports")
            held.imports.forEach { one ->
                functions.findByIdOrNull(one.importedId)
                    ?.takeIf { it.workspaceId == workspaceId }
                    ?.let { target ->
                        imported.addObject().apply {
                            put("functionRef", target.name)
                            put("name", one.importName)
                        }
                    }
            }
            /*
             * The libraries it uses, by key and local name.
             *
             * The library itself does not travel. It is the installation's — loaded
             * once, vouched for once, and shared by every workspace — so an
             * envelope that carried a copy would be an envelope that installs
             * software, which is not a thing importing a function should do. The
             * key is the reference, and an installation that has not loaded it says
             * so on the way in.
             */
            val used = putArray("libraries")
            held.libraries.forEach { one ->
                scriptLibraries.findById(one.importedId).orElse(null)?.let { library ->
                    used.addObject().apply {
                        put("libraryRef", library.key)
                        put("name", one.importName)
                    }
                }
            }
        }

    private fun describeSkill(held: AgentSkill): ObjectNode = start(ComponentKind.SKILL, held.name).apply {
        put("description", held.description)
        // The folder by name. Not a component of its own — a catalog holds
        // nothing but a name, so the import makes one if the target lacks it
        // rather than asking somebody to import a folder first.
        put("catalog", catalogs.findByIdOrNull(held.catalogId)?.name)
        put("content", held.content)
        put("enabled", held.enabled)
        // Its command, so a copy answers to the same one where it is free. Issue #570.
        put("key", held.key)
    }

    /**
     * An action, with the connection it sends through named rather than carried.
     *
     * Its headers and its URL are written as they stand. They are columns the
     * workspace typed in the clear, like a function's source, and the format's
     * rule about secrets is the one the database draws: what is encrypted at
     * rest does not travel, and neither of these is. A header that holds a token
     * is a token in a plain column, which is a thing to fix where it is stored
     * rather than a thing to paper over here.
     */
    private fun describeAction(workspaceId: Long, held: WorkflowAction): ObjectNode =
        start(ComponentKind.ACTION, held.name).apply {
            put("type", held.type.name)
            put("subtype", held.subtype.name)
            putExternal("connectionRef", externals.connectionReference(workspaceId, held.connectionId))
            put("connectionAction", held.connectionAction?.name)
            put("content", held.content)
            // Where a send goes, and no kind beside it: an action stopped
            // holding one, and an older export carrying `target` is read past.
            put("targetName", held.targetName)
            put("emailTo", held.emailTo)
            put("emailCc", held.emailCc)
            put("emailSubject", held.emailSubject)
            put("emailReplyTo", held.emailReplyTo)
            put("url", held.url)
            put("method", held.method)
            put("headers", held.headers)
            put("functionRef", held.functionId?.let { functions.findByIdOrNull(it)?.name })
            // Two names and no ref: a plugin belongs to the installation, and
            // the importing one either has it loaded or says so at the run.
            put("pluginKey", held.pluginKey)
            put("pluginAction", held.pluginAction)
            put("conditionExpression", held.conditionExpression)
            put("conditionRef", held.conditionId?.let { conditions.findByIdOrNull(it)?.name })
            put("timeoutSeconds", held.timeoutSeconds)
            put("retryIntervalSeconds", held.retryIntervalSeconds)
            put("durationSeconds", held.durationSeconds)
            put("icon", held.icon)
            val mappings = putArray("mappings")
            held.mappings.forEach { mapping ->
                mappings.addObject().apply {
                    put("argument", mapping.argument)
                    put("expression", mapping.expression)
                }
            }
        }

    /**
     * A trigger, with everything that makes it fire.
     *
     * Whether it is switched on is not written: an import decides that, and it
     * decides off. A file is opened in a workspace nobody has looked at yet, and
     * a trigger that arrived listening would be firing on somebody else's
     * connection before anybody had read what it does.
     */
    private fun describeTrigger(workspaceId: Long, held: WorkflowTrigger): ObjectNode =
        start(ComponentKind.TRIGGER, held.name).apply {
            put("type", held.type.name)
            putExternal("connectionRef", externals.connectionReference(workspaceId, held.connectionId))
            put("action", held.action?.name)
            put("cron", held.cron)
            put("timezone", held.timezone)
            put("webhookPath", held.webhookPath)
            put("objectRef", held.objectId?.let { objects.findByIdOrNull(it)?.name })
            put("authType", held.authType.name)
            put("authFunctionRef", held.authFunctionId?.let { functions.findByIdOrNull(it)?.name })
            put("conditionRef", held.conditionId?.let { conditions.findByIdOrNull(it)?.name })
            put("payload", held.payload)
            put("icon", held.icon)
        }

    /**
     * An agent: its instructions, what it may call, and what it thinks with.
     *
     * The two access grants travel with it, and they are the reason the plan
     * says so out loud. Neither is a secret — they are settings a workspace
     * chose — but an agent that may open a shell is an agent that may run
     * anything the account on the other end can, and somebody importing a file
     * from elsewhere should be told that before pressing the button rather than
     * after.
     */
    private fun describeAgent(workspaceId: Long, held: Agent): ObjectNode =
        start(ComponentKind.AGENT, held.name).apply {
            put("type", held.type.name)
            put("description", held.description)
            put("systemPrompt", held.systemPrompt)
            put("enabled", held.enabled)
            put("orknuxAccess", held.orknuxAccess)
            put("shellAccess", held.shellAccess)
            put("icon", held.icon)
            putExternal("modelRef", externals.modelReference(workspaceId, held.modelId))
            val servers = putArray("mcpServerRefs")
            held.mcpServers.forEach { servers.add(it) }
            /*
             * Everything it may call, its grants and the built-ins it has not
             * hidden - one list, the way the screen and the API say it. The row
             * keeps the built-ins the other way round since #455, and an
             * envelope that carried that would be a file whose meaning depended
             * on which release wrote it; the import splits it again.
             */
            val granted: ArrayNode = putArray("toolRefs")
            (held.tools + io.mszymanski.orknux.server.chat.BuiltInTools.grantedTo(held))
                .distinct().forEach { granted.add(it) }
            // Grants by name, and by name is how the agent holds them. A catalog
            // holds nothing but a name, so the import makes one it does not have
            // rather than refusing — the same answer a skill's folder gets.
            val skillCatalogs = putArray("skillCatalogs")
            held.skillCatalogs.forEach { skillCatalogs.add(it) }
            val memoryCatalogs = putArray("memoryCatalogs")
            held.memoryCatalogs.forEach { memoryCatalogs.add(it) }
        }

    /**
     * A workflow's graph, node keys and all.
     *
     * The keys travel unchanged. They are the workflow's own — an edge names
     * them, and a node's parameter names the node it reads from — so they mean
     * exactly as much in the target as they did here, which is the one kind of
     * identifier this format does carry.
     *
     * What the workflow is called is written; whether it was published is not.
     * Publishing takes a copy of the graph to run, and an import that arrived
     * published would be promising to run a copy nobody made.
     */
    private fun describeWorkflow(held: Workflow): ObjectNode {
        val workflowId = requireNotNull(held.id)
        return start(ComponentKind.WORKFLOW, held.name).apply {
            put("description", held.description)
            val drawn = putArray("nodes")
            nodes.findByWorkflowId(workflowId).sortedBy { it.nodeKey }.forEach { drawn.add(describeNode(it)) }
            val wired = putArray("edges")
            edges.findByWorkflowId(workflowId)
                .sortedWith(compareBy({ it.sourceKey }, { it.targetKey }))
                .forEach { edge ->
                    wired.addObject().apply {
                        put("source", edge.sourceKey)
                        put("target", edge.targetKey)
                        put("branch", edge.branch?.name)
                        put("option", edge.branchOption)
                    }
                }
        }
    }

    private fun describeNode(held: WorkflowNode): ObjectNode = mapper.createObjectNode().apply {
        put("key", held.nodeKey)
        put("kind", held.kind.name)
        put("name", held.name)
        put("description", held.description)
        put("outputName", held.outputName)
        put("orientation", held.orientation?.name)
        put("icon", held.icon)
        put("x", held.positionX)
        put("y", held.positionY)
        put("yesLabel", held.yesLabel)
        put("noLabel", held.noLabel)
        put("fallbackEnabled", held.fallbackEnabled)
        put("retryAttempts", held.retryAttempts)
        put("retryBackoffSeconds", held.retryBackoffSeconds)
        put("retryMultiplier", held.retryMultiplier)
        put("retryMaxWaitSeconds", held.retryMaxWaitSeconds)
        put("retryJitter", held.retryJitter)
        put("retryBudgetSeconds", held.retryBudgetSeconds)
        put("enabled", held.enabled)
        // The one the node's kind uses, and only that one: an id left behind by
        // a node that changed kind is not something this workflow points at.
        put("agentRef", held.agentId.takeIf { held.kind == NodeKind.AGENT }?.let { agents.findByIdOrNull(it)?.name })
        put("triggerRef", held.triggerId.takeIf { held.kind == NodeKind.TRIGGER }?.let { triggers.findByIdOrNull(it)?.name })
        put("actionRef", held.actionId.takeIf { held.kind == NodeKind.ACTION }?.let { actions.findByIdOrNull(it)?.name })
        put(
            "conditionRef",
            held.conditionId.takeIf { held.kind == NodeKind.CONDITION }?.let { conditions.findByIdOrNull(it)?.name },
        )
        put("objectRef", held.objectId.takeIf { held.kind == NodeKind.OBJECT }?.let { objects.findByIdOrNull(it)?.name })
        // The two halves of an agent's answer shape: the object it is held to
        // (by name, resolved on the far side like every other ref), and the
        // node it is saved into (by key, which the file carries as it is).
        put(
            "outputShapeRef",
            held.outputObjectId.takeIf { held.kind == NodeKind.AGENT }?.let { objects.findByIdOrNull(it)?.name },
        )
        put("outputNodeKey", held.outputNodeKey.takeIf { held.kind == NodeKind.AGENT })
        // What an image node asks of the drawing. Plain words, not references:
        // a size means the same thing in every workspace. The model itself is
        // not carried - an id, and an installation's own - so the far side
        // picks one and draws it at this size.
        put("imageSize", held.imageSize.takeIf { held.kind == NodeKind.IMAGE })
        put("imageQuality", held.imageQuality.takeIf { held.kind == NodeKind.IMAGE })
        put("imageStyle", held.imageStyle.takeIf { held.kind == NodeKind.IMAGE })
        // What a decision node asks, as the document it keeps: questions are
        // words and options, and mean the same in every workspace. The model
        // is left behind like an image node's. Issue #577.
        put("decisionSpec", held.decisionSpec.takeIf { held.kind == NodeKind.DECISION })
        val mappings = putArray("mappings")
        held.mappings.forEach { mapping ->
            mappings.addObject().apply {
                put("name", mapping.name)
                put("expression", mapping.expression)
                put("mode", mapping.mode.name)
                put("sourceNodeKey", mapping.sourceNodeKey)
                // What the field holds, where the node named it. By the shape's
                // name and not its id, like every other reference in a file: an
                // id means nothing in the workspace this is opened in.
                put("fieldKind", mapping.fieldKind?.name)
                put("fieldElementKind", mapping.fieldElementKind?.name)
                put("fieldObjectRef", mapping.fieldRefObjectId?.let { objects.findByIdOrNull(it)?.name })
            }
        }
    }

    private fun start(kind: ComponentKind, name: String): ObjectNode = mapper.createObjectNode().apply {
        put("kind", kind.name)
        put("name", name)
    }

    /** A reference this file cannot carry: a name, a type, and never anything else. */
    private fun ObjectNode.putExternal(field: String, reference: ExternalReference?) {
        if (reference == null) {
            putNull(field)
            return
        }
        putObject(field).apply {
            put("name", reference.name)
            put("provider", reference.provider)
            put("type", reference.type)
        }
    }

    private fun nameOf(workspaceId: Long, kind: ComponentKind, id: Long): String = when (kind) {
        ComponentKind.OBJECT -> object_(workspaceId, id).name
        ComponentKind.FUNCTION -> function(workspaceId, id).name
        ComponentKind.CONDITION -> condition(workspaceId, id).name
        ComponentKind.TOOL -> tool(workspaceId, id).name
        ComponentKind.SKILL -> skill(workspaceId, id).name
        ComponentKind.ACTION -> action(workspaceId, id).name
        ComponentKind.TRIGGER -> trigger(workspaceId, id).name
        ComponentKind.AGENT -> agent(workspaceId, id).name
        ComponentKind.WORKFLOW -> workflow(workspaceId, id).name
    }

    /** The same question, for a column that may name something since deleted. */
    private fun nameOrNull(workspaceId: Long, kind: ComponentKind, id: Long): String? =
        runCatching { nameOf(workspaceId, kind, id) }.getOrNull()

    /*
     * One refusal for an id that is not there and one that belongs to another
     * workspace, for the reason `WorkspaceAccess` gives: two answers to the same
     * question is a directory of what this installation holds.
     */

    private fun object_(workspaceId: Long, id: Long): WorkflowObject =
        objects.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.OBJECT, id)

    private fun function(workspaceId: Long, id: Long): WorkflowFunction =
        functions.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.FUNCTION, id)

    private fun condition(workspaceId: Long, id: Long): WorkflowCondition =
        conditions.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.CONDITION, id)

    private fun tool(workspaceId: Long, id: Long): AgentTool =
        tools.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.TOOL, id)

    private fun skill(workspaceId: Long, id: Long): AgentSkill =
        skills.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.SKILL, id)

    private fun action(workspaceId: Long, id: Long): WorkflowAction =
        actions.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.ACTION, id)

    private fun trigger(workspaceId: Long, id: Long): WorkflowTrigger =
        triggers.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.TRIGGER, id)

    private fun agent(workspaceId: Long, id: Long): Agent =
        agents.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.AGENT, id)

    /**
     * A workflow definition, if this workspace has been given it.
     *
     * The definition is installation-wide and the assignment is what makes it
     * one workspace's, so this asks the assignment rather than the definition —
     * the same question the graph editor asks before it will open one.
     */
    private fun workflow(workspaceId: Long, id: Long): Workflow =
        workflows.findByIdOrNull(id)?.takeIf { assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, id) }
            ?: missing(ComponentKind.WORKFLOW, id)

    private fun missing(kind: ComponentKind, id: Long): Nothing =
        throw ComponentNotExportableException(kind, id)
}

/** No such component in this workspace — the same answer for both reasons. */
class ComponentNotExportableException(kind: ComponentKind, id: Long) :
    RuntimeException("This workspace has no ${kind.label} with id $id")

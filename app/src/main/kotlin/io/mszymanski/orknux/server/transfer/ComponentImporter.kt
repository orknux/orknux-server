package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.action.ArgumentMapping
import io.mszymanski.orknux.server.action.ConnectionAction
import io.mszymanski.orknux.server.action.FunctionExternal
import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ScriptImport
import io.mszymanski.orknux.server.library.ScriptLibraryRepository
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolParam
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.SkillCatalog
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.agent.SkillKeys
import io.mszymanski.orknux.server.agent.SkillFormat
import io.mszymanski.orknux.server.condition.ConditionCheck
import io.mszymanski.orknux.server.condition.ConditionProperty
import io.mszymanski.orknux.server.condition.ConditionType
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.memory.MemoryCatalog
import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import io.mszymanski.orknux.server.obj.ObjectProperty
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObject
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.trigger.TriggerAction
import io.mszymanski.orknux.server.trigger.TriggerType
import io.mszymanski.orknux.server.trigger.WebhookAuthType
import io.mszymanski.orknux.server.trigger.WorkflowTrigger
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workflow.EdgeBranch
import io.mszymanski.orknux.server.workflow.MappingMode
import io.mszymanski.orknux.server.workflow.NodeKind
import io.mszymanski.orknux.server.workflow.NodeMapping
import io.mszymanski.orknux.server.workflow.NodeOrientation
import io.mszymanski.orknux.server.workflow.Workflow
import io.mszymanski.orknux.server.workflow.WorkflowEdge
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNode
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflow
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Reads an envelope, says what it would do, and — separately — does it.
 *
 * The two are the same walk over the same parsed file, which is what makes the
 * preview trustworthy: [plan] and [apply] disagree only in that the second one
 * saves. Anything the first says is impossible, the second refuses outright,
 * and it refuses before writing rather than partway through.
 *
 * Both take the same bindings, and that is the whole of the binding step: what
 * a file points at but could never carry — a model, a connection, an MCP
 * server — is matched against the target workspace by name, and whatever does
 * not match comes back as [ImportDisposition.MISSING] with the kind and the
 * name of what is wanted. Answering those is another call to the same [plan]
 * with the answers attached, which either comes back importable or says what is
 * still outstanding. There is no third call that only asks what needs binding:
 * that is what the plan already is, and a second one could disagree with it.
 */
@Service
class ComponentImporter(
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
    private val memoryCatalogs: MemoryCatalogRepository,
    private val externals: ComponentExternals,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    /** For the tools and skill catalogs a plugin brings, which every workspace has. Issue #383. */
    private val plugins: io.mszymanski.orknux.server.plugin.PluginRepository,
    private val declarations: io.mszymanski.orknux.server.plugin.PluginDeclarations,
) {

    /** What the confirmation dialog shows. Reads nothing into the workspace. */
    fun plan(
        workspaceId: Long,
        envelope: String,
        bindings: List<ComponentBinding> = emptyList(),
        exclude: List<ComponentExclusion> = emptyList(),
        rename: List<ComponentRename> = emptyList(),
    ): ImportPlan = plan(workspaceId, parse(envelope), bindings, exclude, rename)

    /**
     * What an envelope says about itself, without a workspace in the question.
     *
     * A stored template is described on a screen — its format version, and what
     * kinds it holds — before anybody has said which workspace it would go into,
     * and the parser that answers that has to be *this* parser. A second reader
     * that only wanted the header is a second opinion about what the file is,
     * and it would be the lenient one: it would describe happily a file this
     * refuses, and somebody would be offered a template that cannot be used.
     *
     * Throws exactly what [plan] throws, for exactly the same files.
     */
    fun describe(envelope: String): EnvelopeSummary = parse(envelope).let { parsed ->
        EnvelopeSummary(
            formatVersion = parsed.formatVersion,
            producedBy = parsed.producedBy,
            depth = parsed.depth,
            // In the catalogue's own order rather than the file's, so two
            // templates holding the same kinds read the same way in a list.
            kinds = parsed.components.map { it.kind }.distinct().sortedBy { it.ordinal },
            componentCount = parsed.components.size,
            names = parsed.components.map { it.kind to it.name },
        )
    }

    /**
     * Creates everything the plan said it would, or nothing.
     *
     * One transaction, and the refusal for an unresolvable reference happens
     * before the first save — an import that got halfway would leave a function
     * typed against an object that is not there, which is worse than no import.
     */
    @Transactional
    fun apply(
        workspaceId: Long,
        envelope: String,
        bindings: List<ComponentBinding> = emptyList(),
        exclude: List<ComponentExclusion> = emptyList(),
        rename: List<ComponentRename> = emptyList(),
    ): ImportPlan {
        val parsed = parse(envelope)
        val plan = plan(workspaceId, parsed, bindings, exclude, rename)
        if (!plan.importable) throw ImportNotPossibleException(plan.problems)

        /*
         * What is being left out, taken back off the plan rather than worked out
         * a second time. Asking for one exclusion can leave out three — whatever
         * cannot do without it goes too — and the closure that decided which is
         * the plan's. Reading it back from the entries is what makes it certain
         * that what was previewed is what is written.
         */
        val leftOut = plan.entries.filter { it.disposition == ImportDisposition.EXCLUDE && it.carried }
            .mapNotNull { entry -> entry.kind?.let { it to entry.name } }
            .toSet()
        val held = parsed.components.filter { (it.kind to it.name) !in leftOut }
        // And the tools an agent is to arrive without: references left out, which
        // are the rows that are EXCLUDE and not carried. Issue #383.
        val dropped = plan.entries.filter { it.disposition == ImportDisposition.EXCLUDE && !it.carried }
            .map { it.name }
            .toSet()

        // What the file could not carry, settled before anything is written:
        // by name where the target has one, and by what the caller bound where
        // it does not. Resolved through the same function the plan used, so what
        // was previewed and what is written cannot come apart.
        val told = bindings.associateBy { it.kind to it.name }
        val bound = held.flatMap(::externalsOf).distinct()
            .mapNotNull { reference ->
                boundTo(workspaceId, reference, told)?.let { (reference.kind to reference.label) to it }
            }
            .toMap()

        // Every reference resolves through this: an envelope name to the id it
        // means here. Filled as things are created, so a renamed component is
        // pointed at by its new id without anything else having to know it moved.
        val resolved = mutableMapOf<Pair<ComponentKind, String>, Long>()
        val named = plan.entries.filter { it.kind != null && it.disposition in WRITTEN }
            .associate { (it.kind!! to it.name) to it.targetName }

        /*
         * The order things are written in is not a tidiness question, it is what
         * the database will accept. `ck_workflow_function_return_object` and
         * `ck_workflow_condition_shape` are row-level checks: a function that
         * says it returns an object has to name one in the same INSERT, and a
         * composite condition has to have its members. So neither can be created
         * empty and filled in afterwards, and both are created complete, after
         * whatever they point at.
         *
         * An object is the exception, and the reason it can be: its properties
         * are rows in a table of their own, so the object exists before any of
         * them do. That is what lets an object hold a property of its own type,
         * which is how a tree is described, and it is why objects are the one
         * kind written in two passes.
         */
        val objects = held.filter { it.kind == ComponentKind.OBJECT }
        val written = objects +
            // A function may import another, so they go in dependency order too:
            // the import is written into the row that creates the importer, and
            // the imported function has to have an id by then.
            dependenciesFirst(held.filter { it.kind == ComponentKind.FUNCTION }, "function") { node ->
                node.path("imports").values().mapNotNull { it.text("functionRef") }
            } +
            dependenciesFirst(held.filter { it.kind == ComponentKind.CONDITION }, "condition") { node ->
                node.names("memberRefs")
            } +
            // Everything after a condition in the catalogue, in the catalogue's
            // order — which is a dependency order, so an action is written after
            // the function it calls, an agent after the tools it was granted and
            // a workflow after every one of them. A stable sort, so two of a kind
            // are still written in the order the file put them in.
            held.filter { it.kind.ordinal > ComponentKind.CONDITION.ordinal }
                .sortedBy { it.kind.ordinal }

        objects.forEach { component ->
            resolved[component.kind to component.name] =
                createObject(workspaceId, component, named.getValue(component.kind to component.name))
        }
        objects.forEach { component -> wireObject(workspaceId, component, resolved) }
        written.filter { it.kind != ComponentKind.OBJECT }.forEach { component ->
            val here = named.getValue(component.kind to component.name)
            resolved[component.kind to component.name] = create(workspaceId, component, here, resolved, bound, dropped)
        }

        plan.entries.filter { it.kind != null && it.disposition in WRITTEN }.forEach { entry ->
            val said = if (entry.disposition == ImportDisposition.RENAME) {
                "${entry.kind!!.label.replaceFirstChar(Char::uppercase)} ${entry.name} imported as ${entry.targetName}"
            } else {
                "${entry.kind!!.label.replaceFirstChar(Char::uppercase)} ${entry.targetName} imported"
            }
            auditRecorder.record(workspaceId, categoryOf(entry.kind!!), said)
        }
        return plan
    }

    // ---------------------------------------------------------------- planning

    private fun plan(
        workspaceId: Long,
        parsed: Parsed,
        bindings: List<ComponentBinding>,
        exclude: List<ComponentExclusion>,
        rename: List<ComponentRename>,
    ): ImportPlan {
        val inFile = parsed.components.map { it.kind to it.name }.toSet()
        /*
         * What may be left out that the file does not carry: a tool an agent
         * points at. An agent without one of its tools is still an agent - the
         * grant is a list, and a shorter list is a real thing to want when the
         * tool is not here and not worth making. Nothing else a file points at
         * can go: a function an action calls, an object a field is typed
         * against, are what the thing *is*. Issue #383.
         */
        val agentTools = parsed.components.filter { it.kind == ComponentKind.AGENT }
            .flatMap { it.node.names("toolRefs") }
            .toSet()
        val dropped = exclude.filter { (it.kind to it.name) !in inFile }.map { one ->
            if (one.kind != ComponentKind.TOOL || one.name !in agentTools) {
                throw ImportExclusionUnknownException(one.kind, one.name)
            }
            one.name
        }.toSet()
        val leftOut = leftOut(workspaceId, parsed, exclude.filter { (it.kind to it.name) in inFile })
        val held = parsed.components.filter { (it.kind to it.name) !in leftOut }
        val carried = held.map { it.kind to it.name }.toSet()
        val entries = mutableListOf<ImportEntry>()
        val problems = mutableListOf<String>()

        // The names asked for on the way in; one naming nothing the file carries
        // is a client showing one file and asking about another. Issue #383.
        val renames = rename.associate { (it.kind to it.name) to it.targetName.trim() }
        rename.forEach { if ((it.kind to it.name) !in inFile) throw ImportRenameUnknownException(it.kind, it.name) }
        val pluginTools = pluginToolNames()

        if (held.isEmpty()) {
            problems += "Everything this file carries has been left out, so there is nothing left to import."
        }

        // A component's name here, decided before anything else looks at it:
        // what points at it has to point at the name it will actually have. In
        // the file's own order, left-out ones included, so a row that was ticked
        // off stays where it was on the list instead of vanishing from it.
        val taken = mutableMapOf<ComponentKind, MutableSet<String>>()
        parsed.components.forEach { component ->
            val why = leftOut[component.kind to component.name]
            if (why != null) {
                entries += ImportEntry(
                    kind = component.kind,
                    carried = true,
                    name = component.name,
                    targetName = component.name,
                    disposition = ImportDisposition.EXCLUDE,
                    detail = why,
                )
                return@forEach
            }
            val claimed = taken.getOrPut(component.kind) { mutableSetOf() }
            val asked = renames[component.kind to component.name]?.takeIf { it.isNotEmpty() && it != component.name }
            if (asked != null) {
                /*
                 * A name somebody chose, checked the way an automatic one is
                 * and refused in the plan rather than moved along: they typed
                 * it, so the answer to a clash is theirs to give. Issue #383.
                 */
                val why = when {
                    !usable(component.kind, asked) -> "\"$asked\" is not a name ${component.kind.indefinite} can have here."
                    !nameFree(workspaceId, component.kind, asked, claimed) ->
                        "This workspace already has ${component.kind.indefinite} called $asked, so ${component.name} " +
                            "cannot arrive as $asked. Pick another name."
                    else -> null
                }
                if (why != null) problems += why else claimed.add(asked)
                entries += ImportEntry(
                    kind = component.kind,
                    carried = true,
                    name = component.name,
                    targetName = asked,
                    disposition = ImportDisposition.RENAME,
                    detail = why ?: (
                        "Arrives as $asked, as asked. Everything else in this file that pointed at " +
                            "${component.name} points at $asked." + caution(component)
                        ),
                )
                return@forEach
            }
            val name = freeName(workspaceId, component.kind, component.name, claimed)
            claimed.add(name)
            entries += if (name == component.name) {
                ImportEntry(
                    kind = component.kind,
                    carried = true,
                    name = component.name,
                    targetName = name,
                    disposition = ImportDisposition.CREATE,
                    detail = "New ${component.kind.label} in this workspace." + caution(component),
                )
            } else {
                ImportEntry(
                    kind = component.kind,
                    carried = true,
                    name = component.name,
                    targetName = name,
                    disposition = ImportDisposition.RENAME,
                    detail = "This workspace already has ${component.kind.indefinite} called ${component.name}, " +
                        "so this one arrives as $name. Everything else in this file that pointed at it " +
                        "points at $name." + caution(component),
                )
            }
        }

        // Then every reference. One that the file carries is settled above; one
        // it does not — including one it carries and is leaving out — is the
        // target workspace's to satisfy, by name.
        val seen = mutableSetOf<Pair<ComponentKind, String>>()
        held.forEach { component ->
            /*
             * The tools a plugin brings, first: they are in the grant list like
             * any other and every workspace of this installation has them, so
             * they are neither carried nor missing. Said on their own row so the
             * list accounts for every name the agent holds. Issue #383.
             */
            if (component.kind == ComponentKind.AGENT) {
                component.node.names("toolRefs").filter { it in pluginTools }.forEach { name ->
                    if (!seen.add(ComponentKind.TOOL to name)) return@forEach
                    entries += if (name in dropped) {
                        ImportEntry(
                            kind = ComponentKind.TOOL, name = name, targetName = name, droppable = true,
                            disposition = ImportDisposition.EXCLUDE,
                            detail = "Left out: ${component.name} arrives without it.",
                        )
                    } else {
                        ImportEntry(
                            kind = ComponentKind.TOOL, name = name, targetName = name, droppable = true,
                            disposition = ImportDisposition.REUSE,
                            detail = "Brought by the ${pluginNameOf(name)} plugin, which every workspace of this " +
                                "installation has; the imported ${component.name} keeps it.",
                        )
                    }
                }
            }
            referencesOf(component).forEach { (kind, name) ->
                if (kind to name in carried) return@forEach
                if (!seen.add(kind to name)) return@forEach
                val droppable = component.kind == ComponentKind.AGENT && kind == ComponentKind.TOOL
                if (droppable && name in dropped) {
                    entries += ImportEntry(
                        kind = kind, name = name, targetName = name, droppable = true,
                        disposition = ImportDisposition.EXCLUDE,
                        detail = "Left out: ${component.name} arrives without it.",
                    )
                    return@forEach
                }
                val existing = findByName(workspaceId, kind, name)
                if (existing == null) {
                    entries += ImportEntry(
                        kind = kind,
                        name = name,
                        targetName = name,
                        droppable = droppable,
                        disposition = ImportDisposition.MISSING,
                        // The file may carry it and be leaving it out, which is a
                        // different mistake with a different fix: put it back.
                        detail = if (kind to name in leftOut) {
                            "${component.name} points at ${kind.indefinite} called $name, which is being left " +
                                "out and which this workspace does not have. Keep it, or leave " +
                                "${component.name} out as well."
                        } else if (droppable) {
                            "${component.name} points at a tool called $name, which this file does not carry " +
                                "and this workspace does not have. Create it here first, export again with " +
                                "everything included, or leave it out and ${component.name} arrives without it."
                        } else {
                            "${component.name} points at ${kind.indefinite} called $name, which this file " +
                                "does not carry and this workspace does not have. Export again with everything " +
                                "included, or create it here first."
                        },
                    )
                    problems += "There is no ${kind.label} called $name here, and ${component.name} needs one."
                } else {
                    entries += ImportEntry(
                        kind = kind,
                        name = name,
                        targetName = name,
                        droppable = droppable,
                        disposition = ImportDisposition.REUSE,
                        detail = "Already here; the imported ${component.name} will point at it.",
                    )
                }
            }
        }

        /*
         * Then what no export could have carried.
         *
         * A workspace of its own by that name is the ordinary answer and needs
         * nobody: a file moving between two workspaces of one installation
         * usually finds the same connections waiting. Anything else is the
         * binding step — the caller says which of this workspace's rows the name
         * means, and until it has, the import is refused rather than left to
         * invent a connection or leave an agent thinking with nothing.
         */
        val told = bindings.associateBy { it.kind to it.name }
        val asked = mutableSetOf<Pair<ExternalKind, String>>()
        held.forEach { component ->
            externalsOf(component).forEach { reference ->
                if (!asked.add(reference.kind to reference.label)) return@forEach
                val kind = reference.kind
                val was = reference.type?.let { " (${it.lowercase().replace('_', ' ')})" }.orEmpty()
                val here = boundTo(workspaceId, reference, told)
                    ?.let { externals.labelOf(workspaceId, kind, it) }
                if (here == null) {
                    entries += ImportEntry(
                        kind = null,
                        external = kind,
                        name = reference.label,
                        targetName = reference.label,
                        disposition = ImportDisposition.MISSING,
                        detail = "${component.name} points at ${kind.indefinite} called ${reference.label}$was. " +
                            "A ${kind.label} is kept beside a credential, so no export carries one — say which " +
                            "of this workspace's own it means, or make one and import again.",
                    )
                    problems += "There is no ${kind.label} called ${reference.label} here, and " +
                        "${component.name} needs one."
                } else {
                    entries += ImportEntry(
                        kind = null,
                        external = kind,
                        name = reference.label,
                        targetName = here,
                        disposition = ImportDisposition.REUSE,
                        detail = "The imported ${component.name} will point at $here. Its credentials are this " +
                            "workspace's own; nothing came from the file but the name.",
                    )
                }
            }
        }

        /*
         * Variables last, because they are the ones somebody has to go and make.
         *
         * One that only a left-out function was handed is no longer wanted: a
         * variable is required by the functions that name it, and the header
         * lists them because the export read those same functions. So it is
         * dropped only where the file itself shows that every function naming it
         * has gone — never on the header's word alone, which is what keeps a
         * hand-written file behaving exactly as it did.
         */
        parsed.variables.filter { stillHanded(parsed, held, it.name) }.forEach { variable ->
            val existing = variables.findByWorkspaceId(workspaceId).firstOrNull { it.name == variable.name }
            if (existing == null) {
                entries += ImportEntry(
                    kind = null,
                    name = variable.name,
                    targetName = variable.name,
                    disposition = ImportDisposition.MISSING,
                    detail = "A ${variable.type.lowercase()} variable called ${variable.name} is handed to one of " +
                        "these functions. Values never travel in an export, so create it here with its own " +
                        "value first.",
                )
                problems += "This workspace has no variable called ${variable.name}; " +
                    "a variable's value is never exported, so it has to be created here."
            } else {
                entries += ImportEntry(
                    kind = null,
                    name = variable.name,
                    targetName = existing.name,
                    disposition = ImportDisposition.REUSE,
                    detail = "Already here. Its value is this workspace's own; nothing came from the file.",
                )
            }
        }

        return ImportPlan(
            formatVersion = parsed.formatVersion,
            producedBy = parsed.producedBy,
            depth = parsed.depth,
            importable = problems.isEmpty(),
            entries = entries,
            problems = problems,
        )
    }

    /**
     * Which of the file's components this import will not create, and why.
     *
     * Only a component the file *carries* can be left out, and that is the whole
     * of the difference between the two halves of a plan: what is in the
     * envelope is here and can be dropped, while what the envelope points at and
     * does not carry is a name with nothing behind it — dropping it would drop a
     * reference, not a thing, and leave whatever made the reference pointing at
     * nowhere. So an exclusion naming one of those is refused rather than
     * quietly obeyed.
     *
     * What is asked for is not always all that goes. A kept component that
     * points at a left-out one has to point at *something*, and where this
     * workspace has nothing by that name there is nothing for it to point at, so
     * it is left out too — and so is whatever needed *that*, until the set stops
     * growing. Each one carries the sentence saying which loss took it, because
     * the alternative is somebody unticking one row, importing, and finding out
     * afterwards that three did not arrive.
     *
     * The workspace's own row is what stops the cascade, and that is the case
     * worth having: leaving out an object this workspace already has is exactly
     * how somebody says "use the one that is here", and every function typed
     * against it stays and points at that one.
     */
    private fun leftOut(
        workspaceId: Long,
        parsed: Parsed,
        asked: List<ComponentExclusion>,
    ): Map<Pair<ComponentKind, String>, String> {
        if (asked.isEmpty()) return emptyMap()
        val carried = parsed.components.map { it.kind to it.name }.toSet()
        val out = mutableMapOf<Pair<ComponentKind, String>, String>()

        asked.forEach { one ->
            if (one.kind to one.name !in carried) throw ImportExclusionUnknownException(one.kind, one.name)
            out[one.kind to one.name] = "Left out: nothing is created for it, and it is still in the file."
        }

        var settled = false
        while (!settled) {
            settled = true
            parsed.components.filter { (it.kind to it.name) !in out }.forEach { component ->
                val needed = referencesOf(component).firstOrNull { (kind, name) ->
                    kind to name in out && findByName(workspaceId, kind, name) == null
                } ?: return@forEach
                out[component.kind to component.name] =
                    "Left out too: it points at ${needed.first.indefinite} called ${needed.second}, which is " +
                        "being left out and which this workspace does not have either."
                settled = false
            }
        }
        return out
    }

    /**
     * Whether any function still being imported is handed this variable.
     *
     * True as well when no function in the file names it at all: the header is
     * then the only thing that says it is wanted, and a file this did not write
     * is taken at its word rather than second-guessed.
     */
    private fun stillHanded(parsed: Parsed, held: List<ParsedComponent>, name: String): Boolean {
        fun hands(components: List<ParsedComponent>) = components.any { component ->
            component.kind == ComponentKind.FUNCTION && name in component.node.names("variableRefs")
        }
        return hands(held) || !hands(parsed.components)
    }

    /** Every reference one component makes, as kind and envelope name. */
    private fun referencesOf(component: ParsedComponent): List<Pair<ComponentKind, String>> {
        val node = component.node
        return when (component.kind) {
            ComponentKind.OBJECT -> node.path("properties").values().mapNotNull { it.text("objectRef") }
                .map { ComponentKind.OBJECT to it }

            ComponentKind.FUNCTION ->
                (
                    node.path("params").values().mapNotNull { it.text("objectRef") } +
                        listOfNotNull(node.text("returnObjectRef"))
                    ).map { ComponentKind.OBJECT to it } +
                    node.path("imports").values().mapNotNull { it.text("functionRef") }
                        .map { ComponentKind.FUNCTION to it }

            ComponentKind.CONDITION ->
                node.names("memberRefs").map { ComponentKind.CONDITION to it } +
                    listOfNotNull(node.text("functionRef")).map { ComponentKind.FUNCTION to it }

            ComponentKind.TOOL -> node.path("imports").values().mapNotNull { it.text("functionRef") }
                .map { ComponentKind.FUNCTION to it }

            ComponentKind.SKILL -> emptyList()

            ComponentKind.ACTION ->
                listOfNotNull(node.text("functionRef")).map { ComponentKind.FUNCTION to it } +
                    listOfNotNull(node.text("conditionRef")).map { ComponentKind.CONDITION to it }

            ComponentKind.TRIGGER ->
                listOfNotNull(node.text("objectRef")).map { ComponentKind.OBJECT to it } +
                    listOfNotNull(node.text("authFunctionRef")).map { ComponentKind.FUNCTION to it } +
                    listOfNotNull(node.text("conditionRef")).map { ComponentKind.CONDITION to it }

            // The grants that name a component. The catalogs an agent may read
            // are not among them: a catalog holds nothing but a name, so one
            // this workspace lacks is made rather than asked about, which is the
            // answer a skill's own folder already gets.
            // A tool a plugin brings is in the same list and is not a reference
            // to anything of this workspace's: every workspace has it. Issue #383.
            // Nor is one the server brings itself - the note, the clock, saving a
            // file - which every agent holds by name since #444 and every
            // installation has: a file naming one is asking for nothing.
            // All of the server's own, the ones that reach outside included:
            // web_search is a built-in kept off until granted, not a tool of
            // this workspace's, and asking the target for one refused every
            // agent that could search. Issue #570.
            ComponentKind.AGENT -> node.names("toolRefs")
                .filter { it !in pluginToolNames() && !BuiltInTools.switchable(it) }
                .map { ComponentKind.TOOL to it }

            ComponentKind.WORKFLOW -> node.path("nodes").values().flatMap { drawn ->
                listOfNotNull(
                    drawn.text("agentRef")?.let { ComponentKind.AGENT to it },
                    drawn.text("triggerRef")?.let { ComponentKind.TRIGGER to it },
                    drawn.text("actionRef")?.let { ComponentKind.ACTION to it },
                    drawn.text("conditionRef")?.let { ComponentKind.CONDITION to it },
                    drawn.text("objectRef")?.let { ComponentKind.OBJECT to it },
                    // The shape an agent's answer is held to is an object like
                    // any other the graph points at.
                    drawn.text("outputShapeRef")?.let { ComponentKind.OBJECT to it },
                ) + drawn.path("mappings").values().mapNotNull { mapping ->
                    // And the shape one of the node's own fields is declared to
                    // hold, which is one more object the file depends on.
                    mapping.text("fieldObjectRef")?.let { ComponentKind.OBJECT to it }
                }
            }
        }
    }

    /**
     * The imports an envelope carried, resolved against what has just been made.
     *
     * The envelope names the imported function and this side turns that back into
     * an id, which is the reverse of what the export did — a name is the only
     * reference that means anything between two installations, and an id is the
     * only one that means anything within one. The local name travels unchanged,
     * because it is not a reference: it is a word in the source that came with it.
     *
     * An entry naming nothing is dropped rather than refused. Nothing can get here
     * without the walk having pulled the imported function in beside it, so this is
     * the belt on an envelope somebody edited by hand.
     */
    private fun importsIn(node: JsonNode, idOf: (ComponentKind, String) -> Long): MutableList<ScriptImport> =
        node.path("imports").values().mapNotNull { one ->
            val target = one.text("functionRef") ?: return@mapNotNull null
            val called = one.text("name") ?: return@mapNotNull null
            ScriptImport(importedId = idOf(ComponentKind.FUNCTION, target), importName = called)
        }.toMutableList()

    /**
     * The libraries an envelope named, matched against what is loaded here.
     *
     * By key, because that is the only reference that means anything between two
     * installations — and refused outright when this one has not loaded it. A
     * function imported with its library silently missing would be a function
     * whose first call into it is a `TypeError`, found at the moment a workflow
     * needed it rather than while somebody was importing.
     */
    private fun librariesIn(node: JsonNode): MutableList<ScriptImport> =
        node.path("libraries").values().mapNotNull { one ->
            val key = one.text("libraryRef") ?: return@mapNotNull null
            val called = one.text("name") ?: return@mapNotNull null
            val library = scriptLibraries.findByKey(key)
                ?: throw EnvelopeInvalidException(
                    "No library called $key is loaded in this installation. Load it first, then import again.",
                )
            ScriptImport(importedId = requireNotNull(library.id), importName = called)
        }.toMutableList()

    /** Every reference one component makes that no envelope could have carried. */
    private fun externalsOf(component: ParsedComponent): List<ExternalReference> {
        val node = component.node
        return when (component.kind) {
            ComponentKind.AGENT ->
                listOfNotNull(externalIn(node.path("modelRef"), ExternalKind.MODEL)) +
                    node.names("mcpServerRefs").map { externals.mcpServerReference(it) }

            ComponentKind.ACTION, ComponentKind.TRIGGER ->
                listOfNotNull(externalIn(node.path("connectionRef"), ExternalKind.CONNECTION))

            else -> emptyList()
        }
    }

    /** One `{ name, provider, type }` the export wrote, or nothing where it wrote null. */
    private fun externalIn(node: JsonNode, kind: ExternalKind): ExternalReference? {
        if (!node.isObject) return null
        val name = node.text("name")?.trim()?.ifEmpty { null } ?: return null
        return ExternalReference(kind, name, node.text("provider"), node.text("type"))
    }

    /**
     * Which of this workspace's rows a reference means, or null while nothing does.
     *
     * The one place that answers this, so the preview and the import cannot
     * differ. A binding wins over a match by name: somebody who has said which
     * connection they mean has said it about this import, and a row that happens
     * to share the name is not a better answer than the one they gave.
     */
    private fun boundTo(
        workspaceId: Long,
        reference: ExternalReference,
        told: Map<Pair<ExternalKind, String>, ComponentBinding>,
    ): Long? {
        val binding = told[reference.kind to reference.label] ?: return externals.find(workspaceId, reference)
        externals.labelOf(workspaceId, reference.kind, binding.targetId)
            ?: throw ImportBindingInvalidException(reference.kind, reference.label, binding.targetId)
        return binding.targetId
    }

    /**
     * What is worth saying out loud about a component before it is created.
     *
     * Two things are, and both are about a file that came from somewhere else
     * doing something the moment it lands. Neither is a secret and neither
     * refuses the import — they are what somebody should have read before
     * pressing the button rather than after.
     */
    private fun caution(component: ParsedComponent): String = when {
        component.kind == ComponentKind.TRIGGER ->
            " It arrives switched off, so nothing it listens for starts anything until it is switched on here."

        component.kind == ComponentKind.AGENT && component.node.path("shellAccess").asBoolean(false) ->
            " It was granted shell access, which lets it run commands on one of this installation's machines."

        component.kind == ComponentKind.AGENT && component.node.path("orknuxAccess").asBoolean(false) ->
            " It was granted access to Orknux itself, which lets it read this installation and start workflows."

        else -> ""
    }

    // ----------------------------------------------------------------- writing

    /**
     * One kind's components, each one after the ones it points at.
     *
     * Two kinds need this and they need it for the same reason: what they point
     * at has to exist by the time the INSERT that creates them runs. A composite
     * condition names its members in that INSERT, and a function's imports are
     * written with it. Both catalogues refuse a cycle when the component is saved,
     * so a well-formed file has none; one that arrives with a cycle is a
     * hand-edited file, and it is refused rather than looped over.
     *
     * [refs] is what one component points at, by envelope name, and [noun] is what
     * to call the thing in the sentence somebody reads when it points at itself.
     */
    private fun dependenciesFirst(
        held: List<ParsedComponent>,
        noun: String,
        refs: (JsonNode) -> List<String>,
    ): List<ParsedComponent> {
        val byName = held.associateBy { it.name }
        val ordered = LinkedHashSet<ParsedComponent>()
        val placing = mutableSetOf<String>()

        fun place(component: ParsedComponent) {
            if (component in ordered) return
            if (!placing.add(component.name)) {
                throw EnvelopeInvalidException("The $noun ${component.name} would contain itself")
            }
            refs(component.node).mapNotNull { byName[it] }.forEach(::place)
            placing.remove(component.name)
            ordered.add(component)
        }

        held.forEach(::place)
        return ordered.toList()
    }

    /** An object's own row, so that an id exists for a property to point at. */
    private fun createObject(workspaceId: Long, component: ParsedComponent, name: String): Long {
        val now = OffsetDateTime.now()
        val who = currentUser()
        return objects.save(
            WorkflowObject(
                workspaceId = workspaceId,
                name = name,
                description = component.node.text("description"),
                createdAt = now,
                createdBy = who,
                lastModifiedAt = now,
                lastModifiedBy = who,
            ),
        ).id!!
    }

    /** An object's properties, once every object in the file has an id. */
    private fun wireObject(
        workspaceId: Long,
        component: ParsedComponent,
        resolved: Map<Pair<ComponentKind, String>, Long>,
    ) {
        val held = objects.findById(resolved.getValue(ComponentKind.OBJECT to component.name)).orElseThrow()
        held.properties = component.node.path("properties").values().map { property ->
            ObjectProperty(
                name = property.text("name").orEmpty(),
                kind = property.enumOf<PropertyKind>("kind", null, component),
                refObjectId = property.text("objectRef")
                    ?.let { idFor(workspaceId, ComponentKind.OBJECT, it, resolved) },
                elementKind = property.enumOrNull<PropertyKind>("elementKind", component),
                description = property.text("description"),
            )
        }.toMutableList()
    }

    /**
     * Everything that is not an object, created whole.
     *
     * Whole because it has to be: the row-level checks on a function's return
     * type and on a condition's shape are satisfied by the INSERT or not at all.
     */
    private fun create(
        workspaceId: Long,
        component: ParsedComponent,
        name: String,
        resolved: Map<Pair<ComponentKind, String>, Long>,
        bound: Map<Pair<ExternalKind, String>, Long>,
        /** The tools an agent is to arrive without. Issue #383. */
        dropped: Set<String> = emptySet(),
    ): Long {
        val node = component.node
        val now = OffsetDateTime.now()
        val who = currentUser()
        fun idOf(kind: ComponentKind, of: String) = idFor(workspaceId, kind, of, resolved)
        fun boundId(reference: ExternalReference?): Long? = reference?.let { bound[it.kind to it.label] }

        return when (component.kind) {
            // Written by createObject, in two passes; nothing reaches here.
            ComponentKind.OBJECT -> error("An object is created before this")

            ComponentKind.FUNCTION -> functions.save(
                WorkflowFunction(
                    workspaceId = workspaceId,
                    // Always the workspace's own. A plugin's function belongs to
                    // the plugin that declared it and is not importable at all.
                    scope = FunctionScope.WORKSPACE,
                    name = name,
                    description = node.text("description"),
                    source = node.required("source", component),
                    typescript = node.required("typescript", component),
                    returnType = node.enumOf("returnType", ValueType.MAP, component),
                    returnObjectId = node.text("returnObjectRef")?.let { idOf(ComponentKind.OBJECT, it) },
                    params = node.path("params").values().map { param ->
                        FunctionParam(
                            name = param.text("name").orEmpty(),
                            type = param.enumOf<ValueType>("type", null, component),
                            objectId = param.text("objectRef")?.let { idOf(ComponentKind.OBJECT, it) },
                        )
                    }.toMutableList(),
                    // Order is the signature: externals are passed after the
                    // declared parameters, in this order, and the code was
                    // written against that.
                    externals = node.names("variableRefs").map { of ->
                        val variable = variables.findByWorkspaceId(workspaceId).firstOrNull { it.name == of }
                            ?: throw EnvelopeInvalidException("No variable called $of in this workspace")
                        FunctionExternal(variableId = variable.id!!)
                    }.toMutableList(),
                    imports = importsIn(node, ::idOf),
                    libraries = librariesIn(node),
                    lastModifiedAt = now,
                    lastModifiedBy = who,
                ),
            ).id!!

            ComponentKind.CONDITION -> conditions.save(
                WorkflowCondition(
                    workspaceId = workspaceId,
                    name = name,
                    type = node.enumOf<ConditionType>("type", null, component),
                    property = node.enumOrNull<ConditionProperty>("property", component),
                    check = node.enumOrNull<ConditionCheck>("check", component),
                    negate = node.path("negate").asBoolean(false),
                    functionId = node.text("functionRef")?.let { idOf(ComponentKind.FUNCTION, it) },
                    values = node.names("values").toMutableList(),
                    members = node.names("memberRefs")
                        .map { idOf(ComponentKind.CONDITION, it) }
                        .toMutableList(),
                    icon = node.text("icon"),
                ),
            ).id!!

            ComponentKind.TOOL -> tools.save(
                AgentTool(
                    workspaceId = workspaceId,
                    name = name,
                    description = node.text("description"),
                    source = node.required("source", component),
                    typescript = node.required("typescript", component),
                    enabled = node.path("enabled").asBoolean(true),
                    // An envelope written before tools carried their parameters has
                    // none, which is what those tools already were on this side.
                    params = node.path("params").values().map { param ->
                        AgentToolParam(
                            name = param.text("name").orEmpty(),
                            type = param.enumOf<ValueType>("type", null, component),
                            objectId = param.text("objectRef")?.let { idOf(ComponentKind.OBJECT, it) },
                        )
                    }.toMutableList(),
                    imports = importsIn(node, ::idOf),
                    libraries = librariesIn(node),
                    lastModifiedAt = now,
                    lastModifiedBy = who,
                ),
            ).id!!

            ComponentKind.SKILL -> {
                val content = node.required("content", component)
                val checked = SkillFormat.check(content)
                if (!checked.valid) {
                    throw EnvelopeInvalidException(
                        "The skill ${component.name} is not shaped like a skill: ${checked.message}",
                    )
                }
                skills.save(
                    AgentSkill(
                        workspaceId = workspaceId,
                        catalogId = catalogFor(workspaceId, node.text("catalog"), who),
                        name = name,
                        /*
                         * Its command, which the importer never set: every skill
                         * came in with an empty key, and the second one in a
                         * workspace broke the unique index. Issue #570. The key
                         * the file carries where it is usable and free here, and
                         * otherwise one derived from the name, as the editor does.
                         */
                        key = skillKeyFor(workspaceId, node.text("key"), name),
                        description = node.text("description"),
                        content = content,
                        enabled = node.path("enabled").asBoolean(true),
                        lastModifiedAt = now,
                        lastModifiedBy = who,
                    ),
                ).id!!
            }

            ComponentKind.ACTION -> actions.save(
                /*
                 * A function action with no function is refused in words. Issue
                 * #570: an action whose function was deleted keeps the row with
                 * no function in it, and copying one met the database's own
                 * shape check as a raw SQL error.
                 */
                if (node.text("subtype") == "FUNCTION" && node.text("functionRef") == null) {
                    throw EnvelopeInvalidException(
                        "The action ${component.name} calls a function that no longer exists; " +
                            "choose one for it in the original, then copy it again.",
                    )
                } else WorkflowAction(
                    workspaceId = workspaceId,
                    name = name,
                    type = node.enumOf<ActionType>("type", null, component),
                    subtype = node.enumOf<ActionSubtype>("subtype", null, component),
                    connectionId = boundId(externalIn(node.path("connectionRef"), ExternalKind.CONNECTION)),
                    connectionAction = node.enumOrNull<ConnectionAction>("connectionAction", component),
                    content = node.text("content"),
                    /*
                     * `target` is not read. An export written before an action
                     * stopped holding a message kind still carries one, and it
                     * is ignored rather than refused: the column it went into is
                     * gone, nothing sends by it - a send resolves `targetName`
                     * against the connection instead - and failing an import
                     * over a key that never decided anything would strand every
                     * file made before this.
                     */
                    targetName = node.text("targetName"),
                    emailTo = node.text("emailTo"),
                    emailCc = node.text("emailCc"),
                    emailSubject = node.text("emailSubject"),
                    emailReplyTo = node.text("emailReplyTo"),
                    url = node.text("url"),
                    method = node.text("method"),
                    headers = node.text("headers"),
                    functionId = node.text("functionRef")?.let { idOf(ComponentKind.FUNCTION, it) },
                    pluginKey = node.text("pluginKey"),
                    pluginAction = node.text("pluginAction"),
                    mappings = node.path("mappings").values().map { mapping ->
                        ArgumentMapping(
                            argument = mapping.text("argument").orEmpty(),
                            expression = mapping.text("expression").orEmpty(),
                        )
                    }.toMutableList(),
                    conditionExpression = node.text("conditionExpression"),
                    conditionId = node.text("conditionRef")?.let { idOf(ComponentKind.CONDITION, it) },
                    timeoutSeconds = node.integer("timeoutSeconds"),
                    retryIntervalSeconds = node.integer("retryIntervalSeconds"),
                    durationSeconds = node.integer("durationSeconds"),
                    icon = node.text("icon"),
                ),
            ).id!!

            ComponentKind.TRIGGER -> triggers.save(
                WorkflowTrigger(
                    workspaceId = workspaceId,
                    name = name,
                    type = node.enumOf<TriggerType>("type", null, component),
                    connectionId = boundId(externalIn(node.path("connectionRef"), ExternalKind.CONNECTION)),
                    action = node.enumOrNull<TriggerAction>("action", component),
                    cron = node.text("cron"),
                    timezone = node.text("timezone"),
                    webhookPath = node.text("webhookPath")?.let { freeWebhookPath(it) },
                    objectId = node.text("objectRef")?.let { idOf(ComponentKind.OBJECT, it) },
                    authType = node.enumOf("authType", WebhookAuthType.NONE, component),
                    authFunctionId = node.text("authFunctionRef")?.let { idOf(ComponentKind.FUNCTION, it) },
                    conditionId = node.text("conditionRef")?.let { idOf(ComponentKind.CONDITION, it) },
                    payload = node.text("payload"),
                    // Never on arrival, whatever it was where it came from. A
                    // file is opened in a workspace nobody has read it in yet,
                    // and a trigger that arrived listening would be answering
                    // somebody else's events before anybody had looked at it.
                    enabled = false,
                    icon = node.text("icon"),
                ),
            ).id!!

            ComponentKind.AGENT -> agents.save(
                Agent(
                    workspaceId = workspaceId,
                    name = name,
                    type = node.enumOf("type", AgentType.LLM, component),
                    description = node.text("description"),
                    systemPrompt = node.text("systemPrompt"),
                    enabled = node.path("enabled").asBoolean(true),
                    modelId = boundId(externalIn(node.path("modelRef"), ExternalKind.MODEL)),
                    orknuxAccess = node.path("orknuxAccess").asBoolean(false),
                    shellAccess = node.path("shellAccess").asBoolean(false),
                    // The bound server's name here, which is not always the name
                    // the file used: an agent holds the name, so binding "Jira"
                    // to this workspace's "Jira (staging)" has to write the
                    // second one or the grant would point at nothing.
                    mcpServers = node.names("mcpServerRefs").mapNotNull { server ->
                        bound[ExternalKind.MCP_SERVER to server]
                            ?.let { externals.labelOf(workspaceId, ExternalKind.MCP_SERVER, it) }
                    }.toMutableList(),
                    // A tool it was granted may have been renamed on the way in,
                    // and the grant follows it, exactly as every other reference
                    // in the file does.
                    // A plugin's tool and a built-in of the server's travel as
                    // the name itself: neither is a component of the file, and
                    // both mean the same thing here as where the file was made.
                    /*
                     * Keeping the built-ins that reach outside, which are held
                     * the other way round from the rest: on only while named
                     * here. Dropping them with the others took web search away
                     * from every agent that crossed. Issue #570.
                     */
                    tools = node.names("toolRefs")
                        .filter { it !in dropped && (!BuiltInTools.switchable(it) || BuiltInTools.reaches(it)) }
                        .map {
                            if (it in pluginToolNames() || BuiltInTools.reaches(it)) it
                            else toolNameFor(workspaceId, it, resolved)
                        }
                        .toMutableList(),
                    // And the built-ins among them the other way round: the file
                    // says what the agent holds, the row keeps what it does not,
                    // so a built-in this file has never heard of arrives on -
                    // which is the whole of #455. A file written before the
                    // built-ins were named at all carries none, and its agent
                    // therefore arrives with all of them, as it had.
                    hiddenTools = node.names("toolRefs")
                        .filter { BuiltInTools.switchable(it) }
                        .takeIf { named -> named.isNotEmpty() }
                        ?.let { BuiltInTools.hiddenBy(it) }
                        ?: mutableListOf(),
                    // A plugin's catalog is a grant string, not a folder of
                    // this workspace's; making a folder by that name would
                    // shadow the plugin's own. Issue #383.
                    skillCatalogs = node.names("skillCatalogs")
                        .onEach { if (!pluginCatalog(it)) catalogFor(workspaceId, it, who) }
                        .toMutableList(),
                    memoryCatalogs = node.names("memoryCatalogs")
                        .onEach { memoryCatalogFor(workspaceId, it, who) }
                        .toMutableList(),
                    icon = node.text("icon"),
                ),
            ).id!!

            ComponentKind.WORKFLOW -> createWorkflow(workspaceId, component, name, resolved)
        }
    }

    /**
     * The definition, the assignment that makes it this workspace's, and the graph.
     *
     * Arrives as a draft whatever it was where it came from. Publishing takes a
     * copy of the graph to run from, and a workflow that landed published would
     * be promising to run a copy nobody has made — so the first publish here is
     * somebody's own decision, taken after they have looked at what arrived.
     *
     * Node keys travel unchanged and are the one identifier that does. They are
     * the workflow's own rather than the database's: an edge names them, and a
     * node's parameter names the node it reads from, so they mean in the target
     * exactly what they meant in the source.
     */
    private fun createWorkflow(
        workspaceId: Long,
        component: ParsedComponent,
        name: String,
        resolved: Map<Pair<ComponentKind, String>, Long>,
    ): Long {
        val node = component.node
        val workflow = workflows.save(Workflow(name = name, description = node.text("description")))
        val workflowId = requireNotNull(workflow.id)
        assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = workflow, enabled = true))

        fun idOf(kind: ComponentKind, of: String) = idFor(workspaceId, kind, of, resolved)
        nodes.saveAll(
            node.path("nodes").values().map { drawn ->
                WorkflowNode(
                    workflowId = workflowId,
                    nodeKey = drawn.text("key")?.trim()?.ifEmpty { null }
                        ?: throw EnvelopeInvalidException("A node of the workflow ${component.name} has no key"),
                    kind = drawn.enumOf<NodeKind>("kind", null, component),
                    name = drawn.text("name")?.trim()?.ifEmpty { null } ?: "Untitled node",
                    description = drawn.text("description"),
                    agentId = drawn.text("agentRef")?.let { idOf(ComponentKind.AGENT, it) },
                    triggerId = drawn.text("triggerRef")?.let { idOf(ComponentKind.TRIGGER, it) },
                    actionId = drawn.text("actionRef")?.let { idOf(ComponentKind.ACTION, it) },
                    conditionId = drawn.text("conditionRef")?.let { idOf(ComponentKind.CONDITION, it) },
                    objectId = drawn.text("objectRef")?.let { idOf(ComponentKind.OBJECT, it) },
                    // Both halves of an agent's answer shape. Absent from every
                    // envelope written before answers had shapes, and read as
                    // the prose they were.
                    outputObjectId = drawn.text("outputShapeRef")?.let { idOf(ComponentKind.OBJECT, it) },
                    outputNodeKey = drawn.text("outputNodeKey"),
                    // Absent from every envelope written before an image node
                    // could say a size, and read as the model's default.
                    imageSize = drawn.text("imageSize"),
                    imageQuality = drawn.text("imageQuality"),
                    imageStyle = drawn.text("imageStyle"),
                    // Absent from every envelope written before decision nodes.
                    decisionSpec = drawn.text("decisionSpec"),
                    outputName = drawn.text("outputName"),
                    orientation = drawn.enumOrNull<NodeOrientation>("orientation", component),
                    icon = drawn.text("icon"),
                    positionX = drawn.path("x").asDouble(0.0),
                    positionY = drawn.path("y").asDouble(0.0),
                    yesLabel = drawn.text("yesLabel"),
                    noLabel = drawn.text("noLabel"),
                    // Absent from every envelope written before failure was
                    // something a node could handle, which reads as not doing so.
                    fallbackEnabled = drawn.path("fallbackEnabled").asBoolean(false),
                    retryAttempts = drawn.path("retryAttempts").let { if (it.isNumber) it.asInt() else null },
                    retryBackoffSeconds = drawn.path("retryBackoffSeconds").let { if (it.isNumber) it.asInt() else null },
                    /*
                     * An envelope exported before the curve was a number says
                     * `retryBackoff: EXPONENTIAL`, and files already written are
                     * not going to change. EXPONENTIAL was a multiplier of two,
                     * so it is read as one and the imported node waits what the
                     * exported one waited. Everything absent is what it meant
                     * before there was a field for it: a wait that does not
                     * grow, the engine's own ceiling, no jitter, no budget.
                     */
                    retryMultiplier = drawn.path("retryMultiplier").let { if (it.isNumber) it.asDouble() else null }
                        ?: DOUBLING.takeIf { drawn.text("retryBackoff") == "EXPONENTIAL" },
                    retryMaxWaitSeconds = drawn.path("retryMaxWaitSeconds").let { if (it.isNumber) it.asInt() else null },
                    retryJitter = drawn.path("retryJitter").let { if (it.isNumber) it.asDouble() else null },
                    retryBudgetSeconds = drawn.path("retryBudgetSeconds").let { if (it.isNumber) it.asInt() else null },
                    // Absent from every envelope written before a node could be
                    // switched off, and read as on - which is what it was. A
                    // trigger is kept on whatever the file says, because a save
                    // would refuse it and an import is a save nobody is watching.
                    enabled = drawn.path("enabled").asBoolean(true) || drawn.text("kind") == NodeKind.TRIGGER.name,
                    mappings = drawn.path("mappings").values().map { mapping ->
                        NodeMapping(
                            name = mapping.text("name").orEmpty(),
                            expression = mapping.text("expression").orEmpty(),
                            mode = mapping.enumOf("mode", MappingMode.VALUE, component),
                            sourceNodeKey = mapping.text("sourceNodeKey"),
                            // Absent from every file written before a field
                            // could say what it holds, and read as what it
                            // meant then: nobody said.
                            fieldKind = mapping.enumOrNull<PropertyKind>("fieldKind", component),
                            fieldElementKind = mapping.enumOrNull<PropertyKind>("fieldElementKind", component),
                            fieldRefObjectId = mapping.text("fieldObjectRef")
                                ?.let { idFor(workspaceId, ComponentKind.OBJECT, it, resolved) },
                        )
                    }.toMutableList(),
                )
            },
        )

        val keys = node.path("nodes").values().mapNotNull { it.text("key") }.toSet()
        edges.saveAll(
            node.path("edges").values().map { edge ->
                val source = edge.text("source").orEmpty()
                val target = edge.text("target").orEmpty()
                // A hand-edited file is the only way to get here, and an edge
                // between nodes that are not in the graph would draw a line from
                // nowhere. Refused by name rather than saved and puzzled over.
                if (source !in keys || target !in keys) {
                    throw EnvelopeInvalidException(
                        "The workflow ${component.name} has a line from $source to $target, " +
                            "and one of those is not a node in it",
                    )
                }
                WorkflowEdge(
                    workflowId = workflowId,
                    sourceKey = source,
                    targetKey = target,
                    branch = edge.enumOrNull<EdgeBranch>("branch", component),
                    branchOption = edge.text("option"),
                )
            },
        )
        return workflowId
    }

    /**
     * An envelope name to the id it means here.
     *
     * What arrived with the file first, so a renamed component is pointed at by
     * what came with it rather than by something of the same name that was
     * already here; then the workspace's own, which is how a shallow export lands.
     */
    private fun idFor(
        workspaceId: Long,
        kind: ComponentKind,
        name: String,
        resolved: Map<Pair<ComponentKind, String>, Long>,
    ): Long = resolved[kind to name]
        ?: findByName(workspaceId, kind, name)
        ?: throw EnvelopeInvalidException("No ${kind.label} called $name to point at")

    /**
     * The folder an imported skill lands in.
     *
     * Reused when the workspace has one by that name and created when it does
     * not — the only thing here that is matched rather than renamed, because a
     * catalog holds nothing but a name. Making a second "General" beside the
     * first would be a worse answer than putting the skill in the first.
     */
    private fun catalogFor(workspaceId: Long, name: String?, who: String): Long {
        val wanted = name?.trim()?.ifEmpty { null } ?: DEFAULT_CATALOG
        catalogs.findByWorkspaceIdAndName(workspaceId, wanted)?.let { return it.id!! }
        return catalogs.save(SkillCatalog(workspaceId = workspaceId, name = wanted, createdBy = who)).id!!
    }

    /**
     * The memory catalog an imported agent was granted, made if it is not here.
     *
     * Matched rather than renamed, for the same reason a skill's folder is: a
     * catalog holds nothing but a name, and an agent granted "Runbooks" means
     * this workspace's Runbooks. What is written in it stays this workspace's —
     * a memory is not a component and never travels.
     */
    private fun memoryCatalogFor(workspaceId: Long, name: String, who: String): Long {
        val wanted = name.trim().ifEmpty { DEFAULT_CATALOG }
        memoryCatalogs.findByWorkspaceIdAndName(workspaceId, wanted)?.let { return it.id!! }
        return memoryCatalogs.save(MemoryCatalog(workspaceId = workspaceId, name = wanted, createdBy = who)).id!!
    }

    /** What a granted tool is called here, which a rename on the way in may have changed. */
    private fun toolNameFor(
        workspaceId: Long,
        name: String,
        resolved: Map<Pair<ComponentKind, String>, Long>,
    ): String = tools.findById(idFor(workspaceId, ComponentKind.TOOL, name, resolved)).orElseThrow().name

    /**
     * The URL half a webhook trigger answers on, free across the installation.
     *
     * Unique installation-wide rather than per workspace, because the URL is:
     * two workspaces cannot both answer at `/api/webhooks/build`. So it is
     * suffixed the way a taken name is, and for the same reason — somebody
     * else's endpoint is theirs, and quietly taking it over would answer their
     * callers with this workspace's workflow.
     */
    /** A command for an imported skill that is usable and not taken in this workspace. */
    private fun skillKeyFor(workspaceId: Long, carried: String?, name: String): String {
        val taken = { key: String -> skills.findByWorkspaceIdAndKeyIgnoreCase(workspaceId, key) != null }
        val wanted = carried?.trim()?.takeIf { it.isNotEmpty() && SkillKeys.usable(it) && !taken(it) }
        return wanted ?: SkillKeys.free(SkillKeys.derive(name), taken)
    }

    private fun freeWebhookPath(wanted: String): String {
        if (triggers.findByWebhookPath(wanted) == null) return wanted
        for (attempt in 2..MAX_RENAME) {
            val candidate = "$wanted-$attempt"
            if (triggers.findByWebhookPath(candidate) == null) return candidate
        }
        throw EnvelopeInvalidException(
            "This installation already answers at $wanted and at $MAX_RENAME paths like it. " +
                "Change the path in the file before importing.",
        )
    }

    // ------------------------------------------------------------------ naming

    /**
     * The name this arrives under: its own, or the first free one after it.
     *
     * [claimed] holds what earlier components in the same envelope have already
     * taken, so two things that would land on the same free name do not.
     */
    private fun freeName(
        workspaceId: Long,
        kind: ComponentKind,
        wanted: String,
        claimed: Set<String>,
    ): String {
        requireUsable(kind, wanted)
        if (nameFree(workspaceId, kind, wanted, claimed)) return wanted
        for (attempt in 2..MAX_RENAME) {
            val candidate = suffixed(kind, wanted, attempt)
            if (nameFree(workspaceId, kind, candidate, claimed)) return candidate
        }
        throw EnvelopeInvalidException(
            "This workspace already has $MAX_RENAME ${kind.label}s called $wanted or something like it. " +
                "Rename one of them, or rename this in the file before importing.",
        )
    }

    /**
     * `normalise` becomes `normalise_2`, `Out of hours` becomes `Out of hours (2)`.
     *
     * Two spellings because two of these names are identifiers: a function and a
     * tool are called from JavaScript by name, and an object is written into a
     * type annotation, so a space or a bracket in one of those is not a rename
     * but a break. Conditions and skills are prose and read better with the
     * bracket.
     */
    /** Whether nothing here, in the file so far, or in a plugin answers to this name. */
    private fun nameFree(workspaceId: Long, kind: ComponentKind, name: String, claimed: Set<String>): Boolean =
        name !in claimed && findByName(workspaceId, kind, name) == null &&
            // A workspace function may not shadow one a plugin declared: a call
            // by name would have no way to say which of the two it meant.
            (kind != ComponentKind.FUNCTION || functions.findByScopeAndName(FunctionScope.PLUGIN, name) == null) &&
            (kind != ComponentKind.TOOL || name !in pluginToolNames())

    /**
     * The tools loaded plugins bring, by the name an agent's grant list holds
     * them under - `<key>_<function>`. Every workspace of the installation has
     * them, so a grant naming one is neither carried nor missing. Issue #383.
     */
    private fun pluginToolNames(): Set<String> = plugins.findAll().filter { it.enabled }.flatMap { plugin ->
        declarations.readTools(plugin.declaredTools).map { "${plugin.key}_${it.name}" }
    }.toSet()

    private fun pluginNameOf(toolName: String): String = plugins.findAll()
        .firstOrNull { it.enabled && toolName.startsWith("${it.key}_") }?.name ?: "a"

    /** Whether this skill catalog grant names a plugin's catalog rather than a folder here. */
    private fun pluginCatalog(name: String): Boolean =
        name.endsWith("_plugin") && plugins.findByKey(name.removeSuffix("_plugin")) != null

    private fun usable(kind: ComponentKind, name: String): Boolean = when {
        name.isBlank() -> false
        name.length > limitFor(kind) -> false
        identifierNamed(kind) -> IDENTIFIER.matches(name)
        else -> true
    }

    private fun suffixed(kind: ComponentKind, base: String, attempt: Int): String {
        val suffix = if (identifierNamed(kind)) "_$attempt" else " ($attempt)"
        val limit = limitFor(kind) - suffix.length
        return base.take(limit.coerceAtLeast(1)) + suffix
    }

    private fun requireUsable(kind: ComponentKind, name: String) {
        if (!usable(kind, name)) throw EnvelopeInvalidException("\"$name\" is not a name a ${kind.label} can have here")
    }

    private fun identifierNamed(kind: ComponentKind): Boolean =
        kind == ComponentKind.OBJECT || kind == ComponentKind.FUNCTION || kind == ComponentKind.TOOL

    private fun limitFor(kind: ComponentKind): Int = when (kind) {
        // The width of the column, and for the three identifiers what the name
        // regexes already say. A rename that overflowed either would fail on save.
        ComponentKind.OBJECT, ComponentKind.FUNCTION, ComponentKind.TOOL -> 64
        ComponentKind.CONDITION, ComponentKind.SKILL, ComponentKind.ACTION -> 120
        ComponentKind.TRIGGER, ComponentKind.AGENT, ComponentKind.WORKFLOW -> 255
    }

    /**
     * The id this workspace already has under that name, if any.
     *
     * A workflow is asked of the workspace like everything else: a name is taken
     * only where the workspace itself has assigned one, resolved across the
     * assignment rather than by looking the definition up installation-wide. An
     * import into one workspace no longer sees, renames around, or reports a
     * same-named workflow in another. See issues #338 and #341.
     */
    private fun findByName(workspaceId: Long, kind: ComponentKind, name: String): Long? = when (kind) {
        ComponentKind.OBJECT -> objects.findByWorkspaceIdAndName(workspaceId, name)?.id
        ComponentKind.FUNCTION -> functions.findByWorkspaceIdAndName(workspaceId, name)?.id
        // A definition a workflow owns may share a name with another's, so
        // these three take the shared one where there is one and the first
        // otherwise, rather than throwing over two workflows' "Action".
        ComponentKind.CONDITION -> conditions.findAllByWorkspaceIdAndName(workspaceId, name).shared()?.id
        ComponentKind.TOOL -> tools.findByWorkspaceIdAndName(workspaceId, name)?.id
        ComponentKind.SKILL -> skills.findByWorkspaceIdAndName(workspaceId, name)?.id
        ComponentKind.ACTION -> actions.findAllByWorkspaceIdAndName(workspaceId, name).shared()?.id
        ComponentKind.TRIGGER -> triggers.findAllByWorkspaceIdAndName(workspaceId, name).shared()?.id
        ComponentKind.AGENT -> agents.findByWorkspaceIdAndName(workspaceId, name)?.id
        ComponentKind.WORKFLOW -> assignments.findByWorkspaceIdAndWorkflowName(workspaceId, name)?.workflow?.id
    }

    private fun List<WorkflowAction>.shared() = firstOrNull { it.workflowId == null } ?: firstOrNull()

    @JvmName("sharedTrigger")
    private fun List<WorkflowTrigger>.shared() = firstOrNull { it.workflowId == null } ?: firstOrNull()

    @JvmName("sharedCondition")
    private fun List<WorkflowCondition>.shared() = firstOrNull { it.workflowId == null } ?: firstOrNull()

    private fun categoryOf(kind: ComponentKind): WorkspaceAuditCategory = when (kind) {
        ComponentKind.OBJECT -> WorkspaceAuditCategory.OBJECT
        ComponentKind.FUNCTION, ComponentKind.CONDITION -> WorkspaceAuditCategory.WORKFLOW
        ComponentKind.TOOL, ComponentKind.SKILL, ComponentKind.AGENT -> WorkspaceAuditCategory.AGENT
        ComponentKind.ACTION, ComponentKind.TRIGGER, ComponentKind.WORKFLOW -> WorkspaceAuditCategory.WORKFLOW
    }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    // ----------------------------------------------------------------- reading

    /**
     * The file, checked before anything in it is believed.
     *
     * The version is the first thing read and the first thing that can refuse,
     * because everything after it is only meaningful under a version this
     * understands.
     */
    private fun parse(envelope: String): Parsed {
        val root = try {
            mapper.readTree(envelope)
        } catch (cause: JacksonException) {
            throw EnvelopeUnreadableException("it is not valid JSON (${cause.originalMessage})")
        }
        if (!root.isObject) throw EnvelopeUnreadableException("the file does not hold a JSON object")

        val producedBy = root.text("producedBy")
        val version = root.path("formatVersion")
        if (!version.isIntegralNumber) {
            throw EnvelopeUnreadableException("it carries no formatVersion, so there is no telling what it is")
        }
        val found = version.asInt()
        if (found < 1 || found > COMPONENT_FORMAT_VERSION) throw EnvelopeVersionUnknownException(found, producedBy)

        val components = root.path("components").values().mapIndexed { index, node ->
            if (!node.isObject) throw EnvelopeInvalidException("Component ${index + 1} is not an object")
            val kind = runCatching { ComponentKind.valueOf(node.text("kind").orEmpty()) }.getOrElse {
                throw EnvelopeInvalidException(
                    "Component ${index + 1} is a \"${node.text("kind")}\", which this installation does not import",
                )
            }
            val name = node.text("name")?.trim()?.ifEmpty { null }
                ?: throw EnvelopeInvalidException("Component ${index + 1} has no name")
            ParsedComponent(kind, name, node)
        }
        if (components.isEmpty()) throw EnvelopeInvalidException("This export holds nothing to import")
        components.groupBy { it.kind to it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { (kind, name) ->
            throw EnvelopeInvalidException("This export holds two ${kind.label}s called $name")
        }

        val variables = root.path("requires").path("variables").values().map { node ->
            RequiredVariable(
                name = node.text("name")?.trim()?.ifEmpty { null }
                    ?: throw EnvelopeInvalidException("A required variable has no name"),
                type = node.text("type") ?: "STRING",
                secret = node.path("secret").asBoolean(true),
            )
        }

        return Parsed(
            formatVersion = found,
            producedBy = producedBy,
            depth = runCatching { ExportDepth.valueOf(root.text("depth").orEmpty()) }.getOrDefault(ExportDepth.DEEP),
            components = components,
            variables = variables,
        )
    }

    private class Parsed(
        val formatVersion: Int,
        val producedBy: String?,
        val depth: ExportDepth,
        val components: List<ParsedComponent>,
        val variables: List<RequiredVariable>,
    )

    private class ParsedComponent(val kind: ComponentKind, val name: String, val node: JsonNode)

    private class RequiredVariable(val name: String, val type: String, val secret: Boolean)

    private companion object {
        /**
         * The dispositions that mean a row is actually written.
         *
         * REUSE points at what is already here and EXCLUDE was asked to be left
         * out; neither creates anything, so neither is named, audited or built.
         */
        val WRITTEN = setOf(ImportDisposition.CREATE, ImportDisposition.RENAME)

        /** Matches what a function and a tool already require of a name. */
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")

        /** Where an imported skill goes when the file names no folder. */
        const val DEFAULT_CATALOG = "General"

        /** What the word EXPONENTIAL meant, for the envelopes that still say it. */
        const val DOUBLING = 2.0

        /** Far past any honest collision; a bound, so a loop cannot be one. */
        const val MAX_RENAME = 100

        fun JsonNode.text(field: String): String? = path(field).takeIf { it.isString }?.stringValue()

        /**
         * A whole number, or nothing.
         *
         * Refuses rather than coerces, the same way [text] does: a timeout
         * written as `"30"` by something that was not this exporter is a field
         * this does not understand, and a null timeout is a shape the catalogue
         * already allows.
         */
        fun JsonNode.integer(field: String): Int? = path(field).takeIf { it.isIntegralNumber }?.asInt()

        /**
         * An array of names, with anything that is not a name left out.
         *
         * `stringValue` refuses a node that is not text rather than coercing it,
         * so a hand-edited file holding a number where a name belongs is skipped
         * here and reported by the plan as a reference that does not resolve.
         */
        fun JsonNode.names(field: String): List<String> =
            path(field).values().mapNotNull { it.takeIf(JsonNode::isString)?.stringValue() }

        fun JsonNode.required(field: String, component: ParsedComponent): String =
            text(field) ?: throw EnvelopeInvalidException(
                "The ${component.kind.label} ${component.name} has no $field",
            )

        /** An enum by name, refusing a spelling this version has never heard of. */
        inline fun <reified E : Enum<E>> JsonNode.enumOf(field: String, fallback: E?, component: ParsedComponent): E {
            val said = text(field) ?: return fallback ?: throw EnvelopeInvalidException(
                "The ${component.kind.label} ${component.name} has no $field",
            )
            return runCatching { enumValueOf<E>(said) }.getOrElse {
                throw EnvelopeInvalidException(
                    "The ${component.kind.label} ${component.name} says its $field is \"$said\", " +
                        "which this installation does not know",
                )
            }
        }

        inline fun <reified E : Enum<E>> JsonNode.enumOrNull(field: String, component: ParsedComponent): E? {
            val said = text(field) ?: return null
            return runCatching { enumValueOf<E>(said) }.getOrElse {
                throw EnvelopeInvalidException(
                    "The ${component.kind.label} ${component.name} says its $field is \"$said\", " +
                        "which this installation does not know",
                )
            }
        }
    }
}

/**
 * What the import will do about one thing the envelope mentions.
 *
 * At most one of [kind] and [external] is set, and which tells the reader what
 * sort of thing this is. Both are null for a variable, which is neither: it is
 * pointed at, never created, and never bound — a value is the workspace's own
 * and there is nothing to choose between.
 */
data class ImportEntry(
    /** The kind, for something the file carries or would have carried. */
    val kind: ComponentKind?,
    /** The kind, for something no file could carry. Set only when [kind] is not. */
    val external: ExternalKind? = null,
    /**
     * Whether the envelope actually holds this one, rather than pointing at it.
     *
     * The one fact a screen needs in order to know what it may offer. Only what
     * the file carries can be left out of an import; every other row is a
     * reference — something that has to be here already, or bound — and a
     * control offering to remove one of those would be offering to remove a
     * mention rather than a thing.
     *
     * Never true of a variable or an external, and never true of a component the
     * file merely names: a carried one is [ImportDisposition.CREATE],
     * [ImportDisposition.RENAME] or [ImportDisposition.EXCLUDE], and nothing else
     * ever is.
     */
    val carried: Boolean = false,
    /**
     * Whether this reference can be left out of the import: a tool an agent
     * points at, which the agent can arrive without. The one kind of reference
     * a screen may offer to remove, beside everything the file carries.
     * Issue #383.
     */
    val droppable: Boolean = false,
    /** The name in the file; for a model, the provider's name and the model's. */
    val name: String,
    /** The name it will have here, which differs when it was renamed or bound. */
    val targetName: String,
    val disposition: ImportDisposition,
    /** Why, in words a screen shows unchanged. */
    val detail: String,
)

/**
 * One answer to something the envelope could not carry.
 *
 * [name] is the file's — exactly as the plan gave it back, which for a model is
 * the provider's name and the model's with a slash between them — and
 * [targetId] is what it means here. An id rather than a name because a binding
 * is not part of the file: it is a statement about this installation, made from
 * a list this installation drew, and an id is the one thing about a row that
 * cannot be ambiguous.
 */
data class ComponentBinding(
    val kind: ExternalKind,
    val name: String,
    val targetId: Long,
)

/**
 * One component of the envelope that this import is to leave out.
 *
 * Named the way everything in an envelope is named — by kind and by the name the
 * *file* gave it, not the one a rename would land it under here — so a client
 * sends back exactly what the plan showed it.
 *
 * Only what the file carries can be named here. A plan lists what the envelope
 * points at beside what it holds, and the two are not the same sort of thing:
 * one is a component sitting in the file, the other a name that has to be
 * matched against this workspace. Naming the second is refused rather than
 * ignored — see [ImportExclusionUnknownException].
 */
data class ComponentExclusion(
    val kind: ComponentKind,
    val name: String,
)

/**
 * The name one carried component is to have here, chosen by the person
 * importing rather than by the clash rule. Named as the file names it, like
 * an exclusion; refused in the plan rather than moved along where the name is
 * taken or unusable, because somebody typed it. Issue #383.
 */
data class ComponentRename(
    val kind: ComponentKind,
    val name: String,
    val targetName: String,
)

/**
 * What an import would do, shown before it is done.
 *
 * A button that silently creates nine things is worse than one that lists them,
 * so this is what the confirmation dialog reads and it is also what the mutation
 * answers afterwards — the same shape either way, so a client cannot show one
 * thing and get another.
 */
data class ImportPlan(
    val formatVersion: Int,
    /** Quoted in messages only. Nothing branches on it. */
    val producedBy: String?,
    val depth: ExportDepth,
    val importable: Boolean,
    val entries: List<ImportEntry>,
    /** Empty when importable; otherwise what has to be fixed first. */
    val problems: List<String>,
)

/**
 * What one envelope holds, read without deciding where it would go.
 *
 * The header of the file plus an inventory of it: enough for a list to say what
 * a template contains and which version wrote it, and nothing that depends on a
 * target workspace — a collision, a rename or a missing variable is a fact about
 * a pairing rather than about the file, and lives in [ImportPlan].
 */
data class EnvelopeSummary(
    val formatVersion: Int,
    /** Quoted in messages only. Nothing branches on it. */
    val producedBy: String?,
    val depth: ExportDepth,
    /** Which kinds are in it, each once, in the catalogue's order. */
    val kinds: List<ComponentKind>,
    val componentCount: Int,
    /** Every component, as it is named in the file. */
    val names: List<Pair<ComponentKind, String>>,
)

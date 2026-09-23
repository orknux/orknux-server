package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.action.ActionParamView
import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.ActionParameters
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.condition.ConditionProperty
import io.mszymanski.orknux.server.condition.ConditionType
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.trigger.TriggerType
import io.mszymanski.orknux.server.trigger.WorkflowTrigger
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Whether a graph holds together.
 *
 * The rules are generic: every node reports what it needs and what it produces —
 * its **ports** — and an edge is sound when what flows into a node covers what
 * that node needs. Nothing about a particular kind is written into the rules;
 * the kinds differ only in how their ports are worked out, which is the point of
 * [portsOf].
 *
 * The ports are derived rather than stored. A node keeps the id of the catalogue
 * entry it uses and nothing else, so editing an action changes what its nodes
 * need immediately; a copy on the node would be a second truth that goes stale.
 *
 * Two rules are refusals and the rest are warnings. A graph is drawn before it
 * is finished, so a workflow that is not yet wired up has to be saveable — but
 * two nodes answering to one name, or something feeding a trigger, is not.
 */
@Service
class GraphValidator(
    private val triggers: WorkflowTriggerRepository,
    private val actions: WorkflowActionRepository,
    private val conditions: WorkflowConditionRepository,
    private val objects: WorkflowObjectRepository,
    private val parameters: ActionParameters,
    private val mapper: ObjectMapper,
) {

    /** What a node needs, what it hands on, and whether its input survives it. */
    data class Ports(
        val inputs: List<ActionParamView> = emptyList(),
        val outputs: List<ActionParamView> = emptyList(),
        /**
         * True when the node hands on what it was given as well as its own
         * output — a wait and a condition do, work does not.
         */
        val passThrough: Boolean = false,
        /**
         * True when nothing is known about what the node produces, so anything
         * asking for a field downstream is given the benefit of the doubt.
         */
        val opaque: Boolean = false,
        /** Why the node cannot say, if it cannot: nothing chosen from a catalogue. */
        val unresolved: String? = null,
    )

    /**
     * What a node needs and gives, worked out from what it points at.
     *
     * @param among the rest of the graph, for the one relationship that
     *   crosses nodes: an object node an agent saves its answer into is a
     *   declaration, not a step, and only the agent's node says so.
     */
    fun portsOf(node: WorkflowNode, among: List<WorkflowNode> = emptyList()): Ports = when (node.kind) {
        NodeKind.TRIGGER -> {
            val trigger = node.triggerId?.let { triggers.findByIdOrNull(it) }
            if (trigger == null) {
                Ports(opaque = true, unresolved = "no trigger chosen")
            } else {
                Ports(outputs = fieldsOf(trigger))
            }
        }

        NodeKind.ACTION -> {
            val action = node.actionId?.let { actions.findByIdOrNull(it) }
            val unfinished = action?.let { missingFrom(it) }
            if (action == null) {
                Ports(opaque = true, unresolved = "no action chosen")
            } else if (unfinished != null) {
                /*
                 * Stored half-made on purpose - a definition belonging to one
                 * node is filled in where it is used, and the panel writes as
                 * it is typed, so "Function, and I have not picked one yet" is
                 * an ordinary moment rather than a thing to refuse. It is
                 * refused here instead, where the rest of an unfinished graph
                 * is: what it still needs, said in the run's own words.
                 */
                Ports(opaque = true, unresolved = unfinished)
            } else {
                val named = node.outputName?.trim().orEmpty()
                Ports(
                    // What the *node* still needs, which is what will actually
                    // run: a parameter answered with a value is answered, and
                    // nothing upstream has to produce it. Reading the action
                    // here instead would validate a binding the run never uses.
                    inputs = reads(node.mappings),
                    // A named node wraps what it produces under that name, so
                    // that is the field a later node can read. Unnamed, the
                    // action's own output stands — which is what it is called,
                    // not necessarily what arrives.
                    outputs = if (named.isEmpty()) {
                        parameters.outputsOf(action)
                    } else {
                        listOf(ActionParamView(named, parameters.outputsOf(action).firstOrNull()?.type ?: ValueType.MAP))
                    },
                    passThrough = parameters.passesThrough(action),
                )
            }
        }

        NodeKind.CONDITION -> {
            val condition = node.conditionId?.let { conditions.findByIdOrNull(it) }
            if (condition == null) {
                Ports(passThrough = true, opaque = true, unresolved = "no condition chosen")
            } else {
                // A condition hands on what it was given, unchanged, when it holds.
                Ports(inputs = asks(condition, depth = 0), passThrough = true)
            }
        }

        /*
         * An agent answers with prose, so what it gives is only addressable once
         * the node has named it. Named, it declares that one field and stops
         * being opaque — which is what lets the editor show it and a later node
         * refer to it. Unnamed, it hands its answer on as before and nothing
         * downstream can say what it will contain.
         *
         * Held to a shape, the answer is that object: the name holds it and each
         * of the shape's fields is offered as a dotted path under it, which is
         * what makes `reply.priority` something a later node can pick rather
         * than something somebody has to know to type. The run already resolves
         * dotted paths; this is only the offering.
         */
        NodeKind.AGENT -> {
            val named = node.outputName?.trim().orEmpty()
            val shape = node.outputObjectId?.let { objects.findByIdOrNull(it) }
            when {
                /*
                 * Saved into an object node, the answer is spoken for: the
                 * agent answers with that node's voice - its step emits the
                 * shaped answer under the target's output name - so the
                 * fields stand offered on the object node and nowhere else.
                 * The agent's port is empty; what the run actually carries
                 * after it is credited in [availability], which reads the
                 * target's outputs off the reference.
                 */
                node.outputNodeKey != null -> Ports()
                named.isEmpty() && shape == null -> Ports(passThrough = true, opaque = true)
                shape == null -> Ports(outputs = listOf(ActionParamView(named, ValueType.STRING)))
                named.isEmpty() -> Ports(outputs = shape.properties.map { ActionParamView(it.name, typeOf(it.kind)) })
                else -> Ports(
                    outputs = listOf(ActionParamView(named, ValueType.OBJECT)) +
                        shape.properties.map { ActionParamView("$named.${it.name}", typeOf(it.kind)) },
                )
            }
        }

        /*
         * An object node makes what its fields say it makes.
         *
         * Named, that is one field of that name holding the object — the whole
         * thing, for a parameter that takes one — and each of its fields as a
         * dotted path under it, the way a shaped agent's answer is offered.
         * Offering only the name was how the whole object could be passed but
         * no field picked; offering only the fields, before that, was the
         * opposite. Unnamed, the fields themselves are what goes on — an
         * object put together and then handed over as its parts, which is the
         * shape the run was already carrying and which nothing can name whole.
         *
         * It passes on what it was given as well: building something out of two
         * earlier steps should not throw away everything else they produced.
         */
        NodeKind.OBJECT -> {
            val named = node.outputName?.trim().orEmpty()
            val fields = shapeOf(node)
            /*
             * Saved into, the node is a declaration: the agent fills it as it
             * answers, so it needs nothing - its own mappings included, which
             * are simply not consulted - and what it offers arrives from the
             * agent's step, wherever on the canvas this node stands.
             */
            val savedInto = among.any { it.kind == NodeKind.AGENT && it.outputNodeKey == node.nodeKey }
            Ports(
                inputs = if (savedInto) emptyList() else reads(node.mappings),
                outputs = if (named.isEmpty()) {
                    fields
                } else {
                    listOf(ActionParamView(named, ValueType.OBJECT)) +
                        fields.map { ActionParamView("$named.${it.name}", it.type) }
                },
                passThrough = true,
            )
        }

        /*
         * A session node has no ports at all, which is the whole of what makes
         * it a declaration rather than a step.
         *
         * It produces nothing: an agent reads which conversation it belongs to
         * off the edge, not off the payload, so there is no field here for a
         * later node to point at. And it needs nothing, even though its key is
         * often a reference - because that reference is resolved in the agent it
         * feeds, against what *that* node was handed. Reporting it as an input
         * here would demand the field reach a node a run never reaches.
         *
         * What it does report is a key it has not been given, the way an action
         * node reports having no action: a session with no key names nothing and
         * records nothing, which is worth saying before a run proves it.
         */
        /*
         * An image node reads a prompt and produces a picture.
         *
         * Named, that one field holds the picture reference - its url and what it
         * is - which is what a later node points at, and it stands beside what
         * reached the node rather than in place of it: a picture is something a
         * run gains on its way past, and a reply after one usually wants both the
         * words and the picture. Unnamed, the reference is handed on as it stands
         * and nothing downstream can say its shape. What it needs is whatever its
         * prompt mapping reads, and what it reports unset is its model, the way an
         * action node reports having no action.
         */
        NodeKind.IMAGE -> {
            val named = node.outputName?.trim().orEmpty()
            Ports(
                inputs = reads(node.mappings),
                outputs = if (named.isEmpty()) emptyList() else listOf(ActionParamView(named, ValueType.OBJECT)),
                passThrough = named.isNotEmpty(),
                opaque = named.isEmpty(),
                unresolved = "no image model".takeIf { node.imageModelId == null },
            )
        }

        NodeKind.SESSION -> Ports(unresolved = "no session key".takeIf { keyOf(node).isEmpty() })
    }

    /** What a session node's key is set to, written or referenced; blank if neither. */
    private fun keyOf(node: WorkflowNode): String =
        node.mappings.firstOrNull { it.name == SESSION_KEY }?.expression?.trim().orEmpty()

    /**
     * What an object node's fields are called, and what each holds.
     *
     * A saved shape says; a shape of the node's own is the parameters it holds,
     * which are text until something says otherwise.
     */
    private fun shapeOf(node: WorkflowNode): List<ActionParamView> {
        val saved = node.objectId?.let { objects.findByIdOrNull(it) }
        if (saved != null) {
            return saved.properties.map { ActionParamView(it.name, typeOf(it.kind)) }
        }
        return node.mappings.map { ActionParamView(it.name, ValueType.STRING) }
    }

    /** A property's shape, as the graph's own vocabulary of types. */
    private fun typeOf(kind: PropertyKind): ValueType = when (kind) {
        PropertyKind.STRING -> ValueType.STRING
        PropertyKind.NUMBER -> ValueType.NUMBER
        PropertyKind.BOOLEAN -> ValueType.BOOLEAN
        PropertyKind.OBJECT -> ValueType.OBJECT
        PropertyKind.ARRAY -> ValueType.ARRAY
    }

    /**
     * The fields a node's parameters read, which is what has to reach it.
     *
     * Only the references: a parameter holding a value is answered by the value.
     * A reference to `trigger.x` reads the event that started the run, which is
     * there however the node was reached, so nothing upstream has to produce it.
     * Of `response.status` only `response` is asked for — the field is what an
     * edge carries; the rest is inside it.
     */
    private fun reads(mappings: List<NodeMapping>): List<ActionParamView> = mappings
        .filter { it.mode == MappingMode.REFERENCE }
        .map { it.expression.trim().substringBefore('.') }
        .filter { it.isNotEmpty() && it != TRIGGER }
        .distinct()
        .map { ActionParamView(it, ValueType.STRING) }

    /**
     * Everything wrong with the graph, worst first.
     *
     * @param hardOnly what a save refuses over; the rest is advice the editor
     *   shows while a workflow is still being drawn.
     */
    /**
     * What an action still needs before it could run, or null where it needs
     * nothing.
     *
     * The shape the database used to insist on, asked here instead. A shared
     * action still cannot be stored unfinished - it is a finished thing people
     * pick from a list - but one belonging to a node is a draft like the graph
     * around it, and this is where a draft is told what is missing.
     */
    private fun missingFrom(action: WorkflowAction): String? = when (action.subtype) {
        ActionSubtype.OUTGOING_CONNECTION, ActionSubtype.SEND_EMAIL ->
            "no connection chosen".takeIf { action.connectionId == null }

        ActionSubtype.HTTP_REQUEST -> "no address to call".takeIf { action.url.isNullOrBlank() }
        ActionSubtype.FUNCTION -> "no function chosen".takeIf { action.functionId == null }
        ActionSubtype.INLINE_CONDITION -> "nothing to wait for".takeIf { action.conditionExpression.isNullOrBlank() }
        ActionSubtype.CONDITION -> "no condition chosen".takeIf { action.conditionId == null }
        ActionSubtype.TIME -> "no time to wait".takeIf { action.durationSeconds == null }
        // Not the model: a workspace that has chosen one is what most
        // installations have, and an action that names none follows it. What
        // cannot be left out is the words.
        ActionSubtype.SPEAK -> "nothing to say".takeIf { action.speechText.isNullOrBlank() }
    }

    fun problems(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        hardOnly: Boolean = false,
    ): List<GraphProblem> {
        val byKey = nodes.associateBy { it.nodeKey }
        val known = edges.filter { it.sourceKey in byKey && it.targetKey in byKey }
        val problems = mutableListOf<GraphProblem>()

        /*
         * Two nodes cannot answer to the same name.
         *
         * A run carries what every step produced, each under the name its node
         * gave it. Two nodes claiming one name means the later one quietly wins
         * and every reference to the first reads the wrong value — a workflow
         * that runs, finishes, and is wrong. Refused rather than warned about,
         * because there is no version of it that was intended.
         */
        nodes.filter { !it.outputName.isNullOrBlank() }
            .groupBy { requireNotNull(it.outputName) }
            .filterValues { it.size > 1 }
            .forEach { (name, claiming) ->
                claiming.forEach { node ->
                    problems += GraphProblem(
                        severity = GraphProblemSeverity.ERROR,
                        nodeKey = node.nodeKey,
                        message = "\"$name\" is produced by ${claiming.size} nodes; a name has to say which one",
                    )
                }
            }

        // --- The shape that could never run ---
        known.forEach { edge ->
            val target = byKey.getValue(edge.targetKey)
            val source = byKey.getValue(edge.sourceKey)
            if (target.kind == NodeKind.TRIGGER) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.ERROR,
                    nodeKey = target.nodeKey,
                    message = "Nothing can feed ${target.name}: a trigger is where a run starts.",
                )
            }
            /*
             * Nothing feeds a session either, and for a stronger reason than a
             * trigger: a run never reaches a session node at all. It is read by
             * the agents wired to it, so an edge pointing at one is somebody
             * drawing a step that will not happen.
             */
            if (target.kind == NodeKind.SESSION) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.ERROR,
                    nodeKey = target.nodeKey,
                    message = "Nothing can feed ${target.name}: a session is read, not run.",
                )
            }
            /*
             * And a session leads only to an agent. An agent is the only thing
             * here that talks to a model, so a session wired to anything else
             * does nothing whatsoever - there is no version of that graph that
             * was meant, which is why it is refused rather than warned about.
             */
            if (source.kind == NodeKind.SESSION && target.kind != NodeKind.AGENT) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.ERROR,
                    nodeKey = target.nodeKey,
                    message = "${source.name} can only lead to an agent: ${target.name} does not talk to a model.",
                )
            }
            /*
             * A failure edge out of a node that does not handle failure.
             *
             * The engines take a failure edge only where the node failed and
             * the node says it has somewhere to go, so this one would never be
             * taken by anything: a path drawn on the canvas that no run can
             * reach. Refused rather than warned about, because the graph looks
             * exactly like one that works.
             */
            if (edge.branch == EdgeBranch.FAILURE && !source.fallbackEnabled) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.ERROR,
                    nodeKey = target.nodeKey,
                    message = "${source.name} does not handle failure, so nothing ever leaves it for ${target.name}.",
                )
            }
        }

        /*
         * One agent, one conversation.
         *
         * Two sessions reaching a node would have to be resolved by picking one,
         * and whichever rule did the picking - the first edge drawn, the node
         * listed first - would be invisible on the canvas. So it is refused, and
         * the answer is to delete an edge rather than to learn the rule.
         */
        known.filter { byKey.getValue(it.sourceKey).kind == NodeKind.SESSION }
            .groupBy { it.targetKey }
            .filterValues { it.size > 1 }
            .forEach { (targetKey, reaching) ->
                val target = byKey.getValue(targetKey)
                problems += GraphProblem(
                    severity = GraphProblemSeverity.ERROR,
                    nodeKey = targetKey,
                    message = "${reaching.size} sessions reach ${target.name}; a turn belongs to one conversation.",
                )
            }

        if (hardOnly) return problems

        // --- What each node can see, followed along the edges ---
        val ports = nodes.associate { it.nodeKey to portsOf(it, nodes) }
        val available = availability(nodes, known, ports)

        nodes.forEach { node ->
            val port = ports.getValue(node.nodeKey)
            port.unresolved?.let {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.WARNING,
                    nodeKey = node.nodeKey,
                    message = "${node.name} has $it, so it will do nothing.",
                )
            }

            /*
             * A session node is drawn beside the graph rather than in it, so
             * "nothing before it" is its normal state and never a mistake. Its
             * own edge into an agent is not what reaches that agent either - it
             * says which conversation the agent keeps, not that a run gets there
             * - so it does not count towards the agent's incoming either.
             */
            val incoming = known.count {
                it.targetKey == node.nodeKey && byKey.getValue(it.sourceKey).kind != NodeKind.SESSION
            }
            // A redirect target is a declaration, like a session node: the
            // agent fills it as it answers, so nothing needs to lead into it.
            val declaration = node.kind == NodeKind.SESSION ||
                nodes.any { it.kind == NodeKind.AGENT && it.outputNodeKey == node.nodeKey }
            if (node.kind != NodeKind.TRIGGER && !declaration && incoming == 0 && nodes.size > 1) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.WARNING,
                    nodeKey = node.nodeKey,
                    message = "${node.name} has nothing before it, so a run never reaches it.",
                )
            }

            /*
             * A fallback nothing is wired to.
             *
             * The node handles its own failure and the graph has nowhere for it
             * to go, so a failure still ends the run - which is the opposite of
             * what switching it on says. Advice rather than a refusal: the
             * handle exists so somebody can draw from it, and there is a moment
             * between the two.
             */
            if (node.fallbackEnabled && known.none { it.sourceKey == node.nodeKey && it.branch == EdgeBranch.FAILURE }) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.WARNING,
                    nodeKey = node.nodeKey,
                    message = "${node.name} handles failure but nothing leads out of it, so a failure still stops the run.",
                )
            }

            // A session nothing is wired to is a conversation nobody joins.
            if (node.kind == NodeKind.SESSION && known.none { it.sourceKey == node.nodeKey }) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.WARNING,
                    nodeKey = node.nodeKey,
                    message = "${node.name} leads to no agent, so nothing writes into it.",
                )
            }

            val reachable = available.getValue(node.nodeKey)
            port.inputs.forEach { needed ->
                val seen = reachable.fields[needed.name]
                when {
                    // Something before it says nothing about what it produces,
                    // so this cannot be called wrong.
                    reachable.opaque -> Unit
                    seen == null -> problems += GraphProblem(
                        severity = GraphProblemSeverity.WARNING,
                        nodeKey = node.nodeKey,
                        message = "${node.name} needs ${needed.display}, which nothing before it produces.",
                    )

                    !compatible(seen, needed.type) -> problems += GraphProblem(
                        severity = GraphProblemSeverity.WARNING,
                        nodeKey = node.nodeKey,
                        message =
                            "${node.name} needs ${needed.display}, but what reaches it is " +
                                "${needed.name}: ${seen.name.lowercase()}.",
                    )
                }
            }
        }

        /*
         * A save-into reference that no longer names an object node with a
         * shape is a redirection to nowhere: the save refuses it, so this is
         * only ever a preview describing a graph mid-edit - but it is the
         * moment the person who broke the pointing is still looking.
         */
        nodes.filter { it.kind == NodeKind.AGENT && it.outputNodeKey != null }.forEach { agent ->
            val target = byKey[agent.outputNodeKey]
            if (target == null || target.kind != NodeKind.OBJECT || target.objectId == null) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.WARNING,
                    nodeKey = agent.nodeKey,
                    message = "${agent.name} saves its answer into a node that cannot take it. " +
                        "Pick where the answer goes again.",
                )
            }
        }

        return problems.distinct().sortedBy { it.severity.ordinal }
    }

    /** What has reached each node, following the edges from the ones that start. */
    private fun availability(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        ports: Map<String, Ports>,
    ): Map<String, Reachable> {
        val byKey = nodes.associateBy { it.nodeKey }
        val order = runCatching { orderOf(nodes, edges) }.getOrDefault(nodes)
        val produced = mutableMapOf<String, Reachable>()
        val incoming = mutableMapOf<String, Reachable>()

        order.forEach { node ->
            /*
             * Down a failure edge, what arrives is what reached the node that
             * failed - not what that node produces, because it produced nothing.
             * Offering its outputs here would tell somebody they could read a
             * field that only exists when the step they are handling the failure
             * of actually worked.
             */
            val sources = edges.filter { it.targetKey == node.nodeKey }
                .mapNotNull { if (it.branch == EdgeBranch.FAILURE) incoming[it.sourceKey] else produced[it.sourceKey] }
            val reaching = sources.fold(Reachable()) { all, one -> all.merge(one) }
            incoming[node.nodeKey] = reaching

            val port = ports.getValue(node.nodeKey)
            /*
             * An agent that saves into an object node answers with that node's
             * voice: the fields stand offered on the object node, but what the
             * run carries after the agent IS the object - so the walk credits
             * the agent with the target's outputs, wherever the target stands
             * on the canvas. The agent's own port stays empty; this is about
             * what arrives, not what is offered.
             */
            val emitted = node.outputNodeKey
                ?.takeIf { node.kind == NodeKind.AGENT }
                ?.let { ports[it]?.outputs }
                ?: port.outputs
            val own = Reachable(
                fields = emitted.associate { it.name to it.type },
                opaque = port.opaque,
            )
            produced[node.nodeKey] = if (port.passThrough) reaching.merge(own) else own
        }

        return byKey.keys.associateWith { incoming[it] ?: Reachable() }
    }

    /** Nodes in the order a run would reach them; the graph's own order otherwise. */
    private fun orderOf(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>): List<WorkflowNode> {
        val byKey = nodes.associateBy { it.nodeKey }
        val waiting = nodes.associate { it.nodeKey to edges.count { edge -> edge.targetKey == it.nodeKey } }
            .toMutableMap()
        val ready = ArrayDeque(nodes.filter { waiting.getValue(it.nodeKey) == 0 })
        val ordered = mutableListOf<WorkflowNode>()

        while (ready.isNotEmpty()) {
            val node = ready.removeFirst()
            ordered += node
            edges.filter { it.sourceKey == node.nodeKey }.forEach { edge ->
                val left = waiting.getValue(edge.targetKey) - 1
                waiting[edge.targetKey] = left
                if (left == 0) ready += byKey.getValue(edge.targetKey)
            }
        }
        // A cycle leaves nodes behind; they are checked in the order they were drawn.
        return ordered + nodes.filterNot { node -> ordered.any { it.nodeKey == node.nodeKey } }
    }

    /** What a trigger puts in the run's input: its payload, and what fired it. */
    private fun fieldsOf(trigger: WorkflowTrigger): List<ActionParamView> {
        val fromPayload = trigger.payload
            ?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.takeIf { it.isObject }
            ?.properties()
            ?.map { (name, value) ->
                ActionParamView(
                    name,
                    when {
                        value.isNumber -> ValueType.NUMBER
                        value.isBoolean -> ValueType.BOOLEAN
                        value.isArray -> ValueType.ARRAY
                        // Object-shaped, but nothing here says which definition
                        // it matches, and guessing would be worse than saying so.
                        value.isObject -> ValueType.MAP
                        else -> ValueType.STRING
                    },
                )
            }
            .orEmpty()

        val fromFiring = when (trigger.type) {
            TriggerType.SCHEDULED -> listOf("cron", "firedAt")
            /*
             * `connection` is the connection the event arrived on, and it is
             * offered for the same reason the channel and the thread are: a
             * later node has to be able to answer back through the one it came
             * from. A workspace with two Slack connections has two Slacks, and
             * reading a thread through the wrong one reads somebody else's.
             */
            TriggerType.INCOMING_CONNECTION ->
                listOf("action", "text", "channel", "user", "ts", "threadTs", "connection")
            // A webhook's fields are whatever its contract says, below.
            TriggerType.WEBHOOK -> emptyList()
        }.map { ActionParamView(it, ValueType.STRING) }

        /*
         * What a webhook hands on is the shape it refuses anything else for.
         *
         * The endpoint answers 404 to a request that does not match, so by the
         * time a run exists every one of these fields is there — which is what
         * makes them worth offering as something to point a reference at.
         */
        val fromContract = trigger.objectId
            ?.let { objects.findByIdOrNull(it) }
            ?.properties
            ?.map { ActionParamView(it.name, typeOf(it.kind)) }
            .orEmpty()

        return (fromPayload + fromFiring + fromContract).distinctBy { it.name }
    }

    /** Which fields a condition reads, so a node asking it needs those. */
    private fun asks(condition: WorkflowCondition, depth: Int): List<ActionParamView> {
        if (depth > MAX_DEPTH) return emptyList()
        return when (condition.type) {
            ConditionType.ANY_OF, ConditionType.ALL_OF -> condition.members
                .mapNotNull { conditions.findByIdOrNull(it) }
                .flatMap { asks(it, depth + 1) }
                .distinctBy { it.name }

            // The function is handed everything, so it asks for nothing by name.
            ConditionType.FUNCTION -> emptyList()
            ConditionType.TIME -> emptyList()
            else -> condition.property?.let { property ->
                fieldFor(property)?.let { listOf(ActionParamView(it, ValueType.STRING)) }
            }.orEmpty()
        }
    }

    /** The field each property is read from; `ConditionEvaluator` reads the same. */
    private fun fieldFor(property: ConditionProperty): String? = when (property) {
        ConditionProperty.MESSAGE_AUTHOR -> "user"
        ConditionProperty.MESSAGE_CHANNEL -> "channel"
        ConditionProperty.MESSAGE_TEXT -> "text"
        ConditionProperty.ISSUE_PRIORITY -> "priority"
        ConditionProperty.ISSUE_STATUS -> "status"
        ConditionProperty.ISSUE_TYPE -> "issueType"
        ConditionProperty.CURRENT_TIME -> null
    }

    /**
     * Whether what arrives will do for what is wanted.
     *
     * A map is anything and takes anything, and anything can be read as text, so
     * those go anywhere; the rest have to agree.
     *
     * The wildcard used to be OBJECT, back when OBJECT was what you said when you
     * had not said anything. Now that it names a definite shape, an object is as
     * particular as a number: what is loose is MAP, and MAP is what is waved
     * through. An object flowing into an object is still allowed on the name alone
     * — checking that the two definitions agree field by field is a stricter rule
     * than the graph has ever applied, and one worth introducing on its own.
     */
    private fun compatible(given: ValueType, wanted: ValueType): Boolean =
        given == wanted ||
            given == ValueType.MAP ||
            wanted == ValueType.MAP ||
            wanted == ValueType.STRING ||
            // Two objects agree by being objects; which ones is not checked here.
            (given == ValueType.OBJECT && wanted == ValueType.OBJECT)

    /** What has reached a node: the fields, and whether anything is unknown. */
    private data class Reachable(
        val fields: Map<String, ValueType> = emptyMap(),
        val opaque: Boolean = false,
    ) {
        fun merge(other: Reachable) = Reachable(fields + other.fields, opaque || other.opaque)
    }

    private companion object {
        const val MAX_DEPTH = 10

        /** What a reference reads from when it does not read the run's payload. */
        const val TRIGGER = "trigger"

        /** The parameter a session node is identified by; `AgentNodeRunner` reads the same. */
        const val SESSION_KEY = "sessionKey"
    }
}

enum class GraphProblemSeverity {
    /** The graph could not run in this shape; the save is refused. */
    ERROR,

    /** Worth fixing before it runs, but a graph is drawn before it is finished. */
    WARNING,
}

data class GraphProblem(
    val severity: GraphProblemSeverity,
    /** The node it is about; edges are reported against the node they reach. */
    val nodeKey: String,
    val message: String,
)

/**
 * The refusals, as one sentence.
 *
 * Each distinct message once. A problem about a name two nodes share is
 * reported against both of them - rightly, since the canvas marks both - and
 * joining the list verbatim said the same sentence twice in a row: *"image" is
 * produced by 2 nodes; a name has to say which one "image" is produced by 2
 * nodes; a name has to say which one*. The list keeps its per-node entries for
 * the canvas; only what is read aloud here is deduplicated.
 */
class GraphInvalidException(problems: List<GraphProblem>) :
    RuntimeException(problems.map { it.message }.distinct().joinToString(" "))

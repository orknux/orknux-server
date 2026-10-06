package io.mszymanski.orknux.server.agent

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/** Admin, Settings, Bulkheads: the walls in [Bulkheads], read and set. Issue #616. */
@Controller
class BulkheadsAPI(
    private val bulkheads: Bulkheads,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun bulkheads(): BulkheadsView {
        access.requireAdmin()
        return view()
    }

    @MutationMapping
    fun setBulkheads(@Argument input: BulkheadsInput): BulkheadsView {
        access.requireAdmin()
        val before = bulkheads.values()
        val after = BulkheadValues(
            turnsEnabled = input.turnsEnabled,
            turnsAtOnce = input.turnsAtOnce,
            turnWaitSeconds = input.turnWaitSeconds,
            heapEnabled = input.heapEnabled,
            heapPercent = input.heapPercent,
            memoryEnabled = input.memoryEnabled,
            turnMemoryMb = input.turnMemoryMb,
            toolResultKb = input.toolResultKb,
        )
        bulkheads.save(after, SecurityContextHolder.getContext().authentication?.name ?: "system")
        changes(before, after).forEach { said -> audit.record(null, WorkspaceAuditCategory.WORKSPACE, said) }
        return view()
    }

    private fun view(): BulkheadsView {
        val held = bulkheads.values()
        return BulkheadsView(
            turnsEnabled = held.turnsEnabled,
            turnsAtOnce = held.turnsAtOnce,
            turnWaitSeconds = held.turnWaitSeconds,
            heapEnabled = held.heapEnabled,
            heapPercent = held.heapPercent,
            memoryEnabled = held.memoryEnabled,
            turnMemoryMb = held.turnMemoryMb,
            toolResultKb = held.toolResultKb,
            runningTurns = bulkheads.runningTurns(),
            heapAfterGcPercent = Bulkheads.oldGenerationAfterGcPercent(),
        )
    }

    /** One line per value that moved, worded as the screen shows it. */
    private fun changes(before: BulkheadValues, after: BulkheadValues): List<String> = buildList {
        fun onOff(on: Boolean) = if (on) "on" else "off"
        if (before.turnsEnabled != after.turnsEnabled) add("Agent turns at once limit switched ${onOff(after.turnsEnabled)}")
        if (before.turnsAtOnce != after.turnsAtOnce) add("Agent turns at once: ${after.turnsAtOnce}")
        if (before.turnWaitSeconds != after.turnWaitSeconds) {
            add("A turn waits up to ${after.turnWaitSeconds} seconds for a place")
        }
        if (before.heapEnabled != after.heapEnabled) add("Heap guard for agent turns switched ${onOff(after.heapEnabled)}")
        if (before.heapPercent != after.heapPercent) add("Agent turns stop above ${after.heapPercent}% heap after GC")
        if (before.memoryEnabled != after.memoryEnabled) {
            add("Memory budget for agent turns switched ${onOff(after.memoryEnabled)}")
        }
        if (before.turnMemoryMb != after.turnMemoryMb) add("A turn holds up to ${after.turnMemoryMb} MB of tool results")
        if (before.toolResultKb != after.toolResultKb) add("One tool result is kept to ${after.toolResultKb} KB")
    }
}

data class BulkheadsView(
    val turnsEnabled: Boolean,
    val turnsAtOnce: Int,
    val turnWaitSeconds: Int,
    val heapEnabled: Boolean,
    val heapPercent: Int,
    val memoryEnabled: Boolean,
    val turnMemoryMb: Int,
    val toolResultKb: Int,
    val runningTurns: Int,
    val heapAfterGcPercent: Int?,
)

data class BulkheadsInput(
    val turnsEnabled: Boolean,
    val turnsAtOnce: Int,
    val turnWaitSeconds: Int,
    val heapEnabled: Boolean,
    val heapPercent: Int,
    val memoryEnabled: Boolean,
    val turnMemoryMb: Int,
    val toolResultKb: Int,
)

@Component
class BulkheadsExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is BulkheadValueOutOfRangeException -> refused(exception, ErrorType.BAD_REQUEST, environment)
            else -> null
        }
}

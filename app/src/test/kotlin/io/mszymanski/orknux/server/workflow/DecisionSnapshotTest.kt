package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.DecisionOption
import io.mszymanski.orknux.connector.model.DecisionQuestion
import io.mszymanski.orknux.connector.model.DecisionQuestionKind
import io.mszymanski.orknux.workflow.execution.EdgeBranch
import io.mszymanski.orknux.workflow.execution.GraphEdge
import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.WorkflowGraph
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * A published decision node runs what was published: its model, its questions
 * and the option each edge carries all survive the snapshot, and a snapshot
 * written before decision nodes existed still reads. Issue #577.
 *
 * By hand, because [WorkflowSnapshot] is: a field missing from the format is a
 * decision node that publishes and then asks nothing, or branches nowhere.
 */
class DecisionSnapshotTest {

    private val mapper = JsonMapper.builder().build()

    private val spec = DecisionSpec(
        questions = listOf(
            DecisionQuestion(
                "department",
                DecisionQuestionKind.CHOICE,
                "Which team?",
                listOf(DecisionOption("billing", "Charges"), DecisionOption("returns")),
            ),
            DecisionQuestion("severity", DecisionQuestionKind.SCORE, "How bad?", listOf(DecisionOption("low"), DecisionOption("high"))),
        ),
        branchQuestion = "department",
        threshold = 0.75,
    )

    @Test
    fun `a decision node and its option edges survive a publication`() {
        val graph = WorkflowGraph(
            workflowId = 7,
            name = "Triage",
            nodes = listOf(
                GraphNode(
                    key = "decide",
                    kind = NodeKind.DECISION,
                    name = "Route",
                    decisionModelId = 12,
                    decisionSpec = DecisionSpec.write(spec, mapper),
                ),
                GraphNode(key = "billing", kind = NodeKind.OBJECT, name = "Billing"),
            ),
            edges = listOf(
                GraphEdge("decide", "billing", EdgeBranch.OPTION, "billing"),
                GraphEdge("decide", "billing2", EdgeBranch.UNSURE),
            ),
        )

        val read = WorkflowSnapshot.read(WorkflowSnapshot.write(graph, mapper), mapper)

        val node = read.nodes.first { it.key == "decide" }
        assertThat(node.kind).isEqualTo(NodeKind.DECISION)
        assertThat(node.decisionModelId).isEqualTo(12)
        assertThat(DecisionSpec.read(node.decisionSpec, mapper)).isEqualTo(spec)
        assertThat(read.edges).containsExactly(
            GraphEdge("decide", "billing", EdgeBranch.OPTION, "billing"),
            GraphEdge("decide", "billing2", EdgeBranch.UNSURE, null),
        )
    }

    @Test
    fun `a snapshot from before decisions reads as nodes that ask nothing`() {
        val old = """{"workflowId":1,"name":"Old","nodes":[{"key":"a","kind":"ACTION","name":"A"}],
                     "edges":[{"source":"a","target":"b","branch":"YES"}]}"""

        val read = WorkflowSnapshot.read(old, mapper)

        assertThat(read.nodes.single().decisionModelId).isNull()
        assertThat(read.nodes.single().decisionSpec).isNull()
        assertThat(read.edges.single().option).isNull()
        assertThat(DecisionSpec.read(null, mapper)).isEqualTo(DecisionSpec.EMPTY)
    }

    @Test
    fun `only a choice can be the question that branches`() {
        assertThat(spec.branching()?.key).isEqualTo("department")
        assertThat(spec.copy(branchQuestion = "severity").branching()).isNull()
    }
}

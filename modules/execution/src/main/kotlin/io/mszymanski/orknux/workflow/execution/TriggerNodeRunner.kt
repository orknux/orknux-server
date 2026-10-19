package io.mszymanski.orknux.workflow.execution

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * A trigger node: where the run began, and what arrived to start it.
 *
 * It does no work of its own - the firing already happened - so it completes at
 * once and hands on what it was handed. It used to fall through to
 * [UnimplementedNodeRunner] and be skipped with "TRIGGER nodes have no runtime
 * yet", which every run then showed as the first thing in its log, and which
 * read as the reason whenever the run went nowhere else.
 */
@Component
@Order(KIND_RUNNER_ORDER)
class TriggerNodeRunner : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.TRIGGER

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult =
        StepResult(StepStatus.COMPLETED, input)
}

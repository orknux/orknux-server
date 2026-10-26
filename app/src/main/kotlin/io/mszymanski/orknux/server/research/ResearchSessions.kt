package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.agent.AgentNodeRunner
import io.mszymanski.orknux.server.llm.LlmSessionKey
import io.mszymanski.orknux.server.workflow.NodeExpressions

/**
 * The sessions a research's agents talk in, one per step. Issue #668.
 *
 * Plan and Write are agent nodes, and an agent node that names no session
 * keeps nothing: what the planner was asked and answered, which tools it
 * called on the way, was nowhere to be read once the run moved on. So each
 * names one, keyed by the research and the step, and Review - a function
 * rather than a node, because its rounds are a loop - writes its reviewer's
 * and writer's turns into one of its own the same way.
 *
 * **Derived, not stored.** The key is the research's id and the step, so the
 * research page finds a step's session by working the key out again rather
 * than by a column that could disagree with it. A restart runs in the same
 * sessions, which is what makes a second attempt readable beside the first.
 */
object ResearchSessions {

    /** What every research session's key starts with, so the sessions list reads them as one family. */
    const val PREFIX = "research"

    /** The node parameters a step names its session with: what a seeded mode drawn before #668 lacks. */
    val PARAMETERS = setOf(AgentNodeRunner.SESSION_KEY, AgentNodeRunner.SESSION_KEY_PREFIX)

    /** The steps that keep one: the two agent nodes, and Review. */
    val STAGES = listOf(ResearchStage.PLAN, ResearchStage.WRITE, ResearchStage.REVIEW)

    /** The session parameters an agent step of a seeded mode is drawn with, the research's id filled in at the run. */
    fun mappings(stage: ResearchStage): List<Pair<String, String>> = listOf(
        AgentNodeRunner.SESSION_KEY_PREFIX to PREFIX,
        AgentNodeRunner.SESSION_KEY to "{{${NodeExpressions.TRIGGER}.${ResearchCapability.RESEARCH}}}-${suffix(stage)}",
    )

    /** The session's key as it is stored: what the node's two parameters come to for [researchId]. */
    fun key(researchId: Long, stage: ResearchStage): String = LlmSessionKey.of(PREFIX, "$researchId-${suffix(stage)}")

    /** The part of a key after the research's id. */
    fun keyAfterPrefix(researchId: Long, stage: ResearchStage): String = "$researchId-${suffix(stage)}"

    private fun suffix(stage: ResearchStage) = stage.name.lowercase().replace('_', '-')
}

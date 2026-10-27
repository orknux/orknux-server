package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * How many rounds of review a research gets: the workspace's own number where
 * Workspace Settings has one, and Admin -> Settings' otherwise - asked when the
 * Review step runs, so a change applies to the next research to be reviewed.
 */
@Component
class ResearchReviewRounds(
    private val workspaces: WorkspaceRepository,
    private val tuning: Tuning,
) {

    fun of(workspaceId: Long): Int {
        return workspaces.findByIdOrNull(workspaceId)?.researchReviewRounds?.takeIf(Tunable.RESEARCH_REVIEW_ROUNDS::accepts)
            ?: tuning[Tunable.RESEARCH_REVIEW_ROUNDS]
    }
}

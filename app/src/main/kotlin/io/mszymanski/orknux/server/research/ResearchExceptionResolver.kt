package io.mszymanski.orknux.server.research

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

@Component
class ResearchExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ResearchModeNotFoundException,
            is ResearchDocumentNotFoundException,
            is ResearchModeWorkflowNotFoundException,
            is ResearchNotFoundException,
            -> ErrorType.NOT_FOUND

            is ResearchQuestionMissingException,
            is ResearchQuestionTooLongException,
            is ResearchModeWorkflowMissingException,
            is ResearchModelUnusableException,
            is ResearchModelMissingException,
            is ResearchNothingToReadException,
            is ResearchTooManyWebsitesException,
            is ResearchWebsiteTooLongException,
            is ResearchModeNameInvalidException,
            is ResearchModeNameTakenException,
            is ResearchModeWorkflowNotChosenException,
            is ResearchModeSeededException,
            is ResearchStillRunningException,
            is ResearchNeverRanException,
            is ResearchStageNotInRunException,
            is ResearchNothingToRestartException,
            is ResearchRestartRefusedException,
            is ResearchReviewerMissingException,
            is ResearchReviewerUnusableException,
            is ResearchModeNotReviewedException,
            is ResearchReviewRoundsOutOfRangeException,
            -> ErrorType.BAD_REQUEST

            else -> return null
        }
        return refused(exception, errorType, environment)
    }
}

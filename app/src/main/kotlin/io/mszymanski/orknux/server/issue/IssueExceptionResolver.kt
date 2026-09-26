package io.mszymanski.orknux.server.issue

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * The tracker's refusals, in words the page can put in front of somebody.
 *
 * Beside the code that raises them, the way the memory's and the agent's are.
 * The tracker's older refusals are still answered by the workflow resolver,
 * which grew to hold most of the server's - a resolver returns null for what it
 * does not recognise and the next one is asked, so the two sit side by side
 * without either having to know about the other.
 *
 * An address the server will not keep is the caller's to fix and says why, so
 * it is a bad request rather than a failure: somebody who typed a bare hostname
 * needs to be told that, not told that something went wrong.
 */
@Component
class IssueExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is IssueLinkInvalidException,
            is IssueLinkNotYoursException,
            is IssueObserverInvalidException,
            /*
             * A refused move is the caller's to fix and says how, which is what
             * makes it a bad request rather than a failure: an administrator
             * told the assignee is in the way can change it and press the
             * button again.
             */
            is IssueMoveRefusedException,
            /*
             * The three ways of linking two issues badly are all the caller's to
             * fix and each names what is wrong: itself, somewhere else, or
             * already linked some other way.
             */
            is IssueRelationToItselfException,
            is IssueRelationElsewhereException,
            is IssueRelationAlreadyException,
            /*
             * The catalogue's four refusals are each the caller's to fix and
             * each names the word that is wrong: a blank name, one already
             * taken, one nothing here is called, and one that cannot go because
             * issues are on it - which says how many, so an administrator can
             * decide whether they meant it.
             */
            is IssueTypeNameInvalidException,
            is IssueTypeNameTakenException,
            is IssueTypeUnknownException,
            is IssueTypeInUseException,
            /*
             * The statuses' refusals, each the caller's to fix and each saying
             * how: a key the workspace does not have lists the ones it does; a
             * malformed key, label or colour says what one looks like; one that
             * cannot go names the rule - where new issues start, the last that
             * counts as closed, or how many issues hold it.
             */
            is IssueStatusUnknownException,
            is IssueStatusKeyInvalidException,
            is IssueStatusKeyTakenException,
            is IssueStatusLabelInvalidException,
            is IssueStatusColorInvalidException,
            is IssueStatusInUseException,
            is IssueStatusInitialException,
            is IssueStatusInitialClosedException,
            is IssueStatusLastClosedException,
            is IssueStatusReorderException,
            /*
             * Not theirs to remove, which is the caller's to understand rather
             * than a failure: the sentence names both ways somebody could be
             * allowed to, so a reader who was refused knows which of the two
             * they are missing.
             */
            is IssueCommentNotYoursToRemoveException,
            -> ErrorType.BAD_REQUEST

            is IssueLinkNotFoundException,
            is IssueRelationNotFoundException,
            is IssueTypeNotFoundException,
            is IssueStatusNotFoundException,
            -> ErrorType.NOT_FOUND

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}

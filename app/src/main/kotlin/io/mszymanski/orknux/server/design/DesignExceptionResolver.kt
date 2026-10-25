package io.mszymanski.orknux.server.design

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

@Component
class DesignExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is DesignImageRefusedException -> ErrorType.BAD_REQUEST
            is DesignVideoRefusedException -> ErrorType.BAD_REQUEST
            is DesignVideoSettingNotTakenException -> ErrorType.BAD_REQUEST
            is DesignVideoTooLargeException -> ErrorType.BAD_REQUEST
            is DesignVideoExportUnfinishedException,
            is DesignVideoExportTooLargeException,
            is DesignVideoExportTimedOutException,
            is DesignVideoExportFailedException,
            -> ErrorType.BAD_REQUEST
            is WireframeNotFoundException -> ErrorType.NOT_FOUND
            is WireframeNameInvalidException,
            is WireframeDocumentInvalidException,
            is WireframeTooLargeException,
            is WireframeChangedException,
            is WireframeAnswerFailedException,
            -> ErrorType.BAD_REQUEST
            is DocumentNotFoundException, is DocumentCatalogNotFoundException -> ErrorType.NOT_FOUND
            is DocumentNameInvalidException,
            is DocumentPageSizeInvalidException,
            is DocumentContentInvalidException,
            is DocumentTooLargeException,
            is DocumentChangedException,
            is DocumentAnswerFailedException,
            is DocumentCatalogNameInvalidException,
            is DocumentCatalogNameTakenException,
            is DocumentCatalogNotEmptyException,
            is DocumentFormatUnknownException,
            is DocumentSettingInvalidException,
            is GrammarTextTooLongException,
            is GrammarServerInvalidException,
            is GrammarServerUnreachableException,
            is DocumentExportFailedException,
            is DocumentImportFormatUnknownException,
            is DocumentImportTooLargeException,
            is DocumentImportUnreadableException,
            is WireframePictureRefusedException,
            -> ErrorType.BAD_REQUEST
            else -> return null
        }
        return refused(exception, errorType, environment)
    }
}

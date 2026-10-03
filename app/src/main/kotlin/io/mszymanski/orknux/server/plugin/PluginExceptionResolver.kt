package io.mszymanski.orknux.server.plugin

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * The plugin screens' refusals, in English and with a code a client can
 * translate. In a file of its own, named as RefusedErrorTest looks for, so the
 * check that every resolver answers through [refused] reads this one too: it sat
 * inside PluginAPI.kt, built its own GraphQLError, and sent no code.
 */
@Component
class PluginExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is PluginEmptyException,
            is PluginTooLargeException,
            is PluginNotJavaScriptException,
            is PluginNotTextException,
            is PluginContractException,
            is PluginApiVersionUnsupportedException,
            
            is PluginDeclarationInvalidException,
            
            is PluginIdInvalidException,
            is PluginPermissionUnknownException,
            is PluginCapabilityUnknownException,
            is PluginAgreementNeededException,
            is PluginInUseException,
            is PluginFunctionInUseException,
            is PluginParameterUnknownException,
            is PluginParameterAmbiguousException,
            is PluginParameterEmptyException,
            is PluginParameterNotSecretException,
            is PluginParameterNotValueException,
            is PluginParameterVariableElsewhereException,
            is PluginZipInvalidException,
            is PluginUrlInvalidException,
            -> ErrorType.BAD_REQUEST

            /*
             * A service that will not answer is not the caller's mistake, and
             * reporting it as a bad request would have somebody checking what
             * they typed instead of checking the marketplace. What matters
             * either way is that the sentence travels: before these were
             * named here, a marketplace that was down and a key that does not
             * exist both arrived on screen as INTERNAL_ERROR and an id.
             */
            is MarketplaceUnreachableException,
            is PluginUrlUnreachableException,
            // What arrived is not what was published: the marketplace's side or
            // the road to it, never the caller's, and before it was named here it
            // reached the page as INTERNAL_ERROR and an id.
            is PluginDigestMismatchException,
            -> ErrorType.INTERNAL_ERROR

            is PluginNotFoundException,
            is MarketplaceOfferingUnknownException,
            -> ErrorType.NOT_FOUND

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}


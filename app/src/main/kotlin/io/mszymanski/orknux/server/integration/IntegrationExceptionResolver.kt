package io.mszymanski.orknux.server.integration

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.connector.connection.ConnectionNameInvalidException
import io.mszymanski.orknux.connector.connection.ConnectionPluginTypeInvalidException
import io.mszymanski.orknux.connector.connection.ConnectionNameTakenException
import io.mszymanski.orknux.connector.connection.ConnectionNotConfiguredException
import io.mszymanski.orknux.connector.connection.ConnectionUrlInvalidException
import io.mszymanski.orknux.connector.connection.McpServerAddressInvalidException
import io.mszymanski.orknux.connector.connection.McpServerNameInvalidException
import io.mszymanski.orknux.connector.connection.McpServerNameTakenException
import io.mszymanski.orknux.connector.proxy.CertificateInvalidException
import io.mszymanski.orknux.connector.proxy.ProxyRuleNameInvalidException
import io.mszymanski.orknux.connector.proxy.ProxyRuleNameTakenException
import io.mszymanski.orknux.connector.proxy.ProxyRulePatternInvalidException
import io.mszymanski.orknux.connector.proxy.ProxyRuleProxyInvalidException
import io.mszymanski.orknux.connector.shell.NoShellAvailableException
import io.mszymanski.orknux.connector.shell.ShellAddressInvalidException
import io.mszymanski.orknux.connector.shell.ShellKeyInvalidException
import io.mszymanski.orknux.connector.shell.ShellLimitInvalidException
import io.mszymanski.orknux.connector.shell.ShellNameInvalidException
import io.mszymanski.orknux.connector.shell.ShellNameTakenException
import io.mszymanski.orknux.connector.shell.ShellSessionNotFoundException
import io.mszymanski.orknux.connector.shell.ShellUnreachableException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import io.mszymanski.orknux.connector.connection.ConnectionNotFoundException as ModuleConnectionNotFound
import io.mszymanski.orknux.connector.connection.McpServerNotFoundException as ModuleMcpServerNotFound
import io.mszymanski.orknux.connector.proxy.ProxyRuleNotFoundException as ModuleProxyRuleNotFound
import io.mszymanski.orknux.connector.proxy.TrustedCertificateNotFoundException as ModuleTrustedCertificateNotFound
import io.mszymanski.orknux.connector.shell.ShellNotFoundException as ModuleShellNotFound

/**
 * What the connection module refuses is the caller's to fix: a duplicate name is
 * a bad request wherever it was noticed.
 */
@Component
class IntegrationExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ConnectionNameTakenException,
            is ConnectionNameInvalidException,
            is ConnectionPluginTypeInvalidException,
            is ConnectionUrlInvalidException,
            is ConnectionNotConfiguredException,
            is McpServerNameTakenException,
            is McpServerNameInvalidException,
            is McpServerAddressInvalidException,
            is ProxyRuleNameTakenException,
            is ProxyRuleNameInvalidException,
            is ProxyRulePatternInvalidException,
            is ProxyRuleProxyInvalidException,
            is ShellNameTakenException,
            is ShellNameInvalidException,
            is ShellAddressInvalidException,
            is ShellKeyInvalidException,
            is ShellLimitInvalidException,
            // A paste that went wrong, which is the ordinary way this fails.
            is CertificateInvalidException,
            -> ErrorType.BAD_REQUEST

            // A machine that will not answer is not the caller's mistake, and
            // reporting it as a bad request would have somebody checking what
            // they typed instead of checking the host.
            is ShellUnreachableException,
            is NoShellAvailableException,
            -> ErrorType.INTERNAL_ERROR

            is ConnectionNotFoundException,
            is McpServerNotFoundException,
            is ModuleConnectionNotFound,
            is ModuleMcpServerNotFound,
            is ModuleProxyRuleNotFound,
            is ModuleTrustedCertificateNotFound,
            is ModuleShellNotFound,
            is ShellSessionNotFoundException,
            -> ErrorType.NOT_FOUND

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}

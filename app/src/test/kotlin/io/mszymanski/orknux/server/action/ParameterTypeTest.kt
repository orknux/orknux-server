package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolParam
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Every type a parameter may have is a type the database will take.
 *
 * Written after adding one and finding out from a stack trace. `ValueType` is a
 * Kotlin enum and the columns that hold it carry a `CHECK` listing the values by
 * hand, so the two are one fact written in two places — and nothing connected
 * them. Adding `CONNECTION` compiled, typechecked, passed every test there was,
 * and then failed at the moment somebody saved a parameter:
 *
 *     new row for relation "workflow_function_param" violates check constraint
 *     "ck_workflow_function_param_type"
 *
 * This is the connection. It stores a parameter of every type there is, in both
 * tables that hold one, so the next enum value added without a migration fails
 * in a second rather than in front of somebody.
 *
 * Both tables, because a tool's parameters are the same list on the same screen:
 * widening one and not the other would make a type available in a function and
 * refused in a tool, for no reason anybody could see.
 */
@SpringBootTest
class ParameterTypeTest(
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        tools.deleteAll()
        // Not the release's own, which every workspace's Research action calls (#659) and start-up writes back anyway.
        functions.deleteAll(functions.findAll().filter { it.scope != io.mszymanski.orknux.server.action.FunctionScope.EMBEDDED })
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "shapes")).id)
    }

    /**
     * `NONE` is left out: it is what a function *returns* when it returns
     * nothing, and the parameter column has never allowed it. Everything else a
     * parameter may be has to store.
     */
    private val parameterTypes = ValueType.entries.filter { it != ValueType.NONE && it != ValueType.OBJECT }

    @Test
    fun `a function parameter of every type can be stored`() {
        parameterTypes.forEach { type ->
            assertThatCode {
                functions.save(
                    WorkflowFunction(
                        workspaceId = workspaceId,
                        name = "takes${type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        source = "export default function it(given) { return given; }",
                        returnType = ValueType.STRING,
                        params = mutableListOf(FunctionParam(name = "given", type = type)),
                    ),
                )
            }
                .describedAs("a function parameter of type %s", type)
                .doesNotThrowAnyException()
        }

        assertThat(functions.findAll().filter { it.scope != io.mszymanski.orknux.server.action.FunctionScope.EMBEDDED }).hasSize(parameterTypes.size)
    }

    @Test
    fun `a tool parameter of every type can be stored`() {
        parameterTypes.forEach { type ->
            assertThatCode {
                tools.save(
                    AgentTool(
                        workspaceId = workspaceId,
                        name = "tool${type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        description = "Takes a ${type.name.lowercase()}.",
                        source = "export default function it(given) { return given; }",
                        typescript = "export default function it(given: string) { return given; }",
                        params = mutableListOf(AgentToolParam(name = "given", type = type)),
                    ),
                )
            }
                .describedAs("a tool parameter of type %s", type)
                .doesNotThrowAnyException()
        }

        assertThat(tools.findAll()).hasSize(parameterTypes.size)
    }
}

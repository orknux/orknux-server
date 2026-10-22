package io.mszymanski.orknux.server.transfer

/**
 * What a workspace copy may carry, each a switch on the copy page. The
 * workspace's own settings always come; its runs, sessions and roles never do.
 *
 * Leaving out a part that something else points at is allowed and said: a
 * workflow whose function was left out is not copied, and the answer names it
 * with the reason - the copy carries what it can rather than refusing.
 */
enum class WorkspaceCopyPart {
    CONNECTIONS,
    MCP_SERVERS,
    MODELS,
    VARIABLES,
    MEMORIES,
    OBJECTS,
    FUNCTIONS,
    CONDITIONS,
    TOOLS,
    SKILLS,
    ACTIONS,
    AGENTS,
    TRIGGERS,
    WORKFLOWS,
    ;

    companion object {
        /** The part a component kind belongs to. */
        fun of(kind: ComponentKind): WorkspaceCopyPart = when (kind) {
            ComponentKind.OBJECT -> OBJECTS
            ComponentKind.FUNCTION -> FUNCTIONS
            ComponentKind.CONDITION -> CONDITIONS
            ComponentKind.TOOL -> TOOLS
            ComponentKind.SKILL -> SKILLS
            ComponentKind.ACTION -> ACTIONS
            ComponentKind.AGENT -> AGENTS
            ComponentKind.TRIGGER -> TRIGGERS
            ComponentKind.WORKFLOW -> WORKFLOWS
        }

        /** The part an external belongs to, by the name the copy gives its kind. */
        fun ofExternal(kind: String): WorkspaceCopyPart = when (kind) {
            "connection" -> CONNECTIONS
            "mcp server" -> MCP_SERVERS
            else -> MODELS
        }
    }
}

package io.mszymanski.orknux.workflow.script

/**
 * The calling agent's own tools, as a program it wrote reaches them through
 * `orknux.tools.call`.
 *
 * Declared here and implemented in the app, which owns agents: this module
 * holds no notion of one. Text both ways, as everything crossing the sandbox's
 * edge is - the tool's name and its arguments as JSON in, its answer out.
 */
fun interface ScriptToolDoor {
    fun call(name: String, arguments: String): String
}

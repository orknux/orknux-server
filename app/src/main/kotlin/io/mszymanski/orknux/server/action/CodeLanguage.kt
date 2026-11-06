package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.workflow.script.ScriptLanguage

/**
 * What a workspace's function or tool is written in, and the sandbox language
 * that runs it.
 *
 * TypeScript is compiled to JavaScript in the editor and both are stored; Python
 * is run as written, so a Python one holds the same text in both columns. One
 * enum for both because they are the same code in the same sandbox - a choice
 * made for one and not the other would be two products.
 */
enum class CodeLanguage(val script: ScriptLanguage, val spoken: String) {
    TYPESCRIPT(ScriptLanguage.JAVASCRIPT, "TypeScript"),
    PYTHON(ScriptLanguage.PYTHON, "Python"),
}

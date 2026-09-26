package io.mszymanski.orknux.workflow.script

/**
 * Reading a document to find out whether it is one. Issue #416.
 *
 * A script that has just built JSON, a page of HTML or a block of YAML has no
 * way to know it built it correctly: it hands the text to whatever comes next
 * and finds out from the failure, a step later and somewhere else. An agent is
 * worse off again - it writes a config file into a scratchpad and learns it was
 * malformed when a person opens it.
 *
 * So one door, the way the scratchpads have one: a request naming the format and
 * the text, an answer saying whether it parsed and where it did not. What it is
 * emphatically not is a linter with opinions - this says whether a parser can
 * read the document, which is the only question with one answer.
 *
 * Nothing here reaches anything: no network, no disk, no session. It is
 * arithmetic on a string, which is why it needs no permission and is bound
 * wherever a script runs.
 */
interface FormatValidation {

    /**
     * One request, JSON in and JSON out, in the shape every other door here
     * uses.
     *
     * In: `{"format":"json","text":"..."}`. Out: `{"valid":true}` or
     * `{"valid":false,"problems":[{"line":3,"column":11,"message":"..."}]}`,
     * and `{"error":"..."}` where the request itself made no sense.
     */
    fun check(request: String): String
}

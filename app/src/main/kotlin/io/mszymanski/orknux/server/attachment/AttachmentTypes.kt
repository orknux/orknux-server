package io.mszymanski.orknux.server.attachment

/**
 * What an uploaded file is filed as, where the browser's word for it is known
 * to be wrong.
 *
 * The type an upload arrives with is the multipart part's Content-Type, which
 * a browser fills from `File.type` - and that is whatever the operating system
 * maps the extension to. On Windows a .csv is `application/vnd.ms-excel`
 * wherever Excel is installed (the registry's mapping), `text/plain` on some
 * machines, and nothing at all on others, which a browser sends as
 * `application/octet-stream`. Filed as it came, a CSV was kept for the agent as
 * base64 under a type no tool reads.
 *
 * So for the few extensions listed here, a type in that extension's list of
 * known-wrong answers is replaced by the right one; any other type the browser
 * gave is kept, since it said something we have no grounds to overrule. The
 * one place this is decided - every door that files an upload asks here.
 */
object AttachmentTypes {

    const val CSV = "text/csv"
    const val TSV = "text/tab-separated-values"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val XLS = "application/vnd.ms-excel"

    /** What every browser sends for a file whose type it does not know. */
    private const val UNKNOWN = "application/octet-stream"

    /** Per extension: the type it is filed as, and the answers a browser gives that are replaced by it. */
    private val CORRECTED: Map<String, Pair<String, Set<String>>> = mapOf(
        "csv" to (CSV to setOf("", UNKNOWN, XLS, "text/plain", "application/csv", "text/x-csv", "application/x-csv", "text/comma-separated-values")),
        "tsv" to (TSV to setOf("", UNKNOWN, "text/plain")),
        "xlsx" to (XLSX to setOf("", UNKNOWN, "application/zip", "application/x-zip-compressed")),
        "xls" to (XLS to setOf("", UNKNOWN)),
    )

    /** The type [filename] is filed under, given what the browser [declared]. */
    fun of(filename: String, declared: String?): String {
        val said = declared?.trim().orEmpty()
        val bare = said.substringBefore(';').trim().lowercase()
        val extension = filename.substringAfterLast('.', "").lowercase()
        val (right, wrong) = CORRECTED[extension] ?: return said.ifEmpty { UNKNOWN }
        return if (bare in wrong) right else said
    }
}

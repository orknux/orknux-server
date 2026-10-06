package io.mszymanski.orknux.server.plugin

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

/**
 * A plugin's code: the bundle that runs and the TypeScript it was written in.
 *
 * The same `plugin` row as [Plugin], mapped a second time with only these
 * columns, so that reading the code is something a caller asks for rather
 * than something every lookup does. A plugin can be megabytes of source - a
 * bundled renderer is - and while it was a property of [Plugin], every list
 * of plugins, every picker and every `findByKey` read all of it, twice over
 * for Hibernate's dirty-check copy. Issue #616.
 *
 * Two entities over one table rather than a table of its own because a split
 * table is a migration that is not additive, and rather than a lazy column
 * because a lazy basic attribute needs bytecode enhancement. The row is
 * inserted by [Plugin], which writes these columns once; nothing ever loads
 * this entity whole into a persistence context - the reads below are scalar.
 */
@Entity
@Table(name = "plugin")
class PluginCode(
    @Id
    val id: Long,

    @Column(nullable = false, columnDefinition = "text")
    val source: String,

    @Column(columnDefinition = "text")
    val typescript: String? = null,
)

/** The only way into a plugin's code. See [PluginCode]. */
interface PluginCodeRepository : Repository<PluginCode, Long> {

    /** What runs. Null when there is no such plugin. */
    @Query("select c.source from PluginCode c where c.id = :id")
    fun sourceOf(id: Long): String?

    /** What runs and what it was written in, for a download or an export. */
    @Query("select new io.mszymanski.orknux.server.plugin.PluginSource(c.source, c.typescript) from PluginCode c where c.id = :id")
    fun codeOf(id: Long): PluginSource?

    /**
     * A re-upload's new code, written straight to the row: [Plugin] never
     * writes these columns after its insert, and loading the old bundle only
     * to replace it is the read this split exists to avoid.
     */
    @Modifying
    @Query("update PluginCode c set c.source = :source, c.typescript = :typescript where c.id = :id")
    fun replace(id: Long, source: String, typescript: String?): Int
}

/** A plugin's code, outside any persistence context. */
data class PluginSource(val source: String, val typescript: String?)

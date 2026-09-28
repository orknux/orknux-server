package io.mszymanski.orknux.server.database

import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Makes V313 runnable where the date plugin had a function returning an object.
 *
 * V313 moves the date plugin's functions into the server and clears their
 * `return_object_id`, but leaves `return_type` as it was - and where that was
 * OBJECT, `ck_workflow_function_return_object` refuses the row, the migration
 * rolls back and the server does not start. A production installation stopped
 * on exactly that, over `date_describe`. Neither CI nor a development database
 * had such a function, so nothing noticed.
 *
 * Not a fix to V313 itself, because an applied migration cannot be edited: every
 * installation already past it would refuse to start over the checksum. So this
 * runs just before V313 and retypes those rows to MAP, which is what the
 * embedded registrar writes for them anyway. Where V313 has already run there is
 * no V313 to run before, and this never fires.
 */
@Component
class DatePluginRetype : Callback {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun supports(event: Event, context: Context?): Boolean =
        event == Event.BEFORE_EACH_MIGRATE && context?.migrationInfo?.version?.version == "313"

    override fun canHandleInTransaction(event: Event, context: Context?): Boolean = true

    override fun handle(event: Event, context: Context) {
        context.connection.createStatement().use { sql ->
            val retyped = sql.executeUpdate(
                """
                UPDATE workflow_function
                SET return_type = 'MAP', return_object_id = NULL
                WHERE return_type = 'OBJECT'
                  AND plugin_id IN (SELECT id FROM plugin WHERE plugin_key = 'date')
                """.trimIndent(),
            )
            if (retyped > 0) log.info("Retyped {} date plugin function(s) from OBJECT to MAP before V313", retyped)
        }
    }

    override fun getCallbackName(): String = "date-plugin-retype"
}

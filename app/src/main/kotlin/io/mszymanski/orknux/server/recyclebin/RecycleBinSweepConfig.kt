package io.mszymanski.orknux.server.recyclebin

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * The clock that purges recycle bin entries past their retention.
 *
 * A recurring task on the scheduler the triggers, watchers, schedules and
 * temporary agents already share, for the reasons theirs give: it survives a
 * restart and runs on one instance however many are up, so no second gate is
 * needed. Every hour, because the retention is counted in days and an entry kept
 * an hour longer costs a row - the retention itself is the Tunable
 * (RECYCLE_BIN_RETENTION_DAYS), read on each pass. Off where the scheduler is,
 * which is how the suite runs; a test calls [RecycleBin.sweep] itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "db-scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RecycleBinSweepConfig {

    @Bean
    fun recycleBinSweepTask(recycleBin: RecycleBin): RecurringTask<Void> = Tasks
        .recurring(TASK_NAME, Schedules.fixedDelay(EVERY))
        .execute { _, _ ->
            runCatching { recycleBin.sweep() }
                .onFailure { log.error("The recycle bin could not be swept", it) }
        }

    companion object {
        /** What db-scheduler knows the task as, in its table. */
        const val TASK_NAME = "recycle-bin-sweep"

        /** How often; a format of the sweep rather than a behaviour anyone tunes - the retention is the Tunable. */
        private val EVERY: Duration = Duration.ofHours(1)

        private val log = LoggerFactory.getLogger(RecycleBinSweepConfig::class.java)
    }
}

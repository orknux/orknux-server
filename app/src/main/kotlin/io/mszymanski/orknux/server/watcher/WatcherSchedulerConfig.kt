package io.mszymanski.orknux.server.watcher

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import io.mszymanski.orknux.server.trigger.TriggerSchedulerProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The clock behind watchers. Issue #606.
 *
 * A recurring task on the scheduler the triggers already use, rather than a
 * thread of its own, because that scheduler is what survives a restart and
 * runs a task on one instance however many are up: its state is a row in the
 * database, and so is every watcher's. Nothing is registered per watcher -
 * the tick asks the table what is due - so setting, stopping or finishing one
 * is a write and nothing else.
 *
 * On the triggers' tick interval, `ORKNUX_SCHEDULER_TICK_INTERVAL`, ten seconds
 * by default: that is the finest interval a watcher is actually kept to, and
 * why the smallest an agent may ask for defaults to fifteen. A second knob for
 * the same clock would be a second answer to how often this installation
 * looks at the time.
 *
 * Off where the scheduler is, which is how the suite runs; a test calls
 * [WatcherService.tick] itself, the way `TriggerSchedulerTest` calls the
 * triggers' tick.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "db-scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WatcherSchedulerConfig {

    @Bean
    fun watcherTask(
        watchers: WatcherService,
        properties: TriggerSchedulerProperties,
    ): RecurringTask<Void> = Tasks
        .recurring(TASK_NAME, Schedules.fixedDelay(properties.tickInterval))
        .execute { _, _ ->
            runCatching { watchers.tick() }
                .onFailure { log.error("The watchers could not be checked", it) }
        }

    companion object {
        /** What db-scheduler knows the task as, in its table. */
        const val TASK_NAME = "watchers"

        private val log = LoggerFactory.getLogger(WatcherSchedulerConfig::class.java)
    }
}

package io.mszymanski.orknux.server.schedule

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import io.mszymanski.orknux.server.trigger.TriggerSchedulerProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The clock behind agents' schedules.
 *
 * A recurring task on the scheduler the triggers and watchers already use, for
 * the reason [io.mszymanski.orknux.server.watcher.WatcherSchedulerConfig] gives:
 * that scheduler survives a restart and runs a task on one instance however
 * many are up. Nothing is registered per schedule - the tick asks the table
 * what is due - so setting or stopping one is a write and nothing else.
 *
 * On the triggers' tick interval, `ORKNUX_SCHEDULER_TICK_INTERVAL`: the finest
 * a schedule is kept to, and why the smallest floor an administrator may set is
 * ten seconds.
 *
 * Off where the scheduler is, which is how the suite runs; a test calls
 * [ScheduleService.tick] itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "db-scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ScheduleSchedulerConfig {

    @Bean
    fun agentScheduleTask(
        schedules: ScheduleService,
        properties: TriggerSchedulerProperties,
    ): RecurringTask<Void> = Tasks
        .recurring(TASK_NAME, Schedules.fixedDelay(properties.tickInterval))
        .execute { _, _ ->
            runCatching { schedules.tick() }
                .onFailure { log.error("The agents' schedules could not be run", it) }
        }

    companion object {
        /** What db-scheduler knows the task as, in its table. */
        const val TASK_NAME = "agent-schedules"

        private val log = LoggerFactory.getLogger(ScheduleSchedulerConfig::class.java)
    }
}

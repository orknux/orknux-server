package io.mszymanski.orknux.server.agent

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * The clock that removes temporary agents whose conversation went quiet.
 *
 * A recurring task on the scheduler the triggers, watchers and schedules
 * already use, for the reasons theirs give: it survives a restart and runs on
 * one instance however many are up. Every ten minutes, because the idle limit
 * is counted in hours and a temporary agent left a few minutes longer costs a
 * row. Off where the scheduler is, which is how the suite runs; a test calls
 * [TemporaryAgents.sweep] itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "db-scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class TemporaryAgentSweepConfig {

    @Bean
    fun temporaryAgentSweepTask(temporary: TemporaryAgents): RecurringTask<Void> = Tasks
        .recurring(TASK_NAME, Schedules.fixedDelay(EVERY))
        .execute { _, _ ->
            runCatching { temporary.sweep() }
                .onFailure { log.error("Temporary agents could not be swept", it) }
        }

    companion object {
        /** What db-scheduler knows the task as, in its table. */
        const val TASK_NAME = "temporary-agent-sweep"

        /** How often; a format of the sweep rather than a behaviour anyone tunes - the idle limit is the Tunable. */
        private val EVERY: Duration = Duration.ofMinutes(10)

        private val log = LoggerFactory.getLogger(TemporaryAgentSweepConfig::class.java)
    }
}

package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.tuning.Tunable
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import org.slf4j.LoggerFactory
import io.mszymanski.orknux.server.database.SqliteJdbcCustomization
import io.mszymanski.orknux.server.database.isSqlite
import io.mszymanski.orknux.server.database.jdbcUrlOf
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.sql.DataSource

/**
 * Fires the scheduled triggers.
 *
 * db-scheduler owns the clock: the tick below is a recurring task in the
 * database, so it runs on one instance however many are up, and a restart picks
 * it up where it was rather than firing everything again.
 *
 * What the tick does is compare each enabled definition's cron with when it last
 * fired. That is what makes a trigger's schedule editable while the process
 * runs — nothing is registered per trigger, so nothing has to be unregistered.
 *
 * It used to fire the single next occurrence, once a minute, which made a
 * minute the smallest schedule this could keep. Seconds parsed and were
 * accepted the whole time — `sixField` passes six fields straight through — so
 * a cron of every ten seconds saved cleanly and then fired six times less often
 * than it said. Now the tick runs on [TriggerSchedulerProperties.tickInterval] and
 * fires every occurrence that came due in the window, so a schedule finer than
 * the tick arrives in bursts rather than being quietly thinned.
 */
@Component
class TriggerScheduler(
    private val triggers: WorkflowTriggerRepository,
    private val occurrence: ScheduledTriggerOccurrence,
    /** The numbers this reads from Admin -> Settings. Issue #453. */
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    /**
     * Fires everything that came due since it last ran.
     *
     * A trigger that has never fired starts from now, so adding one does not
     * replay the schedule it missed.
     *
     * Deliberately not `@Transactional`. It used to be, and one workspace's
     * draft workflow was then enough to stop every scheduled trigger in the
     * installation: starting a run against an unpublished graph threw out of a
     * transactional method, which marked this shared transaction rollback-only,
     * so the commit at the end of the round failed, every `lastFiredAt` in it
     * was rolled back, and the next minute found exactly the same work to do.
     * Nothing fired, for ever. Each trigger now gets its own boundary — reached
     * through [occurrence], because a `@Transactional` method called from
     * another method of the same bean is not proxied and would be no boundary
     * at all — so a trigger that cannot be fired takes only itself down.
     */
    fun tick(now: OffsetDateTime = OffsetDateTime.now()) {
        for (trigger in triggers.findByTypeAndEnabledTrue(TriggerType.SCHEDULED)) {
            val id = trigger.id ?: continue
            val cron = trigger.cron ?: continue
            val zone = zoneOf(trigger.timezone)
            val expression = runCatching { CronExpression.parse(sixField(cron)) }.getOrElse {
                log.warn("Trigger {} has a cron this cannot schedule: {}", trigger.name, cron)
                continue
            }

            /*
             * How far back this is willing to look.
             *
             * A trigger that has never fired starts from now, so adding one
             * does not replay the schedule it missed. One that has is bounded
             * to [CATCH_UP] as well: firing the single next occurrence used to
             * be its own throttle, and now that every due occurrence fires, a
             * trigger switched back on after a fortnight would otherwise start
             * a fortnight of runs at once.
             */
            val since = trigger.lastFiredAt ?: now.minusMinutes(1)
            val from = maxOf(since, now.minus(CATCH_UP))

            /*
             * Everything due between then and now, and not merely the first of
             * them. A cron of every ten seconds has six occurrences in a
             * minute, and taking one of them is not a schedule running late, it
             * is a schedule running at a sixth of what it says.
             *
             * Tunable.SCHEDULES_PER_TICK is the second bound. It bites only where the cron
             * is finer than the window, which after [CATCH_UP] means a schedule
             * of seconds - and starting sixty runs in one round is a thing
             * worth having a ceiling on whatever the arithmetic says.
             */
            val mostPerTick = tuning[Tunable.SCHEDULES_PER_TICK]
            val due = buildList {
                var at = expression.next(from.atZoneSameInstant(zone).toOffsetDateTime())
                while (at != null && !at.isAfter(now) && size < mostPerTick) {
                    add(at)
                    at = expression.next(at)
                }
            }
            if (due.isEmpty()) continue
            if (due.size == mostPerTick) {
                log.warn("Trigger {} had more than {} occurrences due; the rest are dropped", trigger.name, mostPerTick)
            }

            /*
             * The stamp is committed before the firing is attempted, and that
             * is the deliberate half of this.
             *
             * `lastFiredAt` records that an occurrence was taken, not that it
             * succeeded. Stamping inside the same transaction as the firing
             * would read better, but it means a trigger whose workflow fails
             * has its stamp rolled back and comes back due a minute later, and
             * a minute after that, for as long as the failure lasts - which for
             * anything that sends or charges is not a retry but a second
             * occurrence of something that already half happened. The cost of
             * this order is that a genuinely transient failure loses that one
             * occurrence rather than repeating it. That is bounded and it is
             * written into the firing log; the other way round is unbounded and
             * writes a new line every minute for ever.
             */
            val stamped = runCatching { occurrence.take(id, now) }
                .onFailure { log.error("Trigger {} could not be stamped as fired", trigger.name, it) }
                .getOrDefault(false)
            // A stamp that did not stick would fire this same occurrence again
            // on the next tick, so nothing is started until it has.
            if (!stamped) continue

            /*
             * Each occurrence is handed its own moment rather than the tick's,
             * so a run started by the third of six firings says when it was due
             * instead of reporting the six as having happened together.
             */
            due.forEach { at ->
                runCatching { occurrence.fire(id, cron, at) }
                    .onFailure { log.error("Trigger {} could not be fired", trigger.name, it) }
            }
        }
    }

    private fun zoneOf(timezone: String?): ZoneId =
        runCatching { ZoneId.of(timezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))

    private companion object {
        /**
         * The furthest back a trigger will catch up.
         *
         * A minute, which is where a trigger that has never fired starts from,
         * so the two agree. It is what keeps a schedule that was switched off
         * and back on from starting everything it missed while it was away -
         * an occurrence that did not happen is a gap in a schedule, and the
         * fortnight of them is not work anybody still wants doing.
         */
        val CATCH_UP: Duration = Duration.ofMinutes(1)

        /**
         * And the most any one trigger may start in one round.
         *
         * Sixty, which is a schedule of every second catching up a whole
         * minute - the finest thing [CATCH_UP] can produce. Anything above that
         * is arithmetic nobody intended, and a ceiling that is never reached in
         * normal running is exactly the one worth having. Tunable.SCHEDULES_PER_TICK
         * since #453.
         */

        val log = LoggerFactory.getLogger(TriggerScheduler::class.java)
    }
}

/**
 * One trigger's turn, in a transaction of its own.
 *
 * A separate bean rather than two more methods on [TriggerScheduler], because
 * Spring's `@Transactional` is applied by a proxy and a call from one method of
 * a bean to another goes straight to the object: the annotation would be there
 * and the boundary would not. Everything the round does that touches the
 * database happens through here, so whatever one trigger does to its
 * transaction stays inside its own.
 *
 * `REQUIRES_NEW` rather than the default, for the same reason stated as a rule:
 * a firing must not be able to reach a transaction it did not open, whoever
 * calls the tick and whatever they are in the middle of.
 */
@Component
class ScheduledTriggerOccurrence(
    private val triggers: WorkflowTriggerRepository,
    private val runs: TriggerRunner,
) {

    /**
     * Claims this occurrence by stamping the trigger, and commits.
     *
     * Nothing is caught in here on purpose. A failed write leaves the
     * transaction rollback-only and the commit at this boundary throws whatever
     * a `runCatching` inside the method would have missed - which is the shape
     * of the bug this whole change is about. The caller catches, outside.
     *
     * @return false when there was nothing to stamp, the trigger having been
     *   deleted between the round reading it and this.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun take(triggerId: Long, now: OffsetDateTime): Boolean {
        val trigger = triggers.findByIdOrNull(triggerId) ?: return false
        trigger.lastFiredAt = now
        triggers.save(trigger)
        return true
    }

    /** Starts what the trigger is wired to, in a transaction of this trigger's own. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fire(triggerId: Long, cron: String, now: OffsetDateTime) {
        val trigger = triggers.findByIdOrNull(triggerId) ?: return
        runs.fire(trigger, mapOf("cron" to cron, "firedAt" to now.toString()))
    }
}

/**
 * Builds the scheduler and starts it.
 *
 * This is done here rather than by db-scheduler's Spring Boot starter: that
 * starter's auto-configuration is `@ConditionalOnBean(DataSource)` ordered after
 * Boot 3's `DataSourceAutoConfiguration`, and Boot 4 moved that class to another
 * package — so the ordering no longer applies, the condition is evaluated before
 * the datasource is defined, and nothing is ever scheduled. Six lines here are
 * cheaper than a silence nobody notices.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TriggerSchedulerProperties::class)
@ConditionalOnProperty(prefix = "db-scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class TriggerSchedulerConfig {

    /**
     * One task, one instance: whoever picks it up does the round.
     *
     * A fixed delay rather than a cron of its own, because what this needs is a
     * rate and `fixedDelay` says one without a second cron expression to read
     * alongside the ones it is firing. It is also the floor on how fine a
     * trigger's own schedule can be: a cron of every second on a tick of ten
     * fires ten at a time, ten seconds late.
     */
    @Bean
    fun scheduledTriggerTask(
        scheduler: TriggerScheduler,
        properties: TriggerSchedulerProperties,
    ): RecurringTask<Void> = Tasks
        .recurring("scheduled-triggers", Schedules.fixedDelay(properties.tickInterval))
        .execute { _, _ ->
            runCatching { scheduler.tick() }
                .onFailure { log.error("The scheduled triggers could not be fired", it) }
        }

    @Bean(destroyMethod = "stop")
    fun dbScheduler(
        dataSource: DataSource,
        tasks: List<RecurringTask<*>>,
        properties: TriggerSchedulerProperties,
    ): Scheduler = Scheduler.create(dataSource)
        .startTasks(tasks)
        .threads(properties.threads)
        .pollingInterval(properties.pollingInterval)
        .registerShutdownHook()
        // db-scheduler has a dialect for every database it supports and none for
        // SQLite, so on SQLite it is handed one. See SqliteJdbcCustomization for
        // what the default gets wrong there.
        .also { builder -> if (isSqlite(jdbcUrlOf(dataSource))) builder.jdbcCustomization(SqliteJdbcCustomization()) }
        .build()
        .also {
            it.start()
            log.info("Scheduler started with {} recurring task(s)", tasks.size)
        }

    private companion object {
        val log = LoggerFactory.getLogger(TriggerSchedulerConfig::class.java)
    }
}

@ConfigurationProperties(prefix = "db-scheduler")
data class TriggerSchedulerProperties(
    /** False starts no clock at all; the tests run that way. */
    val enabled: Boolean = true,
    val threads: Int = 4,
    /** How often the table is asked what is due. */
    val pollingInterval: Duration = Duration.ofSeconds(10),
    /**
     * How often the scheduled triggers are looked at, and so the finest
     * schedule this installation can actually keep.
     *
     * Ten seconds, which matches [pollingInterval] because asking more often
     * than the table is read buys nothing. It was a minute, which is what made
     * a cron of seconds parse, save, and then fire at whatever rate the minute
     * allowed.
     *
     * Lowering it is how an installation that genuinely wants a schedule of
     * seconds gets one, and it is a real cost rather than a free switch: every
     * tick is a query per enabled scheduled trigger.
     */
    val tickInterval: Duration = Duration.ofSeconds(10),
)

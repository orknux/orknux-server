package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A provider was created or edited, which is the moment its status is worth
 * knowing again. Published by [ModelService]; acted on by [ModelProviderMonitor]
 * when checking is switched on, and ignored entirely when it is not.
 */
data class ModelProviderSaved(val providerId: Long)

@ConfigurationProperties(prefix = "orknux.model.check")
data class ModelProviderCheckProperties(
    /** False checks nothing on a timer; the button still works. */
    val enabled: Boolean = true,
    /**
     * How often every configured provider is asked whether it is still there.
     * Long enough not to be traffic, short enough that a key revoked this
     * morning is not still reported as working this afternoon.
     */
    val interval: Duration = Duration.ofMinutes(5),
    /** How long after startup the first sweep runs, so booting stays quick. */
    val initialDelay: Duration = Duration.ofSeconds(30),
)

/**
 * Asks every configured provider, on a timer, whether it is still answering.
 *
 * A check is only true when it was made. A key is revoked, a local model server
 * is stopped, an endpoint moves — and a status recorded an hour ago goes on
 * saying "Connected" until somebody presses the button. So nobody has to: the
 * sweep re-runs the same check the button runs and writes down what it found,
 * which is also what puts a date on the screen worth reading.
 *
 * Providers with nothing to check with are skipped rather than reported as
 * failing: not configured is not broken.
 *
 * So are providers whose own switch is off. Not every endpoint is one an
 * installation wants polled - a model server on somebody's laptop is off more
 * often than it is on, and a sweep against it produces a failed row and a
 * warning in the log every five minutes for a state nobody thinks is wrong.
 * That switch stops this timer and nothing else: Test Connection still runs,
 * and so does every chat and task the provider serves.
 */
@Component
@ConditionalOnProperty(name = ["orknux.model.check.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ModelProviderCheckProperties::class)
class ModelProviderMonitor(
    private val providers: ModelProviderRepository,
    private val models: ModelService,
    private val properties: ModelProviderCheckProperties,
    /** Which replica sweeps; alone where nothing says otherwise. Issue #597. */
    private val leader: ClusterLeader = ClusterLeader.alone(),
) : SmartLifecycle {

    /*
     * Made in start() rather than held for the bean's life, because a stopped
     * executor is terminated for good and this bean has to survive stop() then
     * start() - which is exactly what the test framework does to a cached
     * context it paused and picked up again.
     */
    private var sweeper: ScheduledExecutorService? = null
    private var running = false

    override fun start() {
        val pool = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "model-provider-check").apply { isDaemon = true }
        }
        pool.scheduleWithFixedDelay(
            { runCatching(::timedPass).onFailure { log.warn("Could not check the model providers", it) } },
            properties.initialDelay.toSeconds(),
            properties.interval.toSeconds(),
            TimeUnit.SECONDS,
        )
        sweeper = pool
        running = true
        log.info("Checking model providers every {}", properties.interval)
    }

    /**
     * What the timer calls: a pass, on the one replica that leads. Issue #597.
     * Every replica keeps its timer, so the work moves when the leader dies;
     * a test asks [sweep] directly, or this to see the gate.
     */
    fun timedPass(): Boolean {
        if (!leader.leads()) return false
        sweep()
        return true
    }

    override fun stop() {
        if (!running) return
        sweeper?.shutdownNow()
        sweeper = null
        running = false
    }

    override fun isRunning(): Boolean = running

    /**
     * A provider was just saved, so it is checked now rather than at the next
     * sweep.
     *
     * Saving is exactly when somebody wants to know, and "Not checked" for up
     * to [ModelProviderCheckProperties.interval] after typing a key in reads as
     * a screen that is not working. After the commit, because a check runs in
     * another thread and would otherwise race the transaction that wrote the
     * endpoint it is meant to call. Off the caller's thread, because a form
     * should not wait on a provider that may be five seconds from timing out.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onProviderSaved(event: ModelProviderSaved) {
        if (!running) return
        sweeper?.execute {
            runCatching { check(event.providerId) }
                .onFailure { log.warn("Could not check model provider {}", event.providerId, it) }
        }
    }

    /**
     * One pass over everything worth asking.
     *
     * Each provider is checked on its own and a failure to reach one is not
     * allowed to end the sweep — one unreachable endpoint would otherwise
     * leave every provider after it in the list unchecked.
     */
    fun sweep() {
        val worth = providers.findAll().filter { it.configured() && it.checkEnabled }
        if (worth.isEmpty()) return

        for (provider in worth) {
            val id = provider.id ?: continue
            runCatching { check(id) }
                .onFailure { log.warn("Could not check model provider {}", provider.name, it) }
        }
        log.debug("Checked {} model providers", worth.size)
    }

    /**
     * Skipped rather than failed when there is nothing to check with, and
     * skipped rather than asked when somebody said not to.
     *
     * Both guards are here as well as in [sweep] because this is also the
     * save's road in, and a provider saved with checking turned off must not be
     * called once on the way past - which is precisely the sweep somebody was
     * turning off, arriving a second earlier.
     */
    private fun check(providerId: Long) {
        val provider = providers.findByIdOrNull(providerId) ?: return
        if (!provider.configured() || !provider.checkEnabled) return
        models.testProvider(providerId)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelProviderMonitor::class.java)
    }
}

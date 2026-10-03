package io.mszymanski.orknux.workflow.temporal

import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.RetryOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkflowImplementationOptions
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The Temporal client and the worker that runs in this process.
 *
 * The stubs are not connected on creation, and the intention was that this
 * service starts whether or not Temporal is up - the same rule the GraphQL
 * upstreams follow, with a run started while it is down failing at the start
 * rather than the application refusing to boot.
 *
 * That is not what happens. Pointed at a host that does not resolve, the server
 * exits 1 during startup, which was found by starting the published image
 * against a live database and no Temporal while writing deploy/compose.yaml. So
 * Temporal is on the required path today whatever this comment intended, and a
 * deployment has to bring it up before the server. Worth fixing rather than
 * documenting, since the behaviour the comment describes is the better one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TemporalProperties::class)
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "true", matchIfMissing = true)
class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    fun workflowServiceStubs(properties: TemporalProperties): WorkflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.target)
                .build(),
        )

    @Bean
    fun workflowClient(stubs: WorkflowServiceStubs, properties: TemporalProperties): WorkflowClient =
        WorkflowClient.newInstance(
            stubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(properties.namespace)
                .build(),
        )

    /**
     * One worker, polling one queue, running both the interpreter and the
     * activities. They are split when a step becomes something worth scaling on
     * its own — a model call and a graph walk want different machines.
     *
     * Anything else in this process with a durable loop of its own registers
     * through [TemporalRegistrar] rather than building a worker: a second
     * `newWorker` on this queue is an error, and a second queue would be a
     * second thing to configure and to watch.
     */
    @Bean(destroyMethod = "shutdown")
    fun workerFactory(
        client: WorkflowClient,
        activities: ExecutionActivities,
        properties: TemporalProperties,
        registrars: List<TemporalRegistrar>,
    ): WorkerFactory {
        val factory = WorkerFactory.newInstance(client)
        val worker = factory.newWorker(properties.taskQueue)

        registerExecutionWorkflow(worker, activityOptions(properties))
        worker.registerActivitiesImplementations(activities)
        registrars.forEach { it.register(worker) }
        return factory
    }

    private fun activityOptions(properties: TemporalProperties): ActivityOptions =
        activityOptions(properties.stepTimeoutSeconds, properties.stepAttempts)

    companion object {
        /**
         * The run interpreter on [worker], with [stepOptions] as what every step
         * is asked with. Issue #601.
         *
         * By a factory rather than by type, so each run is handed the step
         * options and can add the step heartbeat to them as it goes: a stub's
         * own options are not merged over the worker's defaults, and workflow
         * code has no way to read those back. One function, so the suite
         * registers the workflow exactly as a deployment does.
         */
        fun registerExecutionWorkflow(worker: Worker, stepOptions: ActivityOptions) {
            worker.registerWorkflowImplementationFactory(
                ExecutionWorkflow::class.java,
                io.temporal.workflow.Functions.Func<ExecutionWorkflow> { ExecutionWorkflowImpl(stepOptions) },
                WorkflowImplementationOptions.newBuilder()
                    .setDefaultActivityOptions(stepOptions)
                    .build(),
            )
        }

        /**
         * What every step activity is held to. One function, so the test that
         * kills a worker mid-step is held to what a deployment is.
         *
         * No heartbeat timeout here: that one is an Admin setting and is put
         * on each step's stub by [ExecutionWorkflowImpl] as the step is asked,
         * so a change applies to the next step rather than to the next start
         * of the worker. Issue #601.
         */
        fun activityOptions(stepTimeoutSeconds: Long, stepAttempts: Int): ActivityOptions =
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(stepTimeoutSeconds))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(stepAttempts)
                        .build(),
                )
                .build()
    }
}

/**
 * Starts polling once the application is up, and stops before it goes down, so
 * a worker never picks up a step this process is no longer able to finish.
 */
@Component
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "true", matchIfMissing = true)
class TemporalWorkerLifecycle(
    private val factory: WorkerFactory,
    private val properties: TemporalProperties,
) : SmartLifecycle {

    private var running = false

    override fun start() {
        factory.start()
        running = true
        log.info("Polling Temporal at {} on {}", properties.target, properties.taskQueue)
    }

    override fun stop() {
        if (!running) return
        // Lets the steps in flight finish rather than dropping them on the floor.
        factory.shutdown()
        factory.awaitTermination(SHUTDOWN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        running = false
    }

    override fun isRunning(): Boolean = running

    private companion object {
        val log = LoggerFactory.getLogger(TemporalWorkerLifecycle::class.java)
        const val SHUTDOWN_SECONDS = 10L
    }
}

package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.server.agent.AgentRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * An agent's box grants name boxes, and this keeps them doing so.
 *
 * A grant used to name a box's label, a second name an administrator gave a
 * box beside its own. The label is gone; at start-up every grant still naming
 * one is turned into the names of the boxes that wore it - two boxes sharing a
 * label become two names - and every box's stored label is set to its name.
 * The second half is for a rollback: a jar older than this one grants by
 * label, and with each label equal to its box's name it grants exactly the
 * boxes this one does.
 *
 * Idempotent, so it runs on every start rather than once: after the first run
 * there is no label that is not also a name, and nothing changes.
 */
@Component
class BoxGrantsByName(
    private val boxes: BoxRepository,
    private val agents: AgentRepository,
) : ApplicationRunner {

    @Transactional
    override fun run(args: ApplicationArguments) {
        val rows = boxes.findAll()
        val names = rows.map { it.name }.toSet() + LOCAL_BOX_NAME
        val byLabel = rows.filter { !it.label.isNullOrBlank() && it.label != it.name }
            .groupBy({ it.label!!.trim() }, { it.name })

        var moved = 0
        if (byLabel.isNotEmpty()) {
            agents.findAll().filter { it.boxLabels.isNotEmpty() }.forEach { agent ->
                val now = agent.boxLabels.flatMap { granted ->
                    if (granted in names) listOf(granted) else byLabel[granted] ?: listOf(granted)
                }.distinct()
                if (now != agent.boxLabels) {
                    agent.boxLabels = now
                    agents.save(agent)
                    moved += 1
                }
            }
        }
        rows.filter { it.label != it.name }.forEach {
            it.label = it.name
            boxes.save(it)
        }
        if (moved > 0) log.info("{} agents' box grants moved from labels to box names", moved)
    }

    /**
     * Every agent granted [from] is granted [to] instead; how many there were.
     * Called when a box is renamed, so its grants follow it.
     */
    @Transactional
    fun rename(from: String, to: String): Int {
        var moved = 0
        agents.findAll().filter { from in it.boxLabels }.forEach { agent ->
            agent.boxLabels = agent.boxLabels.map { if (it == from) to else it }
            agents.save(agent)
            moved += 1
        }
        return moved
    }

    private companion object {
        val log = LoggerFactory.getLogger(BoxGrantsByName::class.java)
    }
}

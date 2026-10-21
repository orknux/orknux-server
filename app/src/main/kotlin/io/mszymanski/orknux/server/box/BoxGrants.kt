package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.server.agent.AgentRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * An agent's box grants name labels, and this keeps them pointing at boxes.
 *
 * A label is shared, so changing one box's label is not renaming the label:
 * while another box still carries the old one, the agents granted it keep
 * their grant and reach that box. Only when the last box carrying it is
 * relabelled would the grant point at nothing, and then it follows the box.
 */
@Component
class BoxGrants(
    private val boxes: BoxRepository,
    private val agents: AgentRepository,
) {

    /**
     * A box's label went from [from] to [to]: if no box carries [from] any
     * more, every agent granted it is granted [to] instead. How many moved.
     */
    @Transactional
    fun relabel(from: String, to: String): Int {
        if (from == to || from == LOCAL_BOX_LABEL || boxes.findAll().any { it.label == from }) return 0
        var moved = 0
        agents.findAll().filter { from in it.boxLabels }.forEach { agent ->
            agent.boxLabels = agent.boxLabels.map { if (it == from) to else it }
            agents.save(agent)
            moved += 1
        }
        return moved
    }
}

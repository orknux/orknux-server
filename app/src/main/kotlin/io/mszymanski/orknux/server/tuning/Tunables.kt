package io.mszymanski.orknux.server.tuning

import io.mszymanski.orknux.connector.ConnectionKnob
import io.mszymanski.orknux.connector.ConnectionTuning
import io.mszymanski.orknux.server.attachment.InstallationSetting
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.workflow.ExecutionKnob
import io.mszymanski.orknux.workflow.ExecutionTuning
import org.springframework.core.env.Environment
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * What every [Tunable] is on this installation. Issue #453.
 *
 * The same bargain as the settings written out by hand: what the configuration
 * file says is where a fresh installation starts, and what an administrator
 * stored is the answer from then on. A stored value that is not a number, or is
 * outside what the screen would let anybody choose, reads as the configured one
 * - a row written by an older release with a wider range must not take the
 * server somewhere this one does not go.
 *
 * Read fresh on every call, as the other settings are, which is what lets a
 * change take effect without a restart and on every server at once. It is a
 * primary-key lookup; nothing that reads one is on a path where that shows.
 *
 * The modules' knobs are answered here too, through [ConnectionTuning] and
 * [ExecutionTuning], so a module reads a setting without knowing there is an
 * application behind it.
 */
@Service
class Tunables(
    private val settings: InstallationSettingRepository,
    private val environment: Environment,
) : Tuning, ConnectionTuning, ExecutionTuning {

    override operator fun get(tunable: Tunable): Int {
        val held = settings.findByIdOrNull(tunable.key)?.value?.toIntOrNull()
        return held?.takeIf { it in tunable.least..tunable.most } ?: configured(tunable)
    }

    /** Where a fresh installation starts: the file's number, inside the range, or the code's. */
    fun configured(tunable: Tunable): Int {
        val said = runCatching { environment.getProperty(tunable.property, Int::class.java) }.getOrNull()
        return said?.takeIf { it in tunable.least..tunable.most } ?: tunable.fallback
    }

    /** Whether an administrator has chosen, as against the file deciding. */
    fun isSet(tunable: Tunable): Boolean = settings.existsById(tunable.key)

    /**
     * Stores [value], or drops what was stored when it is null so the file
     * decides again.
     */
    @Transactional
    fun set(tunable: Tunable, value: Int?, by: String) {
        if (value == null) {
            settings.deleteById(tunable.key)
            return
        }
        if (value !in tunable.least..tunable.most) throw TunableOutOfRangeException(tunable, value)
        val held = settings.findByIdOrNull(tunable.key) ?: InstallationSetting(name = tunable.key)
        held.value = value.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    override fun valueOf(knob: ConnectionKnob): Int = get(MODULE_KNOBS.getValue(knob.key))

    override fun valueOf(knob: ExecutionKnob): Int = get(MODULE_KNOBS.getValue(knob.key))

    private companion object {
        val MODULE_KNOBS: Map<String, Tunable> = Tunable.entries.associateBy { it.key }
    }
}

class TunableUnknownException(val key: String) : RuntimeException(
    "There is no setting called \"$key\".",
), Refusal {
    override val arguments get() = mapOf("key" to key)
}

class TunableOutOfRangeException(val tunable: Tunable, val value: Int) : RuntimeException(
    "$value is outside what \"${tunable.label}\" may be. Choose between ${tunable.least} and ${tunable.most}.",
), Refusal {
    override val arguments get() = mapOf(
        "label" to tunable.label,
        "value" to value,
        "least" to tunable.least,
        "most" to tunable.most,
    )
}

package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.workflow.execution.WorkingZone
import org.springframework.stereotype.Component
import java.time.ZoneId

/** The engine's [WorkingZone]: the working timezone an administrator set, read at the moment it is asked. */
@Component
class InstallationWorkingZone(private val settings: InstallationSettings) : WorkingZone {
    override fun zone(): ZoneId = settings.workingZone()
}

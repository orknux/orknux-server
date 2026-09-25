package io.mszymanski.orknux.server.transfer

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/**
 * The Templates page, and the "Use template" button beside Import.
 *
 * Two audiences and two access rules, and the split is the whole design of this
 * screen. A template is installation-wide: publishing one takes a workspace's
 * work and offers it to workspaces its author may never see, which is an
 * administrator's decision — so creating, replacing and deleting are
 * [WorkspaceAccess.requireAdmin]. *Using* one is not: it creates components in
 * one workspace, exactly as an upload would, so it is checked exactly as the
 * upload is, with the same [WorkspaceAccess.requireVisible] an editor's save
 * goes through. Anybody who could have been handed the file by hand can take it
 * from here instead, which is the point of having the list at all.
 *
 * Reading the list is the third case: signed in, and nothing more. A catalogue
 * that only administrators can see is not published.
 *
 * Note what is *not* here: no second import. [useComponentTemplate] hands the stored
 * envelope to the same [ComponentImporter] an upload goes to, so the renaming,
 * the refusal for a missing variable and the plan-before-you-commit are one
 * implementation rather than two that agree today.
 */
@Controller
class ComponentTemplateAPI(
    private val templates: ComponentTemplateService,
    private val exporter: ComponentExporter,
    private val importer: ComponentImporter,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    /** The catalogue. [holding] is what the button on a page of functions asks. */
    @QueryMapping
    fun componentTemplates(@Argument holding: ComponentKind?): List<ComponentTemplateView> {
        access.requireSignedIn()
        return templates.templates(holding)
    }

    @QueryMapping
    fun componentTemplate(@Argument id: Long): ComponentTemplateView? {
        access.requireSignedIn()
        return templates.template(id)
    }

    /**
     * The stored file, for the Download on a template's page.
     *
     * Administrators only, and it is the one place the raw envelope leaves this
     * installation as a file. Everybody else gets what is inside a template by
     * using it, which lands the components in a workspace they already may write
     * to; handing out the file itself is how a template leaves for another
     * installation, and that is an administrator's business.
     */
    @QueryMapping
    fun componentTemplateEnvelope(@Argument id: Long): String {
        access.requireAdmin()
        return templates.envelopeOf(id)
    }

    /**
     * What using this template on this workspace would do, before it does it.
     *
     * The same plan the upload shows, from the same reader. A query, because it
     * writes nothing.
     */
    @QueryMapping
    fun componentTemplatePlan(
        @Argument workspaceId: Long,
        @Argument templateId: Long,
        @Argument bindings: List<ComponentBinding>?,
        @Argument exclude: List<ComponentExclusion>?,
        @Argument rename: List<ComponentRename>?,
    ): ImportPlan {
        access.requireVisible(workspaceId)
        return importer.plan(workspaceId, templates.envelopeOf(templateId), bindings.orEmpty(), exclude.orEmpty(), rename.orEmpty())
    }

    /** Publishes a file somebody uploaded. Administrators only. */
    @MutationMapping
    fun createComponentTemplate(@Argument input: ComponentTemplateInput): ComponentTemplateView {
        access.requireAdmin()
        val created = templates.create(input.name, input.description, requireEnvelope(input.envelope))
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Template ${created.name} added, holding ${created.componentCount} " +
                if (created.componentCount == 1) "component" else "components",
        )
        return created
    }

    /**
     * Publishes a component the caller is looking at, without the file ever
     * touching a disk.
     *
     * The obvious way to make a template is to export, download, and upload the
     * same bytes back, and that works — this exists because it is three steps
     * for something somebody wants in one, and because the file that goes round
     * that loop is a file that can be edited on the way. Exports through
     * [ComponentExporter], the same call the download makes, so a template made
     * here and a template made from a downloaded file are the same bytes.
     *
     * Both checks, and both are needed: administrator, because publishing is
     * installation-wide, and visible, because it reads a workspace's code.
     */
    @MutationMapping
    fun saveComponentAsTemplate(
        @Argument workspaceId: Long,
        @Argument kind: ComponentKind,
        @Argument id: Long,
        @Argument depth: ExportDepth?,
        @Argument input: ComponentTemplateInput,
    ): ComponentTemplateView {
        access.requireAdmin()
        access.requireVisible(workspaceId)
        val envelope = exporter.export(workspaceId, kind, id, depth ?: ExportDepth.DEEP)
        val created = templates.create(input.name, input.description, envelope)
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Template ${created.name} added from ${kind.indefinite}",
        )
        return created
    }

    /** Renames, rewords or replaces the file. Administrators only. */
    @MutationMapping
    fun updateComponentTemplate(
        @Argument id: Long,
        @Argument input: ComponentTemplateInput,
    ): ComponentTemplateView {
        access.requireAdmin()
        val before = templates.template(id)
        val updated = templates.update(id, input.name, input.description, input.envelope?.ifBlank { null })

        val said = when {
            input.envelope?.isNotBlank() == true -> "Template ${updated.name} replaced with a new export"
            before != null && before.name != updated.name -> "Template ${before.name} renamed to ${updated.name}"
            else -> "Template ${updated.name} updated"
        }
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, said)
        return updated
    }

    /** Takes it off the list. What it already created in workspaces stays. */
    @MutationMapping
    fun deleteComponentTemplate(@Argument id: Long): Boolean {
        access.requireAdmin()
        val name = templates.delete(id) ?: return false
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Template $name removed")
        return true
    }

    /**
     * Creates everything the template holds in this workspace, or nothing.
     *
     * Literally the upload, with the file coming from a row. The importer writes
     * its own audit entries in the target workspace — one per component, saying
     * what arrived and under what name — which is where somebody looking at that
     * workspace would go to find out where a function came from.
     */
    @MutationMapping
    fun useComponentTemplate(
        @Argument workspaceId: Long,
        @Argument templateId: Long,
        @Argument bindings: List<ComponentBinding>?,
        @Argument exclude: List<ComponentExclusion>?,
        @Argument rename: List<ComponentRename>?,
    ): ImportPlan {
        access.requireVisible(workspaceId)
        return importer.apply(workspaceId, templates.envelopeOf(templateId), bindings.orEmpty(), exclude.orEmpty(), rename.orEmpty())
    }

    private fun requireEnvelope(envelope: String?): String =
        envelope?.takeIf { it.isNotBlank() } ?: throw TemplateNameInvalidException("A template needs a file")
}

/**
 * What the New Template and Edit Template forms send.
 *
 * `envelope` is optional on the way in and means two different things depending
 * on where it arrives: required when creating from a file, absent when saving a
 * component the server will export itself, and on an update absent means "leave
 * the stored file alone" — which is what makes fixing a description safe.
 */
data class ComponentTemplateInput(
    val name: String,
    val description: String? = null,
    val envelope: String? = null,
)

@Component
class ComponentTemplateExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is TemplateNotFoundException -> ErrorType.NOT_FOUND
            is TemplateNameTakenException, is TemplateNameInvalidException -> ErrorType.BAD_REQUEST
            else -> return null
        }

        return GraphQLError.newError()
            .errorType(errorType)
            .message(exception.message)
            .path(environment.executionStepInfo.path)
            .location(environment.field.sourceLocation)
            .build()
    }
}

package io.mszymanski.orknux.server.research

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/** Which of the three seeded depths a mode is, or one somebody added. */
enum class ResearchModeKind {
    SHALLOW,
    MEDIUM,
    DEEP,
    CUSTOM,
}

/** The register the report is written in. */
enum class ResearchLanguage {
    ACADEMIC,
    COLLOQUIAL,
    FORMAL,
}

/** What the report is handed over as: a file to open, or a page to read here. */
enum class ResearchOutputFormat {
    PDF,
    DOCUMENT,
}

/** Where a research has got to; the run's own ending, read when it ends. */
enum class ResearchStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    STOPPED,
}

/**
 * How deep a research goes, said as the workflow that does it. Issue #659.
 *
 * A mode is a pointer and nothing else: what a shallow research *does* is its
 * workflow's, which is the workspace's to edit like any other. The three
 * seeded ones are named for what they are and may not be removed; a custom
 * one is a name somebody chose and a workflow they picked.
 */
@Entity
@Table(name = "research_mode")
class ResearchMode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: ResearchModeKind,

    @Column(nullable = false, length = 100)
    var name: String,

    /**
     * The workflow a research in this mode runs. Held by id without a key: a
     * workflow leaves a workspace by its assignment and its definition stays,
     * so one that has gone is said on the page rather than cascaded away.
     */
    @Column(name = "workflow_id")
    var workflowId: Long? = null,

    @Column(nullable = false)
    var position: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * One question researched, and what came of it. Issue #659.
 *
 * The mode, the workflow and the model are written down by name beside their
 * ids, because a list of past research is read long after any of them may
 * have been renamed or removed, and "Deep, with GPT" is what the row has to
 * keep saying.
 */
@Entity
@Table(name = "research")
class Research(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, columnDefinition = "text")
    val question: String,

    @Column(name = "mode_id")
    val modeId: Long? = null,

    @Column(name = "mode_name", nullable = false, length = 100)
    val modeName: String,

    @Column(name = "workflow_id", nullable = false)
    val workflowId: Long,

    @Column(name = "workflow_name", nullable = false, length = 255)
    val workflowName: String,

    @Column(name = "model_id")
    val modelId: Long? = null,

    @Column(name = "model_name", length = 255)
    val modelName: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val language: ResearchLanguage,

    @Enumerated(EnumType.STRING)
    @Column(name = "output_format", nullable = false, length = 16)
    val outputFormat: ResearchOutputFormat,

    /** The websites and documents included and excluded, as [ResearchSources] JSON. */
    @Column(nullable = false, columnDefinition = "text")
    val sources: String,

    /**
     * Whether the research searches the web. Off, it reads only the documents
     * picked and the pages of the websites to keep to, as they were typed.
     */
    @Column(name = "use_web_search", nullable = false)
    val useWebSearch: Boolean = true,

    /**
     * Whether the research looks for images to place in its report and keep
     * beside it. Off unless asked for.
     */
    @Column(name = "include_images", nullable = false)
    val includeImages: Boolean = false,

    /**
     * Whether a second model reads the report before it is saved, and the
     * writer revises it where the reviewer asks. Off unless asked for.
     */
    @Column(nullable = false)
    val review: Boolean = false,

    /** The chat model the reviewer thinks with; null where it is not reviewed. */
    @Column(name = "reviewer_model_id")
    val reviewerModelId: Long? = null,

    /** As the reviewer's model was called when the research started, for the reason the mode's name is kept. */
    @Column(name = "reviewer_model_name", length = 255)
    val reviewerModelName: String? = null,

    /*
     * The agents it was started with, and what each was called then. Issue
     * #678: a research runs with agents, each thinking with the model on its
     * own page, and [modelId] and [reviewerModelId] are the models the Writer
     * and the Reviewer had when it started. Null on a research started before.
     */
    @Column(name = "planner_agent_id")
    val plannerAgentId: Long? = null,

    @Column(name = "planner_agent_name", length = 255)
    val plannerAgentName: String? = null,

    @Column(name = "writer_agent_id")
    val writerAgentId: Long? = null,

    @Column(name = "writer_agent_name", length = 255)
    val writerAgentName: String? = null,

    /** Null where it is not reviewed. */
    @Column(name = "reviewer_agent_id")
    val reviewerAgentId: Long? = null,

    @Column(name = "reviewer_agent_name", length = 255)
    val reviewerAgentName: String? = null,

    /** What each round of review said, as a JSON list of [ResearchReviewRound]; written by the Review step. */
    @Column(name = "review_rounds", columnDefinition = "text")
    var reviewRounds: String? = null,

    /** The questions it suggests asking next, as a JSON list of strings; null until it has finished, or where it asked for none. */
    @Column(name = "follow_ups", columnDefinition = "text")
    var followUps: String? = null,

    /** How many follow-up questions it was asked for when it started; null for one started before there were any. */
    @Column(name = "follow_up_count")
    val followUpCount: Int? = null,

    /** The research it was started from as a follow-up, by id without a key: removing the parent leaves the child. */
    @Column(name = "parent_research_id")
    val parentResearchId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ResearchStatus = ResearchStatus.RUNNING,

    /** The run doing it; null only for the moment between the row and the start. */
    @Column(name = "execution_id")
    var executionId: Long? = null,

    /** The report as the workflow wrote it, in markdown; null until it is finished. */
    @Column(columnDefinition = "text")
    var result: String? = null,

    /** Where it has got to while it runs, as [ResearchProgress] JSON; written by [ResearchPipeline]. */
    @Column(columnDefinition = "text")
    var progress: String? = null,

    /** The sources it read, numbered as the report cites them, as a JSON list of [ResearchSource]. */
    @Column(name = "found_sources", columnDefinition = "text")
    var foundSources: String? = null,

    /** The images it kept, numbered as the report places them, as a JSON list of [ResearchImage]. */
    @Column(name = "found_images", columnDefinition = "text")
    var foundImages: String? = null,

    /** The file the report was saved as, on the Artifacts page; null where it could not be. */
    @Column(name = "artifact_id")
    var artifactId: Long? = null,

    /** Why it did not finish, or why the file could not be made. */
    @Column(columnDefinition = "text")
    var error: String? = null,

    @Column(name = "started_by", nullable = false, length = 255)
    val startedBy: String,

    @Column(name = "started_at", nullable = false)
    val startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    /**
     * The runs it was on before [executionId], oldest first, as a JSON list of
     * ids: a research restarted from one of its steps moves onto the new run
     * and keeps its row, so the page and the tools can still reach the old ones.
     */
    @Column(name = "earlier_runs", columnDefinition = "text")
    var earlierRuns: String? = null,
)

interface ResearchModeRepository : JpaRepository<ResearchMode, Long> {

    fun findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId: Long): List<ResearchMode>

    fun existsByWorkspaceIdAndKindNot(workspaceId: Long, kind: ResearchModeKind): Boolean

    fun existsByWorkspaceIdAndName(workspaceId: Long, name: String): Boolean
}

interface ResearchRepository : JpaRepository<Research, Long> {

    fun findByWorkspaceIdOrderByStartedAtDescIdDesc(workspaceId: Long, pageable: Pageable): Page<Research>

    fun findByExecutionId(executionId: Long): Research?

    /** The workspace's newest research, for what its seeded agents were last run with; see [ResearchModes]. */
    fun findFirstByWorkspaceIdOrderByStartedAtDescIdDesc(workspaceId: Long): Research?

    /** And its newest reviewed one, for the reviewer's. */
    fun findFirstByWorkspaceIdAndReviewerModelIdIsNotNullOrderByStartedAtDescIdDesc(workspaceId: Long): Research?

    /**
     * Writes the run's id on a research that has none yet, without loading and
     * saving the row: the run may end and settle the research on another
     * thread at any moment, and a whole-row save from here would put it back.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Research r SET r.executionId = :executionId WHERE r.id = :id AND r.executionId IS NULL")
    fun attachRun(@Param("id") id: Long, @Param("executionId") executionId: Long): Int

    /**
     * Where a running research has got to, written by the pipeline as it goes.
     * Two columns and no more, for the reason [attachRun] gives: the run's end
     * is written by another thread, and a whole row saved from here would undo it.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Research r SET r.progress = :progress, r.foundSources = :sources WHERE r.id = :id")
    fun noteProgress(@Param("id") id: Long, @Param("progress") progress: String, @Param("sources") sources: String): Int

    /** The images a research has kept, written by Gather as it keeps them, for the reason [attachRun] gives. */
    @Modifying
    @Transactional
    @Query("UPDATE Research r SET r.foundImages = :images WHERE r.id = :id")
    fun noteImages(@Param("id") id: Long, @Param("images") images: String): Int

    /**
     * The file a research's Save step made, or why it could not make one -
     * written as it happens, for the reason [attachRun] gives, and read by
     * [ResearchRuns] at the end so the report is not saved a second time.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Research r SET r.artifactId = :artifactId, r.error = :error WHERE r.id = :id")
    fun noteDelivered(@Param("id") id: Long, @Param("artifactId") artifactId: Long?, @Param("error") error: String?): Int

    /**
     * Moves a research onto a run that restarts one of its earlier ones: running
     * again, with no error, no report and no file, and what it had got to set
     * back to the stage the new run starts at. Columns rather than the row, for
     * the reason [attachRun] gives.
     */
    @Modifying
    @Transactional
    @Query(
        "UPDATE Research r SET r.executionId = :executionId, r.status = :status, r.error = NULL, r.result = NULL, " +
            "r.artifactId = NULL, r.finishedAt = NULL, r.progress = :progress, r.foundSources = :sources, " +
            "r.foundImages = :images, r.earlierRuns = :earlier, r.reviewRounds = :reviewRounds, r.followUps = NULL WHERE r.id = :id",
    )
    fun restarted(
        @Param("id") id: Long,
        @Param("executionId") executionId: Long,
        @Param("status") status: ResearchStatus,
        @Param("progress") progress: String,
        @Param("sources") sources: String,
        @Param("images") images: String?,
        @Param("earlier") earlier: String,
        @Param("reviewRounds") reviewRounds: String?,
    ): Int

    /** The follow-up questions a finished research suggests, written by Save once the report is final. */
    @Modifying
    @Transactional
    @Query("UPDATE Research r SET r.followUps = :followUps WHERE r.id = :id")
    fun noteFollowUps(@Param("id") id: Long, @Param("followUps") followUps: String): Int

    /** The research started from this one as follow-ups, oldest first. */
    fun findByParentResearchIdOrderByStartedAtAscIdAsc(parentResearchId: Long): List<Research>

    /** What the Review step's rounds said so far, written as each one ends, for the reason [attachRun] gives. */
    @Modifying
    @Transactional
    @Query("UPDATE Research r SET r.reviewRounds = :rounds WHERE r.id = :id")
    fun noteReview(@Param("id") id: Long, @Param("rounds") rounds: String): Int

    /** The workspace's finished research in one mode, newest first: what an estimate is measured from. */
    fun findByWorkspaceIdAndModeIdAndStatusAndExecutionIdIsNotNullOrderByFinishedAtDesc(
        workspaceId: Long,
        modeId: Long,
        status: ResearchStatus,
        pageable: Pageable,
    ): List<Research>
}

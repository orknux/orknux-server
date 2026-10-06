package io.mszymanski.orknux.server.agent

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AgentRepository : JpaRepository<Agent, Long> {

    /**
     * The workspace an agent is in, without the agent.
     *
     * What an access check needs, asked before anything else on every screen
     * about one agent. Loading the agent for it reads its ten lists. Issue #616.
     */
    @Query("SELECT a.workspaceId FROM Agent a WHERE a.id = :id")
    fun workspaceIdOf(@Param("id") id: Long): Long?

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<Agent>

    /**
     * The same, narrowed to what a word appears in.
     *
     * The name and the description, which are the two things a row shows that
     * somebody could be remembering. Case-insensitive and a substring rather
     * than a prefix: what people recall of a description is a phrase from the
     * middle of it, not how it opened.
     *
     * Asked of the database rather than sieved in the browser because the list
     * is paged - narrowing what arrived on page one would hide matches sitting
     * on page four and quietly call that "no results".
     */
    @Query(
        """
        SELECT a FROM Agent a
        WHERE a.workspaceId = :workspaceId
          AND (LOWER(a.name) LIKE LOWER(CONCAT('%', :looking, '%'))
            OR LOWER(COALESCE(a.description, '')) LIKE LOWER(CONCAT('%', :looking, '%')))
        """,
    )
    fun searching(
        @Param("workspaceId") workspaceId: Long,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<Agent>

    /**
     * All of them, in an order somebody would recognise.
     *
     * Beside the paged one because two callers want the whole list rather than a
     * screenful of it: the chat that has to open on *some* agent when nobody has
     * chatted here yet, and the name a new agent is given when the one derived
     * from a model is taken. Neither is drawing a page, and asking for one of
     * unbounded size to get a list reads as a page that forgot its size.
     */
    fun findByWorkspaceId(workspaceId: Long, sort: Sort): List<Agent>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): Agent?

    /** However the name was typed: a model asking for an agent has read it, not copied it. */
    @Query("select a from Agent a where a.workspaceId = :workspaceId and lower(a.name) = lower(:name)")
    fun findNamed(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): Agent?

    /**
     * Which of the workspace's agents were granted this tool.
     *
     * Exactly as spelled, not however it was typed, because that is how the
     * grant is read: [WorkspaceToolCaller] looks the name up with
     * `findByWorkspaceIdAndName`, so a grant that differs by a letter's case is
     * already a grant that resolves to nothing and there is nothing here to
     * protect.
     */
    @Query("select a from Agent a join a.tools t where a.workspaceId = :workspaceId and t = :name")
    fun findGrantedTool(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): List<Agent>

    /** Which of the workspace's agents were granted this skill catalog, spelled the same way. */
    @Query("select a from Agent a join a.skillCatalogs c where a.workspaceId = :workspaceId and c = :name")
    fun findGrantedSkillCatalog(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): List<Agent>

    /**
     * Which of the workspace's agents were granted this memory catalog, spelled
     * the same way.
     *
     * Exactly as spelled for the reason the tool grant is: `MemoryTool` keeps
     * the catalogs whose `name` is `in` the granted set, so a grant differing by
     * a letter's case already reads nothing and there is nothing here to protect.
     */
    @Query("select a from Agent a join a.memoryCatalogs c where a.workspaceId = :workspaceId and c = :name")
    fun findGrantedMemoryCatalog(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): List<Agent>

    /**
     * Which of the workspace's agents were granted this MCP server, spelled the
     * same way.
     *
     * Exactly as spelled for the reason the other three are: `McpToolCaller`
     * intersects the granted names with the servers the workspace has, so a
     * grant differing by a letter's case already resolves to nothing.
     *
     * Unlike the other three this is not read to refuse a delete but to undo
     * the grant — see `McpServerAPI.removeMcpServer` — so the agents are what
     * the caller wants, not a sentence naming them.
     */
    @Query("select a from Agent a join a.mcpServers s where a.workspaceId = :workspaceId and s = :name")
    fun findGrantedMcpServer(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): List<Agent>
}

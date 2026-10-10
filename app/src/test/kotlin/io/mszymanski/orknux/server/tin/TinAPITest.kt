package io.mszymanski.orknux.server.tin

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The Tins page, the local tin's Admin settings and an agent's tin labels,
 * through the API they use. Remote tins are a stub on this machine, and an
 * unreachable one is a `.invalid` host, which can never resolve.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TinAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tins: TinRepository,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val local: LocalTin,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val proxyRules: io.mszymanski.orknux.connector.proxy.ProxyRuleRepository,
    @Autowired val router: io.mszymanski.orknux.connector.proxy.ProxyRouter,
    @Autowired val sessions: TinSessionRepository,
    @Autowired val events: TinSessionEventRepository,
    @Autowired val outputs: TinSessionOutputRepository,
    @Autowired val recorder: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
) {

    private val stub = StubOrktin()

    @BeforeEach
    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("tin.") })
        local.refresh()
        tins.deleteAll()
        proxyRules.deleteAll(proxyRules.findAll().filter { it.name == "tins" })
        router.reload()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    @AfterEach
    fun stopStub() = stub.close()

    private val fields = "id name kind label address hasToken createdAt"

    private fun create(name: String, address: String = stub.address, token: String? = "change-me", label: String? = "build"): String =
        graphQlTester.document(
            """mutation(${'$'}input: TinInput!) { createTin(input: ${'$'}input) { id } }""",
        ).variable("input", mapOf("name" to name, "label" to label, "address" to address, "token" to token))
            .execute().path("createTin.id").entity(String::class.java).get()

    private fun refusedWith(document: String, code: String, arguments: Map<String, Any?>? = null) {
        graphQlTester.document(document).execute().errors().satisfy { errors ->
            assertThat(errors).hasSize(1)
            assertThat(errors.single().extensions["code"]).isEqualTo(code)
            if (arguments != null) assertThat(errors.single().extensions["arguments"]).isEqualTo(arguments)
        }
    }

    private fun messages(): List<String> = audit.findAll().map { it.message }

    @Test
    fun `the local tin is listed while it is switched on, and hidden while it is off`() {
        val listed = graphQlTester.document("{ tins { $fields } }").execute()
        listed.path("tins[0].id").entity(String::class.java).isEqualTo("local")
        listed.path("tins[0].name").entity(String::class.java).isEqualTo("local")
        listed.path("tins[0].kind").entity(String::class.java).isEqualTo("LOCAL")
        listed.path("tins[0].label").entity(String::class.java).isEqualTo("local")
        listed.path("tins[0].hasToken").entity(Boolean::class.java).isEqualTo(false)
        listed.path("tins[0].address").valueIsNull()

        graphQlTester.document("mutation { setLocalTinEnabled(enabled: false) { localTinEnabled } }").execute()
            .path("setLocalTinEnabled.localTinEnabled").entity(Boolean::class.java).isEqualTo(false)

        graphQlTester.document("{ tins { id } }").execute().path("tins").entityList(Any::class.java).hasSize(0)
        graphQlTester.document("""{ tin(id: "local") { id } }""").execute().path("tin").valueIsNull()
        refusedWith("""{ tinInfo(id: "local") { name } }""", "TinNotFound", mapOf("id" to "local"))
        assertThat(messages()).contains("The local tin switched off")
    }

    @Test
    fun `a remote tin is added, changed and removed, and each is written down`() {
        val id = create("builder")
        tins.findById(id.toLong()).get().let { assertThat(it.token).isEqualTo("change-me") }

        val listed = graphQlTester.document("{ tins { $fields } }").execute()
        listed.path("tins[*].name").entityList(String::class.java).containsExactly("local", "builder")
        listed.path("tins[1].kind").entity(String::class.java).isEqualTo("REMOTE")
        listed.path("tins[1].address").entity(String::class.java).isEqualTo(stub.address)
        listed.path("tins[1].hasToken").entity(Boolean::class.java).isEqualTo(true)
        listed.path("tins[1].createdAt").hasValue()

        // A null token keeps the one stored.
        graphQlTester.document(
            """mutation { updateTin(id: "$id", input: { name: "runner", label: "ci", address: "${stub.address}/" }) { $fields } }""",
        ).execute().path("updateTin.hasToken").entity(Boolean::class.java).isEqualTo(true)
        tins.findById(id.toLong()).get().let {
            assertThat(it.name).isEqualTo("runner")
            assertThat(it.label).isEqualTo("ci")
            assertThat(it.address).describedAs("stored without the trailing slash").isEqualTo(stub.address)
            assertThat(it.token).isEqualTo("change-me")
        }

        // An empty one clears it.
        graphQlTester.document(
            """mutation { updateTin(id: "$id", input: { name: "runner", label: "ci", token: "" }) { hasToken } }""",
        ).execute().path("updateTin.hasToken").entity(Boolean::class.java).isEqualTo(false)

        graphQlTester.document("""mutation { deleteTin(id: "$id") }""").execute()
            .path("deleteTin").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""mutation { deleteTin(id: "$id") }""").execute()
            .path("deleteTin").entity(Boolean::class.java).isEqualTo(false)

        assertThat(messages()).contains(
            "Tin builder added at ${stub.address}",
            "Tin builder renamed to runner",
            "Tin runner label set to ci",
            "Tin runner token cleared",
            "Tin runner removed",
        )
        // Never the token itself.
        assertThat(messages()).noneMatch { "change-me" in it }
    }

    @Test
    fun `what a tin cannot be is refused with a code`() {
        create("builder")
        refusedWith(
            """mutation { createTin(input: { name: "builder", address: "${stub.address}" }) { id } }""",
            "TinNameTaken", mapOf("name" to "builder"),
        )
        refusedWith(
            """mutation { createTin(input: { name: "Local", address: "${stub.address}" }) { id } }""",
            "TinNameTaken", mapOf("name" to "Local"),
        )
        refusedWith(
            """mutation { createTin(input: { name: "other", address: "ftp://somewhere" }) { id } }""",
            "TinAddressInvalid", mapOf("address" to "ftp://somewhere"),
        )
        refusedWith("""mutation { createTin(input: { name: "other" }) { id } }""", "TinAddressInvalid")
        refusedWith("""mutation { createTin(input: { name: "other", address: "http://other.invalid" }) { id } }""", "TinLabelRequired")
        refusedWith("""mutation { createTin(input: { name: "other", address: "http://other.invalid", label: "  " }) { id } }""", "TinLabelRequired")

        val held = create("revealed", token = "change-me")
        graphQlTester.document("""mutation { revealTinToken(id: "$held") }""").execute()
            .path("revealTinToken").entity(String::class.java).isEqualTo("change-me")
        assertThat(messages()).contains("Token for tin revealed revealed")
        refusedWith("""mutation { updateTin(id: "999999", input: { name: "x" }) { id } }""", "TinNotFound", mapOf("id" to "999999"))
        refusedWith("""mutation { deleteTin(id: "local") }""", "LocalTinNotRemovable")
        refusedWith("""{ tinInfo(id: "999999") { name } }""", "TinNotFound")
    }

    @Test
    fun `the local tin cannot be changed, and its label is always local`() {
        graphQlTester.document(
            """mutation { updateTin(id: "local", input: { name: "local", label: "sandbox" }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).hasSize(1)
            assertThat(errors[0].extensions["code"]).isEqualTo("LocalTinNotEditable")
        }
        graphQlTester.document("""{ tin(id: "local") { label } }""").execute().path("tin.label").entity(String::class.java)
            .isEqualTo("local")
        graphQlTester.document("{ tinLabels }").execute().path("tinLabels").entityList(String::class.java)
            .containsExactly("local")
    }

    @Test
    fun `labels in use are suggested once each, sorted`() {
        create("b", label = "zeta")
        create("c", label = "alpha")
        create("d", label = "zeta")
        graphQlTester.document("{ tinLabels }").execute().path("tinLabels").entityList(String::class.java)
            .containsExactly("alpha", "local", "zeta")
    }

    @Test
    fun `a remote tin is asked how it is, and an unreachable one says so`() {
        val id = create("builder")
        val asked = graphQlTester.document("""{ tin(id: "$id") { health { status detail } activeSessions } }""").execute()
        asked.path("tin.health.status").entity(String::class.java).isEqualTo("UP")
        asked.path("tin.activeSessions").entity(Int::class.java).isEqualTo(2)

        stub.ready = false
        graphQlTester.document("""{ tin(id: "$id") { health { status detail } } }""").execute().also {
            it.path("tin.health.status").entity(String::class.java).isEqualTo("DOWN")
            it.path("tin.health.detail").entity(String::class.java).isEqualTo("docker: daemon not reachable")
        }

        val info = graphQlTester.document(
            """{ tinInfo(id: "$id") { name version defaultRuntime maxSessions runtimes { name os shell defaultCpus maxMemoryMb caches networkModes } } }""",
        ).execute()
        info.path("tinInfo.version").entity(String::class.java).isEqualTo("9.9.9")
        info.path("tinInfo.runtimes[0].shell").entityList(String::class.java).containsExactly("bash", "-c")
        info.path("tinInfo.runtimes[0].defaultCpus").entity(Double::class.java).isEqualTo(2.0)
        info.path("tinInfo.runtimes[0].maxMemoryMb").entity(Int::class.java).isEqualTo(16384)

        graphQlTester.document("""{ tinSessions(id: "$id") { id status labels runningExecutions totalExecutions orknuxSessionId } }""").execute()
            .also {
                it.path("tinSessions[0].id").entity(String::class.java).isEqualTo("ses_1")
                it.path("tinSessions[0].labels").entity(String::class.java).isEqualTo("""{"orknux.agent":"7"}""")
                it.path("tinSessions[0].runningExecutions").entity(Int::class.java).isEqualTo(1)
                // Nobody here opened it.
                it.path("tinSessions[0].orknuxSessionId").valueIsNull()
            }
        assertThat(stub.asked.filter { it.second.startsWith("/v1/") }).allMatch { it.third == "Bearer change-me" }

        val gone = create("gone", address = "http://orktin.invalid:8080")
        graphQlTester.document("""{ tin(id: "$gone") { health { status detail } activeSessions } }""").execute().also {
            it.path("tin.health.status").entity(String::class.java).isEqualTo("DOWN")
            it.path("tin.activeSessions").valueIsNull()
        }
        refusedWith("""{ tinInfo(id: "$gone") { name } }""", "TinUnreachable", mapOf("name" to "gone"))
    }

    /**
     * The Networking rules reach a tin as they reach every outbound call. A rule
     * matching the tin's address carries its requests to the rule's proxy -
     * here a second stub, which answers as orktin would for a host that does
     * not even resolve - and the address guard refuses one it will not call,
     * with its reason, before anything is sent.
     */
    @Test
    fun `a proxy rule carries a tin's requests, and the address guard refuses one it will not call`() {
        val proxy = StubOrktin()
        try {
            val rule = proxyRules.save(
                io.mszymanski.orknux.connector.proxy.ProxyRule(
                    name = "tins", pattern = "orktin-behind-proxy\\.invalid",
                    proxyHost = "127.0.0.1", proxyPort = java.net.URI(proxy.address).port, position = 0,
                ),
            )
            router.reload()
            val routed = create("routed", address = "http://orktin-behind-proxy.invalid:8080")
            graphQlTester.document("""{ tinInfo(id: "$routed") { version } }""").execute()
                .path("tinInfo.version").entity(String::class.java).isEqualTo("9.9.9")
            graphQlTester.document("""{ tin(id: "$routed") { health { status } activeSessions } }""").execute().also {
                it.path("tin.health.status").entity(String::class.java).isEqualTo("UP")
                it.path("tin.activeSessions").entity(Int::class.java).isEqualTo(2)
            }
            graphQlTester.document("""mutation { testTinConnection(address: "http://orktin-behind-proxy.invalid:8080", token: "change-me") { ok } }""")
                .execute().path("testTinConnection.ok").entity(Boolean::class.java).isEqualTo(true)
            // The host does not resolve, so only the proxy could have answered - and it says it did.
            assertThat(proxy.asked.map { it.second }).contains("/v1/info", "/readyz", "/v1/sessions?status=ready&limit=1000")
            proxyRules.delete(rule)
            router.reload()
        } finally {
            proxy.close()
        }

        val metadata = create("metadata", address = "http://169.254.169.254:8080")
        graphQlTester.document("""{ tin(id: "$metadata") { health { status detail } activeSessions } }""").execute().also {
            it.path("tin.health.status").entity(String::class.java).isEqualTo("DOWN")
            it.path("tin.health.detail").entity(String::class.java).satisfies({ d -> assertThat(d).contains("link-local") })
            it.path("tin.activeSessions").valueIsNull()
        }
        graphQlTester.document("""{ tinInfo(id: "$metadata") { version } }""").execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("TinUnreachable")
            assertThat(errors.single().message).contains("link-local")
        }
        graphQlTester.document("""mutation { testTinConnection(address: "http://169.254.169.254:8080", token: "x") { ok message } }""")
            .execute().also {
                it.path("testTinConnection.ok").entity(Boolean::class.java).isEqualTo(false)
                it.path("testTinConnection.message").entity(String::class.java).satisfies({ m -> assertThat(m).contains("Networking") })
            }
    }

    @Test
    fun `a connection is tested with the token given, or the one stored`() {
        graphQlTester.document("""mutation { testTinConnection(address: "${stub.address}", token: "change-me") { ok message version } }""")
            .execute().also {
                it.path("testTinConnection.ok").entity(Boolean::class.java).isEqualTo(true)
                it.path("testTinConnection.version").entity(String::class.java).isEqualTo("9.9.9")
            }
        graphQlTester.document("""mutation { testTinConnection(address: "${stub.address}", token: "wrong") { ok message } }""")
            .execute().also {
                it.path("testTinConnection.ok").entity(Boolean::class.java).isEqualTo(false)
                it.path("testTinConnection.message").entity(String::class.java).satisfies({ m -> assertThat(m).contains("token") })
            }
        val id = create("builder")
        graphQlTester.document("""mutation { testTinConnection(address: "${stub.address}", id: "$id") { ok } }""")
            .execute().path("testTinConnection.ok").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""mutation { testTinConnection(address: "http://orktin.invalid", token: "x") { ok message } }""")
            .execute().path("testTinConnection.ok").entity(Boolean::class.java).isEqualTo(false)
        refusedWith("""mutation { testTinConnection(address: "not a url") { ok } }""", "TinAddressInvalid")
    }

    @Test
    fun `every setting reads its default, takes a value in range and refuses one outside it`() {
        val defaults = graphQlTester.document(
            """{ installationSettings {
                localTinEnabled localTinLocation localTinLocationConfigured localTinRuntime
                localTinMaxSessions localTinMaxSessionsConfigured localTinDefaultTtlSeconds localTinDefaultTtlSecondsConfigured
                localTinMaxTtlSeconds localTinMaxTtlSecondsConfigured localTinIdleTimeoutSeconds localTinIdleTimeoutSecondsConfigured
                tinCommandWaitSeconds tinCommandWaitSecondsConfigured tinCommandTimeoutSeconds tinCommandTimeoutSecondsConfigured
                tinOutputBytes tinOutputBytesConfigured tinRequestTimeoutSeconds tinRequestTimeoutSecondsConfigured
                localTinOutputKeptBytes localTinOutputKeptBytesConfigured tinOutputShownBytes tinOutputShownBytesConfigured } }""",
        ).execute()
        defaults.path("installationSettings.localTinEnabled").entity(Boolean::class.java).isEqualTo(true)
        defaults.path("installationSettings.localTinLocation").valueIsNull()
        defaults.path("installationSettings.localTinLocationConfigured").entity(String::class.java)
            .satisfies({ assertThat(it).endsWith("orknux-tin") })
        defaults.path("installationSettings.localTinRuntime").entity(String::class.java).isEqualTo("local")
        mapOf(
            "localTinMaxSessions" to 50, "localTinDefaultTtlSeconds" to 7200, "localTinMaxTtlSeconds" to 86400,
            "localTinIdleTimeoutSeconds" to 900, "tinCommandWaitSeconds" to 300, "tinCommandTimeoutSeconds" to 1800,
            "tinOutputBytes" to 65536, "tinRequestTimeoutSeconds" to 30, "localTinOutputKeptBytes" to 1048576,
            "tinOutputShownBytes" to 4194304,
        ).forEach { (field, value) ->
            defaults.path("installationSettings.$field").entity(Int::class.java).isEqualTo(value)
            defaults.path("installationSettings.${field}Configured").entity(Int::class.java).isEqualTo(value)
        }

        data class Knob(val mutation: String, val argument: String, val field: String, val good: Int, val low: Int, val high: Int, val code: String)
        listOf(
            Knob("setLocalTinMaxSessions", "count", "localTinMaxSessions", 7, 0, 1001, "LocalTinMaxSessionsOutOfRange"),
            Knob("setLocalTinDefaultTtlSeconds", "seconds", "localTinDefaultTtlSeconds", 600, 59, 604801, "LocalTinTtlOutOfRange"),
            Knob("setLocalTinMaxTtlSeconds", "seconds", "localTinMaxTtlSeconds", 3600, 59, 604801, "LocalTinTtlOutOfRange"),
            Knob("setLocalTinIdleTimeoutSeconds", "seconds", "localTinIdleTimeoutSeconds", 120, 59, 86401, "LocalTinIdleTimeoutOutOfRange"),
            Knob("setTinCommandWaitSeconds", "seconds", "tinCommandWaitSeconds", 10, 0, 3601, "TinCommandWaitOutOfRange"),
            Knob("setTinCommandTimeoutSeconds", "seconds", "tinCommandTimeoutSeconds", 20, 0, 86401, "TinCommandTimeoutOutOfRange"),
            Knob("setTinOutputBytes", "bytes", "tinOutputBytes", 2048, 1023, 4194305, "TinOutputBytesOutOfRange"),
            Knob("setLocalTinOutputKeptBytes", "bytes", "localTinOutputKeptBytes", 0, -1, 16777217, "LocalTinOutputKeptOutOfRange"),
            Knob("setTinOutputShownBytes", "bytes", "tinOutputShownBytes", 65536, 65535, 67108865, "TinOutputShownOutOfRange"),
            Knob("setTinRequestTimeoutSeconds", "seconds", "tinRequestTimeoutSeconds", 5, 0, 601, "TinRequestTimeoutOutOfRange"),
        ).forEach { knob ->
            graphQlTester.document("mutation { ${knob.mutation}(${knob.argument}: ${knob.good}) { ${knob.field} ${knob.field}Configured } }")
                .execute().also {
                    it.path("${knob.mutation}.${knob.field}").entity(Int::class.java).isEqualTo(knob.good)
                }
            refusedWith("mutation { ${knob.mutation}(${knob.argument}: ${knob.low}) { ${knob.field} } }", knob.code, mapOf(knob.argument to knob.low))
            refusedWith("mutation { ${knob.mutation}(${knob.argument}: ${knob.high}) { ${knob.field} } }", knob.code, mapOf(knob.argument to knob.high))
        }

        graphQlTester.document("""mutation { setLocalTinRuntime(runtime: "docker") { localTinRuntime } }""").execute()
            .path("setLocalTinRuntime.localTinRuntime").entity(String::class.java).isEqualTo("docker")
        refusedWith("""mutation { setLocalTinRuntime(runtime: "firecracker") { localTinRuntime } }""", "LocalTinRuntimeUnknown", mapOf("runtime" to "firecracker"))

        graphQlTester.document("""mutation { setLocalTinLocation(location: "/srv/tins") { localTinLocation } }""").execute()
            .path("setLocalTinLocation.localTinLocation").entity(String::class.java).isEqualTo("/srv/tins")
        graphQlTester.document("""mutation { setLocalTinLocation(location: "") { localTinLocation } }""").execute()
            .path("setLocalTinLocation.localTinLocation").valueIsNull()

        assertThat(messages()).contains(
            "The local tin holds up to 7 sessions at once",
            "The local tin runs sessions with the docker runtime",
            "The local tin makes its sessions in /srv/tins",
            "A request to a tin is given up on after 5 seconds",
            "A local tin command's output is not kept",
        )
    }

    @Test
    fun `an agent's tin labels are set, cleared and written down`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "tins")).id)
        val agent = requireNotNull(agents.save(Agent(workspaceId = workspace, name = "Builder", type = AgentType.LLM)).id)
        graphQlTester.document("{ agent(id: $agent) { tinLabels } }").execute()
            .path("agent.tinLabels").entityList(String::class.java).hasSize(0)

        graphQlTester.document(
            """mutation { updateAgent(id: $agent, input: { name: "Builder", tinLabels: [" local ", "ci", "local", ""] }) { tinLabels } }""",
        ).execute().path("updateAgent.tinLabels").entityList(String::class.java).containsExactly("local", "ci")
        assertThat(agents.findById(agent).get().tinLabelsText).isEqualTo("local\nci")

        // Left alone when not sent.
        graphQlTester.document("""mutation { updateAgent(id: $agent, input: { name: "Builder" }) { tinLabels } }""")
            .execute().path("updateAgent.tinLabels").entityList(String::class.java).containsExactly("local", "ci")

        graphQlTester.document("""mutation { updateAgent(id: $agent, input: { name: "Builder", tinLabels: [] }) { tinLabels } }""")
            .execute().path("updateAgent.tinLabels").entityList(String::class.java).hasSize(0)

        graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspace, name: "Second", type: LLM, tinLabels: ["local"] }) { tinLabels } }""",
        ).execute().path("createAgent.tinLabels").entityList(String::class.java).containsExactly("local")

        assertThat(messages()).contains(
            "Agent Builder tin labels set to local, ci",
            "Agent Builder tin labels cleared",
            "Agent Second tin labels set to local",
        )
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `only an administrator sees or changes the tins`() {
        refusedWith("{ tins { id } }", "AdminRequired")
        refusedWith("""{ tin(id: "local") { id } }""", "AdminRequired")
        refusedWith("""mutation { createTin(input: { name: "x", address: "http://x" }) { id } }""", "AdminRequired")
        refusedWith("""mutation { deleteTin(id: "1") }""", "AdminRequired")
        refusedWith("mutation { setTinOutputBytes(bytes: 2048) { tinOutputBytes } }", "AdminRequired")
        refusedWith("""mutation { testTinConnection(address: "http://x") { ok } }""", "AdminRequired")
        // The suggestions an agent's form makes are anybody's.
        graphQlTester.document("{ tinLabels }").execute().path("tinLabels").entityList(String::class.java).containsExactly("local")
    }

    @Test
    fun `a remote tin's log and output are read live and nothing of them is kept here`() {
        val tinId = create("builder").toLong()
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "remote")).id)
        val conversation = recorder.open(workspaceId, "chat", "build")
        fun session(remote: String) = requireNotNull(
            sessions.save(
                TinSession(
                    workspaceId = workspaceId,
                    llmSessionId = conversation,
                    tinKind = TinKind.REMOTE,
                    tinId = tinId,
                    tinName = "builder",
                    agentName = "Builder",
                    remoteSessionId = remote,
                    status = "ready",
                ),
            ).id,
        )
        val held = session("ses_1")
        val gone = session("ses_gone")
        fun log(id: Long) = graphQlTester.document(
            "{ sessionTinLog(sessionId: $conversation, sessionTinId: $id) { events { seq type } nextSeq available message } }",
        ).execute()
        fun output(id: Long, offset: Int = 0) = graphQlTester.document(
            """{ sessionTinOutput(sessionId: $conversation, sessionTinId: $id, executionId: "exe_1", offset: $offset) """ +
                "{ text offset nextOffset done available message shownLimit } }",
        ).execute()

        log(held).also {
            it.path("sessionTinLog.events[*].type").entityList(String::class.java).containsExactly("session.created", "execution.finished")
            it.path("sessionTinLog.nextSeq").entity(Int::class.java).isEqualTo(2)
            it.path("sessionTinLog.available").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionTinLog.message").valueIsNull()
        }
        output(held).also {
            it.path("sessionTinOutput.text").entity(String::class.java).isEqualTo(StubOrktin.OUTPUT)
            it.path("sessionTinOutput.nextOffset").entity(Int::class.java).isEqualTo(StubOrktin.OUTPUT.length)
            it.path("sessionTinOutput.done").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionTinOutput.available").entity(Boolean::class.java).isEqualTo(true)
        }
        output(held).path("sessionTinOutput.shownLimit").entity(Int::class.java).isEqualTo(DEFAULT_TIN_OUTPUT_SHOWN)
        output(gone).path("sessionTinOutput.shownLimit").entity(Int::class.java).isEqualTo(DEFAULT_TIN_OUTPUT_SHOWN)
        output(held, 3).path("sessionTinOutput.text").entity(String::class.java).isEqualTo(StubOrktin.OUTPUT.substring(3))
        assertThat(events.findAll().filter { it.tinSessionId == held }).isEmpty()
        assertThat(outputs.findAll().filter { it.tinSessionId == held }).isEmpty()

        log(gone).also {
            it.path("sessionTinLog.available").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionTinLog.message").entity(String::class.java).isEqualTo("Tin builder no longer has this session")
        }
        output(gone).also {
            it.path("sessionTinOutput.available").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionTinOutput.message").entity(String::class.java).isEqualTo("Tin builder no longer has this session")
        }

        // The Tins page lists what was recorded on it, with the tin's word
        // where it has one - ses_gone it no longer lists, so it is stopped.
        graphQlTester.document(
            """{ tinSessions(id: "$tinId") { id status active runtime orknuxSessionId agentName } }""",
        ).execute().path("tinSessions").entityList(Map::class.java).get().also { listed ->
            assertThat(listed.map { it["id"] }).containsExactlyInAnyOrder("ses_1", "ses_gone")
            val live = listed.single { it["id"] == "ses_1" }
            assertThat(live["status"]).isEqualTo("ready")
            assertThat(live["active"]).isEqualTo(true)
            assertThat(live["runtime"]).isEqualTo("docker")
            assertThat(live["orknuxSessionId"]).isEqualTo(conversation.toString())
            assertThat(live["agentName"]).isEqualTo("Builder")
            val stopped = listed.single { it["id"] == "ses_gone" }
            assertThat(stopped["status"]).isEqualTo("stopped")
            assertThat(stopped["active"]).isEqualTo(false)
            assertThat(stopped["runtime"]).isNull()
        }
        graphQlTester.document("{ sessionTins(sessionId: $conversation) { tinLabel } }").execute()
            .path("sessionTins[*].tinLabel").entityList(String::class.java).containsExactly("build", "build")

        graphQlTester.document("""mutation { deleteTin(id: "$tinId") }""").execute()
        graphQlTester.document("{ sessionTins(sessionId: $conversation) { tinLabel } }").execute()
            .path("sessionTins[0].tinLabel").valueIsNull()
        log(held).path("sessionTinLog.message").entity(String::class.java).isEqualTo("Tin builder is no longer configured")
        output(held).path("sessionTinOutput.available").entity(Boolean::class.java).isEqualTo(false)
    }
}

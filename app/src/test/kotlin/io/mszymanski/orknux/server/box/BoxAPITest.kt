package io.mszymanski.orknux.server.box

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
 * The Boxes page, the local box's Admin settings and an agent's box labels,
 * through the API they use. Remote boxes are a stub on this machine, and an
 * unreachable one is a `.invalid` host, which can never resolve.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BoxAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val boxes: BoxRepository,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val local: LocalBox,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val proxyRules: io.mszymanski.orknux.connector.proxy.ProxyRuleRepository,
    @Autowired val router: io.mszymanski.orknux.connector.proxy.ProxyRouter,
    @Autowired val sessions: BoxSessionRepository,
    @Autowired val events: BoxSessionEventRepository,
    @Autowired val outputs: BoxSessionOutputRepository,
    @Autowired val recorder: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
) {

    private val stub = StubOrkbox()

    @BeforeEach
    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("tin.") })
        local.refresh()
        boxes.deleteAll()
        proxyRules.deleteAll(proxyRules.findAll().filter { it.name == "boxes" })
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
            """mutation(${'$'}input: BoxInput!) { createBox(input: ${'$'}input) { id } }""",
        ).variable("input", mapOf("name" to name, "label" to label, "address" to address, "token" to token))
            .execute().path("createBox.id").entity(String::class.java).get()

    private fun refusedWith(document: String, code: String, arguments: Map<String, Any?>? = null) {
        graphQlTester.document(document).execute().errors().satisfy { errors ->
            assertThat(errors).hasSize(1)
            assertThat(errors.single().extensions["code"]).isEqualTo(code)
            if (arguments != null) assertThat(errors.single().extensions["arguments"]).isEqualTo(arguments)
        }
    }

    private fun messages(): List<String> = audit.findAll().map { it.message }

    @Test
    fun `the local box is listed while it is switched on, and hidden while it is off`() {
        val listed = graphQlTester.document("{ boxes { $fields } }").execute()
        listed.path("boxes[0].id").entity(String::class.java).isEqualTo("local")
        listed.path("boxes[0].name").entity(String::class.java).isEqualTo("local")
        listed.path("boxes[0].kind").entity(String::class.java).isEqualTo("LOCAL")
        listed.path("boxes[0].label").entity(String::class.java).isEqualTo("local")
        listed.path("boxes[0].hasToken").entity(Boolean::class.java).isEqualTo(false)
        listed.path("boxes[0].address").valueIsNull()

        graphQlTester.document("mutation { setLocalBoxEnabled(enabled: false) { localBoxEnabled } }").execute()
            .path("setLocalBoxEnabled.localBoxEnabled").entity(Boolean::class.java).isEqualTo(false)

        graphQlTester.document("{ boxes { id } }").execute().path("boxes").entityList(Any::class.java).hasSize(0)
        graphQlTester.document("""{ box(id: "local") { id } }""").execute().path("box").valueIsNull()
        refusedWith("""{ boxInfo(id: "local") { name } }""", "BoxNotFound", mapOf("id" to "local"))
        assertThat(messages()).contains("The local box switched off")
    }

    @Test
    fun `a remote box is added, changed and removed, and each is written down`() {
        val id = create("builder")
        boxes.findById(id.toLong()).get().let { assertThat(it.token).isEqualTo("change-me") }

        val listed = graphQlTester.document("{ boxes { $fields } }").execute()
        listed.path("boxes[*].name").entityList(String::class.java).containsExactly("local", "builder")
        listed.path("boxes[1].kind").entity(String::class.java).isEqualTo("REMOTE")
        listed.path("boxes[1].address").entity(String::class.java).isEqualTo(stub.address)
        listed.path("boxes[1].hasToken").entity(Boolean::class.java).isEqualTo(true)
        listed.path("boxes[1].createdAt").hasValue()

        // A null token keeps the one stored.
        graphQlTester.document(
            """mutation { updateBox(id: "$id", input: { name: "runner", label: "ci", address: "${stub.address}/" }) { $fields } }""",
        ).execute().path("updateBox.hasToken").entity(Boolean::class.java).isEqualTo(true)
        boxes.findById(id.toLong()).get().let {
            assertThat(it.name).isEqualTo("runner")
            assertThat(it.label).isEqualTo("ci")
            assertThat(it.address).describedAs("stored without the trailing slash").isEqualTo(stub.address)
            assertThat(it.token).isEqualTo("change-me")
        }

        // An empty one clears it.
        graphQlTester.document(
            """mutation { updateBox(id: "$id", input: { name: "runner", label: "ci", token: "" }) { hasToken } }""",
        ).execute().path("updateBox.hasToken").entity(Boolean::class.java).isEqualTo(false)

        graphQlTester.document("""mutation { deleteBox(id: "$id") }""").execute()
            .path("deleteBox").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""mutation { deleteBox(id: "$id") }""").execute()
            .path("deleteBox").entity(Boolean::class.java).isEqualTo(false)

        assertThat(messages()).contains(
            "Box builder added at ${stub.address}",
            "Box builder renamed to runner",
            "Box runner label set to ci",
            "Box runner token cleared",
            "Box runner removed",
        )
        // Never the token itself.
        assertThat(messages()).noneMatch { "change-me" in it }
    }

    @Test
    fun `what a box cannot be is refused with a code`() {
        create("builder")
        refusedWith(
            """mutation { createBox(input: { name: "builder", address: "${stub.address}" }) { id } }""",
            "BoxNameTaken", mapOf("name" to "builder"),
        )
        refusedWith(
            """mutation { createBox(input: { name: "Local", address: "${stub.address}" }) { id } }""",
            "BoxNameTaken", mapOf("name" to "Local"),
        )
        refusedWith(
            """mutation { createBox(input: { name: "other", address: "ftp://somewhere" }) { id } }""",
            "BoxAddressInvalid", mapOf("address" to "ftp://somewhere"),
        )
        refusedWith("""mutation { createBox(input: { name: "other" }) { id } }""", "BoxAddressInvalid")
        refusedWith("""mutation { createBox(input: { name: "other", address: "http://other.invalid" }) { id } }""", "BoxLabelRequired")
        refusedWith("""mutation { createBox(input: { name: "other", address: "http://other.invalid", label: "  " }) { id } }""", "BoxLabelRequired")

        val held = create("revealed", token = "change-me")
        graphQlTester.document("""mutation { revealBoxToken(id: "$held") }""").execute()
            .path("revealBoxToken").entity(String::class.java).isEqualTo("change-me")
        assertThat(messages()).contains("Token for box revealed revealed")
        refusedWith("""mutation { updateBox(id: "999999", input: { name: "x" }) { id } }""", "BoxNotFound", mapOf("id" to "999999"))
        refusedWith("""mutation { deleteBox(id: "local") }""", "LocalBoxNotRemovable")
        refusedWith("""{ boxInfo(id: "999999") { name } }""", "BoxNotFound")
    }

    @Test
    fun `the local box cannot be changed, and its label is always local`() {
        graphQlTester.document(
            """mutation { updateBox(id: "local", input: { name: "local", label: "sandbox" }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).hasSize(1)
            assertThat(errors[0].extensions["code"]).isEqualTo("LocalBoxNotEditable")
        }
        graphQlTester.document("""{ box(id: "local") { label } }""").execute().path("box.label").entity(String::class.java)
            .isEqualTo("local")
        graphQlTester.document("{ boxLabels }").execute().path("boxLabels").entityList(String::class.java)
            .containsExactly("local")
    }

    @Test
    fun `labels in use are suggested once each, sorted`() {
        create("b", label = "zeta")
        create("c", label = "alpha")
        create("d", label = "zeta")
        graphQlTester.document("{ boxLabels }").execute().path("boxLabels").entityList(String::class.java)
            .containsExactly("alpha", "local", "zeta")
    }

    @Test
    fun `a remote box is asked how it is, and an unreachable one says so`() {
        val id = create("builder")
        val asked = graphQlTester.document("""{ box(id: "$id") { health { status detail } activeSessions capacity { active busy idle max free } } }""").execute()
        asked.path("box.health.status").entity(String::class.java).isEqualTo("UP")
        asked.path("box.activeSessions").entity(Int::class.java).isEqualTo(2)
        asked.path("box.capacity.active").entity(Int::class.java).isEqualTo(2)
        // Each stub session has a command running: working, not waiting.
        asked.path("box.capacity.busy").entity(Int::class.java).isEqualTo(2)
        asked.path("box.capacity.idle").entity(Int::class.java).isEqualTo(0)
        asked.path("box.capacity.max").entity(Int::class.java).isEqualTo(50)
        asked.path("box.capacity.free").entity(Int::class.java).isEqualTo(48)

        stub.ready = false
        graphQlTester.document("""{ box(id: "$id") { health { status detail } } }""").execute().also {
            it.path("box.health.status").entity(String::class.java).isEqualTo("DOWN")
            it.path("box.health.detail").entity(String::class.java).isEqualTo("docker: daemon not reachable")
        }

        val info = graphQlTester.document(
            """{ boxInfo(id: "$id") { name version defaultRuntime maxSessions runtimes { name os shell defaultCpus maxMemoryMb caches networkModes } } }""",
        ).execute()
        info.path("boxInfo.version").entity(String::class.java).isEqualTo("9.9.9")
        info.path("boxInfo.runtimes[0].shell").entityList(String::class.java).containsExactly("bash", "-c")
        info.path("boxInfo.runtimes[0].defaultCpus").entity(Double::class.java).isEqualTo(2.0)
        info.path("boxInfo.runtimes[0].maxMemoryMb").entity(Int::class.java).isEqualTo(16384)

        graphQlTester.document("""{ boxSessions(id: "$id") { id status labels runningExecutions totalExecutions orknuxSessionId } }""").execute()
            .also {
                it.path("boxSessions[0].id").entity(String::class.java).isEqualTo("ses_1")
                it.path("boxSessions[0].labels").entity(String::class.java).isEqualTo("""{"orknux.agent":"7"}""")
                it.path("boxSessions[0].runningExecutions").entity(Int::class.java).isEqualTo(1)
                // Nobody here opened it.
                it.path("boxSessions[0].orknuxSessionId").valueIsNull()
            }
        assertThat(stub.asked.filter { it.second.startsWith("/v1/") }).allMatch { it.third == "Bearer change-me" }

        val gone = create("gone", address = "http://orkbox.invalid:8080")
        graphQlTester.document("""{ box(id: "$gone") { health { status detail } activeSessions capacity { free } } }""").execute().also {
            it.path("box.health.status").entity(String::class.java).isEqualTo("DOWN")
            it.path("box.activeSessions").valueIsNull()
            it.path("box.capacity").valueIsNull()
        }
        refusedWith("""{ boxInfo(id: "$gone") { name } }""", "BoxUnreachable", mapOf("name" to "gone"))
    }

    /**
     * The Networking rules reach a box as they reach every outbound call. A rule
     * matching the box's address carries its requests to the rule's proxy -
     * here a second stub, which answers as orkbox would for a host that does
     * not even resolve - and the address guard refuses one it will not call,
     * with its reason, before anything is sent.
     */
    @Test
    fun `a proxy rule carries a box's requests, and the address guard refuses one it will not call`() {
        val proxy = StubOrkbox()
        try {
            val rule = proxyRules.save(
                io.mszymanski.orknux.connector.proxy.ProxyRule(
                    name = "boxes", pattern = "orkbox-behind-proxy\\.invalid",
                    proxyHost = "127.0.0.1", proxyPort = java.net.URI(proxy.address).port, position = 0,
                ),
            )
            router.reload()
            val routed = create("routed", address = "http://orkbox-behind-proxy.invalid:8080")
            graphQlTester.document("""{ boxInfo(id: "$routed") { version } }""").execute()
                .path("boxInfo.version").entity(String::class.java).isEqualTo("9.9.9")
            graphQlTester.document("""{ box(id: "$routed") { health { status } activeSessions } }""").execute().also {
                it.path("box.health.status").entity(String::class.java).isEqualTo("UP")
                it.path("box.activeSessions").entity(Int::class.java).isEqualTo(2)
            }
            graphQlTester.document("""mutation { testBoxConnection(address: "http://orkbox-behind-proxy.invalid:8080", token: "change-me") { ok } }""")
                .execute().path("testBoxConnection.ok").entity(Boolean::class.java).isEqualTo(true)
            // The host does not resolve, so only the proxy could have answered - and it says it did.
            assertThat(proxy.asked.map { it.second }).contains("/v1/info", "/readyz", "/v1/sessions?status=ready&limit=1000")
            proxyRules.delete(rule)
            router.reload()
        } finally {
            proxy.close()
        }

        val metadata = create("metadata", address = "http://169.254.169.254:8080")
        graphQlTester.document("""{ box(id: "$metadata") { health { status detail } activeSessions } }""").execute().also {
            it.path("box.health.status").entity(String::class.java).isEqualTo("DOWN")
            it.path("box.health.detail").entity(String::class.java).satisfies({ d -> assertThat(d).contains("link-local") })
            it.path("box.activeSessions").valueIsNull()
        }
        graphQlTester.document("""{ boxInfo(id: "$metadata") { version } }""").execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("BoxUnreachable")
            assertThat(errors.single().message).contains("link-local")
        }
        graphQlTester.document("""mutation { testBoxConnection(address: "http://169.254.169.254:8080", token: "x") { ok message } }""")
            .execute().also {
                it.path("testBoxConnection.ok").entity(Boolean::class.java).isEqualTo(false)
                it.path("testBoxConnection.message").entity(String::class.java).satisfies({ m -> assertThat(m).contains("Networking") })
            }
    }

    @Test
    fun `a connection is tested with the token given, or the one stored`() {
        graphQlTester.document("""mutation { testBoxConnection(address: "${stub.address}", token: "change-me") { ok message version } }""")
            .execute().also {
                it.path("testBoxConnection.ok").entity(Boolean::class.java).isEqualTo(true)
                it.path("testBoxConnection.version").entity(String::class.java).isEqualTo("9.9.9")
            }
        graphQlTester.document("""mutation { testBoxConnection(address: "${stub.address}", token: "wrong") { ok message } }""")
            .execute().also {
                it.path("testBoxConnection.ok").entity(Boolean::class.java).isEqualTo(false)
                it.path("testBoxConnection.message").entity(String::class.java).satisfies({ m -> assertThat(m).contains("token") })
            }
        val id = create("builder")
        graphQlTester.document("""mutation { testBoxConnection(address: "${stub.address}", id: "$id") { ok } }""")
            .execute().path("testBoxConnection.ok").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""mutation { testBoxConnection(address: "http://orkbox.invalid", token: "x") { ok message } }""")
            .execute().path("testBoxConnection.ok").entity(Boolean::class.java).isEqualTo(false)
        refusedWith("""mutation { testBoxConnection(address: "not a url") { ok } }""", "BoxAddressInvalid")
    }

    @Test
    fun `every setting reads its default, takes a value in range and refuses one outside it`() {
        val defaults = graphQlTester.document(
            """{ installationSettings {
                localBoxEnabled localBoxLocation localBoxLocationConfigured localBoxRuntime
                localBoxMaxSessions localBoxMaxSessionsConfigured localBoxDefaultTtlSeconds localBoxDefaultTtlSecondsConfigured
                localBoxMaxTtlSeconds localBoxMaxTtlSecondsConfigured localBoxIdleTimeoutSeconds localBoxIdleTimeoutSecondsConfigured
                boxCommandWaitSeconds boxCommandWaitSecondsConfigured boxCommandTimeoutSeconds boxCommandTimeoutSecondsConfigured
                boxOutputBytes boxOutputBytesConfigured boxRequestTimeoutSeconds boxRequestTimeoutSecondsConfigured
                localBoxOutputKeptBytes localBoxOutputKeptBytesConfigured boxOutputShownBytes boxOutputShownBytesConfigured
                boxEditUndoSteps boxEditUndoStepsConfigured boxMaxPorts boxMaxPortsConfigured
                boxPortCheckSeconds boxPortCheckSecondsConfigured boxPortCheckWaitMillis boxPortCheckWaitMillisConfigured
                boxSessionIdleSeconds boxSessionIdleSecondsConfigured } }""",
        ).execute()
        defaults.path("installationSettings.localBoxEnabled").entity(Boolean::class.java).isEqualTo(true)
        defaults.path("installationSettings.localBoxLocation").valueIsNull()
        defaults.path("installationSettings.localBoxLocationConfigured").entity(String::class.java)
            .satisfies({ assertThat(it).endsWith("orknux-box") })
        defaults.path("installationSettings.localBoxRuntime").entity(String::class.java).isEqualTo("local")
        mapOf(
            "localBoxMaxSessions" to 50, "localBoxDefaultTtlSeconds" to 7200, "localBoxMaxTtlSeconds" to 86400,
            "localBoxIdleTimeoutSeconds" to 900, "boxCommandWaitSeconds" to 300, "boxCommandTimeoutSeconds" to 1800,
            "boxOutputBytes" to 65536, "boxRequestTimeoutSeconds" to 30, "localBoxOutputKeptBytes" to 1048576,
            "boxOutputShownBytes" to 4194304, "boxEditUndoSteps" to 10, "boxMaxPorts" to 5,
            "boxPortCheckSeconds" to 15, "boxPortCheckWaitMillis" to 1500, "boxSessionIdleSeconds" to 7200,
        ).forEach { (field, value) ->
            defaults.path("installationSettings.$field").entity(Int::class.java).isEqualTo(value)
            defaults.path("installationSettings.${field}Configured").entity(Int::class.java).isEqualTo(value)
        }

        data class Knob(val mutation: String, val argument: String, val field: String, val good: Int, val low: Int, val high: Int, val code: String)
        listOf(
            Knob("setLocalBoxMaxSessions", "count", "localBoxMaxSessions", 7, 0, 1001, "LocalBoxMaxSessionsOutOfRange"),
            Knob("setLocalBoxDefaultTtlSeconds", "seconds", "localBoxDefaultTtlSeconds", 600, 59, 604801, "LocalBoxTtlOutOfRange"),
            Knob("setLocalBoxMaxTtlSeconds", "seconds", "localBoxMaxTtlSeconds", 3600, 59, 604801, "LocalBoxTtlOutOfRange"),
            Knob("setLocalBoxIdleTimeoutSeconds", "seconds", "localBoxIdleTimeoutSeconds", 120, 59, 86401, "LocalBoxIdleTimeoutOutOfRange"),
            Knob("setBoxCommandWaitSeconds", "seconds", "boxCommandWaitSeconds", 10, 0, 3601, "BoxCommandWaitOutOfRange"),
            Knob("setBoxCommandTimeoutSeconds", "seconds", "boxCommandTimeoutSeconds", 20, 0, 86401, "BoxCommandTimeoutOutOfRange"),
            Knob("setBoxOutputBytes", "bytes", "boxOutputBytes", 2048, 1023, 4194305, "BoxOutputBytesOutOfRange"),
            Knob("setLocalBoxOutputKeptBytes", "bytes", "localBoxOutputKeptBytes", 0, -1, 16777217, "LocalBoxOutputKeptOutOfRange"),
            Knob("setBoxOutputShownBytes", "bytes", "boxOutputShownBytes", 65536, 65535, 67108865, "BoxOutputShownOutOfRange"),
            Knob("setBoxRequestTimeoutSeconds", "seconds", "boxRequestTimeoutSeconds", 5, 0, 601, "BoxRequestTimeoutOutOfRange"),
            Knob("setBoxEditUndoSteps", "steps", "boxEditUndoSteps", 0, -1, 101, "BoxEditUndoStepsOutOfRange"),
            Knob("setBoxMaxPorts", "count", "boxMaxPorts", 0, -1, 21, "BoxMaxPortsOutOfRange"),
            Knob("setBoxPortCheckSeconds", "seconds", "boxPortCheckSeconds", 30, 1, 601, "BoxPortCheckOutOfRange"),
            Knob("setBoxPortCheckWaitMillis", "millis", "boxPortCheckWaitMillis", 500, 99, 10001, "BoxPortCheckWaitOutOfRange"),
            Knob("setBoxSessionIdleSeconds", "seconds", "boxSessionIdleSeconds", 600, 59, 86401, "BoxSessionIdleOutOfRange"),
        ).forEach { knob ->
            graphQlTester.document("mutation { ${knob.mutation}(${knob.argument}: ${knob.good}) { ${knob.field} ${knob.field}Configured } }")
                .execute().also {
                    it.path("${knob.mutation}.${knob.field}").entity(Int::class.java).isEqualTo(knob.good)
                }
            refusedWith("mutation { ${knob.mutation}(${knob.argument}: ${knob.low}) { ${knob.field} } }", knob.code, mapOf(knob.argument to knob.low))
            refusedWith("mutation { ${knob.mutation}(${knob.argument}: ${knob.high}) { ${knob.field} } }", knob.code, mapOf(knob.argument to knob.high))
        }

        graphQlTester.document("""mutation { setLocalBoxRuntime(runtime: "docker") { localBoxRuntime } }""").execute()
            .path("setLocalBoxRuntime.localBoxRuntime").entity(String::class.java).isEqualTo("docker")
        refusedWith("""mutation { setLocalBoxRuntime(runtime: "firecracker") { localBoxRuntime } }""", "LocalBoxRuntimeUnknown", mapOf("runtime" to "firecracker"))

        graphQlTester.document("""mutation { setLocalBoxLocation(location: "/srv/boxes") { localBoxLocation } }""").execute()
            .path("setLocalBoxLocation.localBoxLocation").entity(String::class.java).isEqualTo("/srv/boxes")
        graphQlTester.document("""mutation { setLocalBoxLocation(location: "") { localBoxLocation } }""").execute()
            .path("setLocalBoxLocation.localBoxLocation").valueIsNull()

        assertThat(messages()).contains(
            "The local box holds up to 7 sessions at once",
            "The local box runs sessions with the docker runtime",
            "The local box makes its sessions in /srv/boxes",
            "A request to a box is given up on after 5 seconds",
            "A local box command's output is not kept",
            "An agent's box edits are not kept for undoing",
        )
    }

    @Test
    fun `an agent's box labels are set, cleared and written down`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "boxes")).id)
        val agent = requireNotNull(agents.save(Agent(workspaceId = workspace, name = "Builder", type = AgentType.LLM)).id)
        graphQlTester.document("{ agent(id: $agent) { boxLabels } }").execute()
            .path("agent.boxLabels").entityList(String::class.java).hasSize(0)

        graphQlTester.document(
            """mutation { updateAgent(id: $agent, input: { name: "Builder", boxLabels: [" local ", "ci", "local", ""] }) { boxLabels } }""",
        ).execute().path("updateAgent.boxLabels").entityList(String::class.java).containsExactly("local", "ci")
        assertThat(agents.findById(agent).get().boxLabelsText).isEqualTo("local\nci")

        // Left alone when not sent.
        graphQlTester.document("""mutation { updateAgent(id: $agent, input: { name: "Builder" }) { boxLabels } }""")
            .execute().path("updateAgent.boxLabels").entityList(String::class.java).containsExactly("local", "ci")

        graphQlTester.document("""mutation { updateAgent(id: $agent, input: { name: "Builder", boxLabels: [] }) { boxLabels } }""")
            .execute().path("updateAgent.boxLabels").entityList(String::class.java).hasSize(0)

        graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspace, name: "Second", type: LLM, boxLabels: ["local"] }) { boxLabels } }""",
        ).execute().path("createAgent.boxLabels").entityList(String::class.java).containsExactly("local")

        assertThat(messages()).contains(
            "Agent Builder box labels set to local, ci",
            "Agent Builder box labels cleared",
            "Agent Second box labels set to local",
        )
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `only an administrator sees or changes the boxes`() {
        refusedWith("{ boxes { id } }", "AdminRequired")
        refusedWith("""{ box(id: "local") { id } }""", "AdminRequired")
        refusedWith("""mutation { createBox(input: { name: "x", address: "http://x" }) { id } }""", "AdminRequired")
        refusedWith("""mutation { deleteBox(id: "1") }""", "AdminRequired")
        refusedWith("mutation { setBoxOutputBytes(bytes: 2048) { boxOutputBytes } }", "AdminRequired")
        refusedWith("""mutation { testBoxConnection(address: "http://x") { ok } }""", "AdminRequired")
        // The suggestions an agent's form makes are anybody's.
        graphQlTester.document("{ boxLabels }").execute().path("boxLabels").entityList(String::class.java).containsExactly("local")
    }

    @Test
    fun `a remote box's log and output are read live and nothing of them is kept here`() {
        val boxId = create("builder").toLong()
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "remote")).id)
        val conversation = recorder.open(workspaceId, "chat", "build")
        fun session(remote: String) = requireNotNull(
            sessions.save(
                BoxSession(
                    workspaceId = workspaceId,
                    llmSessionId = conversation,
                    boxKind = BoxKind.REMOTE,
                    boxId = boxId,
                    boxName = "builder",
                    agentName = "Builder",
                    remoteSessionId = remote,
                    status = "ready",
                ),
            ).id,
        )
        val held = session("ses_1")
        val gone = session("ses_gone")
        fun log(id: Long) = graphQlTester.document(
            "{ sessionBoxLog(sessionId: $conversation, sessionBoxId: $id) { events { seq type } nextSeq available message } }",
        ).execute()
        fun output(id: Long, offset: Int = 0) = graphQlTester.document(
            """{ sessionBoxOutput(sessionId: $conversation, sessionBoxId: $id, executionId: "exe_1", offset: $offset) """ +
                "{ text offset nextOffset done available message shownLimit } }",
        ).execute()

        log(held).also {
            it.path("sessionBoxLog.events[*].type").entityList(String::class.java).containsExactly("session.created", "execution.finished")
            it.path("sessionBoxLog.nextSeq").entity(Int::class.java).isEqualTo(2)
            it.path("sessionBoxLog.available").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionBoxLog.message").valueIsNull()
        }
        output(held).also {
            it.path("sessionBoxOutput.text").entity(String::class.java).isEqualTo(StubOrkbox.OUTPUT)
            it.path("sessionBoxOutput.nextOffset").entity(Int::class.java).isEqualTo(StubOrkbox.OUTPUT.length)
            it.path("sessionBoxOutput.done").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionBoxOutput.available").entity(Boolean::class.java).isEqualTo(true)
        }
        output(held).path("sessionBoxOutput.shownLimit").entity(Int::class.java).isEqualTo(DEFAULT_BOX_OUTPUT_SHOWN)
        output(gone).path("sessionBoxOutput.shownLimit").entity(Int::class.java).isEqualTo(DEFAULT_BOX_OUTPUT_SHOWN)
        output(held, 3).path("sessionBoxOutput.text").entity(String::class.java).isEqualTo(StubOrkbox.OUTPUT.substring(3))
        assertThat(events.findAll().filter { it.boxSessionId == held }).isEmpty()
        assertThat(outputs.findAll().filter { it.boxSessionId == held }).isEmpty()

        log(gone).also {
            it.path("sessionBoxLog.available").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionBoxLog.message").entity(String::class.java).isEqualTo("Box builder no longer has this session")
        }
        output(gone).also {
            it.path("sessionBoxOutput.available").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionBoxOutput.message").entity(String::class.java).isEqualTo("Box builder no longer has this session")
        }

        // The Boxes page lists what was recorded on it, with the box's word
        // where it has one - ses_gone it no longer lists, so it is stopped.
        graphQlTester.document(
            """{ boxSessions(id: "$boxId") { id status active runtime orknuxSessionId agentName } }""",
        ).execute().path("boxSessions").entityList(Map::class.java).get().also { listed ->
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
        graphQlTester.document("{ sessionBoxes(sessionId: $conversation) { boxLabel } }").execute()
            .path("sessionBoxes[*].boxLabel").entityList(String::class.java).containsExactly("build", "build")

        graphQlTester.document("""mutation { deleteBox(id: "$boxId") }""").execute()
        graphQlTester.document("{ sessionBoxes(sessionId: $conversation) { boxLabel } }").execute()
            .path("sessionBoxes[0].boxLabel").valueIsNull()
        log(held).path("sessionBoxLog.message").entity(String::class.java).isEqualTo("Box builder is no longer configured")
        output(held).path("sessionBoxOutput.available").entity(Boolean::class.java).isEqualTo(false)
    }
}

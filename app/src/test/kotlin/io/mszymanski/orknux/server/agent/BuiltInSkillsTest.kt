package io.mszymanski.orknux.server.agent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * The skills Orknux brings itself. Issue #468.
 *
 * What is pinned: the catalog is offered where a plugin's is, so an agent's
 * grant list, the briefing and `skill_load` reach it without any of them
 * learning that a third kind of skill exists; each one is a skill by the same
 * rules a workspace's own must satisfy; and none of it is a row in the
 * workspace's own skills, which is what "hidden from the Skills page" means.
 */
@SpringBootTest
class BuiltInSkillsTest(
    @Autowired val builtIn: BuiltInSkills,
    @Autowired val fromPlugins: PluginSkills,
    @Autowired val skills: AgentSkillRepository,
) {

    @Test
    fun `the caveman skill is brought by the server, in a catalog of its own`() {
        val catalog = builtIn.catalogs().single()
        assertThat(catalog.name).isEqualTo(BuiltInSkills.CATALOG)
        assertThat(catalog.plugin).isEqualTo("Orknux")
        assertThat(catalog.skills.map { it.name }).contains("Caveman", "TL;DR", "Angryman", "Grill me", "Plan", "Memory", "Commands", "Output format", "Language", "Agents")
        // Every one of them is a skill by the rules a workspace's own is held
        // to - the frontmatter, a body - because nothing downstream knows these
        // came from a file rather than a row.
        catalog.skills.forEach { skill ->
            assertThat(SkillFormat.check(skill.content).valid).describedAs(skill.name).isTrue()
            assertThat(skill.description).describedAs(skill.name).isNotBlank()
            assertThat(SkillKeys.usable(skill.key)).describedAs(skill.key).isTrue()
        }

        val caveman = catalog.skills.single { it.name == "Caveman" }
        assertThat(caveman.key).isEqualTo("caveman")
        assertThat(caveman.description).isNotBlank()
        // A skill by the rules a workspace's own is held to, frontmatter and all.
        assertThat(SkillFormat.check(caveman.content).valid).isTrue()

        // And its quieter alternative, which keeps the grammar.
        val tldr = catalog.skills.single { it.name == "TL;DR" }
        assertThat(tldr.key).isEqualTo("tl-dr")
        // And the one a message reaches by name: ::plan, where :: is the
        // workspace's command marker and `plan` is this id.
        assertThat(catalog.skills.single { it.name == "Plan" }.key).isEqualTo("plan")
        assertThat(catalog.skills.single { it.name == "Memory" }.key).isEqualTo("memory")
        assertThat(catalog.skills.single { it.name == "Commands" }.key).isEqualTo("commands")
        // The one whose command carries an argument: ::output-format=json. The
        // marker and the id are what the parser reads; the format after the
        // equals sign is read by the model off the message. Issue #470.
        assertThat(catalog.skills.single { it.name == "Output format" }.key).isEqualTo("output-format")
        // And the other one with an argument: ::language=pl, ::language=polish.
        assertThat(catalog.skills.single { it.name == "Language" }.key).isEqualTo("language")
        // And the one that calls a tool to answer: what this conversation has
        // asked of other agents, and how each is going. Issue #477.
        assertThat(catalog.skills.single { it.name == "Agents" }.key).isEqualTo("agents")
        assertThat(catalog.skills.single { it.name == "Agents" }.content).contains("agent_asks")
        assertThat(SkillFormat.check(tldr.content).valid).isTrue()

        /*
         * And the one that has to name a tool. Issue #471: Commands told the
         * model to read the list off its briefing, which is one paragraph at the
         * top of a conversation that had since loaded a skill of its own - so it
         * listed that one skill and nothing else. `skill_list` is the same list,
         * live, and the skill says to call it.
         */
        val commands = catalog.skills.single { it.name == "Commands" }
        assertThat(commands.content).describedAs("it names the tool that answers the question")
            .contains("skill_list")
        assertThat(commands.content).describedAs("and says not to answer from this turn's loaded skills")
            .contains("loaded into this turn")
    }

    /** Offered where a plugin's are, which is what makes everything downstream work. */
    @Test
    fun `it is offered through the same door as a plugin's skills`() {
        assertThat(fromPlugins.catalogs().map { it.name }).contains(BuiltInSkills.CATALOG)
        assertThat(fromPlugins.granted(listOf(BuiltInSkills.CATALOG)).map { it.name }).contains("Caveman")
        // And an agent granted nothing is offered nothing, as before.
        assertThat(fromPlugins.granted(emptyList())).isEmpty()
    }

    /** And it is not a row anybody can edit, which is the whole of hiding it. */
    @Test
    fun `nothing of it is a workspace's own skill`() {
        assertThat(skills.findAll().map { it.name }).doesNotContain("Caveman")
    }
}

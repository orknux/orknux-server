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
        assertThat(catalog.skills.map { it.name }).contains("Caveman", "TL;DR", "Angryman", "Grill me")
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
        assertThat(SkillFormat.check(tldr.content).valid).isTrue()
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

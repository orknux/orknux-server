package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A value type a plugin defines, as the sandbox reads it and calls it. Issue #377.
 *
 * A Slack user id is a string only some values of are real, and the plugin is
 * the one thing that can say which. So a plugin may name a type over a base
 * type, say what it needs to be told, and offer `suggest` for the picker and
 * `validate` for the save - two doors on one declaration, reached through the
 * same [PluginRunner.call] a function is, on a surface of their own.
 *
 * What is measured here is the contract: what is read, what is refused, and
 * that a call reaches the right function with the right arguments. What the
 * server does with the answers is the app's business.
 */
class PluginTypesRunnerTest {

    private val runner = PluginRunner(PluginProperties(timeoutMillis = 5_000, statementLimit = 2_000_000))

    private val colours = """
        export default class Palette extends OrknuxPlugin {
          id() { return 'palette'; }
          apiVersion() { return 1; }

          types() {
            return [
              new OrknuxType({
                name: 'Colour',
                description: 'One of the named colours.',
                base: 'string',
                parameters: [
                  { name: 'shade', description: 'Light or dark.', type: 'string', required: false },
                ],
                suggest: (typed, args) => {
                  const all = ['red', 'green', 'blue'];
                  return all
                    .filter((one) => one.startsWith(typed))
                    .map((one) => ({ value: one, label: (args.shade ? args.shade + ' ' : '') + one }));
                },
                validate: (value, args) =>
                  ['red', 'green', 'blue'].includes(value)
                    ? { ok: true }
                    : { ok: false, reason: value + ' is not a colour' + (args.shade ? ' in ' + args.shade : '') },
              }),
              new OrknuxType({ name: 'Weight', base: 'number' }),
            ];
          }
        }
    """.trimIndent()

    /* ------------------------------------------------------ what is read --- */

    @Test
    fun `a declared type is read with its base, its parameters and what it can do`() {
        val read = runner.inspect(colours) as PluginInspection.Read

        assertThat(read.types).hasSize(2)
        val colour = read.types.first { it.name == "Colour" }
        assertThat(colour.base).isEqualTo("string")
        assertThat(colour.description).isEqualTo("One of the named colours.")
        assertThat(colour.parameters.map { it.name }).containsExactly("shade")
        assertThat(colour.parameters.single().required).isFalse()
        assertThat(colour.suggests).isTrue()
        assertThat(colour.validates).isTrue()
    }

    /** A name on a number, which is still worth having: it says what the variable is for. */
    @Test
    fun `a type with neither function is read as one that can do neither`() {
        val read = runner.inspect(colours) as PluginInspection.Read

        val weight = read.types.first { it.name == "Weight" }
        assertThat(weight.base).isEqualTo("number")
        assertThat(weight.parameters).isEmpty()
        assertThat(weight.suggests).isFalse()
        assertThat(weight.validates).isFalse()
    }

    @Test
    fun `a plugin with no types declares none, which is the default`() {
        val bare = """
            export default class Bare extends OrknuxPlugin {
              id() { return 'bare'; }
              apiVersion() { return 1; }
            }
        """.trimIndent()

        val read = runner.inspect(bare) as PluginInspection.Read
        assertThat(read.types).isEmpty()
    }

    /* --------------------------------------------------- what is refused --- */

    @Test
    fun `a type over something that is not a base type is refused`() {
        val wrong = """
            export default class Wrong extends OrknuxPlugin {
              id() { return 'wrong'; }
              apiVersion() { return 1; }
              types() { return [new OrknuxType({ name: 'Thing', base: 'object' })]; }
            }
        """.trimIndent()

        val read = runner.inspect(wrong)

        assertThat(read).isInstanceOf(PluginInspection.Unreadable::class.java)
        assertThat((read as PluginInspection.Unreadable).reason).contains("not one of string, number, boolean")
    }

    /**
     * What a variable of a type is told is kept beside it, in the clear, so a
     * type may not ask for a secret - the parameter rules already know how to
     * say no, and the type is held to them.
     */
    @Test
    fun `a type asking to be told a secret is refused`() {
        val wrong = """
            export default class Wrong extends OrknuxPlugin {
              id() { return 'wrong'; }
              apiVersion() { return 1; }
              types() {
                return [new OrknuxType({
                  name: 'Keyed', base: 'string',
                  parameters: [{ name: 'apiKey', type: 'string', secret: true }],
                })];
              }
            }
        """.trimIndent()

        val read = runner.inspect(wrong)

        assertThat(read).isInstanceOf(PluginInspection.Unreadable::class.java)
        assertThat((read as PluginInspection.Unreadable).reason).contains("cannot be told one")
    }

    /* ------------------------------------------------------ what is called - */

    @Test
    fun `suggest is reached on its own surface, with what was typed and what the type was told`() {
        val answered = runner.call(
            colours,
            "Colour",
            listOf("\"gr\"", """{"shade":"dark"}"""),
            surface = "types:suggest",
        )

        assertThat(answered).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((answered as ScriptResult.Returned).json)
            .isEqualTo("""[{"value":"green","label":"dark green"}]""")
    }

    @Test
    fun `validate is reached on its own, and answers for the value it was given`() {
        val good = runner.call(colours, "Colour", listOf("\"red\"", "{}"), surface = "types:validate")
        val bad = runner.call(colours, "Colour", listOf("\"mauve\"", """{"shade":"light"}"""), surface = "types:validate")

        assertThat((good as ScriptResult.Returned).json).isEqualTo("""{"ok":true}""")
        assertThat((bad as ScriptResult.Returned).json)
            .isEqualTo("""{"ok":false,"reason":"mauve is not a colour in light"}""")
    }

    /**
     * Asking a type that declared no suggest is a mistake at the caller, and
     * the answer says which type and which door rather than crashing inside
     * the sandbox with "undefined is not a function".
     */
    @Test
    fun `asking a type to do what it did not offer is refused by name`() {
        val answered = runner.call(colours, "Weight", listOf("\"1\"", "{}"), surface = "types:suggest")

        assertThat(answered).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((answered as ScriptResult.Failed).reason).contains("Weight does not suggest")
    }

    @Test
    fun `and a type the plugin does not declare is refused the same way a function is`() {
        val answered = runner.call(colours, "Nothing", listOf("\"x\"", "{}"), surface = "types:validate")

        assertThat(answered).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((answered as ScriptResult.Failed).reason).contains("no longer declares Nothing")
    }
}

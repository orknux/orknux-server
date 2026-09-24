package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.agent.SkillFormat
import io.mszymanski.orknux.server.agent.SkillKeys
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.workflow.script.DeclaredFunction
import io.mszymanski.orknux.workflow.script.DeclaredObject
import io.mszymanski.orknux.workflow.script.DeclaredType
import io.mszymanski.orknux.workflow.script.DeclaredParam
import io.mszymanski.orknux.workflow.script.DeclaredParameter
import io.mszymanski.orknux.workflow.script.DeclaredSkill
import io.mszymanski.orknux.workflow.script.DeclaredTool
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * What a plugin declared, checked and kept.
 *
 * A plugin answers with names and type names of its own choosing, so this is
 * where that answer stops being the plugin's word for something and becomes the
 * server's: every name has to be a JavaScript identifier, because that is what a
 * function is called by, and every type has to be one this server has.
 *
 * A plugin whose declarations do not survive that is refused at upload. There is
 * no partial acceptance — half a plugin's functions is not a plugin somebody can
 * write a workflow against.
 *
 * Read and written with the tree API rather than by binding to data classes:
 * there is no Jackson Kotlin module on the classpath, so a data class has no
 * usable constructor to bind to, and the rest of this codebase reads JSON the
 * same way.
 */
@Component
class PluginDeclarations(private val mapper: ObjectMapper) {

    /**
     * Checks what a plugin answered and returns it as the JSON to keep.
     *
     * @throws PluginDeclarationInvalidException if anything about it is wrong.
     */
    fun validated(declared: List<DeclaredFunction>, exported: Set<String> = emptySet()): String {
        val names = mutableSetOf<String>()

        val checked = declared.map { function ->
            if (!IDENTIFIER.matches(function.name)) {
                throw PluginDeclarationInvalidException("\"${function.name}\" is not a usable function name")
            }
            if (!names.add(function.name)) {
                throw PluginDeclarationInvalidException("it declares ${function.name} more than once")
            }

            /*
             * A type is either one of this server's, or the name of a shape
             * this plugin exports.
             *
             * The second is what objects() is for. A plugin's functions belong
             * to every workspace at once, so they may not name a *workspace's*
             * object - there is no single workspace whose definitions they
             * could mean - and `map` was the only answer available. A shape
             * the plugin exports is the answer that says something: it travels
             * with the plugin, so it means the same thing wherever the plugin
             * is.
             */
            val returns = shape(function.returnType, exported)
            val returnType = returns?.let { ValueType.OBJECT } ?: valueType(function.returnType)
                ?: throw PluginDeclarationInvalidException(
                    "${function.name} returns \"${function.returnType}\", which is neither a type this " +
                        "server has nor a shape this plugin exports",
                )
            /*
             * A function's return type is constrained in the database to the types
             * that carry a value; NONE is for things that act rather than answer,
             * and a function is not one of those.
             */
            if (returnType == ValueType.NONE) {
                throw PluginDeclarationInvalidException("${function.name} must return something, not none")
            }
            // Bare `object` still names a workspace's definition, and there is
            // still no workspace here. Naming the shape is what works.
            if (returnType == ValueType.OBJECT && returns == null) {
                throw PluginDeclarationInvalidException(
                    "${function.name} returns an object, which names one of a workspace's definitions. A " +
                        "plugin's functions belong to every workspace at once, so name one of this plugin's " +
                        "own shapes${offered(exported)}, or use map.",
                )
            }

            val paramNames = mutableSetOf<String>()
            val params = function.params.map { param ->
                if (!IDENTIFIER.matches(param.name)) {
                    throw PluginDeclarationInvalidException(
                        "${function.name} has a parameter called \"${param.name}\", which is not a usable name",
                    )
                }
                if (!paramNames.add(param.name)) {
                    throw PluginDeclarationInvalidException("${function.name} declares ${param.name} twice")
                }
                val takes = shape(param.type, exported)
                val type = takes?.let { ValueType.OBJECT } ?: valueType(param.type)
                    ?: throw PluginDeclarationInvalidException(
                        "${function.name}'s ${param.name} is a \"${param.type}\", which is neither a type " +
                            "this server has nor a shape this plugin exports",
                    )
                if (type == ValueType.OBJECT && takes == null) {
                    throw PluginDeclarationInvalidException(
                        "${function.name}'s ${param.name} is an object, which names one of a workspace's " +
                            "definitions. A plugin's functions belong to every workspace at once, so there is " +
                            "no workspace whose objects they could name. Name one of this plugin's own " +
                            "shapes${offered(exported)}, or use map.",
                    )
                }
                Taken(param.name, type, takes, param.required, checkedDefault(function.name, param, type))
            }

            /*
             * Optional ones last, because the arguments are positional.
             *
             * A call leaves out what it does not set, and what is left out has
             * to be the tail - a required parameter after an optional one is a
             * signature where "leave it out" has no meaning, and nothing
             * downstream could tell which argument was missing.
             */
            val lastRequired = params.indexOfLast { it.required }
            val firstOptional = params.indexOfFirst { !it.required }
            if (firstOptional >= 0 && lastRequired > firstOptional) {
                throw PluginDeclarationInvalidException(
                    "${function.name} takes ${params[lastRequired].name} after ${params[firstOptional].name}, " +
                        "which is optional. Arguments are positional, so the ones that may be left out come last.",
                )
            }

            Checked(function.name, function.description, params, returnType, returns, function.source)
        }

        val array = mapper.createArrayNode()
        checked.forEach { function ->
            val node = array.addObject()
            node.put("name", function.name)
            function.description?.let { node.put("description", it) }
            node.put("returnType", function.returnType.name)
            // The plugin's own spelling of the shape, kept as a name. What it
            // becomes - a reference by id - is the registry's, because that is
            // the step where the rows exist.
            function.returnObject?.let { node.put("returnObject", it) }
            // Kept as written, never validated: it is the plugin's own code,
            // shown in the editor for reference and executed from the bundle.
            function.source?.let { node.put("source", it) }
            val params = node.putArray("params")
            function.params.forEach { taken ->
                val held = params.addObject().put("name", taken.name).put("type", taken.type.name)
                taken.shape?.let { held.put("object", it) }
                // Written only where it is not the old answer, so a declaration
                // from before this existed and one that says "required" read
                // the same in the database.
                if (!taken.required) {
                    held.put("required", false)
                    taken.default?.let { held.put("default", it) }
                }
            }
        }
        return mapper.writeValueAsString(array)
    }

    /**
     * Checks what a plugin offers to agents and returns it as the JSON to keep.
     *
     * The same rules a function is held to - the names are identifiers, the
     * types are this server's - plus one of its own: a proxy names one of the
     * plugin's functions, which the inspection has already resolved, so what is
     * checked here is the copied shape and the reference is kept as it was
     * written. Tool names are unique among the tools; a tool sharing a name
     * with a function is fine, and is exactly what a proxy defaults to - the
     * two lists have different readers and never answer the same call.
     */
    fun validatedTools(declared: List<DeclaredTool>, exported: Set<String> = emptySet()): String {
        val names = mutableSetOf<String>()

        val array = mapper.createArrayNode()
        declared.forEach { tool ->
            if (!IDENTIFIER.matches(tool.name)) {
                throw PluginDeclarationInvalidException("\"${tool.name}\" is not a usable tool name")
            }
            if (!names.add(tool.name)) {
                throw PluginDeclarationInvalidException("it declares the tool ${tool.name} more than once")
            }

            /*
             * The same rule the functions are held to, and for the same
             * reason: a tool proxying one of them copies its return type, so
             * refusing a shape here would refuse the proxy to a function that
             * was accepted. A shape is better for a model than a map, too -
             * what comes back has named fields it was told about.
             */
            val returns = shape(tool.returnType, exported)
            val returnType = returns?.let { ValueType.OBJECT } ?: valueType(tool.returnType)
                ?: throw PluginDeclarationInvalidException(
                    "the tool ${tool.name} returns \"${tool.returnType}\", which is neither a type this " +
                        "server has nor a shape this plugin exports",
                )
            if (returnType == ValueType.NONE || (returnType == ValueType.OBJECT && returns == null)) {
                throw PluginDeclarationInvalidException(
                    "the tool ${tool.name} returns ${tool.returnType.lowercase()}; a tool answers a model, " +
                        "so it has to return one of ${usableTypes().joinToString(", ")}, or one of this " +
                        "plugin's own shapes${offered(exported)}",
                )
            }

            val paramNames = mutableSetOf<String>()
            val params = tool.params.map { param ->
                if (!IDENTIFIER.matches(param.name)) {
                    throw PluginDeclarationInvalidException(
                        "the tool ${tool.name} has a parameter called \"${param.name}\", which is not a usable name",
                    )
                }
                if (!paramNames.add(param.name)) {
                    throw PluginDeclarationInvalidException("the tool ${tool.name} declares ${param.name} twice")
                }
                val takes = shape(param.type, exported)
                val type = takes?.let { ValueType.OBJECT } ?: valueType(param.type)
                    ?: throw PluginDeclarationInvalidException(
                        "the tool ${tool.name}'s ${param.name} is a \"${param.type}\", " +
                            "which is neither a type this server has nor a shape this plugin exports",
                    )
                Taken(param.name, type, takes)
            }

            val node = array.addObject()
            node.put("name", tool.name)
            tool.description?.let { node.put("description", it) }
            node.put("returnType", returnType.name)
            returns?.let { node.put("returnObject", it) }
            tool.proxyOf?.let { node.put("proxyOf", it) }
            val kept = node.putArray("params")
            params.forEach { taken ->
                val held = kept.addObject().put("name", taken.name).put("type", taken.type.name)
                taken.shape?.let { held.put("object", it) }
            }
        }
        return mapper.writeValueAsString(array)
    }

    /** What was kept about the tools, as the grant list and the dispatch want it. */
    fun readTools(json: String): List<PluginToolView> = runCatching {
        val array = mapper.readTree(json)
        (0 until array.size()).map { at ->
            val node = array.get(at)
            val params = node.get("params")
            val read = (0 until (params?.size() ?: 0)).map { index ->
                val param = params.get(index)
                PluginFunctionParamView(
                    name = param.get("name").asString(),
                    type = param.get("type").asString(),
                    objectName = param.get("object")?.asString(),
                )
            }
            PluginToolView(
                name = node.get("name").asString(),
                description = node.get("description")?.asString(),
                params = read,
                returnType = node.get("returnType").asString(),
                returnObject = node.get("returnObject")?.asString(),
                proxyOf = node.get("proxyOf")?.asString(),
            )
        }
    }.getOrElse { emptyList() }

    /**
     * Checks the instruction sets a plugin brings, and returns them as JSON.
     *
     * A skill is held to the format a skill written in the interface is held
     * to — [SkillFormat] decides that, here as everywhere — with one kindness:
     * a plugin that wrote plain markdown and named the skill in its
     * declaration has the frontmatter written for it rather than being refused
     * for leaving out two facts it has already stated. What a plugin cannot do
     * is ship a *broken* block; a fence that opens and never closes is the
     * plugin's mistake and is refused as one.
     *
     * Names are held to the skill name rule rather than the identifier rule
     * the functions use: nothing calls a skill, an agent reads it, so "Handling
     * a stuck deploy" is a better name than `handling_a_stuck_deploy`.
     *
     * @throws PluginDeclarationInvalidException if anything about it is wrong.
     */
    fun validatedSkills(declared: List<DeclaredSkill>): String {
        val names = mutableSetOf<String>()

        val array = mapper.createArrayNode()
        declared.forEach { skill ->
            val name = skill.name.trim()
            if (name.isEmpty() || name.length > MOST_SKILL_NAME_CHARS) {
                throw PluginDeclarationInvalidException(
                    "\"${skill.name}\" is not a usable skill name: one is 1 to $MOST_SKILL_NAME_CHARS characters",
                )
            }
            if (!names.add(name.lowercase())) {
                throw PluginDeclarationInvalidException("it declares the skill $name more than once")
            }

            val description = skill.description?.trim()?.takeIf { it.isNotEmpty() }
            val content = frontmattered(name, description, skill.content)
            val check = SkillFormat.check(content)
            if (!check.valid) {
                throw PluginDeclarationInvalidException(
                    "the skill $name is not shaped like a skill: ${check.message?.lowercase()}",
                )
            }

            val node = array.addObject()
            node.put("name", name)
            description?.let { node.put("description", it) }
            node.put("content", content)
        }
        return mapper.writeValueAsString(array)
    }

    /**
     * The content as it will be stored: the plugin's own, or its markdown
     * under a block written from what it declared.
     *
     * Only for a body that opens with no fence at all. One that opens with a
     * fence is the plugin's own frontmatter and is left exactly as written —
     * including when it is wrong, which [SkillFormat] then says.
     */
    private fun frontmattered(name: String, description: String?, content: String): String {
        val first = content.lines().firstOrNull { it.isNotBlank() }?.trim()
        if (first == "---") return content
        return buildString {
            append("---\n")
            append("name: ").append(name).append('\n')
            append("description: ").append(description ?: "What this skill is for.").append('\n')
            append("---\n\n")
            append(content.trimStart())
        }
    }

    /** What was kept about the skills, as the catalog and the screen want it. */
    fun readSkills(json: String): List<PluginSkillView> = runCatching {
        val array = mapper.readTree(json)
        (0 until array.size()).map { at ->
            val node = array.get(at)
            PluginSkillView(
                name = node.get("name").asString(),
                key = SkillKeys.derive(node.get("name").asString()),
                description = node.get("description")?.asString(),
                content = node.get("content").asString(),
            )
        }
    }.getOrElse { emptyList() }

    /**
     * Checks the shapes a plugin exports, and returns them as JSON.
     *
     * Where the field checks in the sandbox stop, this begins. The contract's
     * own constructor has already refused a kind that is not a kind and an
     * `of` where none belongs, because those are facts about one field. What
     * needs the whole set is here: a name that is declared twice, and an `of`
     * pointing at an object the plugin does not have.
     *
     * The names are kept as the plugin spelled them. Prefixing is the
     * registry's, at the moment a row is made, for the reason the functions
     * are prefixed there and not here: what is stored on the plugin is what
     * the plugin said.
     *
     * @throws PluginDeclarationInvalidException if anything about it is wrong.
     */
    fun validatedObjects(declared: List<DeclaredObject>): String {
        val names = mutableSetOf<String>()
        declared.forEach { object_ ->
            val name = object_.name.trim()
            if (!IDENTIFIER.matches(name)) {
                throw PluginDeclarationInvalidException("\"${object_.name}\" is not a usable object name")
            }
            if (!names.add(name)) {
                throw PluginDeclarationInvalidException("it declares the object $name more than once")
            }
        }

        val array = mapper.createArrayNode()
        declared.forEach { object_ ->
            val name = object_.name.trim()
            val node = array.addObject()
            node.put("name", name)
            object_.description?.trim()?.takeIf { it.isNotEmpty() }?.let { node.put("description", it) }

            val fields = mutableSetOf<String>()
            val kept = node.putArray("properties")
            object_.properties.forEach { property ->
                if (!IDENTIFIER.matches(property.name)) {
                    throw PluginDeclarationInvalidException(
                        "$name has a property called \"${property.name}\", which is not a usable name",
                    )
                }
                if (!fields.add(property.name)) {
                    throw PluginDeclarationInvalidException("$name declares ${property.name} twice")
                }

                val kind = propertyKind(property.kind)
                    ?: throw PluginDeclarationInvalidException(
                        "$name's ${property.name} is a \"${property.kind}\", which is not a kind this server has",
                    )
                val held = kept.addObject()
                held.put("name", property.name)
                held.put("kind", kind.name)
                property.description?.trim()?.takeIf { it.isNotEmpty() }?.let { held.put("description", it) }

                val of = property.of?.trim()
                when (kind) {
                    PropertyKind.OBJECT -> {
                        val points = of
                            ?: throw PluginDeclarationInvalidException(
                                "$name's ${property.name} is an object, so it has to say which with \"of\"",
                            )
                        if (points !in names) {
                            throw PluginDeclarationInvalidException(
                                "$name's ${property.name} points at \"$points\", which objects() does not declare",
                            )
                        }
                        held.put("of", points)
                    }

                    PropertyKind.ARRAY -> {
                        val holds = of
                            ?: throw PluginDeclarationInvalidException(
                                "$name's ${property.name} is an array, so it has to say what it holds with \"of\"",
                            )
                        /*
                         * An array says what it holds one of two ways, and
                         * which one is decided by whether the word is a kind.
                         * A scalar kind is the element type; anything else is
                         * one of this plugin's objects.
                         */
                        val element = propertyKind(holds)
                        if (element != null && element != PropertyKind.OBJECT) {
                            if (element == PropertyKind.ARRAY) {
                                throw PluginDeclarationInvalidException(
                                    "$name's ${property.name} is an array of arrays, " +
                                        "which this server has no shape for",
                                )
                            }
                            held.put("elementKind", element.name)
                        } else {
                            if (holds !in names) {
                                throw PluginDeclarationInvalidException(
                                    "$name's ${property.name} holds \"$holds\", " +
                                        "which objects() does not declare",
                                )
                            }
                            held.put("of", holds)
                        }
                    }

                    else -> if (of != null) {
                        throw PluginDeclarationInvalidException(
                            "$name's ${property.name} names an \"of\" but is a ${kind.name.lowercase()}",
                        )
                    }
                }
            }
        }
        return mapper.writeValueAsString(array)
    }

    /** What was kept about the objects, as the registry and the screen want it. */
    fun readObjects(json: String): List<PluginObjectView> = runCatching {
        val array = mapper.readTree(json)
        (0 until array.size()).map { at ->
            val node = array.get(at)
            val properties = node.get("properties")
            val fields = (0 until (properties?.size() ?: 0)).map { index ->
                val held = properties.get(index)
                PluginObjectPropertyView(
                    name = held.get("name").asString(),
                    kind = held.get("kind").asString(),
                    of = held.get("of")?.asString(),
                    elementKind = held.get("elementKind")?.asString(),
                    description = held.get("description")?.asString(),
                )
            }
            PluginObjectView(
                name = node.get("name").asString(),
                description = node.get("description")?.asString(),
                properties = fields,
            )
        }
    }.getOrElse { emptyList() }

    private fun propertyKind(name: String): PropertyKind? =
        PropertyKind.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /* ------------------------------------------------------------ types --- */

    /**
     * Checks the value types a plugin defines, and returns them as JSON to keep.
     * Issue #377.
     *
     * A type is a name over a base type, what it needs to be told, and whether
     * it offered `suggest` and `validate`. The parameters are held to the same
     * rules a plugin's own settings are, through [validatedParameters], with
     * one more: none may be a secret, because what a variable of the type is
     * told is stored beside the variable in the clear.
     *
     * @throws PluginDeclarationInvalidException if anything about it is wrong.
     */
    fun validatedTypes(declared: List<DeclaredType>): String {
        val names = mutableSetOf<String>()
        val array = mapper.createArrayNode()
        declared.forEach { type ->
            val name = type.name.trim()
            if (!IDENTIFIER.matches(name)) {
                throw PluginDeclarationInvalidException("\"${type.name}\" is not a usable type name")
            }
            if (!names.add(name)) {
                throw PluginDeclarationInvalidException("it declares the type $name more than once")
            }
            val base = type.base.trim().lowercase()
            if (base !in BASES) {
                throw PluginDeclarationInvalidException(
                    "$name is a \"${type.base}\", and a type is one of ${BASES.joinToString(", ")} with a name on it",
                )
            }
            type.parameters.firstOrNull { it.secret }?.let {
                throw PluginDeclarationInvalidException(
                    "$name asks to be told ${it.name} as a secret, and a type cannot be told one: " +
                        "what a variable of the type is told is kept beside it, in the clear.",
                )
            }

            val node = array.addObject()
            node.put("name", name)
            type.description?.trim()?.takeIf { it.isNotEmpty() }?.let { node.put("description", it) }
            node.put("base", base)
            // The same checks, and the same JSON, a plugin's settings get.
            node.set("parameters", mapper.readTree(validatedParameters(type.parameters)))
            node.put("suggests", type.suggests)
            node.put("validates", type.validates)
        }
        return mapper.writeValueAsString(array)
    }

    /** What was kept about the types, as the variable screen and the caller want it. */
    fun readTypes(json: String): List<PluginTypeView> = runCatching {
        val array = mapper.readTree(json)
        (0 until array.size()).map { at ->
            val node = array.get(at)
            PluginTypeView(
                name = node.get("name").asString(),
                description = node.get("description")?.asString(),
                base = node.get("base").asString(),
                parameters = readParameters(mapper.writeValueAsString(node.get("parameters"))),
                suggests = node.get("suggests")?.asBoolean() ?: false,
                validates = node.get("validates")?.asBoolean() ?: false,
            )
        }
    }.getOrElse { emptyList() }

    /**
     * Checks what a plugin says it has to be told, and returns it as JSON to keep.
     *
     * Held to the same naming rule as everything else a plugin declares, and to a
     * narrower set of types: a parameter is filled in either by typing a value or
     * by pointing at one of the workspace's variables, and a variable holds a
     * scalar. Allowing a map here would mean a parameter that can be typed but
     * never referenced, which is a difference nobody could see on the screen and
     * everybody would trip over.
     *
     * @throws PluginDeclarationInvalidException if anything about it is wrong.
     */
    fun validatedParameters(declared: List<DeclaredParameter>): String {
        val names = mutableSetOf<String>()

        val array = mapper.createArrayNode()
        declared.forEach { parameter ->
            if (!IDENTIFIER.matches(parameter.name)) {
                throw PluginDeclarationInvalidException("\"${parameter.name}\" is not a usable parameter name")
            }
            if (!names.add(parameter.name)) {
                throw PluginDeclarationInvalidException("it declares the parameter ${parameter.name} more than once")
            }

            /*
             * A connection is the one parameter that is neither typed in nor
             * read from a variable: it points at a row the workspace already
             * has, and what crosses into the sandbox is a handle to it rather
             * than anything the connection holds. So it is checked here on its
             * own terms and never against [SETTABLE], which is about scalars.
             */
            if (parameter.type.trim().equals(CONNECTION, ignoreCase = true)) {
                val kind = connectionType(parameter.connectionType)
                    ?: throw PluginDeclarationInvalidException(
                        "the parameter ${parameter.name} is a connection but does not say which kind. " +
                            "It has to name one of ${connectionTypes().joinToString(", ")}.",
                    )
                if (parameter.secret) {
                    /*
                     * Refused rather than ignored. A connection parameter holds
                     * no secret - it names a row, and the credential on that row
                     * is decrypted on the far side of the sandbox and never
                     * crosses it - so a plugin marking one secret has
                     * misunderstood what it is being given, and letting that
                     * through would leave the screen promising a protection that
                     * means nothing here.
                     */
                    throw PluginDeclarationInvalidException(
                        "the parameter ${parameter.name} is a connection and cannot be a secret: it names a " +
                            "connection rather than holding one's credential.",
                    )
                }

                val node = array.addObject()
                node.put("name", parameter.name)
                parameter.description?.let { node.put("description", it) }
                node.put("type", CONNECTION)
                node.put("connectionType", kind.name)
                node.put("required", parameter.required)
                node.put("secret", false)
                return@forEach
            }

            if (parameter.connectionType != null) {
                throw PluginDeclarationInvalidException(
                    "the parameter ${parameter.name} names a connection kind but is a \"${parameter.type}\".",
                )
            }

            val type = valueType(parameter.type)
            if (type == null || type !in SETTABLE) {
                throw PluginDeclarationInvalidException(
                    "the parameter ${parameter.name} is a \"${parameter.type}\". A parameter is either typed in, " +
                        "points at one of the workspace's variables, or names one of the workspace's " +
                        "connections, so it has to be one of " +
                        "${(parameterTypes() + CONNECTION).joinToString(", ")}.",
                )
            }

            val node = array.addObject()
            node.put("name", parameter.name)
            parameter.description?.let { node.put("description", it) }
            node.put("type", type.name)
            node.put("required", parameter.required)
            node.put("secret", parameter.secret)
            /*
             * The values it may take, where the plugin knows them all.
             *
             * Checked here as well as in the sandbox because this is where a
             * refusal reaches the person loading the plugin: a set of one is a
             * field that cannot be filled in, and a duplicate is a picker with
             * the same row twice.
             */
            parameter.options?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { offered ->
                if (offered.size != offered.distinct().size) {
                    throw PluginDeclarationInvalidException(
                        "the parameter ${parameter.name} offers the same option more than once",
                    )
                }
                if (offered.size > MOST_OPTIONS) {
                    throw PluginDeclarationInvalidException(
                        "the parameter ${parameter.name} offers ${offered.size} options, and a picker holds " +
                            "at most $MOST_OPTIONS",
                    )
                }
                val held = node.putArray("options")
                offered.forEach(held::add)
            }
        }
        return mapper.writeValueAsString(array)
    }

    /** What was kept about the parameters, as the screen wants it. */
    fun readParameters(json: String): List<PluginParameterView> = runCatching {
        val array = mapper.readTree(json)
        (0 until array.size()).map { at ->
            val node = array.get(at)
            PluginParameterView(
                name = node.get("name").asString(),
                description = node.get("description")?.asString(),
                type = node.get("type").asString(),
                /*
                 * A declaration written before these existed has neither, and
                 * the safe reading of silence is "not required, not a secret".
                 *
                 * Deliberately the opposite of what the contract's own
                 * constructor does, which defaults `required` to true. The two
                 * are answering different questions: a plugin author who omits
                 * it means the parameter matters, while a stored row that omits
                 * it is one written before parameters existed, and marking that
                 * as missing something would put a red mark on a plugin that
                 * never asked for anything. Anything the constructor wrote has
                 * the key, so this only ever reads the old shape.
                 */
                required = node.get("required")?.asBoolean() ?: false,
                secret = node.get("secret")?.asBoolean() ?: false,
                options = node.get("options")?.takeIf { it.isArray }
                    ?.values()?.map { it.asString() }?.toList() ?: emptyList(),
                connectionType = node.get("connectionType")?.asString(),
            )
        }
    }.getOrElse { emptyList() }

    /**
     * The types a parameter may be, as a plugin should write them.
     *
     * Narrower than [usableTypes] on purpose; see [validatedParameters] for why.
     */
    fun parameterTypes(): List<String> = SETTABLE.map { it.name.lowercase() }

    /**
     * The type names a plugin may use, as it should write them.
     *
     * Derived from the enum rather than listed again, so the template hands out
     * exactly what [validated] will accept.
     *
     * Two are left out. NONE means "answers nothing", and neither a parameter nor a
     * function's result may be that. OBJECT names one of a workspace's objects, and
     * a plugin's functions belong to the organisation — they are available in every
     * workspace at once, so there is no single workspace whose objects they could
     * point at. A plugin that wants a structure asks for a map.
     */
    fun usableTypes(): List<String> = ValueType.entries
        .filter { it != ValueType.NONE && it != ValueType.OBJECT }
        .map { it.name.lowercase() }

    /** What was kept, as the screen wants it. Unreadable JSON reads as nothing declared. */
    fun read(json: String): List<PluginFunctionView> = runCatching {
        val array = mapper.readTree(json)
        (0 until array.size()).map { at ->
            val node = array.get(at)
            val params = node.get("params")
            val read = (0 until (params?.size() ?: 0)).map { index ->
                val param = params.get(index)
                PluginFunctionParamView(
                    name = param.get("name").asString(),
                    type = param.get("type").asString(),
                    objectName = param.get("object")?.asString(),
                    // Absent means required, which is what everything written
                    // before this said by saying nothing.
                    required = param.get("required")?.asBoolean() ?: true,
                    default = param.get("default")?.asString(),
                )
            }
            val returnType = node.get("returnType").asString()
            val returnObject = node.get("returnObject")?.asString()
            PluginFunctionView(
                name = node.get("name").asString(),
                description = node.get("description")?.asString(),
                params = read,
                returnType = returnType,
                returnObject = returnObject,
                signature = signature(read, returnType, returnObject),
                source = node.get("source")?.asString(),
            )
        }
    }.getOrElse { emptyList() }

    /**
     * "(email: string): boolean", the way a workspace's own functions read.
     *
     * A shape reads as its own name rather than as `object`, because `object`
     * says nothing and the name is the whole point of having declared it.
     */
    private fun signature(
        params: List<PluginFunctionParamView>,
        returnType: String,
        returnObject: String? = null,
    ): String {
        // `limit?: number` - the mark TypeScript uses, because this signature is
        // read beside TypeScript everywhere it is shown.
        val taken = params.joinToString(", ") {
            "${it.name}${if (it.required) "" else "?"}: ${it.objectName ?: it.type.lowercase()}"
        }
        return "($taken): ${returnObject ?: returnType.lowercase()}"
    }

    private fun valueType(name: String): ValueType? =
        ValueType.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** The connection kinds a plugin may name, as it should write them. */
    fun connectionTypes(): List<String> = ConnectionType.entries.map { it.name }

    private fun connectionType(name: String?): ConnectionType? =
        name?.trim()?.let { wanted -> ConnectionType.entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) } }

    private data class Checked(
        val name: String,
        val description: String?,
        val params: List<Taken>,
        val returnType: ValueType,
        /** The plugin's own name for the shape it returns, where it returns one. */
        val returnObject: String? = null,
        val source: String? = null,
    )

    /** One checked parameter, with the shape it names where it names one. */
    private data class Taken(
        val name: String,
        val type: ValueType,
        val shape: String? = null,
        val required: Boolean = true,
        /** As JSON, ready to be passed; null where being left out means null. */
        val default: String? = null,
    )

    /**
     * The default a parameter declared, checked against what it is.
     *
     * A default that is not of the parameter's own type is the kind of mistake
     * that only shows up on the call that leaves the argument out - which may
     * be months after the plugin was written, and in somebody else's workflow.
     * It costs one parse to refuse it at load instead.
     */
    private fun checkedDefault(function: String, param: DeclaredParam, type: ValueType): String? {
        val held = param.default ?: return null
        if (param.required) return null

        val read = runCatching { mapper.readTree(held) }.getOrNull()
            ?: throw PluginDeclarationInvalidException(
                "$function's ${param.name} has a default this server cannot read",
            )
        val fits = read.isNull || when (type) {
            ValueType.STRING -> read.isString
            ValueType.NUMBER -> read.isNumber
            ValueType.BOOLEAN -> read.isBoolean
            ValueType.ARRAY -> read.isArray
            ValueType.MAP, ValueType.OBJECT -> read.isObject
            // Neither carries a value a default could be: NONE answers nothing,
            // and a connection names a row rather than holding one.
            ValueType.NONE, ValueType.CONNECTION -> false
        }
        if (!fits) {
            throw PluginDeclarationInvalidException(
                "$function's ${param.name} is a ${type.name.lowercase()} and its default is not one",
            )
        }
        return held
    }

    /**
     * The exported shape this type names, or null where it names none.
     *
     * A server type always wins: a plugin that exports a shape called `String`
     * has not renamed the language. It cannot in practice - an object name is
     * an identifier and so is a type name - but the order is written down so
     * the answer does not depend on which check happened to run first.
     */
    private fun shape(type: String, exported: Set<String>): String? {
        val held = type.trim()
        if (valueType(held) != null) return null
        return held.takeIf { it in exported }
    }

    /** ", one of Issue, User" - so a refusal says what was available. */
    private fun offered(exported: Set<String>): String =
        if (exported.isEmpty()) "" else ", one of ${exported.sorted().joinToString(", ")}"

    companion object {
        /** The same rule a workspace's own function names are held to. */
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")

        /**
         * What a parameter may be: exactly what a workspace variable can hold.
         *
         * Written in the order they read best on a form rather than in the enum's
         * order, since this is also what the template offers.
         */
        val SETTABLE = listOf(ValueType.STRING, ValueType.NUMBER, ValueType.BOOLEAN)

        /**
         * How a plugin spells a parameter that names one of the workspace's
         * connections. Not a [ValueType]: those are what a value can be, and
         * this is a reference to a row.
         */
        const val CONNECTION = "connection"

        /** What a plugin's type may be underneath; the three a variable can hold. Issue #377. */
        val BASES = listOf("string", "number", "boolean")

        /** What the `agent_skill` name column holds. */
        const val MOST_SKILL_NAME_CHARS = 120

        /**
         * How many values a picker may offer.
         *
         * A list past this is not a choice anybody scans, it is a search - and
         * a parameter with two hundred answers wants a different control than
         * the one this buys.
         */
        const val MOST_OPTIONS = 50
    }
}

/**
 * A value type a plugin defines, as the screen and the caller see it. Issue #377.
 *
 * The plugin's own name, unprefixed; what a workspace picks it by is
 * `<plugin>:<name>`, which [PluginTypes] spells.
 */
data class PluginTypeView(
    val name: String,
    val description: String?,
    /** `string`, `number` or `boolean`. */
    val base: String,
    val parameters: List<PluginParameterView>,
    val suggests: Boolean,
    val validates: Boolean,
)

class PluginDeclarationInvalidException(what: String) :
    RuntimeException("The plugin's functions could not be accepted: $what")

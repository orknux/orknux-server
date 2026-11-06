package io.mszymanski.orknux.workflow.script

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value

/** What a workspace's tool, or a plugin, is written in. */
enum class ScriptLanguage {
    /** JavaScript, or TypeScript the browser compiled to it. Everything until Python arrived. */
    JAVASCRIPT,

    /** Python, run by GraalPy in the same sandbox, beside the JavaScript its helpers are written in. */
    PYTHON,
}

/**
 * What both sandboxes hand a Python guest, written once - the Python twin of
 * [HostHelpers], and for the same reason: two copies of an API is two APIs.
 *
 * **The helpers are not written again in Python.** A Python context here holds
 * JavaScript too, the runner evaluates the `orknux` helpers into it exactly as it
 * does for a JavaScript guest, and the Python `orknux` is a door onto that object:
 * `orknux.http.get(url)` walks to `http.get` on the JavaScript side and calls it.
 * So a helper added for JavaScript is there for Python the same day, answers the
 * same shapes, and refuses in the same words - nothing here can drift from it.
 *
 * **Only JSON crosses, and only text.** The door hands its arguments over as one
 * JSON document and reads one back, so Python is given dicts, lists and strings
 * and never a foreign object it would have to know how to treat. The guest has
 * no `polyglot` module to reach JavaScript by itself either; the one function
 * that crosses is put there by the host.
 *
 * **What print says is a log line.** `sys.stdout` and `sys.stderr` are replaced
 * with writers that hand whole lines to `orknux.log`, so a `print` lands where a
 * JavaScript guest's `orknux.log.info` does, under the same threshold and the
 * same cap, rather than in the server's own output.
 */
object PythonGuest {

    /** Where the JavaScript end of the door is left for the installer. */
    private const val BRIDGE = "__orknux_bridge"

    /** Where the module's text is left for the installer, as a string rather than spliced into code. */
    private const val MODULE_SOURCE = "__orknux_module_source"

    /**
     * Whether a plugin's source is Python.
     *
     * Read off the source rather than stored beside it, so every door a plugin
     * is run through - and there are many - knows without being told. A Python
     * plugin has to import its base class from `orknux`, and neither spelling is
     * a line JavaScript can begin with, so the test cannot mistake one for the
     * other.
     */
    fun isPython(source: String): Boolean = PYTHON_PLUGIN.containsMatchIn(source)

    // `import orknux from './x.js'` is JavaScript, so the bare import has to end the statement or rename it.
    private val PYTHON_PLUGIN = Regex("""(?m)^(from\s+orknux\s+import\b|import\s+orknux\s*(?:$|#|,|;|\s+as\s))""")

    /**
     * Puts the `orknux` module into a context whose JavaScript already holds
     * `globalThis.orknux`, and returns it.
     *
     * After this, `import orknux` and `from orknux import ...` both work, and
     * `orknux` is a global of the guest's main module as it is for JavaScript.
     */
    internal fun install(polyglot: Context): Value {
        polyglot.getBindings("python").putMember(BRIDGE, polyglot.eval("js", JS_BRIDGE))
        polyglot.getBindings("python").putMember(MODULE_SOURCE, MODULE)
        return polyglot.eval(Source.newBuilder("python", INSTALL, "orknux-install.py").buildLiteral())
    }

    /** The guest's own code, named so a traceback says where it is. */
    internal fun source(text: String, name: String): Source = Source.newBuilder("python", text, name).buildLiteral()

    /**
     * The JavaScript end of the door: walks a path on `globalThis.orknux` and
     * calls what it finds, answering `{ value }` or `{ thrown }` as text.
     *
     * A helper that throws is caught here and said as data, because the Python
     * side turns it into an `OrknuxError` with the helper's own message - a
     * foreign exception arriving in Python would say far less.
     */
    private val JS_BRIDGE = """
        (function (path, given) {
          try {
            var parts = JSON.parse(path);
            var owner = null;
            var target = globalThis.orknux;
            for (var at = 0; at < parts.length; at++) {
              if (target === null || target === undefined || !(parts[at] in Object(target))) {
                return JSON.stringify({ thrown: 'orknux.' + parts.slice(0, at + 1).join('.') + ' does not exist' });
              }
              owner = target;
              target = target[parts[at]];
            }
            if (typeof target !== 'function') {
              return JSON.stringify({ thrown: 'orknux.' + parts.join('.') + ' is not something to call' });
            }
            var answer = target.apply(owner, JSON.parse(given));
            return JSON.stringify({ value: answer === undefined ? null : answer });
          } catch (failure) {
            return JSON.stringify({ thrown: String((failure && failure.message) || failure) });
          }
        })
    """.trimIndent()

    /** Builds the module from its text and registers it, so an import finds it. */
    private val INSTALL = """
        def __orknux_install():
            import sys, types
            module = types.ModuleType('orknux')
            module.__dict__['_bridge'] = globals()['$BRIDGE']
            exec(compile(globals()['$MODULE_SOURCE'], 'orknux.py', 'exec'), module.__dict__)
            sys.modules['orknux'] = module
            del globals()['$BRIDGE']
            del globals()['$MODULE_SOURCE']
            globals()['orknux'] = module
            sys.stdout = module._Said('info')
            sys.stderr = module._Said('warn')
            return module
        __orknux_install()
    """.trimIndent()

    /**
     * The `orknux` module, as a Python guest imports it.
     *
     * Three things: the door (`orknux.http.get(...)` and every other helper, by
     * PEP 562's module `__getattr__`), the harness the runner calls a tool
     * through, and the plugin contract a Python plugin subclasses.
     */
    private val MODULE = """
        import json as _json
        import types as _types

        class OrknuxError(Exception):
            '''What an orknux helper refused or threw, in its own words.'''


        class _Door:
            '''A path on the JavaScript helpers, called by calling it.'''
            __slots__ = ('_path',)

            def __init__(self, path):
                object.__setattr__(self, '_path', path)

            def __getattr__(self, name):
                if name.startswith('__'):
                    raise AttributeError(name)
                return _Door(self._path + (name,))

            def __setattr__(self, name, value):
                raise AttributeError('orknux helpers cannot be replaced')

            def __call__(self, *args, **options):
                given = list(args)
                # Keyword arguments are the options object a JavaScript helper takes last.
                if options:
                    given.append(options)
                answered = _json.loads(_bridge(_json.dumps(list(self._path)), _json.dumps(given, default=_plain)))
                if 'thrown' in answered:
                    raise OrknuxError(answered['thrown'])
                return answered.get('value')

            def __repr__(self):
                return '<orknux.' + '.'.join(self._path) + '>'


        def __getattr__(name):
            if name.startswith('_'):
                raise AttributeError(name)
            return _Door((name,))


        class _Said:
            '''Whole lines of print, handed to orknux.log at one level.'''

            def __init__(self, level):
                self._level = level
                self._held = ''

            def write(self, text):
                self._held += str(text)
                while '\n' in self._held:
                    line, self._held = self._held.split('\n', 1)
                    self._say(line)
                return len(text)

            def flush(self):
                if self._held:
                    line, self._held = self._held, ''
                    self._say(line)

            def _say(self, line):
                # Where nothing is bound - a script only being parsed or counted - a line goes nowhere.
                try:
                    _Door(('log', self._level))(line)
                except OrknuxError:
                    pass

            def isatty(self):
                return False


        def _plain(value):
            '''What json cannot write by itself, as the JSON anybody would write for it: a set as a list, a date as ISO text, a Decimal as a number.'''
            import datetime, decimal
            if isinstance(value, (set, frozenset)):
                items = list(value)
                try:
                    return sorted(items)
                except TypeError:
                    return items
            if isinstance(value, (datetime.date, datetime.time)):
                return value.isoformat()
            if isinstance(value, decimal.Decimal):
                return float(value)
            raise TypeError('Object of type ' + type(value).__name__ + ' is not JSON serializable')


        def _said(failure):
            name = type(failure).__name__
            message = str(failure)
            return name + ': ' + message if message else name


        def _call(fn, arguments, limit):
            '''Calls a tool's main with JSON arguments; answers [error, None] or [None, JSON text or None].'''
            import sys
            try:
                value = fn(*_json.loads(str(arguments)))
                # A set or a date returned is what a program means as a list or a text; refusing it cost an agent a round.
                text = None if value is None else _json.dumps(value, default=_plain)
            except BaseException as failure:
                return [_said(failure), None]
            finally:
                sys.stdout.flush()
                sys.stderr.flush()
            if text is not None and len(text) > limit:
                return ['returned ' + str(len(text)) + ' characters of JSON, more than the ' + str(limit) + ' it is allowed', None]
            return [None, text]


        def _arity(fn):
            '''How many positional arguments fn takes, or a sentence saying why that cannot be counted.'''
            import inspect
            try:
                signature = inspect.signature(fn)
            except (TypeError, ValueError):
                return 'its parameters could not be read'
            counted = 0
            for parameter in signature.parameters.values():
                if parameter.kind in (parameter.POSITIONAL_ONLY, parameter.POSITIONAL_OR_KEYWORD):
                    counted += 1
                elif parameter.kind == parameter.VAR_POSITIONAL:
                    return 'main takes *args, so how many arguments it is given cannot be read off it'
            return counted


        def _parsed(source, name):
            '''None where source parses, or [message, line, column] where it does not.'''
            try:
                compile(str(source), str(name), 'exec')
                return None
            except SyntaxError as failure:
                return [failure.msg or 'invalid syntax', failure.lineno, failure.offset]


        # ---------------------------------------------------------------- plugins

        class OrknuxPlugin:
            '''What a Python plugin subclasses. The same contract a JavaScript plugin extends, in snake_case.'''

            settings = _types.MappingProxyType({})

            def id(self):
                raise NotImplementedError('a plugin must implement id(), answering what it is called')

            def api_version(self):
                raise NotImplementedError('a plugin must implement api_version(), answering which plugin API it uses')

            def functions(self): return []
            def tools(self): return []
            def parameters(self): return []
            def permissions(self): return []
            def capabilities(self): return []
            def libraries(self): return []
            def skills(self): return []
            def objects(self): return []
            def types(self): return []
            def actions(self): return []
            def connection_types(self): return []


        def _declared(kind, given):
            held = {key: value for key, value in given.items() if value is not None}
            held['_orknux'] = kind
            return held

        def OrknuxFunction(name, run, return_type, description=None, params=None):
            return _declared('function', dict(name=name, run=run, returnType=return_type, description=description, params=params))

        def OrknuxTool(name, run, return_type, description=None, params=None):
            return _declared('tool', dict(name=name, run=run, returnType=return_type, description=description, params=params))

        def OrknuxFunctionTool(function, name=None, description=None):
            return _declared('functionTool', dict(function=function, name=name, description=description))

        def OrknuxParameter(name, type, description=None, required=None, secret=None, connection_type=None, options=None):
            return _declared('parameter', dict(name=name, type=type, description=description, required=required,
                                               secret=secret, connectionType=connection_type, options=options))

        def OrknuxSkill(name, content, description=None, id=None):
            return _declared('skill', dict(name=name, content=content, description=description, id=id))

        def OrknuxObject(name, properties=None, description=None):
            return _declared('object', dict(name=name, properties=properties, description=description))

        def OrknuxType(name, base, description=None, parameters=None, suggest=None, validate=None):
            return _declared('type', dict(name=name, base=base, description=description, parameters=parameters,
                                          suggest=suggest, validate=validate))


        # The contract's own spellings, for keys a Python author writes in snake_case.
        _CAMEL = {'return_type': 'returnType', 'connection_type': 'connectionType',
                  'url_placeholder': 'urlPlaceholder', 'default_url': 'defaultUrl'}

        _METHODS = {'apiVersion': 'api_version', 'connectionTypes': 'connection_types'}

        _held = []


        def _encoded(value):
            '''A declaration as JSON, every callable in it left as a numbered handle the adapter calls back through.'''
            if callable(value):
                _held.append(value)
                return {'__orknuxPy': len(_held) - 1}
            if isinstance(value, dict):
                return {_CAMEL.get(key, key): _encoded(item) for key, item in value.items()}
            if isinstance(value, (list, tuple)):
                return [_encoded(item) for item in value]
            return value


        class _Plugin:
            '''The Python plugin's instance, questioned by the JavaScript adapter.'''

            def __init__(self, cls, settings):
                self.instance = cls()
                self.instance.settings = _types.MappingProxyType(_json.loads(settings or '{}'))

            def ask(self, method):
                method = str(method)
                named = _METHODS.get(method, method)
                found = getattr(self.instance, named, None) or getattr(self.instance, method, None)
                if found is None:
                    return _json.dumps({'value': []})
                try:
                    return _json.dumps({'value': _encoded(found())})
                except BaseException as failure:
                    return _json.dumps({'thrown': _said(failure)})

            def invoke(self, handle, arguments):
                import sys
                try:
                    value = _held[int(handle)](*_json.loads(str(arguments)))
                    return _json.dumps({'value': value})
                except BaseException as failure:
                    return _json.dumps({'thrown': _said(failure)})
                finally:
                    sys.stdout.flush()
                    sys.stderr.flush()


        def _plugin_class(namespace):
            '''The one class the plugin's file defines that extends OrknuxPlugin, or a sentence.'''
            found = [held for held in namespace.values()
                     if isinstance(held, type) and issubclass(held, OrknuxPlugin) and held is not OrknuxPlugin
                     and held.__module__ == '__main__']
            if not found:
                return 'the plugin defines no class that extends OrknuxPlugin'
            if len(found) > 1:
                return 'the plugin defines ' + str(len(found)) + ' classes that extend OrknuxPlugin; it has to be one'
            return found[0]
    """.trimIndent()

    /**
     * The JavaScript class a Python plugin is seen through.
     *
     * Built per evaluation from the Python plugin, so everything past it - the
     * reading at load, the call harness, the permissions, the capabilities - is
     * the JavaScript plugin path, unchanged and unaware. Each declaration is
     * rebuilt through the contract's own classes, so a Python plugin is held to
     * the same checks a JavaScript one is, in the same words; a callable in it
     * becomes a function that calls back into Python with JSON.
     *
     * Executed with the Python side's plugin, whose `ask` and `invoke` are the
     * two doors back; answers the module namespace `evaluated` would have,
     * `{ default }`.
     */
    internal val ADAPTER = """
        (function (python) {
          // Held here rather than read off globalThis at each call, so nothing evaluated later can swap them.
          var ask = python.ask;
          var invoke = python.invoke;
          function revive(key, value) {
            if (value !== null && typeof value === 'object' && typeof value.__orknuxPy === 'number') {
              var handle = value.__orknuxPy;
              return function () {
                var answered = JSON.parse(invoke(handle, JSON.stringify(Array.prototype.slice.call(arguments))));
                if ('thrown' in answered) throw new Error(answered.thrown);
                return answered.value;
              };
            }
            return value;
          }
          function asked(method) {
            var answered = JSON.parse(ask(method), revive);
            if ('thrown' in answered) throw new Error(answered.thrown);
            return answered.value;
          }
          function each(list, method, build) {
            if (!Array.isArray(list)) throw new Error(method + '() did not answer with a list');
            return list.map(build);
          }
          function parameter(one) { return new globalThis.OrknuxParameter(one); }
          class PythonPlugin extends globalThis.OrknuxPlugin {
            id() { return asked('id'); }
            apiVersion() { return asked('apiVersion'); }
            functions() {
              return each(asked('functions'), 'functions', function (one) {
                var declared = new globalThis.OrknuxFunction(one);
                // The text of the wrapper is not what anybody wrote; the editor shows nothing rather than it.
                declared.runSource = null;
                return declared;
              });
            }
            tools() {
              return each(asked('tools'), 'tools', function (one) {
                return one !== null && typeof one === 'object' && 'function' in one
                  ? new globalThis.OrknuxFunctionTool(one)
                  : new globalThis.OrknuxTool(one);
              });
            }
            parameters() { return each(asked('parameters'), 'parameters', parameter); }
            permissions() { return asked('permissions'); }
            capabilities() { return asked('capabilities'); }
            libraries() { return asked('libraries'); }
            skills() { return each(asked('skills'), 'skills', function (one) { var skill = new globalThis.OrknuxSkill(one); if (one && one.id !== undefined) skill.id = one.id; return skill; }); }
            objects() { return each(asked('objects'), 'objects', function (one) { return new globalThis.OrknuxObject(one); }); }
            types() { return each(asked('types'), 'types', function (one) { return new globalThis.OrknuxType(one); }); }
            actions() { return asked('actions'); }
            connectionTypes() { return asked('connectionTypes'); }
          }
          return { default: PythonPlugin };
        })
    """.trimIndent()

    /** Builds the Python side of a plugin and leaves its two doors where [ADAPTER] calls them. */
    internal val PLUGIN_SIDE = """
        def __orknux_plugin(settings):
            import orknux
            found = orknux._plugin_class(globals())
            if isinstance(found, str):
                return found
            return orknux._Plugin(found, settings)
    """.trimIndent()
}

package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NuvioAsyncStart]: a transpiled async function that throws before its first `await`, inside the
 * caller's `try`/`catch`, is caught normally -- run through the REAL QuickJS sandbox, whose
 * alpha13 build otherwise aborts the whole call (docs/plugins/README.md, "The trap"). The helper
 * texts are real transpiler output (esbuild 0.24, TypeScript 5.6 + its esbuild-minified form,
 * Babel 7), copied verbatim.
 */
class NuvioAsyncStartTest {
    private val scraper = NuvioScraperEntry(
        id = "asyncsrc", name = "AsyncSrc", filename = "providers/asyncsrc.js", enabled = true,
        contentLanguage = listOf("es"), supportedTypes = listOf("movie"), logo = null, disabledPlatforms = emptyList(),
    )

    private object EmptyHost : PluginHost {
        override suspend fun fetch(requestJson: String): String {
            val url = JSONObject(requestJson).getString("url")
            return JSONObject().put("ok", true).put("status", 200).put("url", url).put("headers", JSONObject()).put("text", "{}").toString()
        }
        override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String, ttlMs: Long?) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
    }

    /** Runs [script] (a converted plugin) and returns what its stream URL carries after `https://cdn.example/`. */
    private fun play(script: String): String = runBlocking {
        val runtime = PluginRuntime.open("probe", script, EmptyHost, PluginEnv(appVersion = "1.0"))
        try {
            val json = runtime.call("resolve", """{"tmdbId":1,"type":"movie","season":0,"episode":0}""", 5_000)
            JSONObject(json).getString("url").removePrefix("https://cdn.example/")
        } finally {
            runtime.close()
        }
    }

    private fun convert(source: String) = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", tmdbApiKey = "k").script

    /** `inner(null)` throws before its first await; `inner("ok")` answers "ok!" after one. */
    private val innerBody = """if (!x) throw new Error("bad"); return (yield Promise.resolve(x)) + "!";"""
    private val outerBody = """
        var tag;
        try { tag = yield inner(null); } catch (e) { tag = "fallback:" + e.message; }
        var ok = yield inner("ok");
        return [{ name: "x", title: "t", url: "https://cdn.example/" + tag + "-" + ok }];
    """.trimIndent()

    private fun assertCaught(helper: String, wrap: (name: String, params: String, body: String) -> String) {
        val source = helper + "\n" + wrap("inner", "x", innerBody) + "\n" + wrap("getStreams", "", outerBody) +
            "\nmodule.exports = { getStreams: getStreams };\n"
        assertNotEquals("the helper's pattern was found", source, NuvioAsyncStart.defer(source))
        assertEquals("fallback:bad-ok!", play(convert(source)))
    }

    // ---- esbuild ----

    private val esbuild = """
        var __async = (__this, __arguments, generator) => {
          return new Promise((resolve, reject) => {
            var fulfilled = (value) => {
              try {
                step(generator.next(value));
              } catch (e) {
                reject(e);
              }
            };
            var rejected = (value) => {
              try {
                step(generator.throw(value));
              } catch (e) {
                reject(e);
              }
            };
            var step = (x) => x.done ? resolve(x.value) : Promise.resolve(x.value).then(fulfilled, rejected);
            step((generator = generator.apply(__this, __arguments)).next());
          });
        };
    """.trimIndent()
    private val esbuildCall = { name: String, params: String, body: String -> "function $name($params) {\n  return __async(this, null, function* () {\n$body\n  });\n}" }

    @Test fun `esbuild __async`() = assertCaught(esbuild, esbuildCall)

    @Test fun `esbuild __async minified`() = assertCaught(
        """var i=(o,s,e)=>new Promise((c,a)=>{var f=r=>{try{n(e.next(r))}catch(t){a(t)}},w=r=>{try{n(e.throw(r))}catch(t){a(t)}},n=r=>r.done?c(r.value):Promise.resolve(r.value).then(f,w);n((e=e.apply(o,s)).next())});""",
    ) { name, params, body -> "function $name($params){return i(this,null,function*(){$body})}" }

    @Test fun `without the rewrite the engine aborts the whole call, which is why it exists`() {
        val source = esbuild + "\n" + esbuildCall("inner", "x", innerBody) + "\n" + esbuildCall("getStreams", "", outerBody) +
            "\nmodule.exports = { getStreams: getStreams };\n"
        val unrewritten = convert(source).replace(NuvioAsyncStart.defer(source), source)
        val outcome = runCatching { play(unrewritten) }
        // The throw the try/catch was written to catch escapes and fails the call instead.
        assertTrue(outcome.toString(), outcome.exceptionOrNull() is PluginScriptException && "bad" in outcome.exceptionOrNull()!!.message.orEmpty())
    }

    // ---- TypeScript ----

    @Test fun `TypeScript __awaiter`() = assertCaught(
        """
        var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
            function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
            return new (P || (P = Promise))(function (resolve, reject) {
                function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
                function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
                function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
                step((generator = generator.apply(thisArg, _arguments || [])).next());
            });
        };
        """.trimIndent(),
    ) { name, params, body -> "function $name($params) {\n    return __awaiter(this, void 0, void 0, function* () {\n$body\n    });\n}" }

    @Test fun `TypeScript __awaiter minified`() = assertCaught(
        """var __awaiter=this&&this.__awaiter||function(i,d,o,e){function a(t){return t instanceof o?t:new o(function(r){r(t)})}return new(o||(o=Promise))(function(t,r){function h(n){try{c(e.next(n))}catch(u){r(u)}}function s(n){try{c(e.throw(n))}catch(u){r(u)}}function c(n){n.done?t(n.value):a(n.value).then(h,s)}c((e=e.apply(i,d||[])).next())})};""",
    ) { name, params, body -> "function $name($params){return __awaiter(this,void 0,void 0,function*(){$body})}" }

    // ---- Babel ----

    private val babelCall = { name: String, params: String, body: String ->
        "function $name($params) { return _$name.apply(this, arguments); }\n" +
            "function _$name() {\n  _$name = _asyncToGenerator(function* ($params) {\n$body\n  });\n  return _$name.apply(this, arguments);\n}"
    }

    @Test fun `Babel _asyncToGenerator`() = assertCaught(
        """
        function asyncGeneratorStep(n, t, e, r, o, a, c) { try { var i = n[a](c), u = i.value; } catch (n) { return void e(n); } i.done ? t(u) : Promise.resolve(u).then(r, o); }
        function _asyncToGenerator(n) { return function () { var t = this, e = arguments; return new Promise(function (r, o) { var a = n.apply(t, e); function _next(n) { asyncGeneratorStep(a, r, o, _next, _throw, "next", n); } function _throw(n) { asyncGeneratorStep(a, r, o, _next, _throw, "throw", n); } _next(void 0); }); }; }
        """.trimIndent(),
        babelCall,
    )

    @Test fun `older Babel _asyncToGenerator`() = assertCaught(
        """
        function asyncGeneratorStep(gen, resolve, reject, _next, _throw, key, arg) { try { var info = gen[key](arg); var value = info.value; } catch (error) { reject(error); return; } if (info.done) { resolve(value); } else { Promise.resolve(value).then(_next, _throw); } }
        function _asyncToGenerator(fn) { return function () { var self = this, args = arguments; return new Promise(function (resolve, reject) { var gen = fn.apply(self, args); function _next(value) { asyncGeneratorStep(gen, resolve, reject, _next, _throw, "next", value); } function _throw(err) { asyncGeneratorStep(gen, resolve, reject, _next, _throw, "throw", err); } _next(undefined); }); }; }
        """.trimIndent(),
        babelCall,
    )

    // ---- untouched ----

    @Test fun `a source without a transpiler helper is left byte for byte`() {
        val native = """
            async function getStreams() { var p = new Promise((resolve, reject) => { resolve(1); }); await p; return []; }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        assertEquals(native, NuvioAsyncStart.defer(native))
        assertEquals("", NuvioAsyncStart.defer(""))
    }

    @Test fun `a helper's normal result and a rejection after its first await are unchanged`() {
        val source = esbuild + "\n" +
            esbuildCall("late", "", """yield null; throw new Error("late");""") + "\n" +
            esbuildCall("getStreams", "", """
                var why;
                try { yield late(); } catch (e) { why = e.message; }
                var n = yield Promise.all([1, 2].map((v) => __async(this, null, function* () { return (yield v) * 10; })));
                return [{ name: "x", title: "t", url: "https://cdn.example/" + why + "-" + n.join(",") }];
            """.trimIndent()) +
            "\nmodule.exports = { getStreams: getStreams };\n"
        assertEquals("late-10,20", play(convert(source)))
    }
}

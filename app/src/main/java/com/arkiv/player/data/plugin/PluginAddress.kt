package com.arkiv.player.data.plugin

/**
 * Where a plugin lives: a public GitHub repo, optionally a folder inside it and a branch/tag/commit.
 *
 * Accepts what a person would paste: `owner/repo`, `owner/repo/sub/dir`, either with `@ref`, or a
 * `https://github.com/owner/repo[/tree/<ref>/<path>]` URL. Files are read from
 * raw.githubusercontent.com (destination #6 in `.claude/reglas.md`); `HEAD` is the default branch.
 * [canonical] is what `installed.json` stores and what "same plugin, same address" compares.
 */
data class PluginAddress(
    val owner: String,
    val repo: String,
    val path: String = "",
    val ref: String = HEAD,
) {
    val canonical: String
        get() = buildString {
            append(owner).append('/').append(repo)
            if (path.isNotEmpty()) append('/').append(path)
            if (ref != HEAD) append('@').append(ref)
        }

    fun rawUrl(file: String): String =
        "https://raw.githubusercontent.com/$owner/$repo/$ref/" + (if (path.isEmpty()) "" else "$path/") + file

    companion object {
        const val HEAD = "HEAD"
        private val OWNER = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,38}$")
        private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")
        private val GITHUB = Regex("^https?://(www\\.)?github\\.com/", RegexOption.IGNORE_CASE)
        private val RAW = Regex("^https?://raw\\.githubusercontent\\.com/", RegexOption.IGNORE_CASE)

        /**
         * True when [input] is a URL copied from GitHub that names a ref only because the page or
         * file it points at does (`…/tree/<ref>/…`, `…/blob/<ref>/…`, `…/raw/<ref>/…`, or a
         * `raw.githubusercontent.com` file URL): not a pin the person chose. [PluginInstaller.preview]
         * lets a plugin with sealed secrets pasted this way install from the default branch instead.
         */
        fun isBrowsedRefUrl(input: String): Boolean {
            val s = stripUrlNoise(input)
            if (RAW.containsMatchIn(s)) return true
            if (!GITHUB.containsMatchIn(s)) return false
            return GITHUB.replace(s, "").split('/').getOrNull(2) in BROWSE_KINDS
        }

        /**
         * Accepts `owner/repo`, `owner/repo/path…`, `owner/repo@ref` (path allowed) and these URLs:
         * - `https://github.com/owner/repo` (`.git`, `www.` and a trailing slash tolerated)
         * - `https://github.com/owner/repo/tree/<ref>/<path…>` -- a folder
         * - `https://github.com/owner/repo/blob|raw/<ref>/<path…>/<file>.json` and
         *   `https://raw.githubusercontent.com/owner/repo/<ref>/<path…>/<file>.json` (also with
         *   `refs/heads/<branch>` or `refs/tags/<tag>` as the ref) -- a file
         *
         * A file URL must end in a `.json` file (in practice `kino-plugin.json` or Nuvio's
         * `manifest.json`); that last segment is dropped and the address is its folder. Any other
         * file is refused: the address names a folder, and guessing which one a script or icon
         * belongs to isn't worth it. Query strings and fragments are ignored in URLs.
         */
        fun parse(input: String): PluginAddress? {
            var s = input.trim()
            if (s.contains("://")) s = stripUrlNoise(s)
            s = s.trimEnd('/')
            if (s.isEmpty()) return null
            if (RAW.containsMatchIn(s)) {
                val parts = RAW.replace(s, "").split('/')
                if (parts.size < 4) return null
                return fileAddress(parts[0], parts[1], parts.drop(2))
            }
            if (GITHUB.containsMatchIn(s)) {
                val parts = GITHUB.replace(s, "").split('/')
                return when {
                    parts.size == 2 -> build(parts[0], parts[1], emptyList(), HEAD)
                    parts.size >= 4 && parts[2] == "tree" -> build(parts[0], parts[1], parts.drop(4), parts[3])
                    parts.size >= 5 && (parts[2] == "blob" || parts[2] == "raw") -> fileAddress(parts[0], parts[1], parts.drop(3))
                    else -> null
                }
            }
            if (s.contains("://")) return null
            var ref = HEAD
            val at = s.lastIndexOf('@')
            if (at >= 0) {
                ref = s.substring(at + 1)
                s = s.substring(0, at)
            }
            val parts = s.split('/')
            if (parts.size < 2) return null
            return build(parts[0], parts[1], parts.drop(2), ref)
        }

        private val BROWSE_KINDS = setOf("tree", "blob", "raw")

        /** [input] trimmed, without its `?query` or `#fragment`. */
        private fun stripUrlNoise(input: String): String = input.trim().substringBefore('#').substringBefore('?')

        /** [rest] is `<ref>/<path…>/<file>.json` or `refs/heads|tags/<name>/<path…>/<file>.json`. */
        private fun fileAddress(owner: String, repo: String, rest: List<String>): PluginAddress? {
            val (ref, tail) = if (rest.size >= 3 && rest[0] == "refs" && (rest[1] == "heads" || rest[1] == "tags")) {
                rest[2] to rest.drop(3)
            } else {
                rest[0] to rest.drop(1)
            }
            val file = tail.lastOrNull() ?: return null
            if (!file.endsWith(".json", ignoreCase = true) || !NAME.matches(file)) return null
            return build(owner, repo, tail.dropLast(1), ref)
        }

        private fun build(owner: String, repoRaw: String, path: List<String>, ref: String): PluginAddress? {
            val repo = repoRaw.removeSuffix(".git")
            if (!OWNER.matches(owner) || !NAME.matches(repo) || repo == "." || repo == "..") return null
            if (!NAME.matches(ref) || ".." in ref) return null
            if (path.any { !NAME.matches(it) || it == "." || it == ".." }) return null
            return PluginAddress(owner, repo, path.joinToString("/"), ref)
        }
    }
}

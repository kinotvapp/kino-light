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

        fun parse(input: String): PluginAddress? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (GITHUB.containsMatchIn(s)) {
                val parts = GITHUB.replace(s, "").split('/')
                return when {
                    parts.size == 2 -> build(parts[0], parts[1], emptyList(), HEAD)
                    parts.size >= 4 && parts[2] == "tree" -> build(parts[0], parts[1], parts.drop(4), parts[3])
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

        private fun build(owner: String, repoRaw: String, path: List<String>, ref: String): PluginAddress? {
            val repo = repoRaw.removeSuffix(".git")
            if (!OWNER.matches(owner) || !NAME.matches(repo) || repo == "." || repo == "..") return null
            if (!NAME.matches(ref) || ".." in ref) return null
            if (path.any { !NAME.matches(it) || it == "." || it == ".." }) return null
            return PluginAddress(owner, repo, path.joinToString("/"), ref)
        }
    }
}

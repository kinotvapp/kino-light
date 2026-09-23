package com.arkiv.player.data

/**
 * Cleans up a raw file/title name for display, for Magis and Caracol items
 * (`MagisEntities`, `DituEntities`). Pure (no Android dependencies) so it can be tested on the
 * JVM.
 */
object MetadataParser {

    fun stripExtension(path: String): String {
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        return if (dot > slash && dot >= 0) path.substring(0, dot) else path
    }

    /**
     * Display name: without folder or extension, with readable separators.
     *
     * If the file starts with the item's [identifier], that prefix gets stripped: archive.org
     * (removed in this branch's pruning) used to name files that way for many uploads (including
     * ours, where the identifier is a hash), and without stripping it each chapter would show up
     * as "f75163f026d99259e37c 12697 s01e01" instead of "s01e01".
     */
    fun cleanName(path: String, identifier: String? = null): String {
        val file = path.substringAfterLast('/')
        val base = stripExtension(file)
        val stripped = identifier
            ?.takeIf { it.isNotBlank() && base.length > it.length + 1 }
            ?.let { id -> base.removePrefix("${id}_").takeIf { it != base } }
            ?: base
        return stripped.replace('_', ' ').replace("@", " · ").trim()
    }
}

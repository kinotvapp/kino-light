package com.arkiv.player.data.recommendations

import com.arkiv.player.data.ditu.DituRef
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.magis.MagisRef

/**
 * A result's real `kind` ("movie" or "tv"), read from its own `ref` -not from the type that was
 * SEARCHED for-. Exists because of a measured bug: `MagisSource` builds every result with
 * `kind = ctx.type`, so when searching a series every movie from Magis's pool reaches the referee
 * labeled "tv", and the prompt's rule "if I searched a series, a movie does NOT count" can never
 * apply. This fixes it before the referee sees the list.
 *
 * `null` when the ref isn't understood (neither Magis's nor Caracol's): in that case the caller
 * leaves `kind` as it came.
 */
internal fun realKindOfRef(ref: String): String? {
    MagisRef.decode(ref)?.let { return if (it.isSeries) "tv" else "movie" }
    DituRef.decode(ref)?.let { return if (it.isSeries) "tv" else "movie" }
    return null
}

/** [result] with its `kind` fixed per [realKindOfRef], or as-is if the ref isn't understood. */
internal fun withRealKind(result: GatewayResult): GatewayResult =
    realKindOfRef(result.ref)?.let { result.copy(kind = it) } ?: result

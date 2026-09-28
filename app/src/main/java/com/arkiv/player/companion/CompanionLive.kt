package com.arkiv.player.companion

import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.playback.PlayerSource

/** What the TV does with a companion `live` send. */
internal sealed interface LiveSendTarget {
    data class Play(val episodeId: String) : LiveSendTarget
    data class Refuse(val reason: String) : LiveSendTarget
}

/**
 * A companion `live` send's [liveCode] is `<code>` (Xuper, unchanged since before providers) or
 * `plugin:<pluginId>:<code>`. A Xuper channel needs this TV's linked Magis account, as before. A
 * plugin channel needs only its plugin: the player says which to install or switch on when it
 * is missing.
 */
internal fun liveSendTarget(liveCode: String, hasMagisLink: Boolean): LiveSendTarget {
    val (provider, _) = LiveChannelKeys.parse(liveCode) ?: return LiveSendTarget.Refuse("bad_live_code")
    if (provider == LiveChannelKeys.XUPER && !hasMagisLink) return LiveSendTarget.Refuse("no_link")
    return LiveSendTarget.Play(PlayerSource.LIVE_PREFIX + liveCode)
}

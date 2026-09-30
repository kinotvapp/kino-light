package com.arkiv.player.playback

/**
 * Which audio outputs the device has connected, in words, for a report.
 *
 * Audio that arrives late or early on a Bluetooth headset or an HDMI/ARC receiver is the classic cause of "the audio
 * is out of sync" that no player can see, so the route is the first thing to know about a sync report. The types are
 * `AudioDeviceInfo.TYPE_*` as plain ints, so this stays JVM-testable. Android lists what is CONNECTED, not which
 * output is playing right now: a TV always lists its speaker next to HDMI.
 */
internal object AudioRoute {

    private val names = mapOf(
        1 to "earpiece",
        2 to "speaker",
        3 to "wired_headset",
        4 to "wired_headphones",
        5 to "line_analog",
        6 to "line_digital",
        7 to "bt_sco",
        8 to "bt_a2dp",
        9 to "hdmi",
        10 to "hdmi_arc",
        11 to "usb_device",
        12 to "usb_accessory",
        13 to "dock",
        22 to "usb_headset",
        23 to "hearing_aid",
        26 to "ble_headset",
        27 to "ble_speaker",
        29 to "hdmi_earc",
        30 to "ble_broadcast",
    )

    private val bluetooth = setOf(7, 8, 26, 27, 30)

    /** The connected outputs, each named once and sorted; `none` when there are none. */
    fun describe(types: List<Int>): String =
        types.map { names[it] ?: "type$it" }.distinct().sorted().joinToString(",").ifEmpty { "none" }

    fun hasBluetooth(types: List<Int>): Boolean = types.any { it in bluetooth }
}

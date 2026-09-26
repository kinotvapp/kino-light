package com.arkiv.player.crash

/**
 * Handled telemetry signals (NOT crashes): silent, non-fatal conditions that hurt the user but
 * never throw, so they never reached the logs or GlitchTip on their own. Each is reported through
 * [Crash.report] (a no-op when Sentry is off), and each is its OWN type so GlitchTip groups them
 * into distinct issues, with the useful context in the message. Adding proactive signal here is how
 * we find problems before a user has to report one.
 */

/** The backup seed pool is exhausted for a device that needs seeds: every fresh anonymous seed came
 *  back dead too, so the user can't play. Actionable: the Mac minter should re-mint the pool. */
class SeedPoolExhausted(message: String) : Exception(message)

/** A title the user PICKED (a real TMDB id) returned zero playable sources from the portal -- the
 *  "0 fuentes" screen. Usually a name mismatch (Xuper lists it under another title) or content the
 *  portal doesn't carry. The message lists the exact queries tried, so gaps are findable. */
class NoSourcesFound(message: String) : Exception(message)

/** A portal error code reached the USER (it survived the session rescue): `aaa100028` (dead
 *  session), `portal100024` (geo-blocked), a gateway failure, etc. Tells us which codes hit real
 *  users and how often -- the earliest signal of a dying seed pool, a new geo-block, or a portal
 *  API change. */
class PortalErrorReached(message: String) : Exception(message)

/** A LIVE channel could not be resolved/played for a reason other than a plain portal error:
 *  no CDN, no addresses, or "needs a linked account". Measures which channels/regions fail (e.g.
 *  the Bolivia live reports). */
class LiveResolveFailed(message: String) : Exception(message)

/** A catalog fetch for an ACTIVATED device came back empty (no VOD rows) -- the "home cargó pero
 *  no pintó nada" symptom. Points at a dead session, a geo-block, or a portal change. */
class EmptyCatalog(message: String) : Exception(message)

/** The OTA download failed its integrity/HTTP check (sha mismatch, HTTP error). Surfaces
 *  deploy/CDN problems like the "app corrupta" mismatch without waiting for a user to say so. */
class OtaDownloadFailed(message: String) : Exception(message)

/** Activation of a device failed (a fresh install couldn't obtain its credentials, or a refresh
 *  couldn't re-apply them). The user is stuck on the activation screen. */
class ActivationFailed(message: String) : Exception(message)

/** Startup warm-up of the heavy credential/Magis chain took long enough to risk an ANR on a weak
 *  device. Early signal of the slow-device startup problem. The message is constant; the measured
 *  duration and the model travel as Sentry extras (see `Crash.report`) so one issue collects them all. */
class SlowStartup(message: String) : Exception(message)

/** On-disk cache pressure: the Chromecast remux cache (or another cache) was large enough to be a
 *  storage/`SQLITE_FULL` risk. Reported with the bytes involved. */
class StoragePressure(message: String) : Exception(message)

/** An offline (Caracol) download failed. Tells us which titles/devices can't download. */
class OfflineDownloadFailed(message: String) : Exception(message)

/** A device that auto-detection classified as a HANDHELD but looks like it could be a misread TV
 *  box (large landscape screen). Carries its raw DeviceType signals so a real threshold can be set
 *  from field data instead of guesses. One-shot per process; see ArkivApp. */
class DeviceProfile(message: String) : Exception(message)

/** A device was measured too slow for the decorative effects (the drifting hero backdrop), so they were
 *  turned off for it. The message is constant; its model, RAM, share of dropped frames and whether the
 *  sample was severe travel as Sentry extras (see `Crash.report`), to tune the thresholds from real
 *  devices. One-shot per device: only the sample that flips it reports. */
class EffectsReduced(message: String) : Exception(message)

/** How fast a device is at startup: time to the first frame since the process started, plus a fixed CPU
 *  benchmark, next to its model, RAM and cores. TELEMETRY ONLY, once per device per app version, to pick
 *  real "slow device" thresholds from field data. The message is constant; the figures are Sentry extras
 *  (see `Crash.report`), so one GlitchTip issue collects every device. See `StartupProfiler`. */
class StartupProfile(message: String) : Exception(message)

/** A DLNA cast to a TV failed or died. The message carries only the stage (`dlna cast failed: set_uri`,
 *  `renderer_never_fetched`, `stopped_early`…), so GlitchTip keeps one issue per stage; the details
 *  (renderer manufacturer/model, kind of cast, MIME, HTTP and UPnP error codes, whether the TV ever
 *  requested the media, what it can play) travel as Sentry extras, and the cast's log lines ride along as
 *  breadcrumbs. Reported once per cast. See `DlnaController`. */
class DlnaFailure(message: String) : Exception(message)

/** A live channel played badly enough to notice: it froze waiting for data, dropped frames, glitched the audio
 *  or failed to load segments. The message is constant; why (`reason`), the channel, the device and the counts
 *  (stalls and their length, dropped frames, audio underruns, load errors, segment load times, decoder and
 *  whether it is software) travel as Sentry extras, and the channel's `ArkivLive` log lines ride along as
 *  breadcrumbs. Reported once per viewing session. See `LiveQualityMonitor`. */
class LivePlaybackQuality(message: String) : Exception(message)

/**
 * A live channel showed no picture (or a frozen one) on its hardware video decoder and was reopened with a software
 * one. The extras name the decoder and the device, so the chips that can't take the live streams show up on their
 * own instead of by user reports of "sound but no image".
 */
class LiveDecoderSwitched(message: String) : Exception(message)

/**
 * A live channel's playlist error (fell behind the live window, the playlist reset or froze) was fixed by seeking
 * to the live edge and preparing again, without the full reopen (re-resolving the session) that used to be the only
 * way out. Reported once per actual recovery, not per retry -- a stuck playlist keeps hitting the same error for
 * as long as it stays stuck, and only the recovery that fires counts. Tells us how often this class of error still
 * happens and whether it keeps getting fixed in place instead of reaching the person as a cut signal. See
 * `InPlaceRecoveryBudget` and `errorKind` in `LiveExoPlayer`.
 */
class LiveInPlaceRecovery(message: String) : Exception(message)

/**
 * A live channel's session was a shared seed (see `LiveSeedRotation`) and the CDN answered `409 Conflict` to its
 * playlist, or the portal refused the seed on resolve: the channel moved to another seed from the backup pool, or,
 * once the small rotation budget ran out, gave up and went back to the device's own session. Reported once per
 * actual state change (`outcome` extra: `rotated` / `rotated_after_resolve_failure` / `exhausted`), not per retry --
 * the player asks for the same stuck playlist repeatedly, and duplicate refusals of the seed already in use don't
 * report again. The point is measuring whether rotating seeds actually clears the conflicts that used to reach the
 * person as "se cortó la señal", not counting every retry.
 */
class LiveSeedRotated(message: String) : Exception(message)

/**
 * A Chromecast session failed silently: the receiver's own `onPlayerError`, or -- with no exception at all --
 * the tell-tale "audio plays, picture doesn't" (the receiver accepted the audio track and rejected every video
 * one, usually an old Chromecast that can't decode the profile) or a receiver stuck on its idle logo long after
 * being handed media. The message carries only the reason (`player_error: <code>`, `video_track_unsupported`,
 * `stuck_loading`), so GlitchTip keeps one issue per reason; the receiver's model, the episode, the attempted
 * mime/codec and the playback state travel as Sentry extras. Reported once per cast session. See
 * `CastSessionManager`.
 */
class CastFailure(message: String) : Exception(message)

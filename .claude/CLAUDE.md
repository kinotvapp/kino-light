# Kino (open-source app) — notes for whoever works on this repo

This repository is **the public open-source app** of Kino: a small open-source app with Kino's look (splash,
logo, colors, phone and TV layouts) that plays a few public-domain films from the Internet Archive.
Everything else is **mock**: fake data in memory, buttons that only say "eso está en la app
completa" (`fullAppOnly(context)`).

The full Kino app is closed source and lives elsewhere. This repo only mirrors its **interface**.

## Rules

- **Mock only.** Never port real logic, network calls, keys, plugin runtimes, sync, cast, DRM or
  anything that would make a mock screen actually work. The only network this app uses is the
  Internet Archive posters and videos listed in `app/src/main/assets/home.json`.
- **No real third-party service names** in mock data (plugins, channels, addons): use fictional or
  generic names. Never name the services the full app's community plugins talk to. The plugin
  format names "Kino", "Stremio" and "Nuvio" stay visible (owner's call, 2026-10-05).
- **No private details**: no internal hosts, tokens, device IPs, private repo names or commit hashes.
- Language: what the person sees is Spanish (Bogotá, tuteo, never voseo); code, identifiers,
  comments and commit messages are English.
- **Never label this app as a trial or sample version** anywhere: code, identifiers, user texts, docs
  and commit messages (owner's rule). Commit messages also never say "mock", "mirror" or "fake":
  write them as ordinary app development, e.g. "Plugins: three tabs with category chips". Check with
  `git grep -i` for the d-word before every push ("respondemos" is a false hit).
- Commits as `kinotvapp` (`332969319+kinotvapp@users.noreply.github.com`), no `Co-Authored-By`.
- Phone and TV are separate UIs (`ui/phone`, `ui/tv`); a new screen usually needs both.
- Every user-visible change goes in `CHANGELOG.md` and, if it changes the table, in `README.md`.

## Build

`./gradlew assembleDebug` (needs `local.properties` with `sdk.dir=...`, git-ignored).
Unit tests: `./gradlew testDebugUnitTest`.

## Sync log

`.claude/sync-log.md` records which interface pieces of the full app have been mirrored here,
when, and what is still missing. **Update it in the same commit as every change.**

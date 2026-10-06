# Kino demo — notes for whoever works on this repo

This repository is **the public demo** of Kino: a small open-source app with Kino's look (splash,
logo, colors, phone and TV layouts) that plays a few public-domain films from the Internet Archive.
Everything else is **mock**: fake data in memory, buttons that only say "eso está en la app
completa" (`fullAppOnly(context)`).

The full Kino app is closed source and lives elsewhere. This repo only mirrors its **interface**.

## Rules

- **Mock only.** Never port real logic, network calls, keys, plugin runtimes, sync, cast, DRM or
  anything that would make a mock screen actually work. The only network the demo uses is the
  Internet Archive posters and videos listed in `app/src/main/assets/home.json`.
- **No real third-party service names** in mock data (plugins, channels, addons): use fictional or
  generic names. Never name the services the full app's community plugins talk to.
- **No private details**: no internal hosts, tokens, device IPs, private repo names or commit hashes.
- Language: what the person sees is Spanish (Bogotá, tuteo, never voseo); code, identifiers,
  comments and commit messages are English.
- **Commit messages never mention that this is a demo or a mock** (no "demo", "mock", "mirror",
  "fake"): write them as ordinary app development, e.g. "Plugins: three tabs with category chips".
- Commits as `kinotvapp` (`332969319+kinotvapp@users.noreply.github.com`), no `Co-Authored-By`.
- Phone and TV are separate UIs (`ui/phone`, `ui/tv`); a new screen usually needs both.
- Every user-visible change goes in `CHANGELOG.md` and, if it changes the table, in `README.md`.

## Build

`./gradlew assembleDebug` (needs `local.properties` with `sdk.dir=...`, git-ignored).
Unit tests: `./gradlew testDebugUnitTest`.

## Change log of the demo

`.claude/demo-sync.md` records which interface pieces of the full app have been mirrored into the
demo, when, and what is still missing. **Update it in the same commit as every change.**

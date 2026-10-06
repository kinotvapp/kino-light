# Demo sync log

What of the full app's interface is mirrored here (as mock), newest first. Each entry: date, what
was added or changed on phone / TV, and what was left out on purpose.

**Mirrored through:** full-app interface up to 2026-10-02 (the next update starts from the full
app's merges after this date; bump it when an update lands).

**How to update next time:** list the full app's merges since the date above, keep only what the
person sees (screens, tabs, buttons, rows, dialogs), add each one here as mock on phone and TV,
then move the date.

## 2026-10-05 (part b)

- Phone Ajustes: grey section cards (`ui/settings/SettingsCards.kt`), rows/notes inside cards, red only for
  destructive rows, tab row with edge fade and "›". TV Ajustes keeps its own widgets.
- Ajustes ▸ App: "Licencias de software libre" (phone dialog, TV full screen; real runtime libraries from
  build.gradle.kts with a short Apache-2.0 notice) and "Buscar actualizaciones" ("Buscando…" → toast
  "Ya tienes la última versión"). Phone and TV.
- Conectar: one card per paired TV (phone) / phone (TV) with "Sincronizar con este dispositivo", status
  line, "Olvidar"; "Sincronizar ahora" → "Sincronizando…" 1.5 s; connected TV in green
  (`ui/settings/DemoCompanion.kt`). Left out: the red pending dot on the tab and the pending banner.
- Player: "Audio y subtítulos" dialog (Servidor Opción 1/2/3 with the fallback note, Audio, Subtítulos del
  archivo, "Sincronizar subtítulos" ±0,1/±0,5 s cosmetic, "Quitar el ajuste"); "Saltar intro" in the first
  2 min (jumps 85 s, once per film; holds the D-pad focus on TV); "Corregir intro y outro" dialog. Phone and TV.
- Phone mini player (`ui/remote/MiniPlayer.kt`) as the Scaffold bottom bar while a TV is connected: bar,
  full-screen remote, Audio/Subtítulos pickers, "Detener" (hides it for the session), "¿Qué TV quieres
  controlar?" sheet. "Desconectar" in Conectar hides it too. Phone only (the TV is the other side).
- Downloads (phone): a row "Eligiendo la mejor copia…" with indeterminate progress; "Quitar" and
  "Quitar todos" clear rows in memory.

## Baseline — Demo 1.0.0 (2026-10-03)

Splash, Home rows from `home.json`, title page, player (media3) with Kino's controls, search over
`home.json`, About. Mock screens: Categorías, Biblioteca, Descargas, En vivo, Plugins
(Recomendados / Instalados + "De la comunidad" section), Ajustes (Subtítulos / App / Conectar),
"Elige tus fuentes", Kinobot. Phone and TV.

## Pending

- 2026-10-05: mirror the interface added to the full app since 2026-10-02 (in progress).

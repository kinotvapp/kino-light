# Demo sync log

What of the full app's interface is mirrored here (as mock), newest first. Each entry: date, what
was added or changed on phone / TV, and what was left out on purpose.

**Mirrored through:** full-app interface up to 2026-10-02 (the next update starts from the full
app's merges after this date; bump it when an update lands).

**How to update next time:** list the full app's merges since the date above, keep only what the
person sees (screens, tabs, buttons, rows, dialogs), add each one here as mock on phone and TV,
then move the date.

## 2026-10-05

- **Plugins — three tabs (phone + TV):** Recomendados | De la comunidad | Instalados (n); the phone's
  round "+" sits beside the "Plugins" heading. Shared "Buscar plugins" text, per-tab category chips
  (Todos, Películas, Series, Anime, En vivo, Radio, Subtítulos, Utilidades — only those a tab's cards
  have), "No hay plugins que coincidan.", the community note, Stremio/Nuvio format badges and the
  "Gratis y legal" pill. Mock: the catalog in `DemoData.kt` is fictional; the TV now opens its own
  Plugins screen (`TvPluginsScreen.kt`) from the rail.
- **"Agregar un plugin" (phone + TV):** "Tipo de plugin" Kino / Nuvio / Stremio, the type's own
  instruction, the address field, "Agregar" / "Cancelar". Mock: "Agregar" reads "Revisando…" for a
  moment and then says it is in the full app; nothing is fetched.

## Baseline — Demo 1.0.0 (2026-10-03)

Splash, Home rows from `home.json`, title page, player (media3) with Kino's controls, search over
`home.json`, About. Mock screens: Categorías, Biblioteca, Descargas, En vivo, Plugins
(Recomendados / Instalados + "De la comunidad" section), Ajustes (Subtítulos / App / Conectar),
"Elige tus fuentes", Kinobot. Phone and TV.

## Pending

- 2026-10-05: mirror the interface added to the full app since 2026-10-02 (in progress).

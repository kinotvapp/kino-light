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
- **Installed plugin settings (phone + TV):** "Gestionar" (phone) / OK on the card (TV) opens Activo,
  "Modo debug" with its line, "Ver registro" → "Registro de <plugin>" (a few invented log lines,
  "Copiar registro" / "Compartir registro" (phone) / "Volver"), Desinstalar. Instalados ends with
  "Tus colecciones de Stremio" (one invented collection; "Explorar" → its addons, "Instalar") and,
  phone only, "Tus repositorios de Nuvio" ("Ver scrapers", "Quitar"). Mock: copy, share, uninstall,
  install and "Ver scrapers" say it is in the full app; switches and "Quitar" are in memory.
- **Plugin-updates bell (phone + TV):** a bell with its count on Inicio (phone top bar; TV top right,
  beside reload) opens "Novedades de tus plugins": "Esperan tu aprobación" (a row with "Revisar") and
  "Se actualizaron" ("Nombre v1.2.0 → v1.3.0" and the day), "Cerrar". The phone's menu button and its
  drawer's Plugins carry the count; the TV rail's Plugins carries it and, open, "1 por aprobar". Mock:
  the updates are invented and "Revisar" says it is in the full app.
- **Buscar por fuente (phone + TV):** phone: a source icon beside the field; TV: a button above the
  results. The picker lists "Todas las fuentes" and the installed, active plugins that search (format
  badge, the chosen one red with "Elegida"); the phone shows a removable "En: <plugin>" chip, the TV
  names the button so. Mock: the choice is cosmetic; results still come from `home.json`.
- **En vivo — radio and sources (phone + TV):** three invented sources (Canales Abiertos, Radio del
  Mundo, Mis canales) and four radio stations whose logo-less tile shows a radio glyph. Phone: a source
  chips row always on screen with the chosen one lit, then the source's categories and "Mis canales y
  listas". Mock: opening a station says it is in the full app; "Mis canales y listas" too.
- **En vivo on TV, redesigned:** provider tabs on top (colour dot and underline) with Buscar and
  Recargar as round icons; the left rail with Favoritos, Recientes, the categories grouped under
  headers with their counts and "Mis canales y listas" last; a compact channel list (provider badge in
  mixed lists); the preview panel ("EN VIVO", "Ahora: …", "OK para ver …"); 🔍 swaps the rail for the
  keyboard ("Buscar canal por nombre o número…"); empty states for Favoritos, Recientes, search and Mis
  canales. Mock: the preview is the film's poster, not a mini-player; Recargar says it is in the full app.

## Baseline — Demo 1.0.0 (2026-10-03)

Splash, Home rows from `home.json`, title page, player (media3) with Kino's controls, search over
`home.json`, About. Mock screens: Categorías, Biblioteca, Descargas, En vivo, Plugins
(Recomendados / Instalados + "De la comunidad" section), Ajustes (Subtítulos / App / Conectar),
"Elige tus fuentes", Kinobot. Phone and TV.

## Pending

- 2026-10-05: mirror the interface added to the full app since 2026-10-02 (in progress: Plugins,
  bell, Buscar por fuente and En vivo done; Settings, player, mini-player and Downloads in a separate
  branch).

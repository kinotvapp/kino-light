# Kino — demo

**Kino** es un reproductor de video para Android, para celular y para Android TV. Le pones fuentes
(plugins), tus canales y listas M3U, y Kino lo reproduce todo con la misma interfaz: inicio con
filas de títulos, búsqueda, biblioteca, En vivo, descargas, envío a la TV y sincronización entre
tus aparatos.

> **Este repositorio es solo una demo.** Contiene una app pequeña y de código abierto que tiene la
> misma cara que Kino (la animación de inicio, el logo, los colores y el diseño de las pantallas en
> celular y en TV) y reproduce algunas películas **de dominio público en EE. UU.** según el Internet Archive, que las aloja.
> Todo lo demás está de adorno: los plugins, En vivo, las descargas, el envío a la TV y la
> sincronización se ven pero no hacen nada.
>
> **La app completa de Kino es de código cerrado** y no está en este repositorio.

## Descargar Kino completo

- **Releases de este repositorio:** https://github.com/kinotvapp/kino-light/releases/latest (un APK por arquitectura y el universal)
- **archive.org:** https://archive.org/details/kino-app
- **Downloader (Android TV / Fire TV):** códigos **6793041** o **7152029**
- **Grupo de Telegram:** https://t.me/Kinoapptv · **Novedades:** https://t.me/KinoTVApk · **Ayuda:** @kinocodigo_bot
- **Sugerencias:** https://www.reddit.com/r/KinoTv

Kino es gratis, y siempre lo será. Si ves una copia que cobra, no es la oficial.

## Para quienes hacen plugins

Los plugins de Kino tienen su documentación pública:

- Guía y referencia: https://kinotvapp.github.io/kino-plugins
- Repositorio: https://github.com/kinotvapp/kino-plugins

## Capturas

Las capturas van en [`docs/screenshots/`](docs/screenshots/) (por ahora está vacía).

## Qué trae la demo

| Pantalla | Estado |
|---|---|
| Animación de inicio | Igual que en Kino |
| Inicio (celular y TV) | Funciona: filas de `home.json` |
| Ficha de la película | Funciona, con "Reproducir" |
| Reproductor | Funciona: ExoPlayer (media3) con los controles de Kino |
| Buscar | Funciona: filtra `home.json` en el aparato |
| Categorías, Biblioteca, Descargas, En vivo, Plugins, Ajustes, Elige tus fuentes, Kinobot | De muestra, con datos inventados |
| Acerca de | Funciona |

Lo que sale en las pantallas de muestra es inventado: los canales, los plugins de ejemplo y las
descargas no existen. Los botones que harían algo de verdad (instalar, descargar, conectar…) solo
avisan que eso está en la app completa.

La demo no tiene cuentas, analítica ni reportes de errores. Lo único que pide por internet son los
videos del Internet Archive que están en `home.json`; los pósters son fotogramas de las propias
películas, incluidos en la app (`assets/posters/`).

## Compilar la demo (dev)

Requirements: JDK 17 and the Android SDK (platform 36). Point Gradle at the SDK with
`local.properties` (`sdk.dir=/path/to/Android/sdk`) or `ANDROID_HOME`.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew lint
```

- Package: `app.kino.demo` (installs next to the full app without replacing it).
- Kotlin, Jetpack Compose (+ Compose for TV), media3 ExoPlayer, Coil. minSdk 24.
- The catalog is `app/src/main/assets/home.json`: `rows[]` → `items[]` with `id`, `title`,
  `year`, `durationMin`, `synopsis`, `poster`, `video` and `source` (the archive.org page that
  states the film's public-domain status).

## Licencia

El código de esta demo está bajo [Apache-2.0](LICENSE). **El nombre "Kino" y su logo no están
licenciados** (todos los derechos reservados): ver [NOTICE](NOTICE). Las librerías de terceros
están en [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Ver también: [LEGAL.md](LEGAL.md) · [PRIVACY.md](PRIVACY.md) · [SECURITY.md](SECURITY.md) ·
[CHANGELOG.md](CHANGELOG.md)

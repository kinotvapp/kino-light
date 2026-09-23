# Kino

Kino es un reproductor de películas y series para el celular y el televisor (Android TV / Fire TV).
Es el mismo APK para los dos: se instala igual en cualquiera de los dos aparatos, y él solo se
acomoda a una pantalla grande con control remoto o a una pantalla de celular con los dedos.

## Qué hace

- **Ver películas y series**, con póster, sinopsis y las temporadas y capítulos ordenados.
- **Canales en vivo.**
- **Buscar** cualquier título entre todas las fuentes a la vez.
- **Categorías**, para explorar por género.
- **Mi biblioteca**: guarda lo que te interesa y avisa cuando sale un capítulo nuevo de algo guardado.
- **Continuar viendo**: recuerda en qué minuto ibas, en cualquiera de tus aparatos.
- **Descargar** películas y capítulos para verlos sin internet.
- **Chromecast**: manda lo que ves a un Chromecast o televisor compatible.
- **Subtítulos**, con el idioma que prefieras.
- **Recomendaciones**, según lo que ya viste.
- Una sección de anime.

## En el televisor

La versión de TV tiene su propio diseño, pensado para verse y manejarse con el control remoto:
control por flechas, foco grande y legible, y las mismas funciones que en el celular.

## Sin publicidad y sin rastreadores

Kino no trae publicidad, ni rastreadores que reporten lo que ves, ni instala nada aparte de la
app misma.

## Estructura

Un solo módulo Android (Kotlin + Jetpack Compose). La UI vive en `app/src/main/java/.../ui`
(pantallas de celular y de TV), el reproductor en `.../ui/player`, y la capa de datos (catálogo,
biblioteca, descargas) en `.../data`. El emparejamiento celular↔TV por LAN está en `.../companion`.

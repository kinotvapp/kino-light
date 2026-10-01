# Internet Archive plugin for Kino

A plugin for the Kino video app that brings the public-domain films and classic TV of
[archive.org](https://archive.org) into Kino's search, Home and player. It is the reference example
for plugin authors: one manifest, one JavaScript file, no build step.

## What it does

| Capability | How |
| --- | --- |
| `search` | Titles in both the `feature_films` (movies) and `classic_tv` (series) collections, up to 25 from each. Besides what was typed (`q`), it asks for the `originalTitle` and up to two of the `altTitles` Kino sends (at most four distinct titles, each cut to its head with `kino.rank.shortQuery`, all asked at once), so a Spanish title finds a film archive.org lists under its original one ("Asalto y robo de un tren" finds *The Great Train Robbery*). An item found more than once is listed once; the rest are ranked with `kino.rank.sortBySimilarity` against every title, near-misses are dropped with `kino.rank.filterRelevant` (an item whose identifier is the title itself always stays), and when Kino knows the `year`, what is from that year (±1) comes first among equals. The `type` Kino sends is only an ordering preference (the collection that matches it comes first), never a filter, because TMDB's movie/tv split does not line up with archive.org's: public-domain films and classic TV are mixed, and a title can exist as both. Characters and words that are query syntax to archive.org (`/`, `-`, `&`, `AND`, `OR`, `NOT`) are cleaned out of what the person typed. |
| `home` | Three rows: public-domain films, classic TV and classic animation, by downloads, 30 titles each, after the person's own rows if they set addresses (see below). Each row carries a `ref`, so Kino ends it with a "Ver más" card. |
| `browse` | "Ver más" on a Home row: the same query as the row, 50 titles per page, the page number as the cursor (`"2"`, `"3"`, …). |
| `episodes` | The video files of an item, in natural order. Files named `S01E02` get that season and number; otherwise they are numbered 1, 2, 3 in order. |
| `resolve` | The file to play: the item's own mp4/m4v/webm, or the best mp4 archive.org derived from the original (`.avi`, `.mpg`, `.mkv`, `.divx`...). Sibling `.vtt`/`.srt` files become subtitles. |
| `download` | Declarative, no code: Kino offers the titles for offline viewing on phones and saves what `resolve` returns. That is always one progressive file (mp4, m4v or webm, with its `mime`), never an HLS/DASH manifest, so every title that plays can be downloaded. Installing or updating to a version with it shows "Puede descargar videos para verlos sin conexión". |

Two things it does not try to be clever about, so do not copy them as intended behavior:
an item found inside a collection or in the built-in rows that bundles several films is exposed as
a single `movie`, and `resolve` plays its first video in natural name order (put its own address in
Configurar to get one card per video); and episodes numbered `S01E00` (a pilot, a special)
are dropped by Kino, whose episode numbers start at 1.

## Your own addresses (Configurar)

Under Ajustes > Plugins > Internet Archive > Configurar the person can fill up to six archive.org
addresses (Dirección 1 to 6), each with an optional category name (Categoría 1 to 6):

| Address | What it lists |
| --- | --- |
| `https://archive.org/details/<collection>` | the videos of that collection |
| `https://archive.org/details/<item>` | that item (one with no collection filed under it): a single video is one card; an item with several videos is one card per video (`<item>~1`, `<item>~2`, ...), each playing its own file, and a search also looks at their titles |
| `https://archive.org/search?query=...` | the videos a search returns (movies only) |

Each address becomes a Home row of its own, before the three built-in ones, newest additions first
(`addeddate desc`), with the same "Ver más" paging. Addresses that share a category name (capitals
do not matter; the row keeps the first spelling) are merged into one row for that name, asked of
archive.org as a single `OR` query. A category with no valid address behind it makes no row. What is
inside the addresses is also searched (by every title asked, in one request), and comes first in Kino's search results, unranked. Anything that is not
archive.org, or not one of the three forms above, is ignored: the plugin only ever talks to
archive.org. With no addresses the plugin behaves exactly as before.

These are twelve plain settings (`url1`/`cat1` ... `url6`/`cat6`), so the manifest stays at apiVersion 3 and installs
on every Kino from 0.9.43 on. Versions 1.3.0 to 1.4.1 used one `list` setting (`sources`, apiVersion 4) that Kino
0.9.43 could not install; addresses saved in that list are not carried over and have to be typed again.

## Hosts, and why `*.archive.org`

The manifest declares `archive.org` and `*.archive.org`, and the plugin can only talk to those.
`https://archive.org/download/...` answers with a redirect to a storage node such as
`dn720705.ca.archive.org`, and a wildcard does not cover its own bare domain (`*.archive.org` does
not match `archive.org`), so both are listed. Kino shows the list to the person before installing.

## Install it in Kino

In Kino open Ajustes > Plugins and type the address of this repository:

```
kinotvapp/kino-plugin-archive
```

Kino reads `kino-plugin.json` and `plugin.js` from the repository root, shows the hosts the plugin
will reach and asks for approval before anything runs.

## Write your own plugin

This repository is also the starting point for your own plugin:

- [`GUIDE.md`](GUIDE.md) is the authoring guide: file layout, manifest and settings, the five
  functions your code can export, the `kino` API, every limit, the quirks of the JavaScript engine
  and five cookbook recipes.
- [`contract.json`](contract.json) holds every number and rule Kino enforces, and
  [`kino.d.ts`](kino.d.ts) declares the `kino` API for your editor.
- [`sdk/`](sdk) lets you run and test a plugin on your computer with Node 18 or newer, using the same
  `kino` API as the app and checking what you return the way Kino does:

```
node sdk/run.mjs ./plugin.js search "metropolis"
node sdk/run.mjs ./plugin.js home
node sdk/run.mjs ./plugin.js browse films 2
node sdk/validate.mjs .
node sdk/init.mjs ../my-plugin --host example.com
```

Copy `plugin.js` and `kino-plugin.json`, change them, and publish your repository the same way.

## License

The code in this repository is licensed under the [Apache License 2.0](LICENSE). Copyright 2026 kinotvapp.

## License note

What this plugin plays is not ours to license: the videos are public domain or carry the license
their uploader chose on archive.org. Check an item's page before you reuse or redistribute it.

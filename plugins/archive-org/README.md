# Internet Archive plugin for Kino

A plugin for the Kino video app that brings the public-domain films and classic TV of
[archive.org](https://archive.org) into Kino's search, Home and player. It is the reference example
for plugin authors: one manifest, one JavaScript file, no build step.

## What it does

| Capability | How |
| --- | --- |
| `search` | Titles in the `feature_films` (movies) and `classic_tv` (series) collections, most downloaded first. Characters and words that are query syntax to archive.org (`/`, `-`, `&`, `AND`, `OR`, `NOT`) are cleaned out of what the person typed. |
| `home` | Three rows: public-domain films, classic TV and classic animation, by downloads. |
| `episodes` | The video files of an item, in natural order. Files named `S01E02` get that season and number; otherwise they are numbered 1, 2, 3 in order. |
| `resolve` | The file to play: the item's own mp4/m4v/webm, or the best mp4 archive.org derived from the original (`.avi`, `.mpg`, `.mkv`, `.divx`...). Sibling `.vtt`/`.srt` files become subtitles. |

Two things it does not try to be clever about, so do not copy them as intended behavior:
an item that bundles several films (a collection) is exposed as a single `movie`, and `resolve`
plays its first video in natural name order; and episodes numbered `S01E00` (a pilot, a special)
are dropped by Kino, whose episode numbers start at 1.

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

- [`GUIDE.md`](GUIDE.md) is the authoring guide: file layout, manifest, the four functions your
  code exports, the `kino` API, every limit and the quirks of the JavaScript engine.
- [`sdk/`](sdk) lets you run and test a plugin on your computer with Node 18 or newer, using the same
  `kino` API as the app:

```
node sdk/run.mjs ./plugin.js search "metropolis"
node sdk/run.mjs ./plugin.js home
```

Copy `plugin.js` and `kino-plugin.json`, change them, and publish your repository the same way.

## License note

What this plugin plays is not ours to license: the videos are public domain or carry the license
their uploader chose on archive.org. Check an item's page before you reuse or redistribute it.

# traktor-streaming-proxy

Stream Spotify, Tidal and YouTube in Traktor DJ by serving a stand-in for Beatport's API.

<img src="screenshot.png" align="right" width="250"></a>

Traktor supports streaming, but only from Beatport and Beatsource. This is an HTTPS server that
answers the parts of the Beatport API Traktor uses, backed by other sources. It ships a crafted
Beatport license, so no Beatport account or subscription is needed.

This branch runs natively on Windows: a JVM app with a tray icon and a control panel in the browser.
No Docker, no WSL, no Python.

## Features

- **Three sources.** Spotify (Premium, via librespot), Tidal and YouTube, on or off individually.
- **Your music in Traktor's browser.** Saved tracks, playlists and followed artists show up as
  Beatport genres, playlists and charts.
- **Search across providers**, with each result labelled by the source it came from.
- **A cached library.** Tracks are converted once, tagged, given album art and kept, so loading one
  again is a file read. Size-capped, least-recently-played first out.
- **Self-configuring.** The control panel generates and trusts the TLS certificate, fixes the hosts
  file, installs ffmpeg and patches Traktor for you, and tells you what is still missing.
- **Portable.** Unzip and run. Settings, credentials and library stay in that one folder.

As with real Beatport streaming, Traktor will not let you use its recorder.

## Requirements

- Windows, and Traktor Pro 4
- **Java 17 or newer** — the one thing to install by hand, as no runtime is bundled:
  `winget install -e --id EclipseAdoptium.Temurin.21.JRE`. A JRE is enough to run; building needs a JDK.
- **ffmpeg**, only for Spotify. The control panel finds it and offers to install it, so there is
  nothing to do in advance.
- **Spotify Premium** for the Spotify source. YouTube needs no account; Tidal needs a subscription
  and your own API client id and secret.

## Install

1. Unzip the release anywhere you can write to, for example `C:\TraktorProxy`. That folder is the
   whole application.
2. Optionally run **`Create shortcuts.cmd`** once, for a desktop and Start menu shortcut.
3. Start it from the shortcut or `TraktorProxy.cmd`. A tray icon appears; there is no console window.
   Use `TraktorProxy-console.cmd` to watch the output if the tray icon never shows up.
4. Open the control panel at **http://127.0.0.1:8088**, from the tray menu or your browser, and go
   to **Settings**. The installation section is green when Traktor can reach the server, and offers
   a fix for anything that is not:
   - **Certificate** — generated for `api.beatport.com` and added to your trusted roots. Automatic.
   - **Hosts file** — points `api.beatport.com` at this machine. Needs administrator rights.
   - **ffmpeg** — one **Install** button, no restart needed afterwards.
   - **Traktor patch** — swaps the license key Traktor checks with the one this server matches.
     Traktor must be closed, and it needs administrator rights. A `.backup` is kept.
5. Enable your sources under **Providers**. Spotify opens two browser logins on first start, which
   are then remembered.

Changing ports, credentials, the provider list or the certificate puts a **Restart now** button at
the top of the panel — an offer, since a restart drops the server Traktor is talking to.

**Upgrade** by unzipping a newer release over the folder: only `lib\` and the launchers are replaced.
**Uninstall** by turning off *Start with Windows*, quitting and deleting the folder; the hosts entry
and the root certificate are the two things left outside it.

## Activate it in Traktor

With the proxy running and the setup green:

1. Start Traktor and open **Preferences**, **Streaming**.
2. Click **Login on Beatport**. The browser opens and comes straight back — there is no form and no
   account to type, the proxy approves it. If Traktor was started before the proxy, click it again.
3. **Beatport** appears in the browser tree. Load tracks from it as you would from Beatport:

```
Curated Playlists
- <Genres>         --> source
 - <Playlists>     --> followed artists
  - <Tracks>       --> tracks from artist
Genres
- <Genres>         --> source
 - <Tracks>        --> saved/liked tracks in source
Playlists
- <Playlists>      --> playlists (all sources merged)
 - <Tracks>        --> tracks from playlist
Top 100
- <Genres>         --> source
 - <Tracks>        --> generated playlist of new released tracks
```

Enabling or disabling a source shifts these ids, so Traktor's Beatport cache has to be cleared and
Traktor restarted. The panel does the clearing and warns you first.

## Spotify

Audio comes through librespot's protocol; metadata, playlists and search go to `api.spotify.com`,
where Spotify meters the quota per client id. This build ships one, so Spotify works out of the box.

That id is shared by every install, and a shared id is a shared quota: heavy use can meet
`429 API rate limit exceeded`, which Traktor reports as *could not retrieve content*. Registering
your own takes two minutes and the quota is then yours alone:

1. Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) —
   free, no review. Tick **Web API**.
2. Add exactly this redirect URI: `http://127.0.0.1:5589/callback`
3. Put the client id into **Settings, Credentials**. There is no secret to store; the flow uses PKCE.
   Clearing the field falls back to the shipped default.

A registered app in development mode cannot read Spotify's editorial playlists, which is why Release
Radar and the Top 100 category stay empty. Your playlists, saved tracks, followed artists and search
all work.

## Where things are stored

Settings, the certificate, credentials, logs and the library all live in the app folder, next to the
launchers. That is what `portable.txt` means: delete it and the data moves to
`%LOCALAPPDATA%\TraktorProxy`, which is also where `gradlew.bat run` writes.
`-Dtraktorproxy.dataDir=...` overrides both, for running a second copy.

The panel writes `config.properties` itself, so it never needs editing by hand and an upgrade cannot
overwrite your settings. `config.properties.example` is documentation and is never read.

**Coming from the old installer build**, which kept data in `%LOCALAPPDATA%\TraktorProxy`: copy
`config.properties`, `cert`, `data` and `library` into the new folder before the first start, or
delete `portable.txt` to keep using that location. Uninstall the old one from Settings, Apps.

## Build

```
gradlew.bat portableZip
```

Produces `build\distributions\TraktorProxy-1.0.3.zip`, about 23 MB of jars. No installer, no
administrator rights, nothing written outside the folder you unzip it into.

`gradlew.bat portableDir` stages the same folder in `build\staged\TraktorProxy` to run from. It has
no `portable.txt`, so its data goes to `%LOCALAPPDATA%\TraktorProxy` and a rebuild has nothing of
yours to delete — staging refuses outright if it finds settings or a library there.

## Credits

[0xf4b1/traktor-streaming-proxy](https://github.com/0xf4b1/traktor-streaming-proxy) for the project,
and [@v1nc](https://github.com/v1nc) for the original Windows setup and the Traktor patcher this
build reimplements.

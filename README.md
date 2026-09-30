# traktor-streaming-proxy

Stream Spotify, YouTube and Tidal in Traktor DJ by serving a stand-in for Beatport's API.

<img src="screenshot.png" align="right" width="250"></a>

Traktor supports streaming, but only from Beatport and Beatsource. This is an HTTPS server that
answers the parts of the Beatport API Traktor uses, backed by other sources. It ships a crafted
Beatport license, so no Beatport account or subscription is needed.

This branch runs natively on Windows: a JVM application with a tray icon and a control panel in the
browser. There is no Docker, no WSL and no Python.

As with real Beatport streaming, Traktor will not let you use its recorder.

## Requirements

- Windows
- Java 17 or newer, [Temurin](https://adoptium.net/) or Oracle. There is no bundled runtime, so
  this is the one thing to install first: `winget install -e --id EclipseAdoptium.Temurin.21.JRE`.
  A JRE is enough to run it; building needs a JDK.
- [ffmpeg](https://www.gyan.dev/ffmpeg/builds/), used to convert what Spotify streams into the
  format Traktor accepts. The control panel finds it and offers to install it, so there is nothing
  to do by hand; `winget install -e --id Gyan.FFmpeg` is the same thing from a terminal.
- Spotify Premium, for the Spotify source
- Traktor Pro 4

## Build

```
gradlew.bat portableZip
```

Produces one file, `build\distributions\TraktorProxy-1.0.3.zip`, about 23 MB and all of it jars.
No installer, no WiX Toolset, no administrator rights and nothing written outside the folder you
unzip it into.

For the same folder unzipped, to run or test from, use `gradlew.bat portableDir` and look in
`build\portable\TraktorProxy`. Keep any folder you actually use outside the repository:
a rebuild replaces that directory and would take your settings and downloaded tracks with it.

## Install

Unzip anywhere you can write to, for example `C:\TraktorProxy`, and that folder is the whole
application. Then, once:

- **`Create shortcuts.cmd`** puts a *Traktor Streaming Proxy* shortcut with the app icon on the
  desktop and in the Start menu. Optional; run it again if you move the folder.

To **upgrade**, unzip a newer release over the folder. Only `lib\` and the launchers are replaced,
and the folder inside the archive carries no version so it lands in the same place. Your settings,
credentials and library sit beside them and survive.

To **uninstall**, turn off *Start with Windows* in the tray menu, quit, and delete the folder. Two
things live outside it: the `api.beatport.com` line in `C:\Windows\System32\drivers\etc\hosts`, and
the generated root certificate under *Manage user certificates*, *Trusted Root Certification
Authorities*.

## Run

Start it from the shortcut, or run `TraktorProxy.cmd`. There is no console window; the one the
launcher itself opens closes within a moment. Use `TraktorProxy-console.cmd` when you want to watch
the output, which is the thing to reach for when the tray icon never appears.

The launcher finds Java through `JAVA_HOME` first and `PATH` second, and says so plainly when there
is none or when what it finds is older than 17.

A tray icon appears; everything else is in the control panel at **http://127.0.0.1:8088**, also
reachable from the tray menu.

The tray menu lists each provider with a coloured dot, warns when the setup is incomplete, and
opens the control panel or the music folder. Everything else is in the panel: the log is its **Logs**
tab and *Start with Windows* is in **Settings**.

## Setup

The control panel does the setup itself. Open **Settings**; the installation section is green when
Traktor can reach the server, and tells you what is missing when it cannot.

- **Certificate.** Traktor talks to `api.beatport.com` over TLS and validates through Schannel,
  which builds its chain from the Windows certificate stores. A certificate for that name is
  generated, added to your trusted roots and checked at every start. Superseded ones are removed.
- **Hosts file.** `api.beatport.com` has to resolve to this machine. The entry is added if missing;
  because that file needs administrator rights, the panel offers a fix that asks for them.
- **ffmpeg.** Only Spotify needs it, so the row is a warning rather than a failure until Spotify is
  enabled. When it is missing there is an **Install** button that runs winget in the background; the
  panel reports when it finishes, and the converted path picks it up without a restart.
- **Traktor patch.** Traktor checks the license with a platform specific key, and only the macOS one
  matches the license served here, so the embedded key is swapped. A `.backup` is kept beside the
  executable. Traktor must be closed, and it needs administrator rights.

Everything else is in the panel too, so `config.properties` never needs editing by hand.

## Where things are stored

Settings, the certificate, credentials, logs and the track library all live in the app folder, next
to the launchers. That is what `portable.txt` in there means: delete it and the data folder moves to
`%LOCALAPPDATA%\TraktorProxy` instead, which is also where a `gradlew.bat run` writes since there is
no app folder to use. `-Dtraktorproxy.dataDir=...` overrides both, which is how to run a second copy.

`config.properties.example` is documentation and is never read. The real `config.properties` is
written by the panel on first save, so nothing needs editing by hand and an upgrade cannot overwrite
what you have set.

**Coming from the old installer build**, which kept its data in `%LOCALAPPDATA%\TraktorProxy`: copy
`config.properties`, `cert`, `data` and `library` from there into the new folder before the first
start, or delete `portable.txt` to go on using that location. Uninstall the old one from Settings,
Apps.

## Spotify

Audio comes through librespot's own protocol. Metadata, playlists and search go to
`api.spotify.com`, and Spotify meters that quota per client id. librespot's built in id is shared by
every user of that library, so metadata calls made with it are rate limited no matter how little you
ask for: `429 API rate limit exceeded` on a single cold request, and *could not retrieve content* in
Traktor. Your own app id gives you a quota that is yours.

1. Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard). Free,
   no review. Tick **Web API**.
2. Add exactly this redirect URI: `http://127.0.0.1:5589/callback`
3. Put the client id into **Settings, Credentials**. There is no secret to store; the flow uses PKCE.

Enable Spotify under **Providers**. Two browser logins open on first start, one for librespot and one
for your app. Both are remembered, so it happens once.

A registered app in development mode cannot read Spotify's own editorial playlists, which is why
Release Radar and the Top 100 category are empty. Your playlists, saved tracks, followed artists and
search all work.

## Library

Tracks are converted once and kept, so loading the same track again costs a file read rather than a
download. They are named after the track, tagged with the metadata Spotify holds, and carry the
album art.

**Settings, Library** has the size limit, 10 GB by default and switchable off. When it is reached the
least recently played tracks are removed. The folder can be moved from there and the files follow.

## Library mapping

Beatport Streaming has fixed categories, which are matched to the sources as closely as they allow.
The genres are identical in each category, so they are used to tell the sources apart.

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

Enabling or disabling a source shifts these ids, so Traktor's Beatport cache is cleared when you do
and Traktor has to be restarted. The panel says so before it happens.

## Credits

[0xf4b1/traktor-streaming-proxy](https://github.com/0xf4b1/traktor-streaming-proxy) for the project,
and [@v1nc](https://github.com/v1nc) for the original Windows setup and the Traktor patcher this
build reimplements.

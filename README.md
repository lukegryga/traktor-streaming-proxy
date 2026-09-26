# traktor-streaming-proxy
Allow Traktor DJ to stream music from YouTube, Spotify, and Tidal by faking Beatport's API

<img src="screenshot.png" align="right" width="250"></a>

Traktor DJ supports streaming of music tracks, but only from the Beatport and Beatsource services.
This project aims to integrate other streaming sources into Traktor DJ via Beatport Streaming.
It consists of an API server based on ktor which fakes some relevant parts of the Beatport API to serve custom content.

Currently, it supports YouTube Music (via NewPipe), Spotify, and Tidal with support for searching for music and browsing saved tracks and playlists.
In theory other streaming services or self-hosted sources will be possible to integrate as long as they serve music files in mp4a audio format, since Traktor refuses to load other formats (even though these formats are supported for local files).
As a workaround, an on-the-fly format conversion of the music files should be possible at some cost in quality and time.

As with Beatport streaming, Traktor does not allow to use the build-in recorder.

The project now contains a fully crafted Beatport license file that allows the server to handle linking and authentication, with enabling all features such as track analysis and simultaneous playback of multiple decks. You no longer need to take care of the license file or have a Beatport account with subscription! :)

## Setup
### MacOS

1. Get the latest [release](https://github.com/0xf4b1/traktor-streaming-proxy/releases) and unzip.

2. Configure the server by adjusting the `config.properties` file. Both Spotify and Tidal sources require an account.

3. (Optional) Install ffmpeg on your system if you want to use the spotify source.

4. SSL trust

Recent versions of Traktor do not trust the generated certificate and refuse to connect to the server. The certificate verification can be bypassed by preloading a small stub library that lets the respective function always pass to effectively disable the check.

You need to create a code signing certificate in Keychain Access -> Certificate Assistant -> Create a certificate ...

Enter a name, e.g. "code", and choose Certificate Type: Code Signing and click Create.

Afterwards resign the Traktor binary with the following command:

```
sudo codesign --force --sign "code" "/Applications/Native Instruments/Traktor Pro 3/Traktor.app/Contents/MacOS/Traktor"
```

Then either get the prebuilt stub `SecTrustEvaluateStub.dylib` from [releases](https://github.com/0xf4b1/traktor-streaming-proxy/releases) or build it yourself:

```
cd cert
make
```

5. Run the server from the directory where the config.properties file is located

```
bin/traktor-streaming-proxy
```

6. Redirect ports 80 -> 8080 and 443 -> 8443

```
sudo pfctl -f pf.conf
sudo pfctl -e
```

7. Redirect `api.beatport.com` to the server by adding the following to `/private/etc/hosts` on macOS

```
127.0.0.1   api.beatport.com
```

8. Verify that the setup is working correctly

```
curl -k https://api.beatport.com/v4/catalog/genres/
```

The result should be a JSON response similar to the following depending on your enabled sources:

```
{
    "results": [
        {
            "id": 1,
            "name": "YouTube"
        }
    ]
}
```

If you get an error or something different, it will not work with Traktor and you need to fix your setup.

9. Run Traktor with the following command

```
DYLD_INSERT_LIBRARIES=./SecTrustEvaluateStub.dylib "/Applications/Native Instruments/Traktor Pro 3/Traktor.app/Contents/MacOS/Traktor"
```

If you are not yet linked with the server, open settings and connect to Beatport streaming. You should receive an immediate redirect which connects Traktor.

10. Done! If you navigate to Beatport Streaming, you should be able to browse through the predefined categories and use the search box to find content.

### Windows
Huge thanks to [@v1nc](https://github.com/v1nc) for providing a working setup for Windows and the Traktor patcher!

1. Install Docker desktop [with WSL](https://docs.docker.com/desktop/features/wsl/)
2. Enable "Start Docker Desktop when you sign in to your computer" in the Docker Desktop settings to make it run at login
3. Start an Ubuntu WSL shell (or your preferred distribution)
4. Clone this repository: `git clone https://github.com/0xf4b1/traktor-streaming-proxy.git` and navigate to the folder: `cd traktor-streaming-proxy`
5. Adjust the `src/main/dist/config.properties` file to your needs. Make sure to change `beatport.license` to `windows` and `server.useKeystore` to `true`.
6. Generate the required SSL certificates by running `./cert/gen-cert.sh`.
7. Trust the generated `./cert/server.crt` on your machine: For Ubuntu WSL, go to `\\wsl.localhost\Ubuntu\home\username\traktor-streaming-proxy\cert` in you explorer, click on `server.crt`, select *Install certificate*, select *Local computer*, click *Next*, select *All certificates* and choose *Trusted Root Certification Authorities*, then install the certificate.
8. Create and start Docker image:
```
docker build -t traktor-streaming-proxy .
docker run -d --name traktor-streaming-proxy-container -p 443:8443 -p 127.0.0.1:5588:5588 -p 127.0.0.1:5589:5589 -v traktor-spotify-credentials:/app/data --restart always traktor-streaming-proxy
```
Ports `5588`/`5589` and the `/app/data` volume are only needed for the Spotify source, see [Spotify](#spotify). Leave them out if you do not use it.
9. Make your system use the proxy by appending the following line to your `C:\Windows\System32\drivers\etc\hosts` file:
```
127.0.0.1   api.beatport.com
```
10. You should now be able to open `https://api.beatport.com/v4/catalog/genres/` in your browser and see the configured providers without any SSL warnings or errors.
11. Patch `Traktor.exe` to make it accept the custom beatport license: Run `python patch_traktor.py` and input the path to your `Traktor.exe`. If you copy your `Traktor.exe` to the path mentioned in 7., you can run it in WSL so you don't need to install Python on Windows. After that copy back the patched binary to the Traktor program path. Alternatively, see the notes below to patch it manually.
12. Run the patched Traktor binary, go to *settings*, *streaming* and click *Login on Beatport*. If you just booted your device, wait a minute for the docker container to start. If you start Traktor before the container runs, you will need to click *Login to Beatport* again
13. Everything should work :)


#### Notes
the `patch_traktor.py` script was only tested on Traktor version 4.11.23 but should work on any version that uses the same certificate. If it does not work for you, you can patch the `Traktor.exe` manually:
    1. Download a hex editor like [hxd](https://mh-nexus.de/de/hxd/)
    2. Backup `C:\Program Files\Native Instruments\Traktor xx\Traktor.exe` and open it with your hex editor
    3. Search for `-----BEGIN PUBLIC KEY-----`. It should be the first occurrence, but verify it is the windows key listed [here](https://github.com/0xf4b1/traktor-streaming-proxy/issues/13#issuecomment-1742184706)
    4. Replace the key with the mac key listed [here](https://github.com/0xf4b1/traktor-streaming-proxy/issues/13#issuecomment-1742184706). Dots in the hex editor represent new lines, so the best way is to replace the key line per line, leaving the dots where they are.
    5. Save the binary, if hxd warns you about a changing binary size, you did something wrong.
    6. Copy the binary back to `C:\Program Files\Native Instruments\Traktor xx\` if you moved it. Run it to verify it works
    7. Obviously you can not use the usual Beatport API with this version

In some Windows installations, Traktor is unable to analyze tracks downloaded from YouTube and fails with an error message:

`Cannot execute BPM-detection due to missing transients. Please analyze first`

This issue is related to the used codec in the downloaded audio file. We are currently working on a fix.

### Spotify

Spotify no longer accepts username/password logins, so the source authenticates in the browser instead. No Spotify password goes into `config.properties`. Older versions used `spotify.username` and `spotify.password`; those settings are gone and now fail with `SpotifyAuthenticationException: BadCredentials`.

#### Register your own app

This step is required. The source makes two different kinds of call:

- **Audio streaming** goes through librespot's own protocol, using the client id built into that library.
- **Metadata** (playlists, saved tracks, followed artists, search) goes to `api.spotify.com`.

Spotify meters Web API quota per client id, and librespot's built-in id is shared by every user of that library, so metadata calls made with it are rate limited no matter how little you ask for. The symptom is `429 API rate limit exceeded` on a single cold request, and in Traktor, *could not retrieve content*. Using your own app id gives you a quota that is actually yours.

1. Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard). It is free and needs no review.
2. Add exactly this redirect URI: `http://127.0.0.1:5589/callback`
3. Copy the client id into `config.properties` as `spotify.clientId`. There is no client secret to store; the flow uses PKCE.

#### First start

Add `spotify` to `sources.enabled` and start the server. Startup blocks until both logins are done, so complete them before launching Traktor. The log prints two links, one after the other:

```
OAuth: Visit in your browser and log in: https://accounts.spotify.com/authorize?...   <- librespot, audio
Spotify: visit in your browser and log in: https://accounts.spotify.com/authorize?... <- your app, metadata
```

With Docker, read them using:

```
docker logs traktor-streaming-proxy-container
```

Open each link, log in, and approve. They redirect to `http://127.0.0.1:5588/login` and `http://127.0.0.1:5589/callback` respectively.

#### Afterwards

Both logins are one-time. librespot's reusable credentials land in `data/credentials.json` and the Web API refresh token in `data/spotify-refresh-token`, which is why the `/app/data` volume matters — keep it across rebuilds and you never log in again. Treat both files as passwords; they grant access to your account. To force a fresh login:

```
docker run --rm -v traktor-spotify-credentials:/data ubuntu:jammy sh -c "rm -f /data/credentials.json /data/spotify-refresh-token"
```

Verify without exposing anything sensitive:

```
curl -k https://api.beatport.com/v4/catalog/genres/
```

`Spotify` should be listed among the genres, and the log should show `Authenticated as <username>!` with no `Can not instantiate class sources.Spotify` line.

## Library Mapping

Beatport Streaming has the following predefined categories, which we try to match to our available sources in the best possible way.
The genres are identical in each category, which is why we use them to differentiate between the sources.

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
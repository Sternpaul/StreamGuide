# StreamGuide

> **Install on Fire TV:** Open Downloader and enter **8464714**, or visit [aftv.news/8464714](https://aftv.news/8464714). [Download the latest APK](https://github.com/Sternpaul/StreamGuide/releases/latest/download/StreamGuide-firetv.apk).

StreamGuide is a private, local-only IPTV player for Android-based Fire TV devices. It is designed around a Fire TV remote, not a touchscreen, and targets the Fire TV Stick 4K Max 2nd Gen (Fire OS 8 / Android API 30).

StreamGuide provides no channels or media. Use only sources you are authorized to access.

## Current features

### Providers and data

- Xtream Codes live-TV providers
- M3U/M3U8 URLs and local M3U files
- XMLTV and XMLTV.gz programme guides
- Provider-specific User-Agent and HTTP referer headers for playlist, EPG and Media3 playback requests
- Xtream logins (and M3U links that are an Xtream `get.php` playlist) load only the live channels and categories through the provider API; the full playlist, which also lists every movie and episode, is used only if that fails
- Every import recognises saved channels by id, then by stream number (same playlist only), then by an unambiguous name and category; recognised channels keep favorite, order, hidden, lock and custom name/category
- Channels the provider stops listing are archived with their state and restored when they return
- A changed domain or a renewed login still counts as the same playlist; a different playlist never inherits stream addresses and only carries state for channels with the same name and category
- Diagnostics lists the last imports: entries received, channels and categories, known/new/restored/no longer provided, and what was skipped
- Streaming playlist and XMLTV parsing for large providers
- Categories come from each entry's `group-title` (or `#EXTGRP`); entries without one go to **Other**
- Channels that share one `tvg-id` (SD/HD/backup variants) are all kept and share the same guide data; only the identical stream listed twice is skipped
- Skipped on import: on-demand entries (`/movie/`, `/series/`), entries without a name, and URLs that are not HTTP, HTTPS or RTSP
- Transactional SQLite EPG storage with indexed time/channel queries
- Automatic migration of existing local JSON-lines EPG data
- Last-good guide retention when an EPG refresh fails
- Visible guide data reloads after updates and when resuming; the timeline follows the current half-hour
- Encrypted provider credentials
- Local-only channel, guide and preference storage

VOD, series and recording are intentionally excluded.

### Live TV and navigation

- Live TV fills the whole screen: programme details top-left, the running channel as a live picture top-right, and the guide grid below
- The guide is navigated with a row/time cursor, not screen geometry: Up/Down keep the time, Left/Right step through programmes and scroll the timeline up to seven days ahead
- A red line marks the current time; Back returns a scrolled guide to now
- Left at the start of the timeline slides in the categories; Left again shows Live TV, Search, Multiview and Settings
- Live TV opens in Favorites by default; categories start with Favorites, then Recently watched, followed by provider categories
- Recently watched remembers up to 30 channels across app launches, newest first; hidden or removed channels are excluded
- Remote Options/Menu key opens contextual channel or category actions
- Channel actions: favorite, move, move to top, lock and add to Multiview
- Category ordering with move up, move down and move to top
- Persistent favorites, custom names, custom groups, hidden channels and manual ordering
- Two-, three- or six-hour guide width configured globally in Settings
- First Back press on Live TV is captured; press Back again to exit
- Search across channel and programme titles in every category without loading the complete EPG into memory
- Long Select on a search result toggles favorite
- Each category remembers its last channel

### Playback

- Full-screen Media3/ExoPlayer playback from one shared player
- Back returns to the guide without stopping the stream: it continues in the guide's top-right corner, and OK on the same channel returns to full screen without reconnecting
- Up/Down changes channel with an info banner (now/next, progress, clock)
- OK opens a see-through channel list over the picture; Left inside the list switches category
- Left/Right seek when the stream is seekable; on a non-seekable live stream Left opens the channel list
- Remote Play/Pause media key controls playback, also from the guide
- Bounded automatic reconnect attempts with buffering timeout and error-code diagnostics; OK retries after a terminal failure
- A frozen picture with running sound is detected (no new video frame for eight seconds) and the stream is reopened, at most twice per stream; each recovery is written to the error log
- Playback keeps the display awake and stops when the app leaves the foreground
- Provider headers are applied to live, catch-up and Multiview playback

Playback pauses while Search, Multiview or Settings are open and resumes in the guide.

### Guide and catch-up

- Dense EPG timeline with programme titles; times, duration, description and the following programme appear in the details area
- OK on a programme starts its channel
- Existing M3U catch-up templates are expanded from programme start and duration
- Selecting an eligible past programme starts catch-up directly; unsupported programmes add no UI

### EPG diagnostics

Settings contains a clearly labeled **Diagnostics** entry. Its remote-focusable subpage shows:

- Channels with guide data: named numerator, denominator and percentage
- Programmes mapped to known channel IDs: named numerator, denominator and percentage
- Recognized EPG channel IDs: named numerator, denominator and percentage
- Programmes currently airing and starting in the next 24 hours
- Earliest and latest stored programme times
- Channels missing TVG-ID values
- Duplicated TVG-IDs
- Samples of unmatched EPG IDs with programme counts
- Samples of visible channels without guide data
- Last successful EPG refresh, EPG duration and full-update duration
- Last retained update warning
- A persistent, clearable error log for playlist, EPG, diagnostics and playback failures; credentials are redacted
- **View error log** opens all recorded entries in a full-screen view; Up/Down scroll through complete messages and Back returns to Diagnostics

EPG writes use SQLite write-ahead logging, and refresh requests are serialized so guide reads and duplicate refresh triggers do not block one another. Hidden channels are excluded from the channel-coverage denominator, but their IDs remain valid when calculating whether imported programmes are mapped.

### Multiview

- Two to four saved live channels
- Select a tile to activate its audio
- Long-press a tile to remove it

Actual simultaneous decoder capacity depends on the Fire TV model and provider codecs.

## In-app updates

From version 0.7.3 onward, StreamGuide checks for new releases when it opens and offers an update. You can turn **Automatic update checks** off or use **Check for app updates** in Settings. Updates download inside the app and preserve your playlist, favorites and settings.

The first time, allow **Install unknown apps** for StreamGuide in Fire TV Settings. Confirm **Install** in the system installer, then select **Open** to return to StreamGuide. Fire OS controls installation and reopening; silent installation/restart is not assumed. Downloaded installers are checked for the correct app, newer version and matching release signing certificate before installation.

Install 0.7.3 using Downloader once to enable in-app updates for future releases.

## Download and install

Download the latest signed APK from [GitHub Releases](https://github.com/Sternpaul/StreamGuide/releases/latest).

Permanent latest-version URL:

```text
https://github.com/Sternpaul/StreamGuide/releases/latest/download/StreamGuide-firetv.apk
```

On Fire TV:

1. Install **Downloader by AFTVnews**.
2. Enable **Install unknown apps** for Downloader.
3. Enter Downloader code **8464714**.
4. Install the APK.

The matching short URL is `aftv.news/8464714`. Existing releases update in place because they use the same private signing certificate.

## Remote controls

### Live TV

- **Up/Down:** previous/next channel row
- **Left/Right:** previous/next programme; scrolls the timeline at its edges
- **Left at the start:** categories, then the app destinations
- **Rewind/Fast-forward:** page up/down
- **Select:** watch the channel (or catch-up for an eligible past programme)
- **Long Select:** toggle favorite
- **Play/Pause:** pause or resume the running channel
- **Options/Menu:** channel or category actions
- **Back:** close the categories, return to now, then press twice to exit
- **Long Back:** return to the running channel in full screen

### Playback

- **Up/Down:** previous or next channel
- **Select:** channel list; **Up/Down** to choose, **Select** to switch, **Left** for categories
- **Left/Right:** seek when supported
- **Play/Pause:** pause or resume
- **Back:** close the list, or return to the guide with the channel still playing

## Settings

Settings contains functional controls for:

- Automatic EPG updates
- EPG refresh interval
- EPG refresh on every app open or return to the foreground, independently of the periodic-update setting
- Update playlist on startup
- Manual EPG-only refresh
- Manual playlist and EPG refresh
- Global guide width
- Provider connection editing
- Channel management
- EPG mapping and refresh diagnostics
- Optional HTTP or SOCKS5 proxy (see below)

## Proxy

**Settings → Network → Proxy** sends StreamGuide's traffic, and only StreamGuide's, through an HTTP or SOCKS5 proxy, for example a VPN-backed proxy. It is off by default.

- Covered: the playlist and Xtream API, the TV guide, channel logos, live/catch-up/Multiview playback and app update checks and downloads.
- Not covered: a playlist file picked with **Choose file** is read by Android and the app that stores it (e.g. a cloud drive). The system installer opens an update that StreamGuide has already downloaded; it does not download anything itself.
- Host names are resolved by the proxy (HTTP `CONNECT` / SOCKS5 with a domain name), not by the Fire TV.
- If the proxy is on but cannot be reached or rejects the login, requests fail with **Proxy unreachable** or **Proxy login failed**. StreamGuide never falls back to your direct connection.
- **Test connection** sends one request through the entered proxy to `ipinfo.io` and shows the public IP and location the internet sees. Nothing is sent to ipinfo.io unless you press it.
- The proxy login is stored in Android's encrypted preferences and is never written to the error log.
- SOCKS5 needs Android 7 or newer (all current Fire TV models).

An HTTP or SOCKS5 proxy does not encrypt anything by itself. Your traffic is only protected if the proxy sits behind a VPN, like gluetun on a server in your home network. Across the internet, a proxy login and plain-HTTP streams can be read by anyone on the path.

### Example: gluetun on a NAS (HTTP proxy)

Enable gluetun's built-in HTTP proxy in its container settings:

```yaml
environment:
  - HTTPPROXY=on
  - HTTPPROXY_USER=streamguide
  - HTTPPROXY_PASSWORD=choose-a-password
ports:
  - 8888:8888/tcp
```

In StreamGuide choose **HTTP**, host = the NAS's LAN address (e.g. `192.168.1.10`), port `8888`, and the user/password from above. Press **Test connection**: it should show the VPN server's IP and location, not your home connection.

### Example: a VPN provider's SOCKS5 proxy

Many VPN providers offer a SOCKS5 proxy with separate proxy credentials (check your provider's help pages for the current host name, port, usually `1080`, and where to create the proxy login). In StreamGuide choose **SOCKS5**, enter that host, port, username and password, and press **Test connection**. Some providers only accept their SOCKS5 proxy from inside their VPN; in that case it cannot be used on its own from a Fire TV. Without a VPN tunnel, the connection to the proxy is not encrypted.

## Build

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Unsigned release output:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

Public releases are aligned and signed outside the repository. Signing credentials, provider credentials and private playlists must never be committed.

## Sideload with ADB

Enable **Developer Options → ADB Debugging** on the Fire TV, then connect from a device that can reach the Fire TV network:

```bash
adb connect FIRE_TV_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Remote ADB over the internet should use a private VPN or a trusted LAN relay. Do not expose TCP port 5555 publicly.

The Fire TV Stick 4K Select uses Vega OS and cannot install this Android APK. The target Fire TV Stick 4K Max 2nd Gen supports it.

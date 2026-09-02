# StreamGuide

StreamGuide is a private, local-only IPTV player for Android-based Fire TV devices. It is designed around a Fire TV remote, not a touchscreen, and targets the Fire TV Stick 4K Max 2nd Gen (Fire OS 8 / Android API 30).

StreamGuide provides no channels or media. Use only sources you are authorized to access.

## Current features

### Providers and data

- Xtream Codes live-TV providers
- M3U/M3U8 URLs and local M3U files
- XMLTV and XMLTV.gz programme guides
- Provider-specific User-Agent and HTTP referer headers for playlist, EPG and Media3 playback requests
- Streaming playlist and XMLTV parsing for large providers
- Transactional SQLite EPG storage with indexed time/channel queries
- Automatic migration of existing local JSON-lines EPG data
- Last-good guide retention when an EPG refresh fails
- Encrypted provider credentials
- Local-only channel, guide and preference storage

VOD, series and recording are intentionally excluded.

### Live TV and navigation

- Live TV opens as the default screen
- Left application menu: Live TV, Search, Multiview and Settings
- Remote Options/Menu key opens contextual channel or category actions
- Channel actions: favorite, move, move to top, lock and add to Multiview
- Category ordering with move up, move down and move to top
- Persistent favorites, custom names, custom groups, hidden channels and manual ordering
- Two-, three- or six-hour guide width configured globally in Settings
- First Back press on Live TV is captured; press Back again to exit
- Search across channel and programme titles without loading the complete EPG into memory
- Strong white/blue focus for the active column; the open category and selected channel remain muted but identifiable when focus moves right
- No duplicated channel/programme footer in Live TV, leaving room for additional guide rows

### Playback

- Full-screen Media3/ExoPlayer playback
- Up/Down changes channel
- Left seeks back 10 seconds when the stream is seekable
- Right seeks forward 30 seconds when the stream is seekable
- Remote Play/Pause media key controls playback
- Select shows or hides the playback overlay and retries after a terminal playback failure
- Bounded automatic reconnect attempts with buffering timeout and error-code diagnostics
- Previous/recent channel state is retained locally
- Provider headers are applied to live, catch-up and Multiview playback

Seeking cannot work on a provider stream that exposes no seekable live window or archive.

### Guide and catch-up

- Dense, remote-focusable EPG timeline
- Current programme progress and times in the guide cells
- Selecting a live programme starts its channel directly without adding a permanent on-screen Watch button
- Existing M3U catch-up templates are expanded from programme start and duration
- Selecting an eligible past programme starts catch-up directly; unsupported programmes add no UI

No additional catch-up browser or permanent catch-up buttons have been added. A future archive browser should remain contextual to the existing timeline rather than occupy space in Live TV.

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

EPG writes use SQLite write-ahead logging, and refresh requests are serialized so guide reads and duplicate refresh triggers do not block one another. Hidden channels are excluded from the channel-coverage denominator, but their IDs remain valid when calculating whether imported programmes are mapped.

### Multiview

- Two to four saved live channels
- Select a tile to activate its audio
- Long-press a tile to remove it

Actual simultaneous decoder capacity depends on the Fire TV model and provider codecs.

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

- **D-pad:** move focus through categories, channels and programmes
- **Select:** activate the focused item
- **Long Select on a channel:** toggle favorite
- **Options/Menu:** open contextual channel or category actions
- **Back:** close an open overlay; on Live TV, press twice to exit

### Playback

- **Up/Down:** previous or next channel
- **Left/Right:** seek backward or forward when supported
- **Play/Pause:** pause or resume
- **Select:** show/hide information or retry failed playback
- **Back:** return to Live TV

## Settings

Settings contains functional controls for:

- Automatic EPG updates
- EPG refresh interval
- Update stale EPG on startup
- Update playlist on startup
- Manual EPG-only refresh
- Manual playlist and EPG refresh
- Global guide width
- Provider connection editing
- Channel management
- EPG mapping and refresh diagnostics

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

# StreamGuide

A private, local-only live IPTV player for Fire TV, designed for the Fire TV Stick 4K Max 2nd Gen (Fire OS 8 / Android API 30).

## Included

- M3U/M3U8 URL or local-file import and Xtream Codes live-TV providers
- Configurable provider user-agent and HTTP referer headers
- XMLTV/XMLTV.gz guide import
- Configurable automatic EPG update interval (24 hours by default)
- Last-good-data retention when playlist or EPG refresh fails
- Dense remote-first two-, three-, or six-hour EPG timeline with zoom, highlighted Now slot, two-hour/day paging and instant return to Now
- Focusable programme cells with details, date/time, descriptions, live playback, and historical catch-up
- Channel groups, favorites, search, current/next programmes
- Persistent manual channel ordering that survives provider refreshes
- Channel manager with filtering, hide/restore, single-step, ten-step, top/bottom, and exact-position movement
- Full-screen Media3 playback, channel up/down, fit/zoom, audio cycling, subtitle toggle, catch-up seeking, previous channel, sleep timer, external player, PiP, diagnostics, and automatic retry
- Persistent recent-channel history
- Editable provider connection without clearing local organization
- Custom channel names and custom groups that survive provider refreshes
- Catch-up playback for M3U/Xtream playlists that expose `catchup-source` metadata
- Two-to-four-channel Multiview with selectable audio and a saved layout
- Parental PIN and persistent per-channel locks
- Encrypted provider credentials and local-only playlist/EPG storage
- Fire TV launcher banner and remote-only navigation

StreamGuide provides no channels or media. Use only sources you are authorized to access.

## Download and install on Fire TV

Download the latest signed APK from [GitHub Releases](https://github.com/Sternpaul/StreamGuide/releases/latest). The permanent direct-download URL is:

```text
https://github.com/Sternpaul/StreamGuide/releases/latest/download/StreamGuide-firetv.apk
```

On Fire TV, install **Downloader by AFTVnews**, enable **Install unknown apps** for Downloader, enter the published StreamGuide Downloader code, and install the APK. Existing installations update in place because releases use the same signing key.

## Build

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The signed development APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Sideload

Enable **Developer Options → ADB Debugging** and **Install unknown apps** on the Fire TV, then:

```bash
adb connect FIRE_TV_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Fire TV Stick 4K Select uses Vega OS and cannot install this Android APK. The target Fire TV Stick 4K Max 2nd Gen supports it.

## Controls

- D-pad: move focus; while watching, up/down changes channel
- Select: activate/tune; long-select on a guide row toggles favorite
- Back: close playback and return to the guide
- Guide action bar: update, sort, favorite, move up/down

## Data behavior

Provider credentials are stored with Android Keystore-backed encrypted preferences. Channels and EPG are stored only in the app's private local directory. Refreshes are parsed before replacing local data; failed or empty responses do not erase the last working guide.

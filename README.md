# StreamGuide

A private, local-only live IPTV player for Fire TV, designed for the Fire TV Stick 4K Max 2nd Gen (Fire OS 8 / Android API 30).

## Included

- M3U/M3U8 URL and Xtream Codes live-TV providers
- XMLTV/XMLTV.gz guide import
- Configurable automatic EPG update interval (24 hours by default)
- Last-good-data retention when playlist or EPG refresh fails
- Dense remote-first live guide inspired by TiviMate
- Channel groups, favorites, search, current/next programmes
- Persistent manual channel ordering that survives provider refreshes
- Full-screen Media3 playback, channel up/down, and aspect-ratio control
- Encrypted provider credentials and local-only playlist/EPG storage
- Fire TV launcher banner and remote-only navigation

StreamGuide provides no channels or media. Use only sources you are authorized to access.

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

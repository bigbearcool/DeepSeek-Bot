# dsh-mobile-android

Android MVP shell for **dsh-mobile-bridge** (NAS 0.0.3+).

## What it does

- Configure bridge base URL (`…/api/mobile-bridge/v1`)
- Pair with 6-hex code or scan / open `dshmb:<code>`
- Refresh access token (30 min) on demand / 401
- Team list → chat history (cursor) → send → live SSE (`hello` / `live` / `message` / `stream`)

Does **not** add remote-control / Mesh panels. Bot tools stay server-side.

## Package

- `applicationId`: `com.deepseek.dshmobile` (debug: `.debug`)
- Path: next to bridge under DeepSeek-Bot

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd dsh-mobile-android
./gradlew assembleDebug test
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Default base URL

`http://<电脑地址>:8787/api/mobile-bridge/v1` — change in-app to your NAS / LAN dsh origin + `/api/mobile-bridge/v1`.

Cleartext HTTP is allowed for LAN (see `network_security_config.xml`).

## Notes

- Camera permission for QR; typing the code always works.

# smolish-mobile

android app for [smolish.com](https://smolish.com). it's a fast, dark webview shell around the site, not a rewrite.

## getting the apk

every push builds a debug apk on github actions.

1. open the **Actions** tab of this repo
2. click the latest **build** run (green check)
3. scroll down to **Artifacts** and download `smolish-debug-apk`
4. unzip it, copy `app-debug.apk` to your phone and install it (allow "install unknown apps" for your file manager or browser)

you need to be logged into github to download artifacts. they expire after 90 days, just push again or hit **Run workflow** to get a fresh one.

## building locally

needs jdk 17, gradle 8.11+ and the android sdk.

```
gradle assembleDebug
```

the apk ends up in `app/build/outputs/apk/debug/`.

## notes

- feed paths (where pull to refresh is off and back exits) are in `FEED_PATHS` in `MainActivity.kt`
- the app icon comes from `icon.png`, the generated launcher assets live in `app/src/main/res`

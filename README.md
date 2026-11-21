# PhotoGate

Selective photo backup for Android 11+. After you take a photo (or a new file lands in a folder you chose), a quiet
notification offers **Upload** or **Discard**. Discarded photos stay on the phone and never reach Google Photos.

Features: any folder (Camera, Screenshots, WhatsApp, Download, ...), Pending tab, temporary modes
(upload nothing / upload everything / everything into an album, with auto-expiry), decision window, optional album.

NOT compiled yet by the author. If the build reports an error, paste it to Claude Code and it will be a small fix.

## 1. Build the APK

**Option A, GitHub (nothing to install):** create a repo, push this folder to `main`, open the *Actions* tab,
open the latest "Build APK" run and download the `photogate-apk` artifact.

**Option B, Android Studio / Claude Code on your PC:** open this folder in Android Studio and
Build > Build APK, or run `gradle :app:assembleRelease` (JDK 17 + Android SDK 35).
APK: `app/build/outputs/apk/release/app-release.apk`.

## 2. Google Photos sign-in (one-time, required)

Uploads use the Google Photos Library API with the add-only scope (the app cannot read or delete your photos).

1. https://console.cloud.google.com > create a project.
2. APIs & Services > Library > enable **Photos Library API**.
3. OAuth consent screen: External, add your Google account under **Test users**, add scope
   `.../auth/photoslibrary.appendonly`.
4. Credentials > Create credentials > OAuth client ID > **Android**:
   - Package name: `com.raman.photogate`
   - SHA-1: `38:11:0F:77:B9:76:56:1E:AF:58:D8:05:33:1C:79:62:10:AE:2B:8D`
     (from the bundled `app/photogate.jks`; the build signs with it, so it never changes)

## 3. First run

1. Install the APK, open it > Settings > **Allow** photo access and notifications.
2. Settings > **Connect** Google account.
3. Folders tab: switch on the folders you want it to ask about.
4. Settings > switch on **Watch the camera**.
5. In the Google Photos app, turn **off** its own backup, or it uploads everything anyway.

## Notes

- The signing key is committed on purpose so the OAuth SHA-1 is stable. This is a personal sideload app; do not
  publish it to the Play Store with this key.
- Not included from Snapgate: Storage saver (16 MP recompress), context rules, burst review.
- Google may show an "unverified app" warning while the consent screen is in Testing mode. That is expected.

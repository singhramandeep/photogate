# PhotoGate

> **Disclaimer:** PhotoGate is inspired by the idea behind the Snapgate app (choosing which photos reach your cloud).
> It is an independent project. It was not reverse engineered from Snapgate, uses none of its code or assets,
> and is not affiliated with or endorsed by its developer.

Selective photo backup for Android 11+. After you take a photo (or a new file lands in a folder you chose), a quiet
notification offers **Upload** or **Discard**. Discarded photos stay on the phone and never reach Google Photos.

Features: any folder (Camera, Screenshots, WhatsApp, Download, ...), Pending tab, temporary modes
(upload nothing / upload everything / everything into an album, with auto-expiry), decision window, optional album.

Builds with `gradle :app:assembleRelease`; unit tests run in CI before the APK is built.

## 1. Build the APK

**Option A, GitHub (nothing to install):** create a repo, push this folder to `main`, open the *Actions* tab,
open the latest "Build APK" run and download the `photogate-apk` artifact.

**Option B, Android Studio / Claude Code on your PC:** open this folder in Android Studio and
Build > Build APK, or run `gradle :app:assembleRelease` (JDK 17 + Android SDK 35).
APK: `app/build/outputs/apk/release/app-release.apk`.

## 2. Google Photos sign-in (one-time, required)

Uploads use the Google Photos Library API with the add-only scope (the app cannot read or delete your photos).
Sign-in happens in your browser: PhotoGate opens Google's normal login page in an in-app browser tab, you log in
and allow access, and the page sends you back to the app. No Google Play Services sign-in is used, and an embedded
WebView is not an option because Google blocks logins inside WebViews.

1. https://console.cloud.google.com > create a project.
2. APIs & Services > Library > enable **Photos Library API**.
3. OAuth consent screen (Google Auth Platform): External. Under **Data access** add the scope
   `.../auth/photoslibrary.appendonly`. Under **Audience** either:
   - click **Publish app** (recommended: you will see an "unverified app" warning once, and sign-in lasts), or
   - leave it in Testing and add your Google account as a test user. In Testing, Google expires the sign-in
     after 7 days, so you must press Connect again every week.
4. Clients (Credentials) > Create client > OAuth client ID > Application type **Desktop app**.
   Copy the **Client ID** and **Client secret**.
5. In PhotoGate: Settings > Google account > paste both > **Connect** > log in > Allow >
   **Back to PhotoGate** (or just close the tab).

The client secret of a Desktop client is not really secret (Google says so itself); it is kept on the phone only.

## 3. First run

1. Install the APK, open it > Settings > **Allow** photo access and notifications.
2. Settings > Google account: paste the client ID and secret, then **Connect**.
3. Folders tab: switch on the folders you want it to ask about.
4. Settings > switch on **Watch the camera**.
5. In the Google Photos app, turn **off** its own backup, or it uploads everything anyway.

Photos you approve while signed out or offline are kept in a queue (Pending tab shows the count, with
**Retry now**) and go up automatically after you connect.

## Tests

`gradle :app:testDebugUnitTest` runs the unit tests: the sign-in flow (PKCE, the loopback redirect, token exchange
and refresh against a fake Google server), the Photos upload calls, and the scan decisions (folders, modes,
decision window).

## Notes

- The signing key is committed on purpose so updates install over each other. This is a personal sideload app;
  do not publish it to the Play Store with this key.
- Not included from Snapgate: Storage saver (16 MP recompress), context rules, burst review.
- Google shows an "unverified app" warning for your own unverified project. Tap Advanced > Go to (your app). That is expected.

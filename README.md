# PhotoGate

> **Disclaimer:** PhotoGate is inspired by the idea behind the Snapgate app (choosing which photos reach your cloud).
> It is an independent project. It was not reverse engineered from Snapgate, uses none of its code or assets,
> and is not affiliated with or endorsed by its developer.

Selective photo backup for Android 11+, without any Google login or Google Cloud setup.

How it works:

- **Sources:** choose which folders to watch and give each a **category** (Camera, Screenshots, Social, Downloads,
  Other, or your own). PhotoGate guesses one from the folder name.
- When a new photo or video lands in a watched folder, it stays right there and shows up in the **Inbox** (and as a
  quiet notification) with **Approve**, **Discard** or **Delete now**. Tap a photo to review them one by one.
- **Approve** moves it into `DCIM/PhotoGate/<category>/`, e.g. `DCIM/PhotoGate/Screenshots/`, and writes the category
  into the photo's description (EXIF ImageDescription; JPEG, PNG and WebP only, not HEIC or videos). Sync only those
  folders to Google Photos (see First run), and only approved photos reach the cloud.
- **Discard** deletes the original after a delay (Immediately / 1 h / 4 h / 8 h / 24 h, set in Settings). Until then
  it sits in the **Bin** with a ⏱ tag showing how long it has left; you can still Approve it, send it back to the
  Inbox, or Delete now.
- **Delete now** deletes the original at once.

Every photo from a watched folder always shows up in one of three tabs: **Inbox** (waiting), **Approved** (moved to
PhotoGate; Undo puts it back) or **Bin** (discarded, with its countdown).

Also: quick modes with auto-expiry (Auto-approve everything / Quiet mode = Inbox without notifications; a banner on
the Inbox shows when one is on), a notification window, and an Activity log with Scan now in Settings.

## 1. Build the APK

**Option A, GitHub (nothing to install):** create a repo, push this folder to `main`, open the *Actions* tab,
open the latest "Build APK" run and download the `photogate-apk` artifact.

**Option B, Android Studio / Claude Code on your PC:** open this folder in Android Studio and
Build > Build APK, or run `gradle :app:assembleRelease` (JDK 17 + Android SDK 35).
APK: `app/build/outputs/apk/release/app-release.apk`.

## 2. First run

1. Install the APK and open it > Settings > **All files access: Allow**. A system screen opens: switch on
   PhotoGate, then go back. (Android needs this so the app can move and delete photos that the camera app
   created without asking you to confirm each one.)
2. Settings > Notifications: **Allow** (Android 13+).
3. Sources tab: switch on the folders you want caught (Camera is on by default) and check each one's category.
4. Settings > switch on **Watch for new photos**.
5. Approve one photo so `DCIM/PhotoGate` exists. Then:
   - Google Photos app > Settings > Backup: turn **off** (it always includes Camera, so it can't be used).
   - Samsung Gallery > Settings > **Sync with Google Photos** (Cloud sync): choose albums to sync, switch on only the
     PhotoGate category folders, Camera and everything else off. Careful: `DCIM/PhotoGate/Camera` shows up as an
     album named "Camera", just like your real Camera folder. Pick the one inside PhotoGate (or name the category
     something else, e.g. "Camera approved").

## Tests

Screenshots of every screen are rendered without a phone by Paparazzi: `gradle :app:recordPaparazziDebug`, images in
`app/src/test/snapshots/images/`.

`gradle :app:testDebugUnitTest` runs the unit tests: real files in a temporary folder (approve moves the photo into
PhotoGate, no hidden folder, name clashes never overwrite, file date kept, delete, putting back photos staged by the
earlier version), deletion timing, and the scan decisions (folders, modes, decision window, never asking about
PhotoGate's own folder, waiting while the camera is still writing).

## Notes

- The signing key is committed on purpose so updates install over each other. This is a personal sideload app;
  do not publish it to the Play Store with this key.
- An earlier test version moved waiting photos into a hidden `DCIM/PhotoGate/.staging` folder. This version puts
  them back into their original folders the first time it opens, then removes that folder.
- The Google Photos API upload version (with sign-in) is in git history at commit c77f25c.
- Not included from Snapgate: Storage saver (16 MP recompress), context rules, burst review.

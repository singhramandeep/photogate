# PhotoGate

> **Disclaimer:** PhotoGate is inspired by the idea behind the Snapgate app (choosing which photos reach your cloud).
> It is an independent project. It was not reverse engineered from Snapgate, uses none of its code or assets,
> and is not affiliated with or endorsed by its developer.

Selective photo backup for Android 11+, without any Google login or Google Cloud setup.

How it works:

1. **Sources:** switch on the folders to watch (Camera, Screenshots, WhatsApp, ...) and give each a **category**
   (Camera, Screenshots, Social, Downloads, Other, or your own). Watching starts by itself.
2. Every new photo or video in those folders stays where it is and lands in the **Inbox**, with a quiet notification.
3. For each one choose:
   - **Upload:** moves it to `DCIM/PhotoGate/p<category>/` (pCamera, pScreenshots...; the "p" keeps the gallery from
     showing two albums called "Camera") and writes the category into its description. Your
     gallery syncs only those folders to Google Photos. Listed under **Uploaded**, with Undo.
   - **Bin:** waits in the **Bin** with a ⏱ countdown, then is deleted from the phone (timer in Settings).
     You can still Upload it or send it back to the Inbox.
   - **Delete now:** deleted from the phone at once.

In the Inbox, Uploaded and Bin grids: tap to review, **long-press to select** several and act on them together
(Upload / Bin / Delete, Undo upload...), and **pinch with two fingers** to make thumbnails bigger or smaller.

**Checking:** Settings > Check for new photos: Immediately (Android wakes PhotoGate when the photo library changes;
one notification per photo) or every 1 / 2 / 4 / 8 hours (one "N photos to review" notification). No permanent
notification. Set Battery to Unrestricted (Settings > Permissions) so Android doesn't delay the checks.

A welcome guide runs on first launch, and one-time tips explain each screen (Settings > Guide > Show again).

Settings only has: permissions (incl. battery), check frequency, the Bin timer, the sync setup steps, and a
Troubleshooting section (activity log, guide).

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
   Watching starts by itself (a small permanent notification shows it is running).
4. Upload one photo so `DCIM/PhotoGate` exists. Then:
   - Google Photos app > Settings > Backup: turn **off** (it always includes Camera, so it can't be used).
   - Samsung Gallery > Settings > **Sync with Google Photos** (Cloud sync): choose albums to sync, switch on only the
     p-folders (pCamera, pScreenshots...), Camera and everything else off.

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

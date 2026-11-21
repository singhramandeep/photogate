# PhotoGate

> **Disclaimer:** PhotoGate is inspired by the idea behind the Snapgate app (choosing which photos reach your cloud).
> It is an independent project. It was not reverse engineered from Snapgate, uses none of its code or assets,
> and is not affiliated with or endorsed by its developer.

Selective photo backup for Android 11+, without any Google login or Google Cloud setup.

How it works:

- Photos are never moved. When a new photo or video lands in a folder you chose (Camera, Screenshots, ...), it stays
  right there and a quiet notification (and the Pending tab) offers **Approve**, **Discard** or **Delete now**.
- **Approve** puts a copy in `DCIM/PhotoGate/`, a normal device folder. In Google Photos, turn on backup for that one
  folder only, and only approved photos reach the cloud. The original stays in its folder.
- **Discard** deletes the original after a delay (Immediately / 1 h / 4 h / 8 h / 24 h, set in Settings). Until then
  it is listed under Discarded, where you can still Approve or Delete now.
- **Delete now** deletes the original at once.

Also: Folders tab, temporary modes (leave everything alone / approve everything, with auto-expiry), decision window.

## 1. Build the APK

**Option A, GitHub (nothing to install):** create a repo, push this folder to `main`, open the *Actions* tab,
open the latest "Build APK" run and download the `photogate-apk` artifact.

**Option B, Android Studio / Claude Code on your PC:** open this folder in Android Studio and
Build > Build APK, or run `gradle :app:assembleRelease` (JDK 17 + Android SDK 35).
APK: `app/build/outputs/apk/release/app-release.apk`.

## 2. First run

1. Install the APK and open it > Settings > **All files access: Allow**. A system screen opens: switch on
   PhotoGate, then go back. (Android needs this so the app can delete discarded photos that the camera app
   created without asking you to confirm each one.)
2. Settings > Notifications: **Allow** (Android 13+).
3. Folders tab: switch on the folders you want caught. Camera is on by default.
4. Settings > switch on **Watch for new photos**.
5. Approve one photo so `DCIM/PhotoGate` exists. Then in Google Photos > Settings > Backup:
   - **Back up device folders**: switch on **PhotoGate**.
   - Switch off every other folder. Because originals stay in Camera, this only works if Google Photos lets you
     exclude Camera; on some versions Camera is always backed up while backup is on. Check this on your phone.

## Tests

`gradle :app:testDebugUnitTest` runs the unit tests: real files in a temporary folder (approve copies and leaves the
original, no hidden folder, name clashes never overwrite, file date kept, delete, putting back photos staged by the
earlier version), deletion timing, and the scan decisions (folders, modes, decision window, never asking about
PhotoGate's own folder, waiting while the camera is still writing).

## Notes

- The signing key is committed on purpose so updates install over each other. This is a personal sideload app;
  do not publish it to the Play Store with this key.
- Approved photos take space twice on the phone (original + copy) until you delete one, e.g. with Google Photos'
  "Free up space" after backup.
- An earlier test version moved waiting photos into a hidden `DCIM/PhotoGate/.staging` folder. This version puts
  them back into their original folders the first time it opens, then removes that folder.
- The Google Photos API upload version (with sign-in) is in git history at commit c77f25c.
- Not included from Snapgate: Storage saver (16 MP recompress), context rules, burst review.

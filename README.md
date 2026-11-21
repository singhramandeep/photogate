# PhotoGate

> **Disclaimer:** PhotoGate is inspired by the idea behind the Snapgate app (choosing which photos reach your cloud).
> It is an independent project. It was not reverse engineered from Snapgate, uses none of its code or assets,
> and is not affiliated with or endorsed by its developer.

Selective photo backup for Android 11+, without any Google login or Google Cloud setup.

How it works:

- When a new photo or video lands in a folder you chose (Camera, Screenshots, ...), PhotoGate moves it into a
  hidden staging folder, `DCIM/PhotoGate/.staging/`. Galleries and Google Photos cannot see it there.
- A quiet notification (and the Pending tab) offers **Approve**, **Discard** or **Delete now**.
- **Approve** moves it to `DCIM/PhotoGate/`, a normal device folder. In Google Photos, turn on backup for that one
  folder only, and only approved photos reach the cloud.
- **Discard** keeps it in staging for a while (Immediately / 1 h / 4 h / 8 h / 24 h, set in Settings), then deletes
  it from the phone. Until then it is listed under Discarded, where you can still Approve or Delete now.

Also: Folders tab, temporary modes (leave everything alone / approve everything, with auto-expiry), decision window.

## 1. Build the APK

**Option A, GitHub (nothing to install):** create a repo, push this folder to `main`, open the *Actions* tab,
open the latest "Build APK" run and download the `photogate-apk` artifact.

**Option B, Android Studio / Claude Code on your PC:** open this folder in Android Studio and
Build > Build APK, or run `gradle :app:assembleRelease` (JDK 17 + Android SDK 35).
APK: `app/build/outputs/apk/release/app-release.apk`.

## 2. First run

1. Install the APK and open it > Settings > **All files access: Allow**. A system screen opens: switch on
   PhotoGate, then go back. (Android needs this so the app can move photos that the camera app created.)
2. Settings > Notifications: **Allow** (Android 13+).
3. Folders tab: switch on the folders you want caught. Camera is on by default.
4. Settings > switch on **Watch for new photos**.
5. Approve one photo so `DCIM/PhotoGate` exists. Then in Google Photos > Settings > Backup:
   - **Back up device folders**: switch on **PhotoGate**.
   - Make sure nothing else you care about is backed up. Google Photos may always back up the Camera folder while
     backup is on; PhotoGate moves new photos out of Camera about 3 seconds after they are taken, but if Google
     Photos is faster it can still grab one. Check this on your phone.

## Tests

`gradle :app:testDebugUnitTest` runs the unit tests: real file moves in a temporary folder (stage, approve, delete,
name clashes never overwrite, file date kept), deletion timing, and the scan decisions (folders, modes, decision
window, never touching PhotoGate's own folder, waiting while the camera is still writing).

## Notes

- The signing key is committed on purpose so updates install over each other. This is a personal sideload app;
  do not publish it to the Play Store with this key.
- Uninstalling PhotoGate does not delete `DCIM/PhotoGate` or its staging folder. Anything still in staging stays
  there (hidden); a file manager can get it back.
- Moving files out of chat apps' folders (WhatsApp ...) breaks those files inside the chat. Prefer Camera and
  Screenshots.
- The Google Photos API upload version (with sign-in) is in git history at commit c77f25c.
- Not included from Snapgate: Storage saver (16 MP recompress), context rules, burst review.

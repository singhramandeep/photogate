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

## 1. Build

- **Sideload APK:** `gradle :app:assembleDebug` (or the `photogate-apk` artifact of the GitHub "Build" workflow).
  Signed with the bundled `app/photogate.jks`, so new builds install over old ones.
- **Google Play bundle:** `gradle :app:bundleRelease` with your private upload key configured. Everything about
  publishing is in [docs/PLAY_STORE.md](docs/PLAY_STORE.md); the privacy policy is [docs/PRIVACY.md](docs/PRIVACY.md).

Requires JDK 17 and Android SDK 36.

## 2. First run

The welcome guide walks through it:

1. **Photos and videos:** Allow (choose "Allow all", not "selected photos").
2. **Media management** (Android 12+, optional): Allow, so Upload / Bin / Delete don't ask for confirmation each time.
3. **Notifications**, and **Battery: Unrestricted** so background checks aren't delayed.
4. **Folders:** switch on the ones to watch and check each category.
5. **How often to check:** immediately, or every 1/2/4/8 hours.
6. **Sync:** Google Photos app > Backup off. Upload one photo, then Samsung Gallery > Settings > Sync with Google
   Photos: switch on only the p-folders (pCamera, pScreenshots...), Camera off.

## Tests

Screenshots of every screen are rendered without a phone by Paparazzi: `gradle :app:recordPaparazziDebug`, images in
`app/src/test/snapshots/images/`.

`gradle :app:testDebugUnitTest` runs the unit tests: real files in a temporary folder (approve moves the photo into
PhotoGate, no hidden folder, name clashes never overwrite, file date kept, delete, putting back photos staged by the
earlier version), deletion timing, and the scan decisions (folders, modes, decision window, never asking about
PhotoGate's own folder, waiting while the camera is still writing).

## Notes

- `app/photogate.jks` (committed, public password) is only for sideload builds. Play releases use your private
  upload key (docs/PLAY_STORE.md).
- The Google Photos API upload version (with sign-in) is in git history at commit c77f25c.
- Not included from Snapgate: Storage saver (16 MP recompress), context rules, burst review.

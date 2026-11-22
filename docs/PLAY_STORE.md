# Publishing PhotoGate on Google Play

Everything the code needs is done (see the last section). What remains needs your Google account, your key and
your testers.

## 1. One-time setup

1. **Developer account:** https://play.google.com/console (one-time US$25, identity verification).
2. **Upload key** (private, never commit it):
   ```
   keytool -genkeypair -v -keystore upload-photogate.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```
   Keep `upload-photogate.jks` and its passwords somewhere safe (password manager + a backup copy).
3. Create `keystore.properties` in the project root (it is git-ignored):
   ```
   storeFile=upload-photogate.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
   For GitHub Actions instead, add repository secrets `PG_UPLOAD_KEYSTORE_BASE64` (`base64 -w0 upload-photogate.jks`),
   `PG_UPLOAD_STORE_PASSWORD`, `PG_UPLOAD_KEY_ALIAS`, `PG_UPLOAD_KEY_PASSWORD`.
4. **Privacy policy URL:** publish `docs/PRIVACY.md` somewhere public (GitHub Pages, Google Sites...). Fill in
   `CONTACT_EMAIL_HERE` first.

## 2. Build

```
gradle :app:bundleRelease
```
Upload `app/build/outputs/bundle/release/app-release.aab`. In the console, keep **Play App Signing** on (default):
Google signs the app for users; your upload key only proves uploads come from you.

Each new upload needs a higher `versionCode` in `app/build.gradle.kts`.

## 3. Store listing (draft)

- **App name:** PhotoGate
- **Short description (80):** Choose which new photos reach Google Photos. Upload, bin or delete each one.
- **Full description:**

  > PhotoGate puts you in control of what gets synced to the cloud.
  >
  > Every new photo or video in the folders you choose lands in PhotoGate's Inbox and stays exactly where it is
  > until you decide:
  >
  > • **Upload** – moves it into a PhotoGate folder for its category (pCamera, pScreenshots, pSocial…). Point your
  > gallery's cloud sync at those folders, and only what you upload reaches the cloud.
  > • **Bin** – keeps it for 1, 4, 8 or 24 hours with a countdown, then deletes it. Change your mind any time.
  > • **Delete now** – gone at once.
  >
  > Also: categories you can name yourself, the category written into each uploaded photo's description, select
  > several photos at once, pinch to resize thumbnails, check for new photos immediately or every few hours, and
  > undo any upload.
  >
  > Private by design: PhotoGate has no internet access, no account, no ads and no tracking.

- **Category:** Photography. **Tags:** photo management, backup, privacy.
- **Graphics:** 512×512 icon, 1024×500 feature graphic, at least 2 phone screenshots (the screenshots in
  `app/src/test/snapshots/images` show the layout; take real ones on a phone with photos).
- Don't mention other apps' names (e.g. Snapgate, Google Photos as a brand in the title) in the listing.

## 4. App content forms

- **Privacy policy:** the URL from step 1.4.
- **Data safety:** "Does your app collect or share any of the required user data types?" → **No**.
  (Photos are processed on-device only and never transmitted; on-device processing is not "collection".)
  Encryption in transit: not applicable. Data deletion: not applicable (nothing collected).
- **Ads:** No ads. **Target audience:** 18+ (or 13+). **Content rating questionnaire:** utility, no objectionable
  content.
- **Photo and video permissions declaration** (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO): core functionality is
  reviewing **every new photo** in user-selected folders as it arrives and moving or deleting it; the system photo
  picker cannot do this because PhotoGate must see photos the user has not picked, in the background, to offer them
  for review. Suggested text:
  > PhotoGate is a photo management app. Its core function is to detect every new photo and video created in
  > folders the user chooses (for example the camera folder) and let the user upload (move to a synced folder), bin
  > or delete each one. This requires ongoing access to all photos and videos; a one-time picker selection cannot
  > detect newly taken photos.
- **MANAGE_MEDIA:** no declaration form; it is a special access the user grants in system settings.
- **Foreground service / exact alarms / all files access:** not used (removed for Play).

## 5. Testing before production (new personal accounts)

Google requires a **closed test with at least 12 testers for 14 continuous days** before you can apply for
production. Create a closed testing track, add testers' Google accounts (or a Google Group), share the opt-in link,
and keep them opted in for 14 days. Then apply for production access in the console.

## 6. What changed in the code for Play

- No "All files access" (MANAGE_EXTERNAL_STORAGE): photos are read with the photos permission and changed through
  MediaStore. Upload / Undo / Bin / Delete ask Android first (`MediaStore.createWriteRequest` /
  `createDeleteRequest`); with "Media management" granted (Android 12+) Android agrees silently, otherwise it shows
  one confirmation per batch. Notification buttons go through an invisible activity because such requests need a
  screen.
- Bin timers: when a timer runs out while the app is closed, a notification offers to delete; with Media
  management the deletion also happens automatically next time PhotoGate opens.
- Target SDK 36, release builds shrunk with R8 (~2.4 MB), signed with your upload key when configured.
- No foreground service, no boot receiver, no battery-optimization permission.

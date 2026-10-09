# SiteCam

A personal, ad-free Android app that adds a GPS location stamp to construction-site photos and videos.
It is made for the Samsung Galaxy S23 Ultra but works on any Android 10+ phone.

SiteCam does **not** replace your camera. It opens Samsung's own Camera app, so you keep full native
quality and every mode (Photo, Video, Portrait, Night, Pro, 200 MP, all zoom lenses). SiteCam then
makes a **stamped copy** of each photo or video. Your originals are never changed, recompressed or deleted.

## Download and install (on your phone)

1. On your phone, open the **Releases** page of this repository:
   <https://github.com/LanrexBillionz/Lanrex-GPS-Camera/releases>
   The newest version is always at the top.
2. Under **Assets**, tap **SiteCam-1.0.X.apk** to download it.
3. Open the downloaded file (from the browser's download bar or the *My Files* app → *Downloads*).
4. The first time, Android asks you to allow your browser to install apps:
   tap **Settings**, turn on **Allow from this source**, then go back and tap **Install**.
5. If Google Play Protect warns about an unknown app, tap **More details → Install anyway**.
   (It does this for every app that is not from the Play Store.)

Shortcut that always downloads the newest APK:
<https://github.com/LanrexBillionz/Lanrex-GPS-Camera/releases/latest>

## Updating

Do exactly the same as installing: download the newest APK from the Releases page and open it.
Every build is signed with the same key, so it **installs over the old version** and keeps your settings
and history. You never need to uninstall first.

## First start

SiteCam explains each permission in plain English before Android asks:

| Permission | Why |
| --- | --- |
| Precise location | Live coordinates, and the GPS fix recorded when you tap **Open Camera**. |
| Photos and videos ("Allow all") | Finding what the camera saves and making stamped copies. |
| Location inside photos | Android hides the GPS saved in photos from other apps unless this is allowed. |
| Notifications | Stamping progress, and keeping Site Mode running. |
| Battery: Unrestricted | Stops Samsung from putting Site Mode to sleep in the background. |

Also check that **Samsung Camera → Settings → Location tags** is **on**, so every photo has its GPS
position saved inside it.

## Using SiteCam

- **Open Camera**: tap it, take photos and videos in Samsung Camera as usual, then come back to
  SiteCam (Back button or the recent-apps button). Everything saved since you tapped is stamped
  automatically. SiteCam also notes your GPS position and which way the camera faced.
- **Site Mode**: switch it on at the start of the day. Every new photo or video saved by the camera is
  stamped in the background, however you open the camera (side key, lock screen, ...). A small
  notification shows while it is on, with a **Turn off Site Mode** button. Set battery usage to
  **Unrestricted** when asked, or Samsung may pause it.
- **Older photos**: tap **Gallery** and pick them, or select them in Samsung Gallery and **Share → SiteCam**.
- Stamped copies are saved in **Pictures/SiteCam** as `name_stamped.jpg` (photos) or
  `name_stamped.mp4` (videos). The original stays exactly as it was.
- **Videos** get the same stamp burned in, at the same resolution and frame rate, with the sound
  copied unchanged and about the same quality. Long videos take a while; progress shows in the app
  and in the notification. HDR videos stay HDR when the phone can edit them.
- **No internet?** Photos and videos are still stamped with the coordinates and time, and show
  *Address pending* in the app. When the phone is back online SiteCam stamps them again from the
  original, this time with the address and map, and replaces the earlier copy automatically.

## How the app is built

The app is written in Kotlin with Jetpack Compose. Every push to any branch runs the GitHub Actions
workflow in `.github/workflows/build.yml`, which runs the unit tests, builds a signed release APK and
publishes it as a new GitHub Release.

The signing key is `keystore/sitecam-release.jks`, with its password in `app/build.gradle.kts`.
It stays the same for every build so updates install over each other.
**Note:** this repository is public, so anyone can read that key. Only install SiteCam from this
repository's own Releases page.

## Build stages

1. ✅ Project, GitHub build, home screen with live location
2. ✅ Stamping engine and gallery stamping
3. ✅ Open Camera, auto-detect and Site Mode
4. ✅ Offline handling and video stamping

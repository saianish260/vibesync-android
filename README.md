> **This is the `tv` branch: VibeSync TV**, a separate app for Android TV / Google TV (package `com.vibesync.tv`). It installs next to the phone app. Download: `https://github.com/saianish260/vibesync-android/raw/apk-tv/VibeSync-TV.apk`. No Google sign-in on TV: everything is saved on the TV itself. The remote's arrows move the highlight, OK opens, Back closes the open panel; booking is hidden; Watch opens the TV editions of Netflix, Prime Video and YouTube. The TV layout lives in the app (`app/src/main/assets/tv.js`, `tv.css`), so the website is unchanged.

# VibeSync Android: built by GitHub, no Android Studio needed

GitHub's free build servers turn this folder into `VibeSync.apk`. They check the result with `aapt2 dump badging` and `apksigner verify`, then give you the APK as a download.

**What the app is:** a full-screen WebView for your deployed VibeSync site.
- Package `com.vibesync.app`, label **VibeSync**
- JavaScript and DOM storage on
- Back goes back inside the app before closing it
- Neon cyan status bar on a `#07060F` background
- Permissions: INTERNET, plus POST_NOTIFICATIONS and RECEIVE_BOOT_COMPLETED for new-episode reminders
- Home-screen widget: **Tonight's pick** (long-press the home screen → Widgets → VibeSync)
- minSdk 24, targetSdk 34

## Steps

1. **Create an empty repo.** On github.com, click **New repository**, name it `vibesync-android`, and leave every box unticked.
2. **Push this folder** from a terminal (VS Code or any other) opened in this folder:
   ```bash
   git init
   git add .
   git commit -m "VibeSync Android wrapper"
   git branch -M main
   git remote add origin https://github.com/<you>/vibesync-android.git
   git push -u origin main
   ```
   *No Git?* Use **Add file → Upload files** on the repo page. Then create the workflow by hand: **Add file → Create new file**, name it `.github/workflows/build-apk.yml`, and paste in that file's contents. Web uploads can skip hidden `.github` folders, which is why this extra step is needed.
3. **Run the build.**
   - Open the repo's **Actions** tab and allow workflows if GitHub asks.
   - Click **Build VibeSync APK → Run workflow**.
   - Paste your deployed URL (e.g. `https://vibesync-you.streamlit.app/`) and click **Run workflow**.
4. **Download.** The run takes about 3–5 minutes. When it shows a green ✅, open the run and download **VibeSync-apk** under *Artifacts*. That's a zip containing `VibeSync.apk` and `badging.txt` (the verification output).

## Install on your phone (Realme P1 5G)

1. Get `VibeSync.apk` onto the phone. You can download the artifact zip on the phone while logged into GitHub and unzip it in **Files**, or copy the APK over from your laptop.
2. Tap `VibeSync.apk`. If Android blocks it, tap **Settings** and turn on **Allow from this source** for Files (or Chrome). You can also find this under **Settings → Apps → Special app access → Install unknown apps**.
3. Tap **Install**. If Play Protect warns "unknown developer", choose **More details → Install anyway**. That warning is expected for self-built, debug-signed apps.
4. Open **VibeSync** from your home screen.

## Change things later

- **Colours:** edit `app/src/main/res/values/themes.xml`.
- **App URL:** just run the workflow again with a new URL.
- **Icon:** replace the `mipmap-*/ic_launcher.png` files.

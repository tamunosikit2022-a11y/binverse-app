# BinVerse Vision — Android App

Captures a photo, sends it to your Render backend for classification, and lets you
send the result to the sorting robot. No Android Studio required — GitHub Actions
builds the APK for you.

## How it works

1. Tap **Capture & Classify** → phone camera opens
2. Take a photo → app compresses it and uploads to your backend's `/api/classify`
3. Result (object, category, confidence, action) is shown
4. Tap **Send to Robot** → pushes the result to `/api/send-to-robot`, where the ESP32 can poll for it

Default backend URL is already set to `https://binveisionbackend.onrender.com`.
You can change it any time from the **Settings** button in the app.

## Getting the APK (no Android Studio needed)

1. Push this project to a new GitHub repository (see commands below).
2. Go to your repo's **Actions** tab. A "Build APK" workflow run should start automatically.
3. Wait for it to finish (usually 3-6 minutes) — you'll see a green checkmark.
4. Click into the finished run, scroll to **Artifacts**, and download `binverse-vision-debug-apk` (a zip).
5. Unzip it — inside is `app-debug.apk`.
6. Transfer that file to your phone (email it to yourself, upload to Google Drive, or use a USB cable).
7. On your phone, tap the APK file. If prompted, allow "install from unknown sources" for that app (Chrome/Files/Gmail, whichever you used).
8. Install and open — you're running the app.

## Pushing this project to GitHub

```powershell
cd binverse-app
git init
git add .
git commit -m "Initial Android app"
git branch -M main
git remote add origin https://github.com/<your-username>/binverse-app.git
git push -u origin main
```

Every time you push new commits to `main`, GitHub Actions automatically rebuilds the APK.

## Notes

- This is a **debug build** — fine for testing on your own phone. Debug builds are self-signed automatically, no extra setup needed.
- The app downsizes photos to a max of 1024px before uploading, to reduce data usage and API token costs.
- Camera permission is requested the first time you tap Capture.
- If classification fails, check that your backend is awake (Render free tier sleeps after inactivity — first request can take 30-50 seconds) and that you haven't hit Groq's daily rate limit.

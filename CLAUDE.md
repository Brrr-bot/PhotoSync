# PhotoSync — CLAUDE.md

> **Read this file before making any changes. Add a changelog entry at the bottom whenever you modify anything.**
> **After making any code changes, push to `Brrr-bot/PhotoSync` main branch via GitHub API. CI auto-builds on push. Always trigger one `workflow_dispatch` after all file pushes to avoid the portal registration race condition (see CI section below).**

---

## ⚠️ OTA UPDATE SYSTEM — IMPLEMENT THIS FIRST BEFORE ANY OTHER CHANGES

This project has two apps: **hub** (`com.photosync.hub`) and **client** (`com.photosync.client`). Neither has a working OTA update system yet. Implement it in both before making any other changes.

The Timesheet app (`C:\Users\mcubi\Desktop\x\Timesheet`) has a fully working implementation — copy from there.

### What needs implementing in EACH app (hubapp and clientapp modules)

**Step 1 — Copy `InstallReceiver.kt` from Timesheet**

Copy `C:\Users\mcubi\Desktop\x\Timesheet\app\src\main\kotlin\com\mcubi\timesheet\InstallReceiver.kt` to each module's kotlin package folder. Update the package name at the top to match (`com.photosync.hub` or `com.photosync.client`).

**Step 2 — Add to each module's `AndroidManifest.xml`**

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

Inside `<application>`:
```xml
<receiver
    android:name=".InstallReceiver"
    android:exported="false" />
```

**Step 3 — Add OTA methods to each MainActivity**

Add field at class level:
```kotlin
private var updateInProgress = false
```

Add methods (replace `APP_KEY` with `hub` or `client`, `PACKAGE_NAME` with the actual package, `b.root` with root view):
```kotlin
private fun checkForUpdate() {
    if (updateInProgress) return
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val req = Request.Builder()
                .url("https://app-updates.mcubittbuilders.workers.dev/api/version/APP_KEY")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@launch
            val json = org.json.JSONObject(resp.body?.string() ?: return@launch)
            val serverCode = json.optInt("versionCode", 0)
            val apkUrl     = json.optString("apkUrl", "")
            if (serverCode > BuildConfig.VERSION_CODE && apkUrl.isNotEmpty()) {
                updateInProgress = true
                withContext(Dispatchers.Main) { promptInstall(serverCode, apkUrl) }
            }
        } catch (e: Exception) { }
    }
}

private fun promptInstall(serverCode: Int, apkUrl: String) {
    Snackbar.make(b.root, "Update available (build $serverCode)", Snackbar.LENGTH_INDEFINITE)
        .setAction("INSTALL") { downloadAndInstall(apkUrl) }
        .setActionTextColor(Color.parseColor("#FFB300"))
        .show()
}

private fun downloadAndInstall(apkUrl: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !packageManager.canRequestPackageInstalls()) {
        Toast.makeText(this, "Enable 'Install unknown apps' for this app in Settings", Toast.LENGTH_LONG).show()
        try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"))) } catch (e: Exception) { }
        return
    }
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val bytes = client.newCall(Request.Builder().url(apkUrl).build())
                .execute().body?.bytes() ?: return@launch
            val pi      = packageManager.packageInstaller
            val params  = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val sessionId = pi.createSession(params)
            val session   = pi.openSession(sessionId)
            session.openWrite("apk", 0, bytes.size.toLong()).use { out ->
                out.write(bytes); session.fsync(out)
            }
            val intent  = Intent(this@MainActivity, InstallReceiver::class.java)
            // FLAG_MUTABLE required — PackageInstaller fills in EXTRA_STATUS/EXTRA_INTENT
            val pending = android.app.PendingIntent.getBroadcast(
                this@MainActivity, sessionId, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
            // Do NOT call session.close() after commit
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
```

Call `checkForUpdate()` at the end of `onCreate()`.

**Step 4 — First install on device (run for EACH device)**

```powershell
$ADB = "C:\Users\mcubi\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# For hub app:
& $ADB push "hubapp\build\outputs\apk\debug\hubapp-debug.apk" /data/local/tmp/app.apk
& $ADB shell pm install -i com.photosync.hub -r /data/local/tmp/app.apk
& $ADB shell rm /data/local/tmp/app.apk
# For client app (repeat on each client device):
& $ADB push "clientapp\build\outputs\apk\debug\clientapp-debug.apk" /data/local/tmp/app.apk
& $ADB shell pm install -i com.photosync.client -r /data/local/tmp/app.apk
& $ADB shell rm /data/local/tmp/app.apk
```

**Step 5 — Test: deploy a new build and reopen the app**

The Snackbar "Update available" should appear. Tap INSTALL → tap Install on system dialog. Done.

### Key bugs already hit in Timesheet — do NOT repeat

1. **`FLAG_IMMUTABLE` kills everything** — PackageInstaller can't fill in extras. Receiver fires with empty intent, `userIntent=null`, nothing happens. **Must use `FLAG_MUTABLE`.**

2. **`getParcelableExtra<Intent>()` returns null on Android 13+** — Already fixed in `InstallReceiver.kt`. Copy it as-is, don't rewrite it.

3. **`session.close()` after `session.commit()` abandons the install** — Don't add it. The session cleans up itself after commit.

4. **Silent install requires one user tap on Android 12+** — `USER_ACTION_NOT_REQUIRED` doesn't bypass the system dialog for sideloaded apps. One tap is unavoidable. Not a bug.

5. **Double-install on resume** — Install dialog pause/resumes the activity → triggers a second `checkForUpdate()` → second download. The `updateInProgress` flag prevents this.

---

## Overview
Two-app system for wirelessly syncing photos and videos from client phones to a hub tablet over a local hotspot. The hub tablet creates a WiFi hotspot; client phones connect to it and push compressed media automatically. No internet required — fully peer-to-peer over LAN.

## Apps in This Project
| App | Package | Device |
|---|---|---|
| Hub | `com.photosync.hub` | Tablet (hub device, runs hotspot + HTTP server) |
| Client | `com.photosync.client` | Phone (sends media to hub) |

Both apps are built from the same Gradle project. `shared/` contains common constants and utilities.

## Package & Build
| Field | Value |
|---|---|
| Min SDK | 26 (Android 8) |
| Target SDK | 34 |
| Language | Kotlin |
| Build number | `PhotoSync/build_number.txt` — shared between hub and client, auto-incremented |

## How to Build
```
cd "C:\Users\mcubi\Desktop\X\Phone Tablet Sync\PhotoSync"

# Build hub only:
gradlew.bat :hubapp:assembleDebug --daemon

# Build client only:
gradlew.bat :clientapp:assembleDebug --daemon

# Build both:
gradlew.bat :hubapp:assembleDebug :clientapp:assembleDebug --daemon
```
APK outputs:
- Hub: `hubapp/build/outputs/apk/debug/hubapp-debug.apk`
- Client: `clientapp/build/outputs/apk/debug/clientapp-debug.apk`

## How to Deploy Updates
```
# From update-cf folder:
set UPLOAD_KEY=<secret>
python build_upload.py hub       # hub tablet
python build_upload.py client    # client phone
```
Or via ADB directly (USB cable):
```powershell
$ADB = "C:\Users\mcubi\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $ADB -s <device-serial> install -r hubapp\build\outputs\apk\debug\hubapp-debug.apk
```

## Update Portal
`https://app-updates.mcubittbuilders.workers.dev` — tracks hub and client versions separately.

## Key Architecture — Hub
- `HubForegroundService` — main service, always running
- `HubHttpServer` — HTTP server on a local port, clients upload files here
- `HubBroadcastAnnouncer` — UDP broadcast so clients can find the hub
- `FileSyncer` — receives and stores incoming media
- `KeepAliveAccessibilityService` — keeps service alive on aggressive OEMs
- OTA: `InstallStatusReceiver` handles silent APK installs from update server

## Key Architecture — Client
- `ClientForegroundService` — main service, always running
- `MediaHttpServer` — serves the client's local media over HTTP to the hub
- `BroadcastAnnouncer` — announces presence on local network
- `HubDiscovery` — finds the hub via UDP
- `LocalImageProcessor` — compresses photos before sending
- `HubFilesClient` — talks to hub's HTTP server
- `KeepAliveAccessibilityService` — keeps service alive

## Network Flow
1. Hub tablet creates WiFi hotspot
2. Client phone connects to hotspot
3. Client discovers hub via UDP broadcast (no internet needed)
4. Client compresses photos → sends to hub via HTTP
5. Hub stores files, optionally deletes originals from client

## Permissions (both apps)
- `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
- `FOREGROUND_SERVICE` (dataSync type)
- `RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES`
- `WAKE_LOCK`, `SCHEDULE_EXACT_ALARM`
- Client also: media read/write, `ACCESS_MEDIA_LOCATION`

## Key Things to Know
- `KeepAliveAccessibilityService` must be enabled in Accessibility Settings once after install on both devices
- Battery optimisation must be disabled for both apps (Samsung is aggressive)
- Hub and client must be on the same WiFi network (the hub's hotspot)
- Build number is shared — bumping it affects both hub and client version codes
- The old update server (`update-server/app.py`) is being retired in favour of the update portal
- Previous server was at Tailscale IP `100.107.143.20:9000`

## Current Device Tailscale IPs (saved in update-server/devices.json)
| Device | Tailscale IP |
|---|---|
| Hub tablet | `100.126.58.18` |
| Client phone | `100.88.106.50` |

---

## CI / Build / Deploy

**Repo:** `Brrr-bot/PhotoSync` (private). Workflow ID: `283500744`.
**Versioning:** `versionCode = github.run_number + 300`. Run #29 = v329.
**Portal:** `https://app-updates.mcubittbuilders.workers.dev` — app keys `hub`, `client`.

**⚠️ Portal race condition:** Pushing N files one-at-a-time creates N simultaneous CI runs that all race to register with the portal. Only the first write wins, so the portal shows an old version. **Fix: always trigger one `workflow_dispatch` after all pushes.** The dispatch run registers the correct latest version.

**Push files via GitHub API (Python — no gh CLI available):**
```python
import base64, json, urllib.request
TOKEN = '<see project_github_tokens memory — never commit the real token>'
REPO = 'Brrr-bot/PhotoSync'

def push_file(gh_path, local_path, message):
    req = urllib.request.Request(f'https://api.github.com/repos/{REPO}/contents/{gh_path}',
        headers={'Authorization': f'token {TOKEN}'})
    with urllib.request.urlopen(req) as r:
        sha = json.loads(r.read())['sha']
    with open(local_path, 'rb') as f:
        content = base64.b64encode(f.read()).decode()
    payload = json.dumps({'message': message, 'content': content, 'sha': sha}).encode()
    req = urllib.request.Request(f'https://api.github.com/repos/{REPO}/contents/{gh_path}',
        data=payload, method='PUT',
        headers={'Authorization': f'token {TOKEN}', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as r:
        print('OK', json.loads(r.read())['content']['sha'][:12])

# After all file pushes, trigger dispatch:
urllib.request.urlopen(urllib.request.Request(
    f'https://api.github.com/repos/{REPO}/actions/workflows/283500744/dispatches',
    data=json.dumps({'ref': 'main'}).encode(), method='POST',
    headers={'Authorization': f'token {TOKEN}', 'Content-Type': 'application/json'}))
```
**Note:** Local Windows paths in Python are `/Users/mcubi/Desktop/X/...` (Python sees C: as root `/`).

---

## Samsung MediaStore — Date Preservation Rules (Android 13+)

These rules apply to `VideoSpaceManager`, `LocalImageProcessor`, and any code inserting/replacing media:

1. `contentResolver.update(DATE_TAKEN)` is **silently ignored** on files the app doesn't own.
2. **To own a file:** insert it with `IS_PENDING=1`, write bytes, then set `IS_PENDING=0`.
3. **Samsung resets dates during `IS_PENDING=0` transition** — always do a **second `contentResolver.update()`** with DATE_TAKEN/DATE_ADDED/DATE_MODIFIED right after.
4. **For JPEGs:** also stamp `TAG_DATETIME_ORIGINAL` in EXIF via `ExifInterface` — MediaStore reads EXIF on publish and uses it as the authoritative date.
5. **For videos (MP4/MOV):** no EXIF equivalent — rely on the double ContentValues update + owning the row.
6. **Date source priority in VideoSpaceManager:** `parseDateFromName()` (filename pattern VID-YYYYMMDD or 13-digit epoch ms) → DATE_TAKEN from MediaStore → DATE_ADDED × 1000.

---

## Changelog
> Add an entry every time you make a change. Format: `YYYY-MM-DD — description`

- 2026-05-14 — Hub rebuilt to build 87, client rebuilt to build 86. Both deployed via USB. TCP ADB enabled on hub tablet port 5555 for future Tailscale installs. Update portal connected (app-updates.mcubittbuilders.workers.dev).
- 2026-05-17 — Fixed OTA: both UpdateChecker files now fetch from new portal per-app endpoints (/api/version/hub and /api/version/client). Removed old Tailscale server URL (100.107.143.20:9000). Fixed remote hub gallery: added liveHubIpUpdatedAt timestamp; effectiveHubIp() uses local LAN IP when fresh (<90s), Tailscale IP when stale/off-network. Hub v1.0.96, client v1.0.98 deployed via USB + portal.
- 2026-05-25 — Updated RemoteLogger in both hub and client: redirected from old Tailscale server (100.107.143.20:9000/log) to update portal (/api/log). Changed "device" field to "app" to match portal schema. Logs now visible in portal live stream under "hub" and "client".
- 2026-05-17 — Fixed hub files card: UsbStorageManager.listRecentFiles() now uses stale-while-revalidate cache (always returns instantly, refreshes in background thread). invalidateRecentFilesCache() also triggers background refresh so post-sync requests are fast. Fixed client thumbsLoadedForIp guard — only set when files actually load (not on empty result), so card retries on next onResume. Hub v1.0.102, client v1.0.103 deployed.
- 2026-06-03 — Added VideoSpaceManager: old videos (>30 days) replaced by JPEG poster with play badge; recent videos transcoded to 480p MP4 in-place. Both preserve original filename, folder, and date. Poster EXIF stamped with DateTimeOriginal + Software="PhotoSync video poster" marker. imageFolderFor() maps video source folder to correct image destination (DCIM stays DCIM, WhatsApp Video → Pictures/WhatsApp Images, etc.). MediaStoreHelper.getMediaSince() filters poster names to prevent hub upload loop. repairPosterDates() one-off pass fixes existing wrong-dated posters via delete+reinsert. VideoThumbnailer.stampPosterExif() added. v326-v329 deployed.
- 2026-06-03 — Hub: UsbStorageManager.thumbnailForFile() now generates thumbnails for MP4/MOV files using MediaMetadataRetriever (previously returned null → no thumbnail shown). Added deleteFile() method. HubHttpServer: new DELETE /hub/delete endpoint (HMAC authenticated). HubFilesClient: deleteFile() client method. HubGalleryActivity: long-press on any file shows "Delete from hub?" confirmation dialog; on confirm deletes from USB and removes from RecyclerView. Gallery thumbnail loading switched to ThumbnailCache (disk+memory, survives screen reopens). v329 deployed.

# PhotoSync — Project Context
> Read this at the start of every new chat session to get up to speed fast.

---

## What This Project Is

Two Android apps + a dashboard tablet app that form a **WiFi photo backup system**:

| App | Device | Purpose |
|-----|--------|---------|
| **PhotoSync Hub** (`hubapp`) | Tablet (hub) | Discovers phones on WiFi, downloads their photos to a USB drive, compresses images and sends compressed versions back to the phone |
| **PhotoSync Client** (`clientapp`) | Phone | Serves its media library over HTTP; receives compressed replacements from hub |
| **Dashboard Tablet** (`Dashboard Tablet/`) | Separate tablet | Polls hub for status and shows sync progress |

**Repo:** `https://github.com/ngalogivn-ship-it/photosync`
**Location on this PC:** `C:\Users\mcubi\Desktop\X\Phone Tablet Sync\PhotoSync`
**Dashboard location:** `C:\Users\mcubi\Desktop\X\Phone Tablet Sync\Dashboard Tablet`

---

## Architecture

### Communication ports
```
Client phone  --UDP broadcast "PHOTOSYNC_HERE"--> Hub (port 8766)
Hub           --HTTP handshake/sync-------------> Client (port 8765)
Client phone  --HTTP POST /sync every 5 min-----> Hub (port 8767)
Dashboard     --HTTP GET /dashboard-------------> Hub (port 8767)
Hub           --UDP broadcast "PHOTOSYNC_HUB"--> Client (port 8768)
```

### HMAC Auth
All HTTP requests are signed with HMAC-SHA256. Payload = `"$timestampMs:$deviceName"`.
- **Hub & Client**: use `HmacAuth.sign()` → **Base64** encoded
- **Dashboard**: `HubRepository.sign()` → also **Base64** (was hex, now fixed — see bugs below)
- Shared secret: `PhotoSync_ChangeMe_32CharSecretKey` (in `Constants.kt`)
- Validity window: 60 seconds

### Sync flow (hub side, `FileSyncer.kt`)
1. **Date repair pass** — reads EXIF from USB files, corrects DATE_TAKEN on phone
2. **Normal sync** — fetches file list since `lastSync`, downloads new files to USB, compresses images inline, POSTs compressed bytes to client `/replace`
3. **Legacy compression pass** — finds images on USB not yet in `alreadyCompressed` set, re-compresses and replaces

### File replacement flow (client side, `MediaStoreHelper.kt`)
```
Hub calls POST /replace?id=X&mime=image/jpeg&dateTaken=Y
  → client calls replaceFile(originalId, mime, compressedBytes, dateTaken)
  → INSERT new MediaStore record (IS_PENDING=1, app owns it → write always works)
  → write compressed bytes
  → DELETE original (needs MANAGE_MEDIA on Android 12+, createDeleteRequest on 10-11)
  → set IS_PENDING=0 to publish
  → markReplaced(originalId, newId) — prevents re-processing
```

---

## Key Files

| File | Purpose |
|------|---------|
| `shared/src/main/kotlin/com/photosync/shared/Constants.kt` | Ports, endpoints, HMAC headers, shared secret |
| `shared/src/main/kotlin/com/photosync/shared/crypto/HmacAuth.kt` | HMAC sign/verify (Base64) |
| `shared/src/main/kotlin/com/photosync/shared/model/DashboardStatusResponse.kt` | Hub→Dashboard status model |
| `hubapp/.../service/FileSyncer.kt` | Main sync logic (3-pass) |
| `hubapp/.../service/HubForegroundService.kt` | Hub service, discovery, dashboard snapshot |
| `hubapp/.../service/HubHttpServer.kt` | Hub HTTP server (NanoHTTPD, port 8767) — `/sync` + `/dashboard` |
| `hubapp/.../compress/MediaCompressor.kt` | JPEG compress to 82% / 1920px max, bakes EXIF rotation into pixels |
| `hubapp/.../storage/SyncStateRepository.kt` | SharedPrefs: lastSync, downloadedIds, compressedFiles, dateCheckedFiles |
| `hubapp/.../storage/UsbStorageManager.kt` | SAF-based USB read/write |
| `clientapp/.../service/MediaHttpServer.kt` | Client HTTP server (NanoHTTPD, port 8765) |
| `clientapp/.../media/MediaStoreHelper.kt` | MediaStore queries, replaceFile(), cleanup tools |
| `clientapp/.../service/ClientForegroundService.kt` | Client service, UDP announce, auto-sync heartbeat |
| `Dashboard Tablet/.../hub/HubRepository.kt` | Dashboard → hub HTTP calls |
| `Dashboard Tablet/.../hub/HubModels.kt` | `HubDashboardResponse`, `HubUiState` data classes |

---

## Build & Auto-Deploy Pipeline

### Build number
- Shared file: `build_number.txt` at project root (currently `2`)
- Both `hubapp/build.gradle.kts` and `clientapp/build.gradle.kts` read it:
  ```kotlin
  val buildNumberFile = rootProject.file("build_number.txt")
  val appVersionCode = buildNumberFile.readText().trim().toInt()
  val appVersionName = "1.0.$appVersionCode"
  ```

### GitHub upload task (runs automatically after `assembleDebug`)
- `hubapp/build.gradle.kts` → creates GitHub release `v1.0.N`, uploads `photosync-hub.apk`, updates `version.json`
- `clientapp/build.gradle.kts` → uploads `photosync-client.apk` to same release
- `version.json` on GitHub contains both hub and client URLs — devices check this every 5 min

### File watcher (`watch-and-push.ps1`)
- Watches for source file changes, debounces 10s, auto-commits + pushes
- After push: increments `build_number.txt`, commits, runs `.\gradlew.bat assembleDebug`
- Run from project root: `.\watch-and-push.ps1`

### Building manually (when watcher isn't running)
```powershell
# In Android Studio terminal or any PowerShell:
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```
> The bash shell in Claude Code cannot reach dl.google.com — always build from PowerShell/Android Studio terminal.

### Tokens
GitHub token is in `local.properties` (never committed):
```
github.token=YOUR_TOKEN
github.repo=ngalogivn-ship-it/photosync
```

---

## Bugs Fixed in This Session

### Bug 1 — Compression loop (FIXED)
**Problem:** `replaceFile()` creates a new MediaStore entry with a new ID and a potentially auto-renamed filename (e.g. `photo.jpg` → `photo (1).jpg`). On the next sync this replacement appeared as a new file → hub re-downloaded, re-compressed, re-replaced → infinite loop. **872 duplicate `(1)` files** were created on the phone before the fix.

**Fix applied in `MediaStoreHelper.kt`:**
```kotlin
// New SharedPreferences — tracks replaced IDs and new file IDs
private val compressionPrefs by lazy {
    context.getSharedPreferences("compression_state", Context.MODE_PRIVATE)
}

fun isAlreadyReplaced(originalId: Long): Boolean =
    compressionPrefs.getStringSet("replaced_original_ids", emptySet())!!
        .contains(originalId.toString())

private fun markReplaced(originalId: Long, newId: Long) { ... }

// replaceFile() now starts with:
if (isAlreadyReplaced(originalId)) return ReplaceResult.REPLACED

// getMediaSince() now filters out replacement files:
val compressedNewIds = compressionPrefs.getStringSet("compressed_new_ids", emptySet())!!
return results.filter { it.id.toString() !in compressedNewIds }.sortedBy { it.dateAdded }

// queryUri() now excludes IS_PENDING files at SQL level:
val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    "${MediaStore.MediaColumns.DATE_ADDED} > ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
else "${MediaStore.MediaColumns.DATE_ADDED} > ?"
```

**Fix applied in `MediaHttpServer.kt`:**
```kotlin
// handleReplace() early-return before reading POST body:
if (mediaStore.isAlreadyReplaced(id)) {
    onLog?.invoke("Skipped replace for $id — already compressed")
    return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "REPLACED")
}
```

### Bug 2 — Dashboard HMAC encoding mismatch (FIXED)
**Problem:** `HubRepository.kt` signed HMAC as lowercase hex; hub verifies Base64 → every dashboard request returned 401.

**Fix in `Dashboard Tablet/.../hub/HubRepository.kt`:**
```kotlin
private fun sign(payload: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(SHARED_SECRET.toByteArray(), "HmacSHA256"))
    return android.util.Base64.encodeToString(
        mac.doFinal(payload.toByteArray()), android.util.Base64.NO_WRAP
    )
}
```

### Bug 3 — dateTaken inconsistency (FIXED)
**Problem:** Hub stamped computed `dateTakenMs` into EXIF but passed raw `file.dateTaken` (0 when missing) to `/replace`. Client then fell back to its own `dateAdded * 1000` which could differ.

**Fix in `FileSyncer.kt`:**
```kotlin
// Normal sync (was file.dateTaken):
val ok = postReplace(clientInfo.ip, file.id, "image/jpeg", compressed, dateTakenMs)
// Legacy pass (was file.dateTaken):
val ok = postReplace(clientInfo.ip, file.id, "image/jpeg", compressedBytes, legacyDateMs)
```

### Bug 4 — Gallery position lost after replacement (FIXED)
**Problem:** Android ignores `DATE_ADDED` on insert; replaced files appeared at "today" position.

**Fix in `MediaStoreHelper.replaceFile()`:** reads `DATE_MODIFIED` from original, does a second `contentResolver.update()` after publish to restore both `DATE_ADDED` and `DATE_MODIFIED`. Pending-delete queue entry format extended to carry these values: `"<i|v>:<originalId>:<newId>:<dateAdded>:<dateModified>"`.

---

## Phone State — Full Audit (April 16 2026, ADB)

| Category | Count | Status |
|---|---|---|
| Has correct DATE_TAKEN | 175 | ✅ fine |
| NULL datetaken, date_added correct (Telegram/WA/Zalo originals) | 1039 | 🔧 fixable via filename |
| NULL datetaken, date_added=April 2026 (our (1) files) | 546 | 🔧 fixable via filename |
| NULL datetaken, NO parseable filename date | 20 | ❌ cannot auto-fix |
| **Total** | **1780** | |

**The 20 unfixable files** (old family photos, hash-named downloads):
`Charlotte and Michael 1991.JPG`, `Michael buried in the sand.JPG`, `Michael Dymchurch rd 1988.JPG`,
`Michael 84 4.JPG`, `centra.JPG`, `IMG_0008.JPG`, `IMG_0008.JPG`, `Untitled-1.JPG`, `Untitled-26.JPG`,
`_1_40_40.JPG`, `Michaael and his bicycle.JPG`, `george sucata stencil.jpg`,
`155150.jpg`, `7c8d86f8bd865045f56bae0c3a625bde.jpg`, `sucata car .jpg`, `Photo0041.jpg`
These have no date in filename and no EXIF date. Gallery shows them at date_added position (Feb 2026 when transferred).

**Key folders:** DCIM/Camera (1125), Zalo (197+180), Screenshots (103), Download (6+), WhatsApp (3+7)

**stampFileDate() strategy (v1.0.9):**
- Owned files (compressed_new_ids): IS_PENDING=1 → write EXIF → IS_PENDING=0+DATE_TAKEN
- Non-owned files (Telegram, Zalo originals etc.): openFileDescriptor("rw") via MANAGE_MEDIA → ExifInterface.saveAttributes() → update DATE_TAKEN

---

## Current Phone State (diagnosed via ADB, April 2026)

- **872 duplicate `(1)` files** exist from the old compression loop — originals and compressed twins coexist in gallery
- **1,162 camera photos from 2026** are unprocessed originals with EXIF `Orientation=6` (rotated) — hub needs to sync these
- **No IS_PENDING stuck files** — good
- Fix: menu → **"Clean up duplicate originals"** in the client app deletes the originals, keeping the smaller compressed `(1)` copies

### Cleanup tool added to `MediaStoreHelper.kt`:
```kotlin
fun cleanupOrphanedOriginals(): Int {
    // Finds all "filename (1).jpg" where "filename.jpg" exists AND is larger
    // Deletes the originals via MANAGE_MEDIA (silent on Android 12+)
    // Returns count of deleted originals
}

fun buildOrphanCleanupRequest(): android.app.PendingIntent? {
    // Batch delete PendingIntent for Android 10-11 without MANAGE_MEDIA
}
```
Wired to **"Clean up duplicate originals"** in `menu_client.xml` → `MainActivity.kt`.

---

## Local Image Processor (added this session)

**File:** `clientapp/.../media/LocalImageProcessor.kt`

Runs on startup (15s delay) and every hour in `ClientForegroundService`. Two separate fix strategies:

1. **Rotation fix** → `replaceFile()` INSERT+DELETE with 95% JPEG re-encode + EXIF stamp. Only when orientation != NORMAL.

2. **Date fix** → direct `contentResolver.update(DATE_TAKEN)` only. **Never calls replaceFile() for date-only issues** — that created chains of `(1)(1)(1)` files. Works because MANAGE_MEDIA is granted (Android 15, confirmed via ADB).

**Safety rules (learned the hard way):**
- Never touches files with `(1)` in the name — they're already replacement copies
- Skip files in `replaced_original_ids` and `compressed_new_ids`
- Date-only fix = direct update only; if it fails silently, file stays wrong but no new file is created

**Date resolution order:** MediaStore `DATE_TAKEN` → EXIF `DateTimeOriginal` → filename parse
- Filename patterns: `IMG_20250804_122335.jpg`, `20260216_172618.jpg`, `IMG-20250821-WA0001.jpg`
- 13-digit ms-epoch timestamps in filename (e.g. `IMG_1771417696748_...`) → read directly as ms
- **Year range check: 2000–2035** — prevents bogus dates from partial substring matches (was causing year 7714 dates)

**Second pass:** `fixOwnedFilesWithNullDate()` — stamps DATE_TAKEN on files in `compressed_new_ids` via IS_PENDING trick

**IS_PENDING trick (used for ALL date fixes):** set IS_PENDING=1 → stamp EXIF DateTimeOriginal → write bytes back → set IS_PENDING=0 + DATE_TAKEN. Scanner reads EXIF on publish → date persists across re-scans. Works on any file with MANAGE_MEDIA granted (confirmed on this device).

**Versioned rescan:** `LOCAL_FIX_CODE` in `ClientForegroundService.kt` (currently `4`). Bump whenever scan logic changes — service auto-clears `checked_ids` on first run so all files get re-evaluated.

**Phone state (checked via ADB April 16 2026):**
- Android 15, MANAGE_MEDIA = allow ✓
- 1780 total images, 1605 with NULL datetaken
- 698 `(1)` files (hub-created compressed copies)
- `(1)(1)(1)` files exist — caused by hub re-syncing same files (hub-side dedup issue, not LocalImageProcessor)

Tracks checked IDs in SharedPrefs `local_fix_state / checked_ids`. IDs in `replaced_original_ids` or `compressed_new_ids` are also skipped automatically.

Manual trigger: menu → **"Fix orientation & dates now"**

Wired in `ClientForegroundService.kt`:
```kotlin
localFixJob = lifecycleScope.launch(Dispatchers.IO) {
    delay(15_000L)
    while (true) {
        LocalImageProcessor(this@ClientForegroundService).processUnfixed { ... }
        delay(LOCAL_FIX_INTERVAL_MS) // 1 hour
    }
}
```

---

## Outstanding Tasks

- [ ] **Commit + push + build** (in Android Studio terminal):
  ```
  git add -A
  git commit -m "Add LocalImageProcessor: client-side orientation fix + DATE_TAKEN repair"
  git push
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  .\gradlew.bat assembleDebug
  ```

- [ ] **Build v1.0.2** (if not done above): Run in Android Studio terminal (2 separate lines):
  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  .\gradlew.bat assembleDebug
  ```
  Gradle will auto-create GitHub release `v1.0.2`, upload both APKs, update `version.json`. Both devices auto-update within 5 min. Build number is already set to `2` in `build_number.txt` and committed.

- [ ] **Run the cleanup on phone**: Open PhotoSync Client → menu (⋮) → "Clean up duplicate originals" — deletes ~872 orphaned originals (larger un-compressed versions), keeps compressed `(1)` copies

- [ ] **Let hub sync**: After cleanup, hub will compress the 1,162 unprocessed 2026 camera photos (3.5MB each, EXIF Orientation=6) and fix their orientation by baking rotation into pixels

- [ ] **Dashboard Tablet**: Rebuild and install Dashboard app with the HMAC Base64 fix so it can connect to hub

---

## Session Notes

- **Always update CONTEXT.md** at the end of each session (or when something significant changes) instead of re-reading the full conversation history
- Build artifacts go to `hubapp/build/outputs/apk/debug/` and `clientapp/build/outputs/apk/debug/` — if those folders are empty, the build hasn't run yet
- The bash shell inside Claude Code has **no internet access** to `dl.google.com` — always build from Android Studio terminal or a normal Windows PowerShell window
- ADB `content query` on this device does NOT support `--limit`, `DESC` sort, or `!=` in `--where`
- `run-as com.photosync.client` fails (release build, not debuggable) — use logcat + MediaStore queries instead

---

## ADB Quick Reference
```powershell
$adb = 'C:\Users\mcubi\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices
& $adb shell content query --uri content://media/external/images/media --projection "_id:_display_name:_data"
& $adb pull '/storage/emulated/0/DCIM/Camera/somefile.jpg' 'C:\Users\mcubi\Desktop\somefile.jpg'
```
> Note: ADB `content query` on this device does NOT support `--limit`, `DESC` sort, or `!=` in `--where`.

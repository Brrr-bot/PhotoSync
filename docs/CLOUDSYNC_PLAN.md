# CloudSync — plan

## Goal
Bring the user's **entire OneDrive + Google Drive photo/video history** into the phone gallery as
**small compressed copies that extend the timeline backwards**, while the **originals stay in the
cloud** and can be **recalled at original quality** on demand.

This is the PhotoSync model with the cloud as the archive instead of the USB hub.

## Where it runs
A **separate Android app, `CloudSync` (`com.photosync.cloudsync`), on the tablet/hub** — it's
already on and connected. No PC needed. It is its own app so it can't destabilise PhotoSync.

## How it works
1. **Auth (device-code flow)** — the tablet shows a short code + URL; you enter it once in any
   browser. We store a refresh token and reuse it. Works for both clouds:
   - Google Drive: OAuth2 device flow, scope `drive.readonly`.
   - OneDrive: Microsoft Graph device flow, scope `Files.Read offline_access`.
2. **Enumerate** every photo/video:
   - Google: Drive API `files.list` (paged), `q = mimeType contains image/ or video/`.
   - OneDrive: Graph `/me/drive/root/delta` (paged), filter by `file.mimeType`.
   Each file's best date is read (EXIF/taken time → created time).
3. **Download + compress** each original:
   - photos → WebP (quality 72) with EXIF copied verbatim via a raw chunk mux (orientation/date/GPS
     preserved; no ExifInterface write, so it works on the tablet's Android).
   - videos → a JPEG poster frame (full video stays in the cloud).
   The compressed copy is saved under the app's external files dir; a **manifest** records
   `compressedName → {provider, originalId, originalName, dateMs, isVideo}`.
4. **Resumable** — processed cloud IDs are remembered, so re-runs skip done files.
5. **Serve to the phone** over a local HTTP server (`:8770`):
   - `GET /cloud/manifest` — the index (name, provider, id, date, isVideo)
   - `GET /cloud/thumb?name=` — the compressed copy bytes
   - `GET /cloud/original?provider=&id=` — downloads + streams the **original** from the cloud (recall)

## Phone-side (next step, once this is proven)
The PhotoSync client pulls `/cloud/manifest`, downloads each `/cloud/thumb` into the gallery with
`DATE_TAKEN/DATE_ADDED = dateMs` (so it slots into the timeline going back in time), and a "download
original" action hits `/cloud/original`. This reuses the existing MediaStore-insert + restore plumbing.

## Plug in tomorrow (credentials only)
Edit `CloudConfig.kt`:
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` — Google Cloud Console → OAuth client, type
  "TVs and Limited Input devices"; enable Drive API; add yourself as a test user.
- `MS_CLIENT_ID` (+ `MS_TENANT`, default `consumers`) — Azure → App registration (personal MS
  accounts), enable "Allow public client flows", add Graph delegated `Files.Read` + `offline_access`.

Then: open CloudSync on the tablet → Connect Google Drive → enter the code → Connect OneDrive →
enter the code → Start Sync. Both run together.

## Files
- `CloudConfig.kt` — credentials + endpoints + tunables
- `auth/DeviceCodeAuth.kt`, `auth/TokenStore.kt` — OAuth device flow + token persistence
- `cloud/CloudProvider.kt`, `GoogleDriveProvider.kt`, `OneDriveProvider.kt`, `CloudFile.kt`
- `media/WebpCompressor.kt` (raw-mux WebP), `media/VideoPoster.kt`
- `sync/CloudSyncEngine.kt`, `CloudManifest.kt`, `SyncState.kt`
- `server/CloudHttpServer.kt`
- `service/CloudSyncService.kt` (foreground), `ui/MainActivity.kt`

## Tested
- Compiles in CI alongside hubapp/clientapp (new `:cloudsync` module).
- JSON parsing + WebP raw-mux logic validated against sample API responses / a real JPEG off-device
  (see `tools/` notes). Live cloud calls need the credentials (tomorrow).

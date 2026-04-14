# PhotoSync — Phase 1

Two-app Android system: hub device auto-downloads photos/videos from a client phone over hotspot.

## Setup in Android Studio

1. **Open project**: File → Open → select the `PhotoSync/` folder
2. Android Studio will create `local.properties` with your SDK path automatically
3. Sync Gradle — all dependencies download automatically
4. **Add launcher icons**: Right-click `hubapp/src/main/res` → New → Image Asset to generate `ic_launcher` mipmaps (do the same for clientapp)

## Building

- Select the `hubapp` run configuration → install on the hub device
- Select the `clientapp` run configuration → install on your phone

## First-time setup

### Hub device
1. Open PhotoSync Hub app
2. Tap **Grant Battery Exemption**
3. Tap **Select USB Drive** — pick your OTG hard drive
4. Optionally change the interface name if your device uses something other than `wlan0`
5. Tap **Start Hub Service**
6. Enable Android's built-in hotspot (Settings → Hotspot)

### Client phone
1. Open PhotoSync Client app
2. Tap **Grant Media Permissions**
3. Tap **Grant Battery Exemption**
4. Tap **Start Client Service**
5. Connect to the hub's hotspot

## How it works

- Hub polls `/proc/net/arp` every 8s to detect connected devices
- On new device: attempts HMAC-authenticated handshake on port 8765
- On success: downloads all new media (since last sync) and writes to USB drive
- Both services auto-restart on reboot

## Changing the secret key

Edit `shared/src/main/kotlin/com/photosync/shared/Constants.kt`:
```kotlin
const val SHARED_SECRET = "your-own-32-char-secret-here!!"
```
Rebuild and reinstall both apps.

# SecureVault — Private Photo Vault for Android

An **offline**, encrypted photo vault for Android, written in **Java** using only built-in Android APIs (no third-party libraries).

## Features
- **6 colour themes**: Aurora, Sunset, Ocean, Emerald, Rose Gold, Neon (gradient backgrounds)
- Unlock with **PIN + fingerprint**, using a **built-in PIN keypad** (keyboard apps never see your PIN)
- Hide photos from the gallery (optionally deletes the original after encrypting)
- **Albums** with colourful filter chips (album names are encrypted too)
- **Multi-select** (long-press): delete, move to album, or restore many photos at once
- Full-screen viewer: **swipe** between photos, pinch/double-tap zoom, photo details
- **Dashboard**: photo count, storage used, backup reminder
- **★ Favourites**, **sort** (newest / oldest / name / largest), **slideshow**
- **Album manager**: long-press an album chip to rename or delete it
- **Wrong-PIN alert**: after unlocking, see when someone typed a wrong PIN (+ full history in Settings)
- **Disguise mode**: home-screen icon and name become “Calculator”
- **Auto-lock timer**: instantly, 30 seconds, or 1 minute (screen-off always locks instantly)
- **Auto-erase** (optional): destroy the vault after 10 or 20 wrong PINs in a row

📘 **New to the code? Read [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md)** — setup, how it works, how to add features, interview notes.
- **Encrypted backup file** for pendrive / laptop, restorable on any phone

## How the security works

| Threat | Protection |
|---|---|
| Someone opens the app | PIN (6–16 digits) or fingerprint required |
| Someone guesses PINs | After 5 wrong tries: wait 30s, 1m, 2m … up to 1 hour per try. PBKDF2 (210,000 rounds) makes every guess slow |
| Someone copies the files to a PC | Files are AES-256-GCM encrypted, and the key is also bound to the phone's security chip (Android Keystore / StrongBox). Useless off this phone |
| The app leaks photos online | **No INTERNET permission** — the app cannot send anything |
| Screenshots / screen recording / recent-apps preview | Blocked with `FLAG_SECURE` |
| Cloud / Google backup | Disabled (`allowBackup=false` + data-extraction rules) |
| Phone left unlocked | Vault locks the moment you leave the app or the screen turns off |
| New fingerprint added by someone | Fingerprint key is destroyed automatically; PIN required |
| Debug access (`adb run-as`) | Release build is not debuggable |

### Key chain
```
photos  ──encrypted by──▶  MASTER KEY (random AES-256)
MASTER KEY copy A  ──▶ AES-GCM(PBKDF2(PIN))  ──▶ wrapped again by hardware DEVICE key
MASTER KEY copy B  ──▶ hardware BIOMETRIC key (needs a fresh fingerprint for every use)
```
The PIN is **never stored**. A wrong PIN simply fails to decrypt.

⚠️ **There is no "forgot PIN".** Lost PIN, lost phone or uninstalled app = photos gone, **unless you have an encrypted backup file** (see below).

## Encrypted backup (`.svb` file)

Menu → **Backup to file** creates one encrypted file you can save anywhere Android lets you pick: Downloads, SD card, or a **pendrive via USB OTG**. Copy it to a pendrive or laptop.

- Protected by a separate **backup password** (10+ characters, letters + numbers), not by the phone, so it opens on **any** device.
- AES-256-GCM, key from PBKDF2-HMAC-SHA256 (310,000 rounds).
- Every record is bound to its position (AAD), and an END record stores the photo count. So changed, reordered, removed or cut-off data is detected.

**Restore on a new phone:** install the app → create a PIN → Menu → **Restore from backup file** → enter the backup password. Photos already in the vault are skipped, so restoring twice is safe.

**Open on a laptop (Ubuntu), without the app:**
```bash
javac -d /tmp/svb tools/SvbExtract.java app/src/main/java/com/sabari/photovault/backup/BackupFormat.java
java -cp /tmp/svb SvbExtract ~/SecureVault-backup.svb ~/RestoredPhotos
```
The laptop tool uses the exact same `BackupFormat.java` as the app. Extracted photos are **not** encrypted, so delete them when done.

### `.svb` file layout
```
HEADER : "SVB1" | PBKDF2 iterations (int) | salt (16 bytes)
RECORD : length (int) | IV (12) | AES-GCM( type | id | meta JSON | photo bytes )   AAD = header + record number
...
END    : AES-GCM( type=END | photo count )
```

## Project structure
```
app/src/main/java/com/sabari/photovault/
├── VaultApp.java              # auto-lock when the app leaves the screen
├── backup/
│   ├── BackupFormat.java      # .svb file format (pure Java, also used by the laptop tool)
│   └── BackupManager.java     # backup / restore the vault
├── crypto/
│   ├── CryptoManager.java     # AES-256-GCM, PBKDF2
│   ├── KeyStoreHelper.java    # hardware keys (device + fingerprint)
│   ├── VaultKeys.java         # PIN / fingerprint wrapping, lockout
│   └── Session.java           # master key in RAM while unlocked
├── data/
│   ├── VaultStore.java        # encrypted file storage, import, export
│   ├── BitmapUtils.java       # decode in memory, thumbnails, EXIF rotation
│   └── PhotoItem.java
│   ├── AppSettings.java       # theme, last backup, auto-erase limit
│   └── VaultWiper.java        # auto-erase: destroys keys + files
└── ui/
    ├── SecureActivity.java    # base class: FLAG_SECURE + lock check + theme
    ├── AppTheme.java          # the 6 colour themes
    ├── Ui.java                # shared drawing helpers (gradients, dialogs)
    ├── LockActivity.java      # custom PIN keypad: create / unlock
    ├── Fingerprint.java       # BiometricPrompt wrapper
    ├── VaultActivity.java     # dashboard, album chips, grid, multi-select
    ├── ViewerActivity.java    # swipe viewer + details
    ├── SettingsActivity.java  # theme, security, backup, about
    ├── BackupFlow.java        # backup/restore screens (shared)
    ├── AlbumPicker.java       # "move to album" dialog
    ├── Disguise.java          # switch launcher icon (SecureVault / Calculator)
    └── ZoomImageView.java     # pinch-zoom + swipe
tools/
└── SvbExtract.java            # open a backup on a laptop
```

## Build (Ubuntu)
Requirements: JDK 17+, Android SDK (platform 34, build-tools 34).

```bash
git clone https://github.com/SabariGireeswaran/securevault.git
cd securevault
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

Create your own signing key (keep it private — you need the same key for every future update):
```bash
keytool -genkeypair -v -keystore app/vault-release.jks -alias vault \
  -keyalg RSA -keysize 4096 -validity 10000
```
Create `keystore.properties` in the project root:
```
storeFile=vault-release.jks
storePassword=YOUR_PASSWORD
keyAlias=vault
keyPassword=YOUR_PASSWORD
```
Build and install:
```bash
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```
`keystore.properties` and `*.jks` are in `.gitignore` and must never be committed.

Requires Android 11 or newer.

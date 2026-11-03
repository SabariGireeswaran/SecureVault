# SecureVault — Developer Guide

This guide is for **you, Sabari**, to build, fix and grow this app on your own.
Everything is written for **Ubuntu**. Read sections 1–4 once. Use the rest when you need it.

---

## 1. One-time setup on Ubuntu

### 1.1 Install the tools
```bash
sudo apt update
sudo apt install -y openjdk-17-jdk adb unzip git
sudo snap install android-studio --classic
```
Open **Android Studio** once. It downloads the Android SDK into `~/Android/Sdk`.

> **IntelliJ IDEA Community** can also open this project (it has Android support),
> but Android Studio is easier for Android work. Both are free.

### 1.2 Get the code
```bash
cd ~
git clone https://github.com/SabariGireeswaran/securevault.git
cd securevault
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

### 1.3 Put your signing key back (VERY IMPORTANT)
Unzip `SecureVault-signing-key.zip` (the file I gave you) into the project:
```bash
unzip ~/Downloads/SecureVault-signing-key.zip -d /tmp/svkey
cp /tmp/svkey/vault-release.jks app/
cp /tmp/svkey/keystore.properties .
```
Now `app/vault-release.jks` and `keystore.properties` exist.
They are in `.gitignore`, so git will never upload them.

---

## 2. Build and install

```bash
./gradlew assembleRelease
```
Your app: `app/build/outputs/apk/release/app-release.apk`

**Install on your phone with a USB cable:**
1. Phone: Settings → About phone → tap **Build number** 7 times (turns on Developer options).
2. Settings → Developer options → turn on **USB debugging**.
3. Connect the cable, tap **Allow** on the phone.
```bash
adb devices                       # your phone should be listed
adb install -r app/build/outputs/apk/release/app-release.apk
```
`-r` means "replace the old version and keep its data".

**Without a cable:** copy the APK to the phone (Telegram "Saved Messages", Google Drive, etc.) and tap it.

### Test builds (safe playground)
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
The debug app is a **separate app** (`com.sabari.photovault.debug`). It has its own empty vault.
Break it as much as you like — your real photos are safe.
⚠ Never keep real photos in the debug app: debug builds are less secure.

---

## 3. The golden rules (read twice)

1. **Always sign with the same key** (`vault-release.jks`). A different key = Android refuses the update,
   and uninstalling to fix it **deletes all photos**. Keep a copy of the key zip on a pendrive.
2. **Increase `versionCode`** in `app/build.gradle` for every new APK (5 → 6 → 7 …).
3. **Make a backup file before installing a new version** (Settings → Backup to file).
4. **Never change these without a migration plan**, or old data can't be opened:
   - `crypto/CryptoManager.java`, `crypto/VaultKeys.java`, `crypto/KeyStoreHelper.java` (key names, AAD strings)
   - `backup/BackupFormat.java` (the `.svb` layout)
5. **Never add the INTERNET permission.** It's the app's biggest promise.
6. **Never commit** `keystore.properties` or `*.jks`. Check with `git status` before `git push`.

---

## 4. How the app works (big picture)

```
 ┌──────────────┐  PIN / fingerprint  ┌───────────────┐  tap photo  ┌───────────────┐
 │ LockActivity │ ──────────────────▶ │ VaultActivity │ ──────────▶ │ ViewerActivity│
 └──────────────┘                     │ (home + grid) │             └───────────────┘
        ▲                             └──────┬────────┘
        │ app hidden / screen off            │ ⋮
        │ (VaultApp locks)                   ▼
        └──────────────────────────── SettingsActivity
```

### The key idea in one paragraph
A random **master key** encrypts every photo. The master key itself is stored only in
**locked form**: once locked by your PIN (and the phone's security chip), and once by the fingerprint chip.
Unlocking = unlocking the master key. Locking = throwing the master key out of memory (`Session.lock()`).

### Folder map
| Package | Class | What it does |
|---|---|---|
| (root) | `VaultApp` | Auto-lock when the app is hidden or the screen turns off |
| `crypto` | `CryptoManager` | AES-256-GCM encrypt/decrypt, PBKDF2 (PIN → key) |
| | `KeyStoreHelper` | Hardware keys in the phone's security chip |
| | `VaultKeys` | Locks/unlocks the master key with PIN or fingerprint; wrong-PIN waiting time |
| | `Session` | Holds the master key in RAM while unlocked, plus RAM caches |
| `data` | `VaultStore` | Saves/loads encrypted photos, albums, favourites |
| | `PhotoItem` | One photo (id, date, album, name, size, favourite) |
| | `BitmapUtils` | Decodes images in memory, thumbnails, rotation |
| | `AppSettings` | Normal settings (theme, sort, auto-lock, wrong-PIN log …) |
| | `VaultWiper` | Auto-erase |
| `backup` | `BackupFormat` | The `.svb` file format (pure Java — also used by the laptop tool) |
| | `BackupManager` | Backup / restore the whole vault |
| `ui` | `SecureActivity` | **Parent of all screens**: blocks screenshots, checks lock, applies theme |
| | `LockActivity` | Custom PIN keypad, create/unlock |
| | `VaultActivity` | Home: dashboard, chips, grid, multi-select |
| | `ViewerActivity` | Full photo, swipe, slideshow |
| | `SettingsActivity` | All settings |
| | `AppTheme` / `Ui` | Colours and drawing helpers |
| | `BackupFlow`, `AlbumPicker`, `Fingerprint`, `Disguise`, `ZoomImageView` | Helpers |

### Where files live on the phone
`/data/data/com.sabari.photovault/files/vault/` — for each photo:
`<id>.enc` (photo), `<id>.thm` (thumbnail), `<id>.meta` (name, album, favourite… as JSON). All encrypted.

### What happens when you add a photo
1. `VaultActivity.pickPhotos()` opens Android's picker.
2. `VaultStore.importPhoto()` reads the bytes, makes a thumbnail, writes 3 encrypted files.
3. Optionally deletes the original from the gallery.

---

## 5. How to add things (worked examples)

### Example A — add a 7th colour theme (2 minutes)
Open `ui/AppTheme.java` and add one line inside `ALL`:
```java
new AppTheme("Mango", 0xFF1A1205, 0xFF2E1F08, 0xFF2A1E0C, 0xFFFFB300, 0xFFFF6D00, 0xFFFFF8E7, 0xFFC9B48F),
```
Order: name, background top, background bottom, card colour, accent 1, accent 2, text, grey text.
Build → done. It appears in Settings automatically.

### Example B — add a new ON/OFF setting
1. `data/AppSettings.java`: add a getter and setter (copy `disguised()` as a pattern).
2. `ui/SettingsActivity.java`: add a `Switch` row (copy the "Disguise as Calculator" row).
3. Use the setting where you need it: `new AppSettings(this).yourSetting()`.

### Example C — add a new button in the photo viewer
In `ViewerActivity.buildUi()`, add another
`bottomBar.addView(action(R.drawable.ic_xxx, "Label", 0xFFRRGGBB, v -> yourMethod()));`
Icons: Android Studio → right-click `res/drawable` → New → **Vector Asset** → pick a Material icon.

### Example D — save a new detail about a photo
Photo details are JSON in `<id>.meta`. Copy how **favourites** work:
`VaultStore.setFavourite()` → `updateMeta(id, "fav", true)`, and `list()` reads `m.optBoolean("fav", false)`.
Old photos without the field just get the default value — no migration needed.

---

## 6. Debugging

```bash
adb logcat | grep -i photovault          # live logs (debug build only — release removes logs)
adb logcat -b crash                      # see the crash reason
./gradlew assembleDebug --stacktrace     # more detail when a build fails
```

| Problem | Fix |
|---|---|
| `SDK location not found` | Create `local.properties` (section 1.2) |
| `keystore.properties not found` / unsigned APK | Put the key files back (section 1.3) |
| Install says *"App not installed"* / *"conflicts"* | Signed with a different key. Use the original key. Don't uninstall unless you have a backup |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Increase `versionCode` |
| Build error after editing | Read the FIRST error line; it gives the file and line number |
| Photos don't appear after an edit | You may have changed an AAD string or key name — undo that change (rule 4) |

---

## 7. Ideas for next features

| Feature | Difficulty | Hint |
|---|---|---|
| Search photos by name | ★☆☆ | Filter `shown` in `VaultActivity.applyFilter()` by `p.name.contains(query)` |
| Grid size 2/3/4 columns | ★☆☆ | Save in `AppSettings`, call `grid.setNumColumns(n)` |
| Rotate a photo | ★★☆ | Decode → rotate with `Matrix` → re-encode → `encryptToFile` |
| Decoy PIN (opens an empty fake vault) | ★★★ | Second set of keys + second folder; `VaultKeys` with a different prefs name |
| Videos | ★★★ | Needs **chunked** encryption (e.g. 1 MB GCM chunks) — whole-file GCM uses too much RAM for videos |

Do features in the **debug build** first. Commit small steps:
```bash
git add -A && git status        # check: no .jks, no keystore.properties!
git commit -m "Add search"
git push
```

---

## 8. Explaining this project in an interview

- **Problem:** private photos on a phone are visible to anyone with the phone, apps, or cloud backups.
- **Encryption:** AES-256-GCM (confidentiality + tamper detection). Random 96-bit IV per file.
  AAD binds each file to its id, so files can't be swapped.
- **Key management (envelope encryption):** a random master key encrypts data; it is wrapped by
  PBKDF2-HMAC-SHA256(PIN, 210k rounds) **and** a non-exportable Android Keystore key (TEE/StrongBox).
  So offline brute force needs the physical phone.
- **Fingerprint:** a Keystore key with `setUserAuthenticationRequired` + per-use auth, invalidated when
  new fingerprints are enrolled. Unlocking uses a `CryptoObject`, so a fake "success" can't decrypt anything.
- **Attack surface:** no INTERNET permission, `FLAG_SECURE`, backups disabled, release build not debuggable,
  logs stripped by R8, custom keypad (no keyboard logging), exponential lockout, optional auto-erase.
- **Backups:** a portable `.svb` format: PBKDF2 (310k), per-record GCM with AAD = header + index,
  and an END record → detects tampering, reordering and truncation. Tested with a separate Java CLI tool.
- **OOP:** `SecureActivity` base class (inheritance), `BackupFlow`/`AlbumPicker` (composition/reuse),
  `AppTheme` objects (data + behaviour), small single-purpose classes.
- **Trade-off you can discuss:** no "forgot PIN" by design — recovery only through an encrypted backup.

# MorseLink — Complete Product & Engineering Specification

**You are an autonomous build agent. Your task: implement the full Android application described below, exactly as specified.** Every numbered requirement is binding. Where a detail is prescribed (a color, a port, a string, a behavior), reproduce it precisely — do not substitute your own design. Where you must choose (exact XML layout syntax, class internals), choose the simplest implementation that satisfies every requirement and runs on the oldest supported device. The document is organized so you can build in order: platform rules → architecture → transfer engine → transports & wire protocol → on-device UI → WebShare → media library → build/release → acceptance tests.

---

## 1. Product identity

- **Name:** MorseLink (applicationId `com.morselink.app`). Named after Morse code: communication without infrastructure.
- **One-line pitch:** Offline, peer-to-peer file transfer for Android. No cloud, no internet, no accounts, no ads, no analytics. Files move directly between two phones, or from a phone to any browser on the same network.
- **Three transfer surfaces:**
  1. **Phone ↔ Phone** over **Wi-Fi LAN** (both phones on the same router or hotspot) — the fast path.
  2. **Phone ↔ Phone** over **Google Nearby Connections** (Bluetooth / Wi-Fi Direct) — the zero-infrastructure path when there is no shared network.
  3. **Phone ↔ PC browser** over **WebShare** — the phone runs an HTTP server; any laptop browser on the same network can browse, download, and upload the phone's media and files.
- **Target user:** someone with an old, low-RAM phone in a connectivity-poor environment. The **primary reference device is a HUAWEI MYA-L10 running Android 6.0 (API 23)**. Everything must work end-to-end on that phone. A modern Android 14 device is the second reference device.
- **Design language:** dark theme by default, single accent color (user-selectable: teal default, plus amber, indigo, rose, sky), rounded cards, generous touch targets, custom lightweight widgets (no heavy UI framework). Calm, technical, trustworthy. The app must feel instant on a 2016 phone.

---

## 2. Hard platform rules (never violate)

1. **Kotlin**, single `:app` module, no DI framework, no code generation beyond AndroidX defaults.
2. `minSdk = 21`, `targetSdk = 34`, `compileSdk = 34`. **All runtime code must be API-23-safe**: every API above level 23 is version-checked or guarded; no crash on Android 6.
3. **Third-party libraries:** allowed only if total APK growth ≤ 5 MB. The shipped set is: AndroidX, Google Play Services Nearby Connections, NanoHTTPD (web server), ZXing core (QR). Do not add anything else.
4. **No network egress except the local network.** No analytics, no crash upload, no update pings. Crash reports and logs stay on the device until the user exports them.
5. **Manifest permissions (exactly this set, no more):**
   - `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `WAKE_LOCK`
   - `ACCESS_FINE_LOCATION` (maxSdkVersion 30), `BLUETOOTH` + `BLUETOOTH_ADMIN` (maxSdkVersion 30), `BLUETOOTH_SCAN` + `BLUETOOTH_ADVERTISE` + `BLUETOOTH_CONNECT` (API 31+, `neverForLocation`), `NEARBY_WIFI_DEVICES` (API 33+, `neverForLocation`)
   - `READ_EXTERNAL_STORAGE` (maxSdkVersion 32), `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28), `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VISUAL_USER_SELECTED`
   - `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `REQUEST_INSTALL_PACKAGES`, `CAMERA`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   - **Do not** declare `CHANGE_NETWORK_STATE` or any permission not listed. Wi-Fi/bt features declared `required="false"`.
6. `MainActivity` handles `SEND` and `SEND_MULTIPLE` share intents (`*/*`): shared files queue directly into the send flow.
7. A **foreground service** (`dataSync` type) keeps an active transfer session alive when the UI is minimized, with a persistent notification. Battery-optimization exemption is requested, never required.
8. All user-visible text lives in `strings.xml` with correct escaping (apostrophes as `\'`), and no duplicate string names.

---

## 3. Architecture blueprint

Package `com.morselink.app`, organized as:

```
MainActivity.kt            — single activity, fragment overlays + back stack
MorselinkApp.kt            — process init, crash handler hookup
core/
  transfer/  TransferEngine (singleton), TransferService, SoundFx
  network/   Transport (interface), LanTransport, NearbyTransport
  media/     MediaLibrary
  webshare/  WebShareController, WebShareServer, HotspotController
  data/      Prefs, JournalStore, HistoryStore
  logging/   LogStore (ring buffer + crash reports)
  storage/   SafStore, Destinations, ZipUtil
  util/      Integrity, DeviceTier, Fmt, Permissions, Qr, ThemeColors
  ui/        Ui (dialogs/toasts/tips), RadarView
  model/     Models.kt (TransferItem, DiscoveredPeer, MediaItem, …)
di/AppServices.kt          — manual service locator (AppServices/MorselinkServices)
feature/
  dashboard/  DashboardFragment
  transfer/   TransferFragment, QrScanActivity, QueueSheet, QueueAdapter, TransferItemsAdapter
  filemanager/FileManagerFragment, FilesPageFragment, MediaPageFragment, SelectionState
  webshare/   WebShareFragment
  history/    HistoryFragment
  settings/   SettingsFragment, ConnectionDoctorFragment, LogViewerFragment
  viewer/     ViewerActivity (built-in image/video/audio viewer)
  onboarding/ OnboardingActivity
  help/       HelpFragment
```

Principles:

- **TransferEngine is the single source of truth** for queue state. It exposes `StateFlow`s (items list, session state) and a shared `EngineEvent` flow (PeerConnected, PeerDisconnected, BatchCompleted, …). UI fragments collect and render; they never hold transfer state themselves.
- **TransportSession interface** abstracts a connected peer: `sendFile(item, sha)`, `pauseOutgoing(fileId)`, `cancelTransfer(fileId)`, `close(reason)`, plus callbacks for incoming files and progress. Two implementations: `LanSession`, `NearbySession`. The engine is transport-agnostic.
- **Journal persistence:** the outgoing queue and in-progress receiving files are journaled to disk (`JournalStore`) so a crash or force-kill never loses the queue; on relaunch, paused items are offered for resume.
- **History:** completed transfers are recorded (`HistoryStore`) and browsable, with a retry action.
- **DeviceTier** detects low-RAM/old devices and scales behavior (e.g., pre-hash limits, thumbnail sizes) so the A6 is never overwhelmed.
- **Sounds** (`SoundFx`): short synthesized tones for connected / transfer-failed / success; toggleable in settings.
- **RadarView:** custom canvas animation (sweeping radar, animated blips) used on the dashboard and pairing screen to visualize discovery. Must be cheap enough for a 2016 GPU.

---

## 4. Transfer engine — exact semantics

### 4.1 Item model
Every queued unit is a `TransferItem` with: `id`, `batchId`, `direction` (SENDING/RECEIVING), `state`, `file` (displayName, uri, mime, size), `totalBytes`, `bytesTransferred`, `speedBps`, `resumeOffset`, `retryCount`, `lastError`.

States: `QUEUED → IN_PROGRESS → COMPLETED | SKIPPED | PAUSED | CANCELLED | FAILED`.

### 4.2 The outgoing worker — the most important loop in the app
- Exactly **one** worker coroutine picks the first `QUEUED` send and calls `session.sendFile(item, sha)`.
- **Invariant INV-1 (never hang):** the worker must never wait forever. Every `sendFile` implementation must guarantee terminal completion of its await, enforced by: (a) session close/disconnect completes **all** pending send waiters immediately with a failure result; (b) every pause/cancel path schedules a **3-second terminal fallback** that force-completes the waiter if no transfer update arrives (some OEM stacks never emit a terminal update after cancelling a payload); (c) the await itself is bounded.
- **Invariant INV-2 (pause/cancel never blocks others):** pausing or cancelling one file can only ever affect that one item. The worker must return and pick the next queued file within seconds.
- **Invariant INV-3 (session restart unsticks everything):** when a session is lost or closed, all IN_PROGRESS and QUEUED sends become `PAUSED` with error "Connection lost" — never stuck, never failed. When a new session is established, all PAUSED sends are automatically re-queued (best-effort resume from `resumeOffset`).

### 4.3 Outcomes and retry
`sendFile` returns one of: `Completed`, `SkippedAlreadyPresent`, `Paused`, `Cancelled`, `Failed(error)`.
- On `Failed` with the session still active: auto-retry up to **3** times (800 ms apart), re-queuing the item; after that mark `FAILED` with the error.
- On `Failed` because the session died: mark `PAUSED` ("Connection lost") per INV-3.
- On `Paused`: state PAUSED, `resumeOffset = bytesTransferred`.

### 4.4 Integrity and de-duplication
- Sender computes **SHA-256** of files up to **256 MB** (`PREHASH_LIMIT`) before sending (cached per item); larger files skip pre-hash.
- Receiver, on receiving META, checks whether an identical file (same SHA) already exists in the destination → replies "already present" → sender marks the item `SKIPPED` ("identical file already present on receiver").
- Every data chunk is CRC32-verified on arrival; a CRC mismatch **fails the file** — it is never "repaired" by appending.
- A **conflict policy** setting governs name collisions on the receiver: skip / rename / overwrite.
- Partial writes go to a `.part` file that is appended to on resume and atomically renamed on completion.

### 4.5 Batch UX
- Each "choose files" picker session is one **batch** (`batchId`).
- When a batch finishes, the engine emits `BatchCompleted`. The receiver's UI shows **exactly one coalesced summary dialog per batch window**, ~2.5 s after the last activity: "N sent, N failed, N skipped — Average speed X MB/s". **Never one popup per file.**
- Progress rows show live bytes (e.g. "28.0 MB / 144 MB"), rolling speed, and a state chip. `Fmt` renders human-readable sizes/speeds.

### 4.6 Consent
- An incoming connection request triggers an **accept/reject popup** on the receiving phone (name + transport shown). Reject = clean `REJECT` message, nothing saved.
- Ending a session asks for confirmation. Minimizing the transfer screen does **not** end the session (it lives in the foreground service).

---

## 5. Transports and wire protocol

### 5.1 Transport selection (user-facing rule)
- **Default discovery transport: Wi-Fi LAN whenever the phone is connected to any network** (router or hotspot). Rationale: Nearby Connections never uses a shared infrastructure network — only Bluetooth or Wi-Fi Direct — and on old devices it negotiates Bluetooth and is slower than Bluetooth file sharing; LAN over the shared Wi-Fi is the fast path.
- If the phone has **no network**, default to Nearby Connections (when Play Services are available and permission granted).
- A **"switch transport" button** toggles LAN ↔ Nearby at any time. **Manual IP:port connect** is always offered as a third path. A **Connection Doctor** overlay diagnoses: Wi-Fi state, same-network check, multicast lock, Play Services availability, permissions, hotspot state — with fix-it buttons and OEM-specific guidance (battery restrictions etc.).
- Discovery list shows discovered peers (name, transport badge, signal/quality hint) with a radar animation; tapping a peer requests connection.

### 5.2 Wi-Fi LAN transport (`LanTransport`)
- **Discovery:** UDP broadcast on port **33457**, announce every **1200 ms** (device id, name, app id). A multicast lock is held while discovering.
- **Data/serve:** TCP on port **33456**.
- **Control connection:** one TCP connection carrying UTF-8 **JSON lines** (newline-terminated). Messages: `HELLO` (device identity) → `ACCEPT`/`REJECT` (session consent) → per-file `META` (name, size, mime, sha256, resume offset) → `ACK`, plus `PAUSE_REQ`, `RESUME_REQ`, `CANCEL`, `BYE`.
- **Data connection:** a separate TCP connection per file. Framing per chunk: 4-byte magic **`MLNK`** + int32 `seq` + int32 `len` + `len` payload bytes + int32 **CRC32** of the payload. Chunk size **256 KB**; reject `len` > 4× chunk size. `tcpNoDelay = true`; read timeout 25 s with idle/progress watchdog handling.
- **CRITICAL FRAMING RULE (regression-guarded):** the first line (the JSON header) of a DATA connection must be read **byte-by-byte from the raw InputStream** into a buffer until `'\n'`. Never wrap a socket that will carry binary chunks in a `BufferedReader` before the header is consumed — its 8 KB pre-read swallows the first `MLNK` frame and causes "chunk magic mismatch — framing slipped" failures. A `BufferedReader` may only be created for pure-text branches (e.g. HELLO handling).
- **Resume:** receiver reports its `.part` length in `ACK`; sender seeks and streams from that offset, continuing the `seq` numbering.

### 5.3 Nearby Connections transport (`NearbyTransport`)
- Uses Google Play Services **Nearby Connections** API: advertise + discover concurrently with a fixed service id; strategy suitable for peer-to-peer payload transfer.
- Control messages (the same JSON vocabulary: META, ACK, PAUSE_REQ, RESUME_REQ, CANCEL, BYE) travel as small byte payloads; files travel as **STREAM payloads**.
- `sendFile` registers a `CompletableDeferred` per outgoing file keyed by fileId (`outgoingWaiters`), maps payload-id → fileId, and translates `PayloadTransferUpdate`s into results: SUCCESS → `Completed`, FAILURE → `Failed`, CANCELED → `Paused` or `Cancelled` depending on the pause/cancel flag set, IN_PROGRESS → progress updates. Incoming files are written with the same chunk/CRC/integrity rules as LAN.
- **Session-end rule:** `onTransportDisconnected` and `close()` must first **fail every outgoing waiter** ("session closed") and clear pause/cancel/payload maps before tearing down — this is what makes INV-1/INV-3 hold.
- Pause/cancel: send the control message, call `cancelPayload` on the current payload, **and schedule the 3 s terminal fallback** (§4.2b).
- The Nearby UI copy must be honest: it uses Bluetooth/Wi-Fi Direct, requires Play Services, and does not use the shared router network.

### 5.4 QR pairing
- `QrScanActivity`: camera-based QR scanner (no external scanner app dependency). A QR containing `host:port` connects to that LAN peer or WebShare address. WebShare displays a QR of its URL for the PC to scan. Generation uses ZXing core.

---

## 6. On-device UI — screen by screen

Shared chrome: dark background, card surfaces, one accent color, bottom action bars where natural. Every screen works in portrait on a 5″ 720p display and must remain responsive on API 23.

1. **Onboarding** (first launch, replayable from Settings): 3–4 slides — what the app is, permissions rationale (nearby devices, storage, battery), how the three transfer surfaces work. No sign-up.
2. **Dashboard:** header with avatar (user-pickable color), device name, connection status, help button; radar animation with caption; **recent devices** list (tap to reconnect); big **Send** and **Receive** buttons; a **WebShare / PC card** with a QR-scan shortcut. Send → file picker then transfer screen; Receive → transfer screen in listening mode.
3. **Transfer screen:** toolbar; **collapsible pairing card** containing: current transport label + **switch transport** button, **scan QR** button, **manual `host:port` + connect** row, connecting indicator, discovered peers list with empty state, **connection doctor** button; **Sending** and **Receiving** lists (per-item progress, speed, state, actions); status line; action bar: **Choose files · Queue · Pause all/Resume all (contextual) · Minimize · End session (with confirm)**. Minimizing keeps the session alive in the service.
4. **Queue sheet** (bottom sheet): full outgoing queue with per-item pause / resume / cancel / retry / remove, batch progress, and the coalesced batch summary when it completes.
5. **File manager:** tabs **Photos / Videos / Files** (plus Music/Apps entries where natural). Photos and Videos show **real thumbnails in a gallery grid with multi-select** and a send button. Files shows **real file names + type-specific icons/thumbnails**, SAF folder destinations (add a folder, default download location), breadcrumb navigation.
6. **Viewer:** built-in fullscreen image / video / audio viewer (`ViewerActivity`, fullscreen material theme, config-change safe) — the user never needs a third-party gallery to preview a received file.
7. **History:** chronological list of finished transfers (direction, peer, files, sizes, outcome) with **retry failed** action.
8. **Settings:** profile (device name, avatar color) · appearance (theme/dark, accent color) · transfer (sounds, **conflict policy**) · notifications · storage access (SAF destinations, default download location, all-files access guidance) · battery (optimization status + request) · logs (**app log toggle, open log viewer, export to .txt, clear**) · crash reports toggle · connection doctor · OEM guidance · replay onboarding · FAQ/help · about.
9. **Log viewer:** monospace, severity-colored, searchable; **export produces one .txt** beginning with a `MorseLink <version> (<versionCode>)` header, containing the full app log **plus any captured crash reports**. Crash reports are captured by an uncaught-exception handler, stored locally, listed, and never sent anywhere.
10. **Tips:** lightweight one-time-per-topic tooltips (e.g. pairing hint), dismissible.
11. **Empty/error states everywhere:** every list has a friendly empty state; every failure has a human message and a next step (doctor / retry).

---

## 7. WebShare — phone-to-browser server

### 7.1 Server behavior
- **NanoHTTPD on `0.0.0.0:33455`**, started/stopped **only by explicit user action** (WebShare card/fragment shows Start/Stop, the URL, and a QR code).
- **INV-4 (no idle shutdown):** the server **stays on until the user stops it**. No idle timeout, no auto-teardown, and screen-off is never a reason to close. It must also never shut itself down while a browser session is connected.
- The address is a **bare `IP:port` URL with no token** (`http://192.168.x.x:33455`). Access control is **consent-based**: when a new browser session opens the page, the phone shows an **accept/reject popup**; until accepted, the browser sees only a waiting screen. Rejected sessions get nothing.
- Runs beside the foreground service; a persistent notification shows the running state and URL.
- **Optional hotspot mode** (`HotspotController`): start the phone's Wi-Fi hotspot and show SSID/password so a PC can join the phone directly; falls back gracefully on Android versions where hotspot control is restricted (then show manual-setup instructions).
- Endpoints: `GET /` (single-page app UI), `/api/hello` (handshake/consent), `/api/info` (device name, OS), `/api/counts` (per-category counts + storage used/total), `/api/files` (media query: category, folder, search, sort, paging), `/api/fs` (file-system browse), `/thumbnail` (sized media thumbnails), `/download`, `/download-file` (raw file), `/download-folder`, `/download-zip` (streamed zip of a selection or folder), `/api/qr` (QR image of the URL), `POST /upload` (browser → phone, into the user's chosen destination), `/api/upload-status` (upload progress polling).

### 7.2 Browser UI (dark, per the reference design; single-page app served from `/`)
- **Left icon sidebar:** app logo, Home, Photos, Videos, Music, Files, Apps, Folders. **Top right:** connected status, downloads, refresh, power icon.
- **Home:** device name, OS, stat cards (photos / videos / music / documents / apps counts), storage bar (used/total), and an upload panel — "Upload files" button **plus a drag-and-drop box** ("Drag files here or click to send to phone").
- **Category pages (Photos / Videos / Music / Documents / Apps / Files)** share a top bar: **Upload button, search box ("Search this category"), sort dropdown (e.g. "Newest first"), "N selected" indicator, and a green "Download as zip" button.**
- **Photos:** grid of **real thumbnails** (green checkmark overlays when selected). **No filename text under photo/video tiles.** Left **folder sidebar** (browsable, with per-folder counts: Camera, Screenshots, WhatsApp Images, …). Progress overlays on tiles during operations. **Transfer summary** popup after a batch: "N sent, N failed, N skipped — Average speed X MB/s".
- **Videos:** same grid + folder sidebar; thumbnails with duration; **preview modal** (large preview, filename, green Download button, close X, left/right arrows).
- **Music:** **table** (Title, Artist, Duration, Size) with row selection; **bottom player bar that keeps playing while the user switches tabs** (INV-5). Exactly **one upload progress UI** at a time (INV-6).
- **Files:** internal-storage browser — list rows (Name, Size, Modified) with checkboxes and row actions (download/share/delete); left sidebar with quick folders (Internal storage, Download, DCIM, Documents, Pictures, Movies, Music, WhatsApp, Telegram, …); **sticky address bar** (`storage / emulated / 0 / Download`) whose **every segment is clickable** to navigate (INV-7); folder multi-select + download-folder-as-zip.
- **Apps:** grid of installed app icons with names; per-app **Download APK** (extracts the APK).
- **Uploads:** drag-drop or picker → progress via `/api/upload-status` → files land in the phone's chosen destination and appear in the phone's history/log.
- **Mobile browsers (responsive):** the Files tab shows **real file names and type-specific thumbnails**; Photos and Videos tabs show **real gallery-style thumbnails with individual selection** (INV-8) — i.e. the mobile WebShare experience mirrors a native file manager, not a desktop table dump.
- Media JSON exposes `date = dateTakenMs > 0 ? dateTakenMs : dateModified * 1000` so the browser sorts by real capture date.

---

## 8. Media library

- Backed by **MediaStore** (`MediaLibrary`), with categories PHOTOS / VIDEOS / MUSIC / DOCUMENTS / APPS (+ "large files" view), and `folderInfo()` (name, count, size) powering folder sidebars. Installed apps come from PackageManager (icon, label, APK path/size).
- **Photos/Videos ordering (INV-9):** sort by **date taken**, falling back to date modified — implemented as a SQL `CASE WHEN DATE_TAKEN > 0 THEN DATE_TAKEN ELSE DATE_MODIFIED * 1000 END` sort expression with `_ID DESC` tiebreak — so photos group by day with day headers (Today / Yesterday / date) **appearing exactly once, in order** (no scattered or duplicated day headers).
- **Pagination (INV-10):** never place `LIMIT`/`OFFSET` inside the MediaStore sort string (Android 14's MediaProvider rejects it with "Invalid token LIMIT"). Page by opening the cursor, `moveToPosition(offset)`, then a do-while loop reading `limit` rows.
- Thumbnails load through standard sized content URIs, downsampled per DeviceTier.

---

## 9. Security & privacy rules

1. No account, no cloud, no telemetry, no ads. The app's entire network surface is the local network and Nearby.
2. Consent popups (accept/reject) guard: incoming phone connections and new WebShare browser sessions.
3. Crash reports and logs are local-only; export is user-initiated and produces a plain .txt.
4. **There is no "connect to stranger" / temporary-link / hotspot-join feature.** Do not implement one; transfers are between devices the user explicitly paired, or a WebShare session the phone holder explicitly accepted.
5. `networkSecurityConfig` permits cleartext only for local/LAN use as required by WebShare.

---

## 10. Build, versioning, release

1. Version scheme: `versionName` semver (currently **1.5.1**), `versionCode` monotonic integer (currently **15**); bump both on every release.
2. **Launcher logo:** the custom MorseLink logo ships as launcher icons (adaptive + legacy + round mipmaps) in **every** build. Never ship a default template icon.
3. CI (GitHub Actions): on push, assemble **release (signed) and debug APKs**, run the workflow to green, and **publish the APKs as GitHub Release assets**. A release is not done until both APKs are downloadable artifacts.
4. Keep the release APK small (currently ~6.5 MB; debug ~8 MB) — the ≤5 MB third-party growth budget is part of this.
5. The in-app log header format `MorseLink <version> (<versionCode>)` ties exported logs to the exact build.

---

## 11. Acceptance tests — a build is done only when all pass

1. Two phones on the **same Wi-Fi**: discovery defaults to **LAN**, and a 144 MB video transfers at Wi-Fi speed (not Bluetooth speed). Transport switch to Nearby still works.
2. **Pause or cancel one file mid-transfer** → the other queued files continue and complete; nothing remains "Queued" forever; no app restart is needed.
3. **End a session mid-transfer, immediately start a new session** → the interrupted file resumes (or re-queues) on the new session; new sends start right away; recovery never requires restarting both apps.
4. **WebShare stays on** after 30+ minutes idle, with screen off, and while a browser session is connected; it stops only when the user taps stop.
5. WebShare opened from a browser → phone shows accept/reject popup; rejected browser gets nothing; accepted browser gets the full UI.
6. LAN transfer does not fail with "chunk magic mismatch" (byte-by-byte header read present).
7. Photos day groups (Today / Yesterday / dates) appear **once, in order**; no duplicate headers.
8. Android 14 device paginates media without "Invalid token LIMIT" errors.
9. Receiver sees **one** summary dialog per batch (not one per file).
10. Full flow works on **Android 6 (API 23)**: pairing, send, receive, resume, WebShare, viewer, export.
11. Logo present; crash report capture + log export to .txt intact.
12. WebShare: no filename text under photo/video tiles; folder sidebar browsable; music player persists across tab switches; exactly one upload progress UI; Files address bar sticky with all segments clickable.
13. Mobile browser: Files shows real names + type thumbnails; Photos/Videos show real gallery thumbnails with individual selection.
14. No stranger-link/temp-link functionality anywhere; permission list matches §2.5 exactly.
15. All strings escaped; no duplicate resource names; builds green.

**Definition of done:** all 15 acceptance tests pass on the Android 6 reference phone and an Android 14 device, the workflow is green, and signed release + debug APKs are published as release assets.

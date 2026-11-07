# 常青笔记 Ivynote for Android

Native Android client, the counterpart of the Apple client
([ivynote-apple](../ivynote-apple)) with the same features and the same
behaviour. Application ID `app.ivynote` (debug builds: `app.ivynote.debug`).

The server, web client, and the protocol this client implements live in the
[ivynote](../ivynote) repository. **The source of truth for the API and sync
protocol is `ivynote/client/docs/client-development-guide.md`**; this
repository does not keep a copy.

| | |
|---|---|
| Server API | `/api/v1`, tested against ivynote `dev` (2026-11) |
| Platforms | Android 8.0+ (API 26), phones and tablets |
| Language | Kotlin, Jetpack Compose (Material 3) |

## Features

- **Libraries**, as on the desktop and Apple clients: a *local library* lives
  only on this device (no account, no sync); a *synced library* belongs to one
  account on one server. Several of each, switched from the shelf header.
  A local library can turn on sync later: it is copied into a newly registered,
  empty account (`LibraryMigrator`).
- **Sign in / sign up** to the official nodes (中国大陆 `web.ivynote.net`,
  全球 `web.ivynote.app`, preselected by the device's region) or a self-hosted
  server. The first account on an empty server becomes its admin.
  The official addresses can be moved by the website without a release: the
  sign-in screen asks `ivynote.net`, then `ivynote.app`, for
  `/api/v1/regions`, keeps the answer, and otherwise uses the last answer or
  the built-in addresses (`OfficialRegions`, as `regions.ts` on the desktop).
- **Local-first sync** (guide ch. 6): everything is read from and written to the
  local SQLite database first; an outbox is pushed in the background with HLC
  field timestamps and `baseRev` for three-way body merges; incremental pulls
  by `seq`; snapshot on first sync; server events (SSE) trigger pulls; syncs on
  start, on edits (800 ms debounce), on returning to the foreground and when
  the network comes back; exponential back-off when offline. Conflict copies
  are announced, refused changes are kept and counted, nothing is lost.
- **Shelf** (guide 11.2): pinned section, nested notebooks, root notes, tags,
  trash; sort by updated / created / title; expansion remembered per library;
  long-press menus for notebooks and notes (new, rename, move, pin, duplicate,
  trash, restore, delete forever, empty trash); pull to refresh.
- **Editor**: the web client's own CodeMirror 6 editor with live preview,
  tables, mermaid and `[[wiki links]]` (with completion) in a WebView, so all
  clients render Markdown the same way. Phone format bar above the keyboard,
  outline sheet, following a missing `[[link]]` creates the note. Images and
  other attachments load with the account's token and stay cached for offline
  reading; videos seek with byte ranges.
- **Attachments** (guide 7): photos, camera and files from the format bar,
  embedded at the cursor (`![name](ivy://res/<id>)`, a file card for other
  files). Uploaded at once; offline they wait under a `local-` id, show from
  the device, and are uploaded and rewritten in every note before the next
  push. A refused file (too large) is reported and does not hold back sync.
  A local library keeps its files on the device (de-duplicated by SHA-256)
  and uploads them when it turns on sync.
- **Media library**: filters (all, images, video, audio, files, unreferenced,
  trash), file name search, a thumbnail grid, and details: preview, the notes
  that reference the file, rename, public link (make, copy, revoke),
  share or save the original, trash, restore, delete forever. Offline it
  shows the synced metadata.
- **Tags**: a note's tags under its title; a picker to search, create
  (「创建 xxx」) and toggle them (at most 50 per note); rename, recolor and
  delete from the picker or the shelf.
- **Backlinks**, computed locally (guide 9.2: no self links, no trash, not
  from code; a title shared by several notes points at the newest), with the
  line around each link.
- **History**: the server's saved versions with device and size; preview the
  source; restore writes it back as a normal edit, so it syncs and the
  replaced text stays in the history.
- **Sharing**: a public link with the required password (generated or typed,
  at least 6), expiry (1, 7, 30 days, never) and views; copy link and
  password together. Copy Markdown, send to another app or export `.md`;
  online the export publishes attachments and uses their public addresses,
  offline it is the local text.
- **Search** across titles and bodies, ranked as on the web (guide 11.5).
- **Updates**, as on the desktop (`AppUpdates` in core, `update/` in the app):
  15 s after start and then every 6 hours the app asks `ivynote.net`, then
  `ivynote.app`, for `GET /api/v1/update?platform=android&kind=apk&…` (never
  GitHub). A new version opens a dialog with its notes: 立即更新 downloads the
  APK (resumable, falling back to the other site, checked against size and
  SHA-256), saves and syncs the open library, and hands it to the system
  installer (`PackageInstaller`); 稍后 asks again tomorrow, 跳过此版本 stays
  quiet about that version. 关于常青笔记 (library list, or the shelf menu)
  checks by hand and turns the anonymous install id off. The first update asks
  once to allow 常青笔记 to install apps. Debug builds (`app.ivynote.debug`)
  cannot be updated by a release APK and point to the website instead.
- Phones show one pane at a time; tablets and unfolded foldables show the
  shelf beside the note. Light and dark themes follow the system.
- **Languages**: 简体中文 and English, as on the web (`core/…/Strings.kt`,
  one interface, so a string missing from a language does not compile).
  The language follows the system (the first supported one in its list,
  English otherwise) until one is picked under 语言 · Language (library
  menu, libraries list, account sheet); the choice is remembered. Signed in
  to a synced library, the account's language (`User.language`) comes first;
  choosing there also saves it to the account (`PATCH /me {language}`, kept
  as pending and sent later when offline; "follow the system" clears it).
  A new account is registered with the language in use (`language` in
  `POST /auth/register`). See guide §界面语言 and `LanguageSettings`.

## Layout

```text
core/          Kotlin/JVM, no Android: models, API client (OkHttp), session,
               HLC, sync engine (outbox, push/pull, SSE), shelf tree, outline,
               search, library catalog and migration. Unit-tested on the JVM.
app/           The Android app (Compose): screens, SQLite store, Keystore
               credentials, the editor WebView and its attachment loader.
app/src/main/assets/editor/
               built note editor page (generated by editor-web/build.sh, committed)
editor-web/    sources of that page: the bridge (main.ts) and the native frame
.github/       CI (tests, lint, APKs) and the tag-triggered release
```

`core` holds everything that can be tested without a device; the UI stays thin.
`SyncEngine` is a port of the web client's `packages/core/src/sync/engine.ts`
and of the Apple client's `SyncEngine.swift`; keep the three in step when the
protocol changes.

## Building

Requirements: JDK 17+ and the Android SDK (platform 35). Android Studio
(Ladybug or newer) opens the project as is.

```bash
./gradlew :core:test                 # sync engine, API client, shelf, libraries
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug
```

On a machine without the Android SDK the core still builds and tests:

```bash
./gradlew -Pivy.coreOnly=true :core:test
```

### The note editor page

```bash
(cd ../ivynote && npm install)   # once
editor-web/build.sh              # after changing editor-web/ or pulling ivynote
```

The page is served from the app's assets at
`https://appassets.androidplatform.net/assets/editor/` (androidx.webkit's
asset loader); attachments (`ivy://res/<id>`) load from `/res/<id>` on the same
origin, which the app fetches with the account's token and caches on disk.
Kotlin talks to the page through `window.ivy` (see `editor-web/main.ts`); the
page reports edits, link taps, focus and scrolling back through `IvyAndroid`,
a message listener that answers only that origin.

## Releases

Every push and pull request runs CI: core tests, app unit tests, lint, and
debug and release APKs (the debug APK is attached to the run).

A tag `vX.Y.Z` builds a release APK and publishes it on a GitHub release with
its SHA-256. `versionName` is the tag without `v`, `versionCode` is
`X*1000000 + Y*1000 + Z`.

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Set these repository secrets so releases are signed with your key (without
them the APK is signed with a throwaway debug key, fine for testing, but it
cannot update an install signed with the real key):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | the key alias |
| `ANDROID_KEY_PASSWORD` | the key password (defaults to the keystore password) |

Create a key once with:

```bash
keytool -genkeypair -v -keystore release.jks -alias ivynote -keyalg RSA -keysize 4096 -validity 10000
```

Locally, a git-ignored `keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`) does the same for `./gradlew :app:assembleRelease`.

## Rules carried over from the guide

- Self-hosted: the user enters the server address; nothing is hard-coded but
  the two official nodes. Plain `http` is accepted only on the local network
  and shows a warning unless it is this device.
- Bearer token only, no cookies, no HTTP cache; the token and cached user are
  encrypted with a key in the Android Keystore. Backups exclude all app data.
- Session restore: open with the cached account, refresh with `GET /me`;
  only 401/403 end the session (local notes and the outbox are kept for the
  next sign-in), network errors and 5xx mean offline.
- Signing out syncs first, revokes the token, then deletes the library's
  local copy and cached attachments.
- Never log tokens, passwords or note content.
- The admin console is not rebuilt here; admins get a link to `{server}/admin`.
- Links in notes open in the system browser; the editor page cannot navigate
  anywhere else.

## Roadmap

1. ~~Connect, sign in, sign up, session restore, libraries~~
2. ~~Local store (SQLite) and sync engine: HLC, outbox, push/pull, SSE~~
3. ~~Shelf, editor with live preview, outline, search, tags editing~~
4. ~~Attachment upload (camera, files) with the offline upload queue (guide 7.1–7.2)~~
5. ~~Backlinks, history, sharing, media library~~; next: rendered history
   preview, copy as rich text, share management and settings

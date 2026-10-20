# Feature: video in notes + AList (`feat-video-alist`)

You can embed videos on a page of a paged note. They play in place, can pop out to a floating window over the canvas, and can open from an AList server, a URL or a local file. Timestamp links and frame capture insert into the note.

> **Not compiled.** The sandbox (aarch64, no Android SDK/JDK) couldn't build or run anything, so none of this code has been compiled or tested. The first CI build must compile it and run the unit tests. See "Unverified" at the end.

## Scope change during development

The first brief asked for a dockable **side panel** plus a floating mini window. The user then changed it: **no side panel**. A video is an **element on the canvas**, like an image, that plays in place. The floating window stays, toggled with *Pop out* and *Pin to canvas*. No side-panel code was ever written. Everything below is the on-canvas design.

## Commits (on `feat-video-alist`, base `2d19e7e` "Bump to 0.9.2")

1. `Add Media3 ExoPlayer, HLS and UI to the version catalog`
2. `Add the video_link page item and persist it in the .xnote manifest`
3. `Add an AList v3 client with Keystore-sealed credentials`
4. `Add the embedded video page item`
5. `Play videos embedded on the page, or in a floating window`
6. `Document the video/AList feature for merging` (this file, plus a doc-comment fix)

## Files

### New (isolated, no merge conflicts expected)
| File | What it is |
|---|---|
| `core/model/VideoItem.kt` | Embedded video page item (`kind: "video"`). Box like an image, aspect-locked resize, no rotate or mirror. Holds `source`, `title`, `startSeconds` (resume point). Runtime-only `poster` (last frame). Also defines the `EraserProtected` marker interface. |
| `core/model/VideoLinkItem.kt` | Timestamp link chip (`kind: "video_link"`), plus `VideoSource` (`url` / `alist` / `local`) and `formatTimestamp`. |
| `format/VideoLinkJson.kt` | Manifest read/write for both kinds. Their fields sit under one nested `"video"` object. |
| `alist/AlistPaths.kt` | Pure helpers: normalize/join/parent/breadcrumbs paths, video extension filter, percent-encoding, `{server}/d{path}?sign=` URL. |
| `alist/AlistJson.kt` | AList v3 envelope parsing (`code`/`message`/`data`), listing, `fs/get`, `AlistException`. |
| `alist/AlistClient.kt` | `login`, `list`, `get`, `playbackUrls` over `HttpURLConnection` (no new HTTP dependency). On a 401 with a signed-in token it signs in again and retries once. |
| `alist/AlistSettings.kt` | Server, username, password and token in their own SharedPreferences file `alist`. Password and token are sealed. Also remembers the last folder. |
| `alist/SecretBox.kt` | AES-256-GCM with a key held in the Android Keystore (alias `xnotes.alist.secret`). |
| `video/Playback.kt` | `PlayerPool` (at most **2** ExoPlayers, recycled) and `Playback` (one video in one player, state mirrored to Compose, frame grab from the TextureView). |
| `video/VideoSession.kt` | Per-pane state: embedded playbacks, floating, fullscreen, dialogs, per-frame screen boxes, off-screen release, pop out / pin, capture, links. `VideoSessions.of/drop`. |
| `video/VideoLayer.kt` | The composable laid over the canvas: live pictures over item boxes, floating window, fullscreen dialog. Also `ForwardingHost`, which passes touches through to the canvas. |
| `video/VideoSurface.kt` | `VideoFrame` (TextureView in Media3 `AspectRatioFrameLayout`), `VideoSurface`, `InkOverVideo`, `EmbeddedVideoHost`. |
| `video/VideoControls.kt` | Transport bar: 48dp hover-aware buttons. |
| `video/VideoDialogs.kt` | Open-video picker, URL dialog, local file picker (SAF, persistable permission), AList settings (with connection test), AList browser. |
| `res/values/strings_video.xml`, `res/values-b+zh+Hans/strings_video.xml` | All new strings, English and Simplified Chinese. |
| `test/.../format/VideoLinkCodecTest.kt`, `test/.../alist/AlistTest.kt` | JVM unit tests. |

### Shared files touched (where conflicts may occur)
| File | Change | How to resolve |
|---|---|---|
| `gradle/libs.versions.toml` | `media3 = "1.8.0"` in `[versions]`. `androidx-media3-exoplayer`, `-exoplayer-hls`, `-ui` in `[libraries]`, after `latex-renderer`. | Keep both sides. Append lines. |
| `app/build.gradle.kts` | 3 `implementation(libs.androidx.media3.*)` lines after `latex.renderer`. | Keep both sides. |
| `AndroidManifest.xml` | `<uses-permission INTERNET>` and `android:usesCleartextTraffic="true"` on `<application>` (so LAN AList servers over `http://` work). | The WebDAV and AI branches likely add INTERNET too. Keep one copy. If a merger prefers a `networkSecurityConfig`, it must still allow cleartext for user-entered LAN hosts. |
| `format/DocumentCodec.kt` | +1 import (`VideoItem`, `VideoLinkItem`). +2 lines in `writePage` `when`. +1 field `video` in `ItemScratch`. +1 key `"video"` in `parseItem`. +2 branches in the kind `when`. All marked `feat-video-alist`. | Small additive edits. Re-apply by hand if the context moved. |
| `core/model/Clone.kt` | +2 `deepCopy` branches (`VideoLinkItem`, `VideoItem`). | Additive. |
| `canvas/InteractionController.kt` | The two whole-item eraser filters (scribble-erase and the STROKE eraser) also skip `EraserProtected`. | One-token addition per line. **The ink branch may also edit these lines.** Keep their change and append `&& it !is com.xnotes.core.model.EraserProtected`. |
| `core/infinite/EraseSession.kt` | Same `EraserProtected` skip. | Same. |
| `ui/Editor.kt` | (a) `VIDEO_GAP` constant after `AUTO_LINK_SLOP_DP`. (b) First line of `controller.onLinkTap` calls `tapVideo(...)`. (c) A delimited block `// --- video (feat-video-alist) --- … // --- end video ---` just before `addSticker`: `onVideoTap`, `pageIndexOfItem`, `insertVideoItem`, `insertVideoLink`, `insertVideoFrame`, `videoItemChanged`. | (c) is self-contained. Move it whole. (b) is one line. |
| `MainActivity.kt` | One line in `EditorPane`'s paged-note canvas Box after `ZoomLockHint(editor)`: `com.xnotes.video.VideoLayer(editor)`. | Keep it the **last** child of that Box (so it sits over the canvas). |
| `ui/Toolbar.kt` | `ImageMenu` gets an *Insert video…* `DropdownMenuItem` after *Stickers*. | Additive. |

## Design

### On the page
- **`VideoItem`** is an ordinary `CanvasItem`. Select, move, copy/paste, undo and locking all work through the existing paths. Resize uses the generic transform handles. `applyTransform` keeps the aspect ratio and ignores rotation, and `selectionIsRotatable` doesn't list it, so it gets no rotate grip. Like images and text boxes, the whole-item erasers can't remove it (`EraserProtected`), so ink written over a video can be erased without deleting the video.
- The **page caches only ever paint a poster**: a dark box with a play mark and "title · resume time", or the last grabbed frame once it has played. The canvas pipeline never rasterizes video frames.
- **Placement:** in the middle of the current view, 60% of the page width at 16:9. Once the first frame reports the real size, the box is re-fitted to the video's actual aspect (`fitToVideo`, which marks the note dirty).

### Playing in place
- A **finger tap** on a video (the existing PAN-tap → `onLinkTap` route, now checked first by `Editor.tapVideo`) starts it. Tapping again shows or hides its controls.
- `VideoLayer` lays a `TextureView` exactly over the item's on-screen box. While anything plays inline, the box is recomputed every frame (`withFrameNanos` → `updateGeometry`), so it follows scroll, zoom and page layout. When nothing plays inline, no per-frame work runs.
- **Writing over a playing video:** `ForwardingHost` wraps the picture and re-dispatches every touch and hover event to `editor.surfaces`, shifted into its coordinates. Without this, Compose would stop hit-testing at the overlay and the canvas would never see the pen. So pen, finger-scroll, selection and the tap-to-toggle all keep working over the video.
- **Ink above the video:** the cached ink sits under the live picture, so `InkOverVideo` redraws every page item above the video in z-order, clipped to the box, through the app's own `AndroidRenderer`. It refreshes on each `contentVersion` bump, i.e. after each committed stroke.
- **Off screen, deleted or note closed:** the playback pauses, writes its position into `VideoItem.startSeconds` (dirty → autosaved), saves the current frame as the poster (JPEG in `cacheDir/video-posters`) and returns its player to the pool.
- **Pool:** at most 2 ExoPlayers per process. Starting a third evicts the least recently used embedded playback, then the floating one. One idle player is kept warm.

### Floating window
- Screen-fixed (it doesn't scroll with the canvas) and composed in the pane's canvas Box.
- Drag it by the title bar. Resize it from the 40dp grip at the top right of the picture: width only, height follows the aspect. Minimise it to a 40dp bar that keeps play/pause.
- Sizes are in dp and clamped to the pane on every layout, so it survives split-screen, multi-window resizes and rotation. Default width is 36% of the pane on wide panes (≥840dp, the expanded width class) or 70% on narrower ones, between 240dp and 560dp. Maximum is 60% or 95%.
- **Pop out** moves the *same* `Playback` to the floating slot, so playback is never interrupted. **Pin to canvas** moves it back into its origin item if that is still on screen, or else embeds a new video in the middle of the view and continues there.
- **Fullscreen** (a full-window `Dialog`) borrows the playback from either slot and returns it.
- Only one surface is bound to a player at a time (`Playback.surfaceSlot`). Bind and unbind are identity-checked, so the order in which slots attach and detach doesn't matter.

### Configuration changes
`MainActivity` already declares `configChanges` for orientation, screen size, layout, density and uiMode, so the activity isn't recreated. `VideoSession` and `Playback` also live outside composition (`VideoSessions` is keyed by `Editor`). Rotation, multi-window and split resizes therefore never restart playback. ON_STOP pauses all playback. Orientation is never forced.

### Tablet and stylus
- All controls are 48dp targets with `pointerHoverIcon(Hand)`.
- The control row scrolls sideways when the window is narrow. Small floating windows drop the time label.
- Dialogs size to `min(90% width, 640dp)`.
- The AList browser has 52dp rows.

### Timestamp links
- `VideoLinkItem` is a chip that reads `▶ title · mm:ss` and stores `{source, title, t}`.
- **Insert timestamp link** in any control bar places it under the video (or under the captured frame), stacking below links already there.
- **Tapping a link** seeks the matching video in place if it is on screen. Otherwise it seeks one already playing inline, or failing that opens the video in the floating window at that moment. It never hands off to an external app.

### Frame capture
`TextureView.getBitmap` at the video's resolution (capped at 1920px wide) → JPEG → `Editor.insertImageAt`, the app's existing image path, so undo and saving behave as for any image. The picture goes to the right of the video if the paper has room, otherwise below it, and a timestamp link is placed under it. That makes two undo steps.

### AList
- `POST /api/auth/login {username,password}` → `data.token`.
- `POST /api/fs/list {path, password:"", page, per_page, refresh}` with `Authorization: <token>`.
- `POST /api/fs/get {path}` → `raw_url`, `sign`.
- **Playback URLs are resolved fresh every time** a video or link opens, because raw URLs expire. Links and items store the **AList path**, never the raw URL. The candidate order is `raw_url` (a server-relative one is prefixed with the server), then `{server}/d{encoded path}?sign=…` when a sign came back or no `raw_url` did. If every candidate fails in the player, the path is resolved once more (to cover an expired URL) before an error is shown.
- **No `Authorization` header is ever sent to media URLs.** They carry their own grant, and a redirect could leak the token to a storage host.
- **Credentials:** use a token, or a username and password, or neither (guest). The password and token are encrypted with a Keystore AES-GCM key. I used this instead of `EncryptedSharedPreferences` because security-crypto is deprecated and would be one more dependency; it's the same primitive. A backup restored to another device can't decrypt them, so the user just signs in again.
- **Browser:** folders plus the extensions mp4 / mkv / webm / m3u8 / mov / flv (plus m4v / ts), folders first, breadcrumbs and an up button, refresh (`refresh:true`), and it remembers the last folder.

### File format (backward compatible)
```json
{"kind":"video", "rect":[x,y,w,h], "video":{"source_type":"alist","source":"/movies/a.mp4","title":"a","t":83.5}}
{"kind":"video_link", "pos":[x,y], "video":{"source_type":"url","source":"https://…/a.m3u8","title":"a","t":12,"size":14}}
```
- **Old app reading new files:** the loader already skips unknown `kind`s, so the note opens without the videos. Note that if the old app then saves the note, the videos are lost, which is inherent to the existing forgiving loader.
- **New app reading old files:** nothing changes.
- **Malformed data:** a link or video with no source is dropped, a bad `t` reads as 0, an unknown `source_type` reads as a URL, and a bad rect falls back to 480×270. This is covered by `VideoLinkCodecTest`.

## Known limits
- **Paged notes only.** On the infinite canvas (`InfiniteEditor`, GL/mesh renderer, `CanvasCodec`), `insertVideoItem` refuses with a message. Supporting it needs a mesher path for the poster, `CanvasCodec` read/write and an `InfiniteEditor` tap hook. The model and `VideoSession` are reusable for that.
- **Rotated view** (View ▸ rotation ≠ 0): inline play falls back to the floating window, and *pin* asks you to un-rotate first. Turning the overlay and its touch forwarding wasn't worth the risk unverified.
- **Ink still being written** over a playing video is drawn by the canvas or front-buffer wet path *under* the picture. It appears over the video once the stroke is lifted and committed.
- **Moving or resizing a playing video:** the live picture jumps to the new box when the gesture ends. The selection handles drawn by the canvas can be partly covered by the picture.
- **Finger taps:** a finger tap plays or toggles. With finger-draw on, a finger draws instead (the same rule as PDF links), so use the controls' stop and pop-out buttons.
- **Local videos** keep a persistable read grant. If the file is moved, the link can't open and an error appears in the controls.

## Unverified (could not compile)
- I checked none of the Kotlin against a compiler. The likeliest compile issues are:
  - Media3 1.8.0 API names: `setVideoTextureView`, `DefaultMediaSourceFactory(DataSource.Factory)`, `VideoSize.pixelWidthHeightRatio`, `PlaybackException.errorCodeName`. Some of these sit behind `@UnstableApi`; I added `@OptIn(UnstableApi::class)` where I expected it to be needed.
  - The `material-icons-extended` icon names used.
  - `androidx.lifecycle.compose.LocalLifecycleOwner`.
  - Sealed `GeometrySnapshot` subclasses, which is why the items live in `core.model`.
- **Version check:** the Media3 version `1.8.0` was not checked against Maven. Bump it to the current stable if it doesn't resolve.
- **Untested behaviour:**
  - Touch forwarding from the Compose `AndroidView` overlay to `editor.surfaces`, including stylus unbuffered dispatch and the GL front-buffer pad.
  - `TextureView.getBitmap` timing.
  - AList against a real server: token header format, `sign` for `/d` links, `raw_url` for 302-only drivers.
  - Keystore on devices without StrongBox.
- **Tests:** the unit tests (`VideoLinkCodecTest`, `AlistTest`) are written but have not been run.
- **Chinese strings folder:** they went into the project's existing `values-b+zh+Hans/` folder (in a separate `strings_video.xml`), not into a new `values-zh-rCN/`. A parallel zh-rCN folder would add a second Chinese locale to the generated locale config. If `values-zh-rCN` is required, move the file there; the content is ready.

# NoctDock Azahar Fork — Changelog And License Notes

This document describes **what changed** in the **NoctDock-Azahar** tree relative to upstream Azahar, and how those changes relate to **upstream licensing**. It is for maintainers, testers, and anyone redistributing this fork.

**Companion apps (separate projects):**

- **NoctDock Sender** and **NoctDock Receiver** live in the `NOCTDOCK` repository. They are not part of this APK’s source tree. This fork talks to them over the **local network only**.

**Related docs:**

- **`NOTICE`** (this repo root) — short redistribution notice for releases  
- `NOCTDOCK/NOCTDOCK_AZAHAR_INTEGRATION.md` — integration architecture and hook points  
- `NOCTDOCK/NOCTDOCK_AZAHAR_TESTING.md` — device test checklist  

---

## License And Compliance (Read First)

### Upstream license (unchanged)

This tree is a derivative of **Azahar** / **Citra Emulator Project** software. The project root still includes:

- **`license.txt`** — GNU General Public License **version 2** (GPLv2), plus “or any later version” as stated in file headers.

**We did not replace, narrow, or relicense the emulator core.** Fork changes are contributed under the same GPLv2 terms as the rest of Azahar.

### Standard source file header

All **new and modified** NoctDock-related `.kt`, `.cpp`, and `.h` files in this fork use the same header required by upstream CI:

```text
// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.
```

This matches `.ci/license-header.rb` and `.github/workflows/license-header.yml`.

### What we did **not** change for licensing

- **`license.txt`** at repo root — left as upstream GPLv2 text.  
- **Third-party `externals/`** — not modified for NoctDock; their own `LICENSE` files remain.  
- **In-app third-party license list** (`src/android/app/src/main/res/values/licenses.xml`) — not altered for NoctDock (NoctDock does not add new vendored native libraries).  
- **Kotlin package namespace** — remains `org.citra.citra_emu` for source compatibility.  
- **Emulator copyright and attribution** — Citra/Azahar notices preserved in modified files.

### Fork-specific packaging (not a relicensing)

| Item | Value | License note |
|------|--------|----------------|
| Application ID | `com.glowseed.noctdock.azahar` (debug: `.debug`) | Distribution identity only; does not change GPLv2 coverage of combined work. |
| App label | `NoctDock Azahar` | User-facing name only. |
| Launcher icon | Adaptive icon background `noctdock_azahar_icon_background.xml`; foreground still uses Citra launcher assets | Branding layer; upstream icon assets retain their existing terms. |

### NoctDock protocol code in this fork

UDP packet layout and behaviour are implemented in:

- `org.citra.citra_emu.noctdock.NoctDockPacketTransport` (Kotlin)

The wire format is kept compatible with **NoctDock Receiver** (see `NOCTDOCK/noctdock-core`). This implementation is **part of the GPLv2 Android app** when you build and distribute this fork—you must comply with GPLv2 for the **combined** emulator + NoctDock export binary (source offer, license text, etc.).

### Redistribution checklist (GPLv2)

If you distribute this fork’s APK or binaries:

1. Include **`license.txt`** (or GPLv2 full text) with the distribution.  
2. Provide **corresponding source** for the version you ship (this tree + build instructions).  
3. Keep **copyright and license headers** on modified files.  
4. Do not imply Azahar or Citra **endorses** NoctDock unless that is separately true.  
5. **NoctDock Sender/Receiver** are separate apps; their license is defined in the NOCTDOCK repo, not in this file.

### Privacy / network (unchanged principles)

NoctDock integration adds **no** accounts, analytics, ads, Firebase, or internet streaming services. Export uses **local UDP** to a user-chosen receiver on the LAN. **NoctDock Stream Watch** exposes debug JSON on the LAN only when explicitly enabled.

---

## Summary Of Functional Changes

| Area | What was added |
|------|----------------|
| **3DS Mode** | Stream emulated **top screen** to NoctDock Receiver; bottom screen and touch stay on handheld. |
| **Launch bridge** | Intent extras from NoctDock Sender; pending launch stored in preferences. |
| **OpenGL export** | Encoder Surface path + readback fallback; hooks in `renderer_opengl.cpp`. |
| **Vulkan export** | Experimental encoder-surface + staging readback fallback; hooks in `renderer_vulkan.cpp`. |
| **Encode / send** | MediaCodec H.264/HEVC, UDP fragments, heartbeat, CONFIG, connection test. |
| **HEVC** | Preferred when launch requests `hevc`; **AVC fallback** if HEVC encoder fails to start. |
| **Performance** | Export profiles (Battery → Experimental); gameplay-first FPS/resolution downgrade. |
| **Bottom Screen Auto-Dim** | Optional handheld bottom-screen brightness dim during export (Azahar-only). |
| **60 Hz helper** | Requests high refresh during 3DS export session only (not forced on every launch). |
| **Stream Watch** | Optional local HTTP debug metrics for development (off by default). |
| **Secondary display** | Existing HDMI/Presentation path **not** reused for NoctDock encoder surface. |

---

## New Files (NoctDock package)

All under `src/android/app/src/main/java/org/citra/citra_emu/noctdock/`:

| File | Role |
|------|------|
| `NoctDockBridge.kt` | Launch request persistence, default export config, top-screen source factory. |
| `NoctDockBridgeService.kt` | Android `Service` entry for bridge lifecycle (manifest). |
| `NoctDockBridgeSettings.kt` | 3DS Mode enable, launch behaviour, export performance, auto-dim, Stream Watch prefs. |
| `NoctDockIntentContract.kt` | Intent extra key constants. |
| `NoctDockScreenRoute.kt` | Route enum for launch modes. |
| `NoctDockAvailabilityChecker.kt` | Detects installed `com.glowseed.noctdock.sender`. |
| `NoctDockTopScreenSource.kt` | Interface + `NoctDockExportConfig` + exceptions. |
| `NoctDockTopScreenExporter.kt` | Export manager, MediaCodec encoder, safety controller, native bridge. |
| `NoctDockPacketTransport.kt` | NoctDock UDP packet encode/send (CONFIG, VIDEO, HEARTBEAT, etc.). |
| `NoctDockExportCodecPolicy.kt` | HEVC vs AVC string/mime detection. |
| `NoctDockStreamWatch.kt` | Local debug HTTP server and metric snapshots. |
| `NoctDockBottomScreenAutoDim.kt` | Bottom-screen idle dim during export. |
| `NoctDockRefreshRateHelper.kt` | Display refresh boost during 3DS export. |

**Unit tests** (`src/android/app/src/test/java/org/citra/citra_emu/noctdock/`):

| File | Role |
|------|------|
| `NoctDockBottomScreenAutoDimTest.kt` | Auto-dim policy tests. |
| `NoctDockExportCodecTest.kt` | Codec policy / config copy tests. |

**Tools:**

| File | Role |
|------|------|
| `tools/noctdock_stream_watch.py` | Laptop-side Stream Watch poller (debug only). |

**Resources:**

| File | Role |
|------|------|
| `res/values/strings.xml` | NoctDock UI strings (appended entries). |
| `res/drawable/noctdock_azahar_icon_background.xml` | Launcher adaptive-icon background. |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Points background to NoctDock drawable. |

---

## Modified Upstream Files

### Android UI / lifecycle

| File | Changes |
|------|---------|
| `ui/main/MainActivity.kt` | Remember NoctDock launch intents via `NoctDockBridge`. |
| `activities/EmulationActivity.kt` | Touch hook for auto-dim; bind/unbind refresh + auto-dim helpers. |
| `fragments/EmulationFragment.kt` | Pre-run 3DS Mode prompt; export start/stop around `EmulationState.run()`. |
| `fragments/HomeSettingsFragment.kt` | NoctDock 3DS Mode settings, export profiles, auto-dim, Stream Watch. |
| `NativeLibrary.kt` | JNI declarations for export lifecycle, encoder surface, frame callbacks. |

### Android build / manifest

| File | Changes |
|------|---------|
| `app/build.gradle.kts` | `applicationId` `com.glowseed.noctdock.azahar`; product flavors aligned. |
| `AndroidManifest.xml` | Queries NoctDock sender package; registers `NoctDockBridgeService`. |
| `res/values/strings.xml` | `app_name` → NoctDock Azahar; NoctDock string resources. |

### JNI

| File | Changes |
|------|---------|
| `jni/native.cpp` | NoctDock export state, encoder EGL surface, frame JNI callbacks, start/stop/profile JNI. |

### Native video core (top-screen export hooks)

| File | Changes |
|------|---------|
| `video_core/renderer_opengl/renderer_opengl.h` | Export framebuffer/texture members; `ExportNoctDockTopScreen()`. |
| `video_core/renderer_opengl/renderer_opengl.cpp` | Weak-symbol bridge; encoder-surface draw + readback fallback. |
| `video_core/renderer_vulkan/renderer_vulkan.h` | Vulkan encoder window, present window, staging export members. |
| `video_core/renderer_vulkan/renderer_vulkan.cpp` | `NoctDockVulkanEncoderWindow`, surface export, readback fallback. |
| `video_core/renderer_vulkan/vk_present_window.cpp` | Optional notify hook after present (encoder surface timing). |

**Not modified for NoctDock:** `SecondaryDisplay.kt`, `EmuWindow` secondary layout paths, or removal of existing external-display behaviour.

---

## Feature Changelog (By Release Theme)

### Phase 1 — Bridge and settings (Kotlin-only)

- NoctDock package and launch contract.  
- Home settings: enable 3DS Mode, launch behaviour, sender package check.  
- `applicationId` / app name / launcher branding for NoctDock distribution.  

### Phase 2 — OpenGL real export

- Native `startNoctDockTopScreenExport` / `stop` / frame callback.  
- OpenGL `ExportNoctDockTopScreen()` after normal mailbox render.  
- Readback RGBA → MediaCodec → UDP.  
- Receiver `CONNECTION_TEST` probe before export.  

### Phase 3 — OpenGL encoder surface

- Dedicated MediaCodec input `Surface` + EGL window surface (does **not** use `secondary_window`).  
- Fallback to readback with user toast: compatibility export.  

### Phase 4 — Vulkan experimental export

- Vulkan encoder-surface attempt via dedicated `PresentWindow` / swapchain on MediaCodec `ANativeWindow`.  
- Staging-buffer readback fallback.  
- Stream Watch Vulkan-specific metrics.  

### Phase 5 — Gameplay-first safety

- Export FPS throttle before readback/copy.  
- Auto downgrade resolution/FPS when readback or queues exceed thresholds.  
- Bounded queues; drop stale frames under pressure.  

### Phase 6 — Sender integration hardening (cross-repo)

- Launch codec aligned with NoctDock `StreamNegotiator` + device policy (sender repo).  
- HEVC → AVC encoder fallback in `NoctDockTopScreenExporter` (this repo).  
- HEVC Main profile on MediaCodec when supported.  

### Phase 7 — Handheld UX during export

- **Bottom Screen Auto-Dim** (Off / Gentle / Dark / Maximum Dark; 10 s idle; touch restores).  
- **NoctDockRefreshRateHelper** — 60 Hz request during export session only.  
- Stream Watch fields for dim mode and refresh state.  

### Phase 8 — Export settings and quality guide

- **`NoctDockExportSettingsResolver`** — export performance, resolution, and FPS prefs are combined when building `NoctDockExportConfig`.  
- **Export resolution Auto** follows the selected performance preset; explicit resolution/FPS override size and frame rate.  
- **Picture quality guide** dialog on first open of Export Settings (reopen from **Picture quality guide** in the menu). Explains **Internal Resolution** vs export settings and recommended combinations.  

---

## Behaviour Users Should Know

1. **Normal Azahar** works with 3DS Mode disabled.  
2. **Physical secondary display / HDMI** is unchanged; NoctDock uses a separate encoder surface.  
3. **Vulkan** is experimental; OpenGL is the stable export path.  
4. **Default export** is typically **800×480 @ 30 fps** (Balanced profile in Azahar export settings).  
5. **HEVC** is used only when NoctDock Sender negotiates it and encode succeeds; otherwise **AVC**.  
6. **Stream Watch** must stay off for normal end-user privacy unless debugging on a trusted LAN.  

---

## Build Identifiers

```bash
JAVA_HOME=/usr/lib/jvm/temurin-17-jdk ./gradlew :app:assembleVanillaDebug
JAVA_HOME=/usr/lib/jvm/temurin-17-jdk ./gradlew :app:testVanillaDebugUnitTest
```

Package: `com.glowseed.noctdock.azahar` / `com.glowseed.noctdock.azahar.debug`  
Min SDK: **29** (from `app/build.gradle.kts`)

---

## Maintainer Verification

Before tagging a release:

- [ ] `license.txt` present in distribution.  
- [ ] New `.kt` / `.cpp` / `.h` files use the standard three-line Citra/Azahar header.  
- [ ] Run upstream-style header check on changed branch (`.ci/license-header.rb` against merge base).  
- [ ] `NOCTDOCK_AZAHAR_TESTING.md` checklist executed on target handheld + receiver.  
- [ ] Confirm Stream Watch disabled in default preferences for release builds.  

---

## Upstream Relationship

This fork is **not** a replacement for upstream Azahar. It is a **downstream variant** for the NoctDock product line. Bug reports that reproduce without NoctDock code paths should still be considered for upstream Azahar separately.

When merging upstream Azahar releases, re-apply NoctDock hooks in:

- `renderer_opengl.cpp` / `renderer_vulkan.cpp`  
- `native.cpp`  
- `EmulationFragment.kt` / `HomeSettingsFragment.kt` / `EmulationActivity.kt`  
- `NativeLibrary.kt`  

Keep this changelog updated when adding new NoctDock features.

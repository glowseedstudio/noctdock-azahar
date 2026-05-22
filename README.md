<p align="center">
  <img src="docs/naz.png" width="120" alt="NoctDock Azahar app icon">
</p>

<h1 align="center">NoctDock Azahar</h1>

<p align="center"><strong>Azahar fork for 3DS top-screen play on your TV</strong></p>

<p align="center">
  <a href="https://github.com/glowseedstudio/noctdock">NoctDock</a> · companion to the sender and receiver apps
</p>

NoctDock Azahar is a custom build of [Azahar](https://github.com/azahar-emu/azahar) for people who use **NoctDock** on Android handhelds. It keeps normal 3DS emulation on the device, and can send **only the top screen** to a **NoctDock Receiver** on your LAN while the bottom screen, touch, and controls stay on the handheld.

No accounts. No cloud relay. No analytics. Local network only.

Package: `com.glowseed.noctdock.azahar` (debug: `com.glowseed.noctdock.azahar.debug`)

---

## Why this is a separate app

3DS games are meant to be played on two screens at once. Mirroring the whole Android UI to a TV works for many apps, but it is not the same as a real top-and-bottom layout.

I wanted the TV to show the **game’s top screen** and the handheld to stay the **bottom screen and controller** — without turning NoctDock into a monolithic emulator build. Keeping Azahar as its own fork keeps upstream attribution clear, GPLv2 compliance straightforward, and the emulator easier for others to hack on.

---

## How it fits with NoctDock

You need **NoctDock Sender** on the handheld and **NoctDock Receiver** on the TV (or another Android display). This APK is the emulator that exports the top screen when **NoctDock 3DS Mode** is active.

**Launch** from the sender opens Azahar like any other app while the sender mirrors the full Android display (Console Mode). That path does not start top-screen export by itself.

**Launch in 3DS Mode** from the sender checks that Azahar is installed, a receiver is selected, online, and trusted, then opens Azahar with the receiver address, port, negotiated codec, and sound settings. Azahar handles encode and UDP for the top screen; the sender stops its own Console Mode session for that path.

You can also enable **NoctDock 3DS Mode** inside Azahar after a normal launch, once a screen is set up.

---

## What it does

| Area | Summary |
| --- | --- |
| **Top-screen export** | Encoded stream to the paired receiver over the same NoctDock UDP protocol as Console Mode. |
| **Local bottom screen** | Touch and lower display stay on the handheld during export. |
| **Codec negotiation** | AVC or HEVC with fallback if the encoder fails to start. |
| **Bottom-screen dim** | Optional idle dim on the handheld (Off / Gentle / Dark / Maximum) without affecting the TV picture. |
| **Normal Azahar** | Still runs as a full emulator when NoctDock export is off. |
| **Stream Watch** | Optional LAN-only debug metrics when enabled (no cloud telemetry). |

The export path is moving toward **direct render into the encoder surface** where possible, instead of slow full-frame readback.

---

## What it is not

Not a replacement for upstream Azahar for everyone — it is a **NoctDock-focused fork**. Not a cloud service, not a game store, and not bundled with the sender or receiver APKs (those live in the [noctdock](https://github.com/glowseedstudio/noctdock) repo).

---

## Privacy

Same idea as main NoctDock: **local network only.** No accounts, analytics, ads, or remote streaming. Stream Watch, if you turn it on, is for your LAN and debugging — not uploaded anywhere.

---

## License

This tree is a **derivative of Azahar / Citra**, licensed under **GNU GPLv2** (or later). See `license.txt` in the source tree when published. **NoctDock Sender** and **NoctDock Receiver** are separate projects (Apache 2.0 in the noctdock repo). Distributing builds of this fork must follow GPLv2 (source, notices, `license.txt`).

Copyright Citra Emulator Project / Azahar Emulator Project — see [NOTICE](NOTICE).

---

## Current status

In active development. Integration covers the Android launch bridge, in-app 3DS Mode settings, Kotlin transport, and native renderer hooks. Full fork source and build notes will be published in this repository as the tree is ready for public checkout.

For integration detail and testing checklists, see the main NoctDock repo (`NOCTDOCK_AZAHAR_INTEGRATION.md`, `NOCTDOCK_AZAHAR_TESTING.md`) when those docs are linked from a combined release.

---

## Contributing

Useful help right now: real-device testing (handheld + receiver), export performance on your GPU, HEVC fallback cases, and clear issue reports with device model, Android version, receiver type, Wi‑Fi vs Ethernet, and selected profile.

When opening issues, do not paste ROM paths or personal data. GPLv2 applies to contributions in the emulator tree.

---

## Links

| | |
| -- | -- |
| **NoctDock (sender / receiver)** | https://github.com/glowseedstudio/noctdock |
| **Upstream Azahar** | https://github.com/azahar-emu/azahar |

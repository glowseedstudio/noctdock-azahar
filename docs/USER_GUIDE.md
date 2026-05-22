# NoctDock Azahar User Guide

NoctDock Azahar is the **emulator APK** for 3DS top-screen play on your TV. You still need **NoctDock Sender** and **NoctDock Receiver** from the [noctdock](https://github.com/glowseedstudio/noctdock) repo. Install APKs from [Releases](https://github.com/glowseedstudio/noctdock-azahar/releases/latest).

---

## Before you use 3DS Mode

Do these **outside** Azahar first (in NoctDock Sender):

1. Install and open **NoctDock Receiver** on the TV or display.
2. On the handheld, open **NoctDock Sender** → **Screens** → pair with the **4-digit code** and **remember / trust** the screen.

Then in **NoctDock Azahar** (this app):

1. Open Azahar → **Settings** (home screen) → **NoctDock 3DS Mode**.
2. Confirm **NoctDock is installed** (the entry is greyed out with a hint if the sender app is missing).
3. Set **launch behaviour** and **export settings** (see below). You do not have to max everything out before the first test — **Balanced** defaults are fine.
4. In normal Azahar **Graphics** settings, set **Internal Resolution** (see [Picture quality](#picture-quality-internal-resolution-vs-export)).
5. Prefer **OpenGL** for the renderer when you are learning the feature; **Vulkan** is supported but marked experimental.

When you start a game, Azahar may ask **Send top screen to NoctDock?** — that is separate from the settings above (launch behaviour controls how often you see it).

---

## Where to find NoctDock settings

**Azahar home → Settings → NoctDock 3DS Mode**

From that dialog you can:

| Control | What it does |
| --- | --- |
| **Ask each time** | 3DS Mode enabled; prompts when a game starts whether to send the top screen. |
| **Always send when launched from NoctDock** | Skips the prompt when Sender used **Launch in 3DS Mode** (still uses your export presets). |
| **Play normally** | Turns 3DS export off; Azahar behaves like stock for NoctDock. |
| **Export Settings** | Opens export performance, resolution, FPS, picture guide, bottom-screen dim, Stream Watch. |

Tap **Export Settings** (neutral button on the first dialog) for the full export menu.

---

## Export settings (stream to TV)

These control **what size and frame rate** leave the handheld encoder — not how sharp the 3DS renders internally.

### Export performance (preset)

| Preset | Sent to TV | Best for |
| --- | --- | --- |
| **Battery / Safe** | 400×240 @ 30 fps | Weak handhelds or heavy games |
| **Balanced** *(default)* | 800×480 @ 30 fps | **Start here** on most devices |
| **Sharp** | 800×480 @ 60 fps | Strong handheld + good Wi‑Fi |
| **TV** | 1280×720 @ 30 fps | Large TV, solid network |
| **Experimental** | 1280×720 @ 60 fps | Testing only; may stutter |

The app can **automatically step down** to a safer preset during play if the device struggles (you may see a short “safer setting” message).

### Export resolution

| Option | Meaning |
| --- | --- |
| **Auto** *(recommended)* | Uses the size from the performance preset above |
| **400×240** | Native top-screen size |
| **800×480** | Sharp dock-style size |
| **1280×720** | Full HD export (needs a capable receiver and network) |

### Export frame rate

| Option | Meaning |
| --- | --- |
| **30 fps safe** *(default)* | Stable for most sessions |
| **60 fps test** | Higher smoothness on TV; more load on handheld and Wi‑Fi |

### Bottom screen auto-dim

Only dims the **handheld bottom screen** while exporting; the TV picture is unchanged.

| Mode | Effect |
| --- | --- |
| Off | No extra dim |
| Gentle | Soft dim in dark rooms |
| Dark | Stays dim until you touch |
| Maximum Dark | Deepest idle dim |

Touch the bottom screen to brighten again.

### Stream Watch (debug only)

Optional **LAN-only** metrics for developers. Off by default. Do not leave on for normal play. See [noctdock STREAM_WATCH.md](https://github.com/glowseedstudio/noctdock/blob/main/STREAM_WATCH.md).

---

## Picture quality: Internal Resolution vs export

NoctDock uses **two separate** quality knobs. The in-app **Picture quality guide** (first time you open Export Settings, or **Picture quality guide** in that menu) explains this in full.

**Internal Resolution** — Azahar → **Settings → Graphics → Internal Resolution**  
How sharply the 3DS **renders** the top screen on the handheld **before** streaming.

**Export settings** — Azahar → **NoctDock 3DS Mode → Export Settings**  
What **resolution and FPS** are **encoded and sent** to the TV.

| Situation | Suggested starting point |
| --- | --- |
| Most handhelds | Internal **2× Native**, Export **Balanced**, Resolution **Auto**, FPS **30 fps safe** |
| Weak device or heavy game | Internal **1× Native**, Export **Battery / Safe** |
| Large TV, strong Wi‑Fi | Internal **2×–3× Native**, Export **TV** or **Sharp** — only after play stays smooth |

Raising export size without enough internal resolution only **upscales a soft image**. Very high internal resolution costs GPU time and may trigger automatic safety downgrades.

---

## Graphics API (renderer)

In Azahar **Graphics** settings, choose the renderer API:

| Renderer | NoctDock 3DS Mode |
| --- | --- |
| **OpenGL** | Stable path; recommended default |
| **Vulkan** | Experimental; may use encoder surface or fall back to compatibility readback |

If Vulkan export cannot start, Azahar shows a message and continues **normal local play** on the handheld.

---

## How you can start a session

### From NoctDock Sender (recommended)

1. Trusted receiver online.
2. **Library** or Game Hub → **NoctDock Azahar** → **Launch in 3DS Mode**.  
   Sender passes receiver address, port, codec (AVC/HEVC), and sound mode. Sender does **not** mirror the whole screen for this path.
3. Play; top screen on TV, bottom screen and touch on handheld.

**Launch** (without “3DS Mode”) opens Azahar while Sender **Console Mode** mirrors the **full Android UI** — not top-screen-only. You can still enable 3DS export later inside Azahar if settings allow.

### From inside Azahar

1. Open a game normally.
2. If launch behaviour is **Ask each time**, confirm **Send to Screen** when prompted.
3. Azahar checks the receiver is reachable, then starts export.

---

## Codec (AVC / HEVC)

You do **not** pick the codec inside Azahar for a typical **Launch in 3DS Mode** flow — **NoctDock Sender** negotiates AVC or HEVC with the receiver. Azahar uses that choice and falls back to **AVC** if HEVC encode fails (short compatibility toast; play continues).

---

## Troubleshooting

| Symptom | Things to check |
| --- | --- |
| NoctDock 3DS Mode greyed out | Install **NoctDock Sender** on the same device |
| “Screen is not available” | Receiver app open on TV, same Wi‑Fi, paired and trusted in Sender |
| Soft or blurry TV image | Raise **Internal Resolution** before raising export size |
| Stutter or drops | Lower export preset (Balanced → Battery / Safe), use 30 fps, improve Wi‑Fi or Ethernet on receiver |
| “Compatibility export mode” | Fallback readback path; OpenGL/Vulkan could not use direct encoder surface — still playable |
| “3DS Mode stopped” | Export ended; emulation continues locally on handheld |

For sender/receiver pairing and Console Mode, see the [NoctDock user guide](https://github.com/glowseedstudio/noctdock/blob/main/docs/USER_GUIDE.md).

**Maintainer note:** NoctDock Azahar is maintained in spare time alongside a full-time job. I will do my best to respond to issues quickly; thanks for your patience.

---

## More for developers

| Document | Location |
| --- | --- |
| Integration architecture | [NOCTDOCK_AZAHAR_INTEGRATION.md](https://github.com/glowseedstudio/noctdock/blob/main/NOCTDOCK_AZAHAR_INTEGRATION.md) |
| Test checklist | [NOCTDOCK_AZAHAR_TESTING.md](https://github.com/glowseedstudio/noctdock/blob/main/NOCTDOCK_AZAHAR_TESTING.md) |
| Fork changelog | [NOCTDOCK_FORK_CHANGELOG.md](../NOCTDOCK_FORK_CHANGELOG.md) |

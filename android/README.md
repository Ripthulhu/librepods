# LibrePods for Android

**[Download Android APK](https://github.com/Ripthulhu/librepods/releases/latest/download/LibrePods.apk)**

[Current release: notes, checksums and encoder source](https://github.com/Ripthulhu/librepods/releases/latest)

This fork provides Apple-style AirPods controls, native AAC-ELD/PCM32 on supported
rooted Android builds, and Pico dongle integration. The signed APK updates existing
fork installations in place. Apple is the default design; Material remains
available in the app settings.

For upgrade history and known limitations, see
[Pixel fork on the Android rewrite](PIXEL-REWRITE.md).

LibrePods can control the Pixel Dolby decoder through its local spatial-audio interface.
On a supported Android 17 build, root access allows LibrePods to calibrate the
AirPods Pro 3 and use Android's native spatial audio and head tracking. A
separate app signed with the same key can bind to the calibrated motion service
while Spatial Audio and Head Tracking are enabled.

The standard APK includes the calibrated motion service and the ARM64 Pixel
Dolby control client. Install the separate Pixel Dolby decoder on supported
devices to use that route.

## Root Requirement

- The APK requires Android 13 or later. Rootless control also needs the updated
  Android Bluetooth stack. The app's compatibility check accepts:
  - Devices running Android 17+
  - Oppo/OnePlus/Realme devices running Android 16
  - Google Pixel devices running Android 16 with a build ID beginning `CP1A`
    **and the Google Play system update**
- This fork has been tested on a Pixel 10 Pro XL. Other accepted configurations
  depend on their Bluetooth implementation; they have not all been tested here.
- On older Bluetooth stacks, LibrePods needs root with an Xposed framework to
  work around the Fluoride/AirPods connection issue. Shizuku does not replace
  that workaround. [This issue was tracked here](https://issuetracker.google.com/issues/371713238).
  **Please do not comment on the issue thread.**
- Older stacks use the Xposed Vendor ID hook. Android 17 can use the optional
  [native Device ID module](tools/bluetooth-identity-module/README.md) instead.
  Protected controls require successful ATT discovery and current-session
  readback; an identity setting alone does not enable them. See
  [Android ATT access](PROTOCOL.md#android-att-access).

> [!IMPORTANT]
> This workaround with Xposed is not guaranteed to work on all devices.

## Installation

### Install the APK

Use **Download Android APK** at the top of this page. Keep an existing fork
installation in place to preserve its settings and root or Shizuku authorization.
An APK signed with a different certificate cannot update an existing installation.

For a Pico dongle, follow the [dongle connection guide](DONGLE.md). For direct
phone Bluetooth, continue below.

### Bluetooth access with root or Shizuku

1. Install the signed APK and open LibrePods. Grant Nearby devices and allow
   notifications when requested. Allow display over other apps if you
   want nearby pairing cards and the Dynamic Island.
2. Open Settings â†’ **Bluetooth access** and grant LibrePods access through your
   root manager. Rooted phones do not need Shizuku. Without root, start Shizuku
   using wireless debugging and authorize LibrePods there. Shizuku may need to
   be started again after a phone restart.
   KernelSU users need its standard **SU compatibility** interface enabled and
   LibrePods allowed under Superuser. A root ADB shell alone does not grant the app access.
3. For a new pair, open the case near the phone and follow the pairing card's
   instructions until the case light flashes white, then tap **Connect**.
   Authorized root or Shizuku can confirm the selected pair's consent request; Android's
   normal confirmation remains the fallback.
4. Access is reused for subsequent connections. Opening Settings or starting the
   background service does not request a first root grant.

Ordinary Bluetooth music playback stays on Android. The privileged helper is used for the
nearby island's explicit Connect action, pairing consent, background volume
access/recovery and Recorder playback recovery. Opening the case
alone does not take audio from another host. The root helper belongs to LibrePods
and stops after its last client leaves. It exposes only the existing fixed
Bluetooth and media operations, with selected-pair, permission and cancellation
checks; it does not expose a shell-command interface.

### Link NTPods on a PC

The experimental **PC audio switching** section in app settings links an already
paired PC over Bluetooth. Select the PC and the same AirPods used by NTPods, then
paste NTPods' private linking code. The code is encrypted with Android Keystore
and excluded from backups. Both apps must explicitly trust the other host.

Keep **Act as an Apple device** off on Android and authorize root or Shizuku
through **Bluetooth access**. The PC keeps its existing Bluetooth identity. Pause source music,
then choose **Move audio to this phone** or **Move audio to PC**. A transfer first
confirms release of the source's media, headset and control connections, then
confirms the destination's native audio route. Manual transfer does not start
music, change pairings or connection policies, or run when the case opens.
Calls, microphone work and unavailable platform access block manual transfer.
A failed or cancelled transfer never blindly reconnects the source.

The PC link is optional. With the PC offline, choose **Phone** or tap your
AirPods' device card to connect locally. If phone audio already works, LibrePods
reconnects its controls without restarting the audio connection.

If Android independently reconnects the selected AirPods' audio, LibrePods can
reattach their controls even while a saved PC handover hold remains. This does
not clear the hold or request an audio connection. Missing local audio or an
active transfer still blocks passive control attachment.

Companion v2 shares a fixed 15-key whitelist: host auto-pause, theme,
conversation policy, popup and disconnected-battery preferences, call options,
and supported saved, unconfirmed accessory choices. Synced choices do not become
device readback or automatically write to the AirPods. Host-specific settings
and permissions remain local.

Incoming mobile-call takeover requires an explicit opt-in in each app and
phone-state permission on Android. Only its matching incoming-call reservation
gets a narrow call-acquire exception; other busy guards remain. Return defaults
on but stays inactive until takeover is enabled. After verified return, the PC
resumes only the same player NTPods paused, provided no newer choice revoked it.
Update both apps together; v2 preference sync and calls are not yet validated on
hardware.

See [the companion protocol](COMPANION-PROTOCOL.md) for the implementation contract.
End-to-end switching still requires physical validation on each host pair.

Pilot limitation (`shortcut:`): app actions retire the pending transfer, but a
manual route change in Android's Bluetooth settings has no separate user-intent
generation. Use the app's transfer buttons and avoid changing the OS route during
a transfer. Add profile-event intent arbitration before supporting that overlap.
Bluetooth shutdown, unpairing, unrelated calls and microphone work retain their
existing cleanup and busy guards.

### As a system app (root module)

The upstream root module provides privileged Bluetooth permissions to

- show battery status in the system settings and widgets
- show AirPods icon in the system settings (xposed is also currently required for this)
- switch audio to phone speakers when you are not wearing your AirPods

These system-app features are separate from the app-owned root helper and the
optional Android 17 Device ID module. The Device ID module does not install the
APK as a system app or grant global permissions.

### A few notes on this rewrite

- Listening modes, Adaptive strength, Allow Off, ear detection, battery status,
  Conversation Awareness, nearby pairing, the Dynamic Island, Quick Settings and
  widgets use the rewrite's per-device state. Features vary by model and firmware.
- Tap the AirPods tile in Android's Quick Settings for the Control Center-style
  listening rail and your chosen live toggles. Choose them in LibrePods â†’
  **Settings â†’ Quick Settings**. Unsupported controls are hidden automatically;
  supported controls remain visible during temporary loading or busy states.
  Native Bluetooth and the USB dongle use their existing setting paths. The panel
  adapts to larger text and scrolls on smaller screens.
- Recorder retains experimental high-quality microphone recording. The failed
  app microphone injection and cellular-call relay experiments have been removed.
  Microphone Mode contains only the native Automatic, Always Right and Always
  Left choices. Older-fork hardware tests do not validate this rewrite. Sensors,
  head gestures and call audio have not been retested on hardware here.
- High-quality two-way call audio is unavailable; ordinary Android/HFP calls
  continue to use the native Bluetooth route. The proposed root integration and
  its remaining checks are documented in [High-quality microphone routing for calls](HQ-COMMUNICATION.md).
- Native hearing settings, Custom Transparency and Loud Sound Reduction use
  discovered Classic ATT characteristics and confirmed current-session data.
  Identity spoofing alone does not authorize these controls. Hearing Aid and
  Media Assist retain their fitting and mode guards; the dongle currently has
  no typed Loud Sound Reduction control.

## Development builds

The [Android CI workflow](https://github.com/Ripthulhu/librepods/actions/workflows/ci-android.yml)
checks Android changes on pushes and pull requests. Successful runs provide an
`apk-debug` artifact containing `app-debug.apk`. Builds from this fork's `main`
and release tags in its history use the existing fork signing certificate, so
they update the installed fork in place. Manual builds check out the workflow's
selected ref, with no separate source-ref override. They use that certificate
only when the workflow runs on `main` and checks out its current tip. Pull requests, other
branches and untrusted manual refs use a development certificate and cannot
update the installed signed app. CI verifies the certificate before uploading
trusted APKs.

Normal Debug and Release APKs build the full AAC-ELD encoder and PCM32/Q31 path
from the pinned source, with optimized native code in both variants. The signed
`main` CI APK is the Pixel installation input; no older native library is
overlaid afterward. Each APK includes the FDK, wrapper and modification notices,
and CI supplies the corresponding encoder source bundle alongside it. CI checks
the packaged native build contract and notices before upload. Runtime opt-in,
phone-image validation and codec/session guards still apply; including the
encoder does not enable it on an unsupported device.

The `apk-debug` and signed release artifacts contain the APK,
`aac-eld-source.tar.gz`, `aac-eld-source.json` and `SHA256SUMS`. The source archive
contains the complete linked, Q31-modified FDK tree and owned wrapper; its JSON
records the pinned upstream source, app commit and source hashes.

The full build retains the existing GPL and FDK licenses. FDK/GPL compatibility
remains a known distribution issue; notices and source availability do not
resolve it. See the [encoder license boundary](tools/native-owned-fdk-tests/README.md#build-and-license-boundary).

From `android/`, run `./gradlew :app:verifyPixel` with JDK 21 and the SDK/NDK
versions declared in the project. This runs core and app unit tests, Android
lint, detekt, and debug and minified release APK builds. There is one Android
distribution, with no Play flavors or app-bundle release flow.

### Static analysis

`verifyPixel` runs Android lint and [detekt](https://detekt.dev) on `:app` and
`:core`. Both fail the build on any finding that is not already recorded in the
module's baseline, so existing issues do not block work, but new ones do:

- `./gradlew :core:detektMain :app:detektMain` runs detekt with type resolution on
  production sources (the debug variant; release has no sources of its own). Rules
  are in `config/detekt/detekt.yml`, which builds on detekt's defaults and turns off
  pure style rules. Reports go to `<module>/build/reports/detekt/`.
- `./gradlew :core:lintDebug :app:lintDebug` runs Android lint with warnings treated
  as errors. Checks that depend on newly published library versions are disabled so
  an unchanged commit cannot start failing. Reports go to
  `<module>/build/reports/lint-results-debug.*`.

The baselines are `<module>/detekt-baseline-debug.xml` and
`<module>/lint-baseline.xml`. **Baselines only shrink.** Fix new findings instead
of recording them; if a finding is a false positive, suppress it at the site with
a short reason. When you fix existing findings, regenerate the baseline so the
fixed entries cannot come back, and check in review that the diff only removes
entries:

```
./gradlew :core:detektBaselineMain :app:detektBaselineMain
./gradlew :core:updateLintBaseline :app:updateLintBaseline
```

Regenerating to absorb new findings is only acceptable for a deliberate tool or
rule upgrade (new detekt, lint or AGP version), or right after merging code from
another repository that does not run these checks. Either way that change should be
its own commit so the added entries are visible. detekt baseline entries are keyed by rule,
file, enclosing declaration and a short signature (for caught exceptions, the
variable name), not by line, so a new identical finding in a function or class
that already has one can still be masked.

Run `./gradlew :app:collectReleaseArtifacts` to collect the APKs in `release/`
at the repository root. Release signing uses the existing `RELEASE_STORE_FILE`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` entries
in an untracked `android/local.properties`. Without those entries, the release
APK is unsigned. Keep signing material out of the repository.

Successful `main` builds automatically publish a signed release with a unique
`v<app-version>-<commit>` tag. Download its `LibrePods.apk` from
[the latest release](https://github.com/Ripthulhu/librepods/releases/latest);
the matching encoder source, provenance and checksums accompany it. Releases
are marked as previews in their title and notes. Older build completions cannot
replace a newer build as Latest, and existing tags and release assets are not overwritten.
Before publishing a version, add its user-facing changes and verification results
to `android/release-notes/<app-version>.md`. CI requires that version's notes and
includes them in the GitHub release.
To publish an explicit tag, run the workflow against `main` with its
`publish_release` and `release_tag` inputs, or push a `v*` or `pixel-*` tag on
`main` history. Trusted CI builds require the fork's existing
`RELEASE_KEYSTORE_FILE` (base64 keystore), `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` secrets. The same key signs debug
and release APKs; no new key is generated. Publishing from a manual workflow
requires the current `main` commit. Signing material is removed after the job.

Detailed call, packet, sensor and Game Mode logs are off by default, including in
debug APKs. For a targeted investigation, enable only the required log tag with
`adb shell setprop log.tag.LibrePodsCallAudio DEBUG`, then restore it to `INFO`
afterward. Warnings, errors and diagnostic counters remain available. Verbose
native hook tracing requires an explicit CMake build with
`-DLIBREPODS_NATIVE_TRACE=ON`; its default is `OFF` for all build types.

## Screenshots

These are upstream screenshots and a demo. This fork uses the Android rewrite's
UI; the hearing/transparency screens below do not indicate working ATT access.

|                                                                                 |                                            |                                                                      |
| ------------------------------------------------------------------------------- | ------------------------------------------ | -------------------------------------------------------------------- |
| ![Settings 1](./imgs/settings-1.png)                                            | ![Settings 2](./imgs/settings-2.png)       | ![Head Tracking and Gestures](./imgs/head-tracking-and-gestures.png) |
| ![Long Press Configuration](./imgs/long-press.png)                              | ![Customizations 1](./imgs/customizations-1.png)                                | ![accessibility](./imgs/accessibility.png) |
| ![transparency](./imgs/transparency.png)                                        | ![hearing-aid](./imgs/hearing-aid.png)     | ![hearing-test](./imgs/hearing-test.png)   |
| ![hearing-aid-adjustments](./imgs/hearing-aid-adjustments.png)                  | ![Battery Notification and QS Tile for NC Mode](./imgs/notification-and-qs.png) | ![Widget](./imgs/widget.png)               |


here's a very unprofessional demo video

https://github.com/user-attachments/assets/43911243-0576-4093-8c55-89c1db5ea533

### Troubleshooting steps for common errors

- For rootless use, check your Android/Google Play system updates and restart
  Shizuku if it has stopped. Confirm that LibrePods is authorized in Shizuku.
- The following steps apply only to the optional root/Xposed setup:
- Ensure the correct scope is set in LSPosed/Vector.
- Ensure there is no root-hiding module preventing the hook from loading on the Bluetooth app.
- Restart your phone after confirming the scope.

### A few notes

- Due to recent AirPods' firmware upgrades, you must enable `Off listening mode` to switch to `Off`. This is because in this mode, loud sounds are not reduced.

- When renaming your AirPods through the app, you'll need to re-pair them with your phone for the name change to take effect. This is a limitation of how Bluetooth device naming works on Android.
### Companion settings and mobile calls

The v2 companion link syncs a typed whitelist over the existing authenticated Bluetooth connection:
host auto-pause, appearance theme, conversation volume policy (absolute or relative cap, two stages,
optional media pause), automatic popup feedback, disconnected component-battery retention, and desired
call takeover/return policies. Theme has one global revision; the other fields are scoped to the linked
AirPods pair. Each field reconciles by a durable unsigned Lamport revision with PC winning equal counters.
Reconnect reconciles values; it never replays a transfer or writes accessory settings.

Supported saved microphone-side, ear-detection, noise-mode-cycle, swipe-duration, case-sound/volume and
silent Allow Off choices are separate unconfirmed intent. They are used only when fresh accessory
readback is absent and pass model/capability checks. Android's host-delegated hold actions are not
hardware hold configuration, so that field remains unsupported. The shared swipe values are semantic
Default/Longer/Longest; Android preserves its existing one-based raw setter, while Windows preserves
its existing zero-based setter. These adapters do not claim a new hardware calibration.

Incoming mobile-call takeover is off until explicitly enabled on this phone. Only that local action
requests READ_PHONE_STATE; syncing a desired policy cannot grant permission or local consent.
Return after call defaults on but is inert until takeover is enabled. All active subscriptions must
have known state. Startup inside a call, outgoing calls, unknown coverage, and internet calling apps
do not start automatic takeover. The new feature does not answer or end calls and does not use the
experimental duplex-audio path. Existing head-gesture answering remains a separate permission feature.

The phone reserves one fresh ringing episode using a PC-ready token received before ringing.
Selected live HFP connection plus active-device proof is readiness, not proof of answered/SCO audio.
The ordinary selected-SCO/microphone/call release guards stay in force for return. Natural end drains
pending call work and waits up to eight seconds for all calls idle, normal audio mode, live selected
SCO idle and no app operation owner. A newer action, call, policy, route change, lost permission/link
or restart retires return/resume eligibility. Route changes are detected through the available profile
and audio events; Android does not expose an omniscient record of every OS Settings user intent.
There is no persisted call/resume lease. PC resumes only the exact player it paused, after verified
return and unchanged player/track/user intent; a player that was already paused is never started.

Host ear auto-pause is independent of accessory ear detection. Exact-player pause/resume needs the
authorized Shizuku bridge, a single identifiable local player and its actual selected AirPods output.
Unknown or ambiguous media identity refuses the operation; there is no generic Play fallback or new
notification-listener permission. A newer playback/metadata/session or observed route change revokes
the pause claim. Existing legacy global media-key behavior is replaced by this conservative policy.

Android BLE scanning, microphone/codec/OS permissions, delegated gestures/calibration, heart-rate
alerts, detailed Island layout, widgets, material/font/accessibility/navigation/debug choices and
unused legacy takeover flags stay local. Language remains local without a common locale adapter.
No credentials, device identity, permission grants, battery samples or diagnostic history sync.
Percentages that cannot round-trip through Android's existing saved Float are unsupported rather
than silently rounded. The native bridge's hidden HFP service signatures and actual cellular-call
routing still require a separate hardware compatibility check; source tests do not establish hearing.

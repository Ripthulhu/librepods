# Pixel fork on the Android rewrite

This branch starts at upstream `android/rewrite`, commit
`a4d8377df0bb468f371289a1b53eeba7c23a9f06`. It retains the rewrite's screens,
design choices, settings and per-device architecture. The older
`pixel-shizuku-port` branch is a reference for previous hardware checks, not
the frontend base.

## Build and upgrade

Use JDK 21 and the Android SDK/NDK versions declared by the project. The existing
Gradle toolchain configuration selects/downloads JDK 25 for its daemon. Run
`./gradlew verifyPixel` from `android/`. This checks core and app unit tests,
Android lint, the debug APK and the minified FOSS release APK. Signing is
deliberately external; no signing key or password belongs in the repository.

Install a release signed with the existing fork certificate using an in-place
update. Do not uninstall first. The rewrite migrates the old app's selected
pair, local behavior choices and correctly owner-bound BLE keys once. Existing
rewrite database rows are preserved. Unconfirmed device control values are not
migrated as device reports. The Android Bluetooth bond stays with Android.

The current fork version is `2.0.0-alpha.1-pixel28`, code `200000113`.

## Pairing and island latency

- Company-filtered BLE scanning delivers individual reports with no batching.
  While the screen is on, the service temporarily uses low-latency scanning;
  screen-off scanning uses the configured mode. The preference is not changed.
- Public lid reports display a model invitation without waiting for Classic
  discovery, an audio connection or AACP initialization. The first card explains
  the model's double-tap or setup-button gesture until the light flashes white,
  then says to tap Connect. Discovery starts only after Connect. A sole matching
  Classic candidate can proceed after two stable
  seconds, without waiting for the full inquiry and another fixed delay. Name
  updates are accepted only for Classic addresses found in that scan; multiple
  matching addresses prevent automatic selection.
  A selection timer that fires before its eligibility deadline re-arms for the
  remaining interval; it cannot strand a valid candidate until inquiry finishes.
- Incoming native pairing can begin before discovery selects the device.
  The same attempt receiver tracks that progress, admits fresh Classic results
  already bonding, and adopts only the sole stable selected address instead of
  starting another bond. A protected consent request received during discovery
  can be revisited once that address is selected, for at most fifteen seconds from
  receipt, provided the exact bond remains active. Before selection it never
  confirms; the separate consent worker can defer the native prompt for at most
  seven seconds while discovery continues. Removing the bond, changing the variant,
  retrying or closing retires
  the hint. PIN/passkey prompts retain Android's normal confirmation flow.
- With already-authorized Shizuku, Connect warms the shell bridge without
  delaying discovery. It can confirm only the active selected AirPods request's
  consent step while Android reports that exact device is bonding. Closing,
  retrying or revoking permission retires the request. PIN/passkey variants and
  unavailable Shizuku retain Android's normal confirmation flow. Putting the
  case into pairing mode remains a physical step.
- For an ordered consent request from that selected bonding device, the
  controller briefly holds delivery while Shizuku confirms it. Success or an
  observed completed bond retires the request before Android opens its dialog;
  failure, cancellation or a 1.5-second deadline releases the normal prompt.
  This avoids the system dialog briefly hiding the card when the controller
  receives the request first. Cross-process receiver order remains platform
  dependent; unordered requests keep the existing confirmation behavior.
- The pairing card presents separate nearby, case setup, connecting, retry and
  completion states. Its title follows the current step, busy states use a
  compact progress indicator, and completion offers Continue without delaying
  device setup. The white card slides as a single sheet over a subdued backdrop;
  artwork and text retain their layout instead of scaling or fading separately.
  The existing Pro spinning video excludes its two blank opening frames and uses
  a matching first-frame poster until the current decoded texture is available.
  Unsupported models, reduced motion and playback errors retain model-specific
  static artwork. The video stays visible through the short exit, then releases.
- The success card stays open for Continue or a real lid close, and updates its
  AirPods/case battery row from the exact newly bonded device. Readings from
  earlier invitations or pairing attempts, unknown statuses and unavailable or
  stale components do not become live percentages. Different bud levels remain
  separate. Recognizing the new owned BLE identity does not synthesize a close
  for the active card.
- Successful pairing suppresses that pair's automatic island announcements for
  the current case opening, including queued connection updates. Continuing or
  dismissing the success card keeps that suppression. A newer verified lid-close
  report re-enables the next opening. Listening-mode notices and other pairs
  remain available; replayed batteries and model-only invitations cannot reset
  the suppression.
- Owned battery/lid reports require a current bond and valid per-device keys.
  Public advertisements cannot establish a Classic identity or overwrite an
  owned device's batteries.
- Repeated case cycles retain known battery percentages when disconnected caching
  is enabled. Disconnected readings keep their original age and lose their live
  charging state; unavailable slots cannot fabricate a fresh zero. Battery updates
  reach the island independently of coalesced capability changes.
- Recent LE advertisements stay in a small memory-only buffer for 15 seconds.
  When the bonded device's saved keys are available, or its current control
  connection supplies a complete key pair, matching frames are verified and
  decoded to fill missing battery readings without waiting for another
  advertisement or a control battery report. Their original
  observation times are preserved, and current live component readings win.
  Pairing completion accepts verified readings from its active invitation and
  pairing attempt; earlier attempts and foreign devices remain excluded.
  Replaying a battery reading does not replay lid, reconnect or pairing actions.
- Bluetooth connection work runs off the UI thread. Construction and connection
  attempts are serialized per device. The cached identity loads before scanning.
- Saved, still-bonded Apple identities load even when Android's service UUID
  cache is empty, so nearby ownership does not wait for Classic discovery.
  Android 17 uses passive scanning to receive advertisements without waiting
  for a scan response. Older versions retain their existing scan type.
- Incomplete key responses retain the previous complete IRK/encryption pair;
  a valid replacement changes both together. Cache, metadata and settings
  updates are transactional so concurrent saves cannot overwrite each other.
- The island attaches immediately as a visible camera-sized capsule. Its shell
  expands around a payload measured at its final size, so text and artwork do
  not reflow while opening or closing. Remove Animations snaps to the requested size.
  It wraps the actual camera path and limits expanded width to 420 dp with
  screen margins. Passive battery updates retain the owner and dismissal time;
  a canceled exit cannot remove a reopened or replacement overlay.
- Content scales together to fit inside the shell below the camera, with camera
  clearance kept outside that movement. Compact artwork and battery occupy
  separate spaces beside the camera. Closing returns to the capsule before removal.
  The native window retains enough width for the outgoing content until the
  matching transition finishes. Cached battery ages use coarse units and share
  one last-seen label when all components match; their stored ages stay separate.
- Confirmed listening-mode changes display their localized mode name. Cached
  values, pending requests, first reports and duplicate reports stay silent;
  each connection establishes a fresh notification baseline.
- Adaptive strength keeps the requested slider position separate from reported
  controls because Pro 3 can accept the write without echoing it. A subsequent
  valid strength report remains authoritative, including rounded values. Merely
  opening the Material slider does not send its initial/default value.
- Pro 3's Allow Off choice is saved per pair after a successful current-session
  write. It stays separate from device reports, survives reconnects and app
  restarts, and is replaced by a valid fresh report. Reopening does not resend it.
- Refresh device settings reopens only the selected AirPods' control session.
  It retains the Bluetooth bond and media connection, requests fresh settings,
  and refuses calls, microphone sessions, audio recovery and concurrent refreshes.
- Debug timing logs measure Android's radio timestamp to the overlay request
  and the first drawing pass.
  They do not measure when the physical case opened or prove Windows parity.
  Screen-on scanning trades additional radio activity for prompt detection.

## NTPods protocol cross-check

Reviewed against NTPods' control, audio and macOS case/sensor references on
2026-10-16. Findings transferred into the rewrite include:

| Area | Correction |
| --- | --- |
| Information | Preserve all positional strings, including empty fields; select the public firmware safely rather than interpreting an internal build's first digit. |
| Battery | Validate counted records, unknown/unavailable values and duplicates; retain independent component ages; charging expires with the live observation. |
| Nearby BLE | Validate complete Apple records, RPA ownership, model mapping, battery slots and known versus unknown lid state. Reject stale/reordered scan observations. |
| Keys | Unsigned bounded TLV lengths, duplicate/zero-key rejection, exact identity binding and no general key packet history. |
| Controls/EQ | Validate lengths and supported values; distinguish requested values from reports; EQ uses opcode `0x63` and the verified Pro 3 firmware gate. |
| Rename | Count UTF-8 bytes and put the reserved zero before the name; reject invalid/overlong names; remove optimistic reported-name changes. |
| Connected hosts | Reject incomplete/duplicate lists without replacing the current list with a partial result; host flags do not establish device type. |
| Conversation awareness | Process status independently of unrelated battery changes; bind ducking/restoration to the owning transport and route; suppress host ducking during HQ microphone capture. |
| Sensors | Use validated descriptors and signed channels, bounded frame reassembly and HR initialization; suspend the captured sensor session during microphone use and preserve its intent. |
| Privacy/lifetime | Bound packet history; exclude audio, keys and identity-bearing packets; redact raw logs; retire callbacks with their owning connection. |

## Experimental audio boundaries

The port keeps the verified physical PCM clock (64 kHz), bounded microphone
decoder and session recovery. Recording is explicit, has Stop/Play/Share and a
foreground indicator, and fails closed if microphone access is revoked. Short
diagnostic recordings remain private and are excluded from backups. The database
and retained legacy preferences are also excluded because they contain keys.

Previous physical tests on the older fork established normal-pitch HQ microphone
capture with good media quality and two-way cellular call relay. They do not
validate this new rewrite build. Normal-quality media during a cellular call is
still unresolved; Pixel Recorder's explicit microphone selection can bypass the
UID injection route. AAC-ELD playback remains gated because the available encoder
does not produce the verified 480-sample configuration.

Upstream rewrite ATT transport is disabled. Safe hearing/transparency codecs and
read-only handling of unsupported formats do not imply functioning rootless ATT
access. No fixed ATT handle has been reintroduced. Live pending requests remain
connection-owned. Pro 3's saved Allow Off choice is a separate presentation
fallback, and is not presented as a device report.

## Validation

Pixel28 moves the existing model-specific pairing-mode instructions onto the
initial card, followed by “Then tap Connect.” Pairing, discovery, consent guards,
layout and animation behavior are unchanged. Existing rendering fixtures use the
longer real message for normal, narrow large-text and landscape checks.
On 2026-10-17, `verifyPixel` passed all 233 tests in 43 suites with no failures,
errors or skips; lint reported 0 errors, 289 warnings and 1 hint; debug and
minified FOSS release builds passed. Native rendering checks and batched visual
inspection confirmed the longer instructions fit, with Connect reachable at
normal size, narrow large text and scrolled landscape large text. Review caught
and repaired a missing local context in a rendering fixture; the obsolete first
snapshot was stopped and the corrected full build passed. Pixel28 was signed
with the existing fork certificate and installed in place. Android confirmed
version `2.0.0-alpha.1-pixel28`, code `200000113`, with Shizuku permission retained.
Fresh pairing was not repeated for this copy change; no bond, audio route or
call setting was changed.

Pixel27 defers compatible incoming consent in a separate, non-exported app
process during an explicit pairing attempt. Android serializes broadcasts per
process, so this lets the main process continue fresh Classic discovery while
the worker holds the dialog for at most seven seconds from receipt. The main
observer runs first and retains native-cycle/variant invalidation. Approval
still requires the sole stable fresh Classic selection, a live attempt,
native bonding, and the existing guarded Shizuku confirmation. The helper gets
at most 1500 ms and never extends the worker's original deadline. Unavailable
Shizuku, ambiguity, another selected identity, expiry, cancellation, owner death
and process teardown return the request to Android without aborting it.
A one-time 100 ms capture grace preserves automatic confirmation if a worker
acknowledges but misses an already-resolved broadcast. No native dialog is
disabled globally.
On 2026-10-17, `verifyPixel` passed with 233 tests in 43 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Eleven new tests cover worker admission,
exact request ownership, native fallback, acknowledgement and disconnect
retirement, late callbacks, delayed fresh selection, a lost capture callback,
and the original hold deadline limiting a late helper. Existing controller
tests supply a valid service binding because Robolectric's default null
connection callback does not match Android's separate `onNullBinding` callback.
An initial compile failure was repaired by retaining the paired-device callback
as the trailing constructor argument; a second run exposed that fixture gap.
The full third run passed after fixture repair, with production unchanged.
Independent review found no remaining blockers. Pixel27 was signed with the
existing certificate and installed in place; Android confirmed version
`2.0.0-alpha.1-pixel27`, code `200000112`, and the retained Shizuku grant.
The user repeated fresh pairing twice and confirmed automatic completion
without the Android prompt flashing in either attempt. Both traces captured
the worker-held request before guarded confirmation; helper responses took
41 ms and 30 ms after capture, with native bonding complete at 11270 ms and
10616 ms after Connect. Discovery and physical pairing-mode entry vary, so
these totals do not measure a fixed speedup. Both requests arrived after fresh
selection; incoming-before-discovery is covered by the delayed-selection
regression and still needs another physical sample. The bounded capture is
stopped, the worker service is unbound, and the original empty logging property
is retained. No microphone or call route was started; normal Android audio mode
was verified before installation.

A subsequent controlled timing check on the unchanged Pixel27 build put the
case into flashing-white pairing mode before tapping Connect. The user reported
noticeably faster pairing. First fresh Classic discovery arrived at 708 ms,
sole-candidate selection at 2709 ms, BR/EDR bond creation at 2716 ms, consent at
3183 ms, guarded confirmation at 3286 ms, and native BONDED at 4087 ms after
Connect. This is one physical sample, compared with the earlier 11270/10616 ms
attempts whose gesture happened after Connect; it does not isolate all radio
variation or prove a fixed 6–7 second improvement. Keep the current two-second
identity/ambiguity window and prompt fallback. No implementation or rebuild was
needed for this faster order. Do not start ambient Classic inquiry just to hide
the wait: it uses the shared Bluetooth radio and can degrade other connections.
The timing capture is stopped and the original logging setting is retained.

Pixel26 repairs a one-shot selection callback that could drop a still-valid
candidate when it ran just before the elapsed-time eligibility deadline.
It rechecks and re-arms only for a positive remaining interval; ambiguity,
cancellation, current-attempt lifetime and the two-second sole-candidate window
remain enforced. Candidate-check timestamps distinguish timer delivery from
later bond/discovery IPC. The earlier Pixel25 trace is consistent with a
stranded selection, but did not record timer delivery and does not prove that
specific attempt's cause.
On 2026-10-17, `verifyPixel` passed with 222 tests in 42 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Two new regressions simulate a timer at
1999 ms and verify one BR/EDR bond at 2000 ms without inquiry completion, plus
ambiguity or cancellation retiring the replacement timer. Independent review
found no remaining blockers. Pixel26 was signed with the existing certificate
and installed in place; Android confirmed version `2.0.0-alpha.1-pixel26`,
code `200000111`, and the retained Shizuku grant. The user reported fast pairing;
Android's prompt appeared but required no tap and dismissed automatically.
The captured attempt selected exactly 2000 ms after its first fresh Classic
candidate, adopted the incoming bond in 5 ms, and completed at 10153 ms after
Connect. Its first candidate arrived at 6817 ms; this includes time before the
phone found the case and does not isolate entry into physical pairing mode.
No early re-arm or full-scan stall occurred in that attempt, so it does not prove
the old trace's cause or a fixed speedup across radio conditions. Bounded captures
are stopped and the original system logging setting is retained. Layout and
motion are unchanged; no microphone route or call started.

Pixel25 suppresses automatic island announcements for the exact newly paired
device's current case opening. Suppression is armed before asynchronous device
setup and checked on the main thread after queued shows arrive. A newer owned,
decoded close releases it; battery replay, stale or foreign close reports and
ordinary UI dismissal do not. Explicit listening-mode notices remain available.
Unpairing, Bluetooth-off and service teardown retire the matching suppression.
No layout or animation geometry changes are included.
On 2026-10-17, `verifyPixel` passed with 220 tests in 42 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Two new window regressions cover queued
announcements, exact-owner dismissal, stale/equal/foreign closes, the next
opening, ordinary dismissal, explicit notices and cleanup. Their synthetic
window supplies lifecycle, saved-state and view-model owners for real queued
main-thread traversal. Independent review found no remaining blockers.
Pixel25 was signed with the existing certificate and installed in place;
Android confirmed version `2.0.0-alpha.1-pixel25`, code `200000110`, and the
retained Shizuku grant. On 2026-10-17, the user confirmed two fresh pairings
complete automatically without Android's Pair button. They also confirmed
the success card appears alone, and after Continue plus a case close/open the
island returns on that next opening. The bounded timing capture was stopped;
the system logging preference remains at its original setting.
No experimental microphone route or call was started.

Pixel24 retains an exact active incoming consent for fifteen seconds from its
original receipt, allowing the observed 5667 ms discovery/selection path to
reach Shizuku. Duplicate requests do not renew it. The selected helper still
has at most 1500 ms and never outlives the hint; shell and client deadlines,
native fallback, cycle/variant retirement and fresh candidate selection are
unchanged. Tests reproduce the observed delayed candidate and cover exact
fifteen-second expiry plus a late selection with only 500 ms remaining.
On 2026-10-17, `verifyPixel` passed with 218 tests in 42 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Independent review found no remaining
blockers. Pixel24 was signed with the existing certificate and installed in
place; Android confirmed version `2.0.0-alpha.1-pixel24`, code `200000109`,
and the retained Shizuku grant. Subsequent physical checks are recorded under
Pixel25. Layout and motion are unchanged; no experimental microphone route or
call was started.

Pixel23 adds release-visible, identity-free pairing diagnostics for request
variant and native state, selected consent age, helper availability and guard
rejection. No names, addresses, keys or pairing codes are recorded. Pairing
consent policy and timing bounds are unchanged. A new regression models the
observed two incoming native cycles during one uninterrupted Classic inquiry,
with both orderings of the second bonding broadcast and protected consent.
On 2026-10-17, `verifyPixel` passed with 216 tests in 42 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Pixel23 was signed with the existing
certificate and installed in place. Android confirmed version
`2.0.0-alpha.1-pixel23`, code `200000108`, and the retained Shizuku grant.
The physical diagnostic repeat still required Android's Pair button on one
attempt. The trace captured protected consent with native BONDING state, then
its first Classic candidate 3660 ms later. Sole stable selection followed
another 2007 ms later, while the same bond remained active. Its valid consent
hint was 5667 ms old and skipped the former five-second limit, identifying a
concrete cause of the intermittent manual confirmation. No experimental
microphone route or call was started.

On 2026-10-17, Pixel22's `verifyPixel` passed with 215 tests in 42 suites,
no failures, errors or skips, lint with 0 errors, 289 warnings and 1 hint,
and successful debug and minified release builds. Six new controller regressions
cover incoming consent before selection, continuous receiver ownership, adoption
without duplicate bonding, completed-bond reconciliation, expired and invalidated
hints, changed variants, native fallback and a new bond cycle with a delayed
state broadcast. Independent review found no remaining blockers. Layout and
animations are unchanged; existing rendering and input tests still pass.

Pixel22 was signed with the existing certificate and installed in place.
Android confirmed version `2.0.0-alpha.1-pixel22`, code `200000107`, and the
retained Shizuku grant. In the physical repeat, the user reported that the first
pair completed automatically, but the second required Android's Pair button.
Native history showed the second request arriving during uninterrupted inquiry,
about two seconds before candidate selection, so the scan-retry listener gap
does not explain that attempt. Android's earlier
history showed one local bond completing in 895 ms, while an incoming pairing
request waited 3423 ms for the user's manual confirmation. These are native bond
timings, not measurements from tapping Connect. No experimental microphone
route or call was started for this update.

On 2026-10-17, Pixel21's `verifyPixel` passed with 209 tests in 42 suites,
no failures, errors or skips, lint with 0 errors, 289 warnings and 1 hint,
and successful debug and minified release builds. New regressions cover raw
pre-key reports, their original ages, bounded retention, expired/foreign data,
latest-frame unavailable slots, missing-slot fill during AACP, current device,
bond, transport and key guards, saved-key replay without a control socket,
pre-Connect invitation readings, retry boundaries and stale key publication.
The service replay tests exercise the actual handoff method. Overlay layout
and animations are unchanged; existing rendering and input tests still pass.

Pixel21 was signed with the existing certificate and installed in place.
Android confirmed version `2.0.0-alpha.1-pixel21`, code `200000106`, and the
retained Shizuku grant. On 2026-10-17, the user confirmed that after forgetting
and pairing again, both the success card and island show battery percentages
immediately, without the temporary dashes.
A first-ever pair still needs its keys before encrypted LE readings can be
verified. No experimental microphone route or call was started for this update.

On 2026-10-17, Pixel20's `verifyPixel` passed with 201 tests in 40 suites,
no failures, errors or skips, lint with 0 errors, 289 warnings and 1 hint,
and successful debug and minified release builds. New regressions exercise
Allow Off through real transport writes, Room persistence, app reload and fresh
contradictory reports; control-only refresh, concurrent/canceled replacement
sockets and audio reservations; and repeated case cycles with partial battery
reports, retained zero readings and stale callbacks. The real Refresh touch
test also caught a shared Apple button's stale enabled-state capture; disabled
buttons now reject taps and cancel their pressed appearance cleanly. Native
Refresh captures cover narrow/large-text light and dark Material settings;
Apple input checks use the existing legacy-renderer workaround.

Pixel20 was signed with the existing certificate and installed in place.
Android confirmed version `2.0.0-alpha.1-pixel20`, code `200000105`, and the
retained Shizuku grant. On 2026-10-17, the user confirmed that repeated case
cycles keep the island's battery and charging indicators updating, Allow Off
stays selected after reconnecting and reopening LibrePods, and Refresh completes
without leaving the settings page or interrupting music playback.
No experimental microphone route or call was started for this update.

Pixel19's `verifyPixel` passed with 183 tests in 36 suites, no failures, errors
or skips, lint with 0 errors, 289 warnings and 1 hint, and successful debug and
minified release builds. New regressions cover exact post-bond battery ownership,
retaining the success card, independent reading expiry, unpair cleanup, rounded
Adaptive reports, actual drag/release and reopening, and Material's input-only
writes. Battery layouts were checked with native API 37 rendering in light,
narrow/large-text dark and short landscape layouts, with both balanced and split
bud levels. The Apple drag test uses API 33's legacy renderer because Robolectric's
native AGSL compiler rejects the existing backdrop shader; Material uses native
rendering. The expiry test ages its readings before real scheduled callbacks
because this runner's virtual Handler clock does not advance the wall clock.
Pixel19 was signed with the existing certificate and installed in place; Android
confirmed version `2.0.0-alpha.1-pixel19` and the retained Shizuku grant. On
2026-10-17, the user confirmed that Adaptive strength holds after release and
reopening, and that fresh pairing shows AirPods/case percentages on the success
card until Continue. No audio checks were repeated.

Pixel18's `verifyPixel` passed with 173 tests in 35 suites, no failures, errors
or skips, lint with no errors, and successful debug/minified release builds.
New regressions cover successful held consent, completed bonding before IPC
returns, cancellation with a late successful reply, the independent fallback
deadline, helper failure and unrelated/passkey requests. Robolectric does not
propagate an asynchronous pending result's abort flag through its ordered
receiver chain, so success checks assert that result's abort/completion flags;
fallback checks retain delivery to a lower-priority receiver. Pixel18 was signed
with the existing certificate and installed in place; Android confirmed its
version. After forgetting the old bond, the user confirmed that the card stays
visible through Connecting to Paired and pairing completes. This verifies the
handoff on this Pixel/Shizuku setup; receiver order remains platform dependent.
No audio checks were repeated.

Pixel17's `verifyPixel` passed with 167 tests in 35 suites, no failures, errors
or skips, lint with no errors, and successful debug/minified release builds.
Native Android 17 rendering covers all five pairing states, narrow screens,
large text and short landscape layouts, plus coordinated sheet motion and
reduced motion. The Pro clip's 178 decoded frames contain no blank frames; its
first frame and loading poster match. Pixel17 was signed with the existing
certificate and installed in place after wireless debugging reconnected; Android
confirmed its version. The user reported a brief disappearance and return of the
card during automatic confirmation, prompting Pixel18's consent handoff fix.
This polish does not retest audio.

Pixel16's `verifyPixel` passed with 163 tests in 35 suites, no failures, errors or skips,
Android lint with no errors, and successful debug/minified release builds.
Pairing regressions cover the live selection deadline, repeated/name-delayed
reports, ambiguous or LE candidates, older Android's bond API, discovery
ownership, canceled receivers and narrowly scoped Shizuku consent. Pixel16 was
signed with the existing certificate and installed in place; Android confirmed
its version. After forgetting the old bond, the user confirmed that Pixel16
pairs automatically and is much faster. No pairing timing logs
were retained for that attempt, so there is no measured end-to-end duration.
Native pairing-card captures cover light mode and dark mode with narrow width
and larger text. Pixel15 was signed with the existing fork certificate and
installed in place, retaining the app's data. Android confirmed its version.
Android's scan state confirms
unbatched passive low-latency monitoring. The user confirmed the island now
appears promptly on Pixel12 and confirmed Pixel13's camera-origin expansion.
Pixel14 added cached-age cleanup and confirmed listening-mode notices, but the
user reported a white flash on dismissal and rejected the content fading.
Pixel15 replaces that motion with fixed-layout payloads and a coordinated
camera-capsule transition, and applies a dark framework context to the overlay
to opt out of Android's color inversion. Pixel15 passes native rendering checks
for fixed layout and complete content fitting during folds, including compact
camera clearance and the shortest accepted expanded layout. Rendering uses
API 33 because Espresso's legacy input bridge is unavailable on API 37; theme,
native window width and callback lifetime checks run on API 37.
The user confirmed Pixel15 opens and closes smoothly, with no overlapping text
or white flash, and that listening-mode changes display the new mode in the island.
Regression coverage includes visible initial geometry, reduced motion,
interrupted exits, age grouping, per-transport mode baselines and concurrent
control/battery/wear cache updates.

The first physical Pixel11 check still took roughly ten seconds after opening
the case, despite 32 ms from Android's scan timestamp to the overlay request and
58 ms to its first drawing pass. Closing the lid dismissed promptly. Pixel12
changes scanning and cached-identity admission. Its owned lid reports reached
the overlay request in 11–31 ms and first drawing pass in 27–62 ms; those are
radio-to-frame measurements, not physical lid-to-frame measurements. Fresh
pairing was subsequently confirmed on Pixel16; sensors and call audio have not
been retested on the rewrite.

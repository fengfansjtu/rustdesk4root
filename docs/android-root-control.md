# Android root control

The native control path is:

`Rust session -> MainService -> RootInputManager -> su/app_process -> InputManager`

It does not require `InputService` (Android accessibility). New installations default
to root mode. Existing saved mode selections are preserved; select **Root Permission
Mode** when upgrading an installation previously configured for accessibility.
Root authorization still requires approval in the device's root manager.

## Fixes

- Returning to the activity only enables accessibility when that mode is selected.
  Input permission status follows the selected mode.
- Root authorization failure reports failure without opening accessibility settings.
- Stopping root control closes the daemon and shell, clears pending daemon commands,
  and rejects subsequent input while disabled. Switching away from root also closes
  the daemon.
- The worker checks daemon liveness while idle, supports interruption during shutdown,
  and does not launch a second worker while the previous one is exiting. A delayed
  readiness message from an obsolete process cannot publish a new writer.
- Injection prefers the two-argument `injectInputEvent`. The three-argument overload
  receives target UID `-1` (unrestricted), rather than UID `0`; its third parameter is
  not a display ID. See [AOSP InputManagerGlobal](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/java/android/hardware/input/InputManagerGlobal.java).
- Keyboard click events send both down and up, and the daemon receives modifier
  flags. Failed down injection can use the existing shell key fallback.
- Stray move commands cannot synthesize a new touch after daemon restart. Wake
  requests use one transport instead of sending duplicate key sequences.
- An unanswered root authorization check times out after 30 seconds instead of
  indefinitely blocking the root command executor.

## Device verification

1. Install the ARM64 APK on a rooted device. Select root mode, approve `su`, and
   leave RustDesk accessibility disabled. Connect with an official RustDesk client.
   Verify tap, drag, long press, scroll, Back, Home, Enter, and Shift plus arrow.
2. Stop input control during a connection. Verify subsequent pointer and keyboard
   events do nothing; enable input again and verify a fresh drag works. Repeat
   after stopping and restarting screen sharing.
3. With no finger/key held, terminate the root input daemon from a root shell.
   Verify the worker reconnects and a new gesture works. Deny root authorization
   on a fresh launch and verify the app reports failure without enabling or opening
   accessibility.

## Touch responsiveness fixes

The phone controller can send an early mouse down from `onLongPressDown`, followed
by another down when a tap or pan wins gesture recognition. The daemon now cancels
an outstanding injected touch before accepting a replacement down, so Android sees
valid gesture boundaries rather than overlapping downs.

The old queue discarded its oldest entry when full, including down/up commands.
The root queue now combines consecutive absolute moves, removes only a queued move
when space is needed, and reports control-only overflow instead of silently losing
a release. Overflow explicitly cancels the touch. Buffered daemon moves are also
combined without crossing a down/up/key boundary.

Synthetic scroll/long-press swipes no longer overlap a live finger, and skip their
remaining artificial delays when another command is waiting. The daemon removes
the extra 15 ms sleep before up; AOSP's own `input tap` uses matching down/up times.
Shell fallback gestures run serially so their separate processes cannot interleave
touch sequences.

For phone-to-Android-16 testing, repeat quick taps, rapid tap/swipe alternation,
continuous drags, and a deliberate hold followed by a drag. Check both idle input
and input immediately after scrolling. The three JVM queue regression tests run in
the ARM64 workflow; real-device gesture recognition still needs verification.

## Unlocked-screen latency fixes

Android's Image plane buffer becomes invalid when the Image is closed. Previously
the JNI video callback stored only its address, then `Image.use` closed the Image
before the Rust encoder read it. The video handoff now owns the pixels before
returning to Android. Explicit width, height, and row stride remove the previous
stride guess from buffer capacity, including devices whose final row has no mapped
padding. See [Android Image.close](https://developer.android.com/reference/android/media/Image#close()).

The handoff has one pending frame and reuses its allocations. While the encoder is
busy, additional producer frames are skipped before copying; the existing 100 ms
age limit lets fresh frames replace an expired pending frame. The consumer swaps
buffers instead of making a second output copy. `update`, `take`, and `set_enable`
are the only state readers/writers, all under the existing video mutex. Audio's
raw-frame path and the shared capturer interface are unchanged.

The ImageReader callback uses the existing lock-screen-active flag instead of
issuing KeyguardManager/PowerManager IPC on every frame. Screen broadcasts and the
existing watchdog maintain lock-screen capture. Pure root pointer moves bypass
the wake/lock-screen checks and daemon-start lock; presses, releases, keyboard,
screen broadcasts, and the watchdog retain their checks. Hovering alone no longer
wakes a sleeping device.

Three Rust regressions cover source reuse, row padding/truncation, and slow-consumer
handoff. They also compile in ordinary workspace tests and run before native APK
compilation. Measure FPS, codec, transport, and latency on the actual Android 16
device before attributing all observed lag to these bugs: relay bandwidth and
software encoding can still limit performance.

## Known limits

- Compilation does not verify OEM root policies or event injection on real devices.
  Device checks above are still required.
- Shell `input text` does not provide general Unicode/IME composition support.
- Screen capture still uses the existing MediaProjection and lock-screen capture
  code. Root input permission does not itself guarantee capture of secure surfaces
  or remove credential requirements on a secure lock screen.
- Control-only queue overload cancels the touch rather than replaying a partial
  gesture. An active gesture or held key is not restored across a daemon failure.
- The wire input does not carry the phone's raw finger timestamps or gesture-cancel
  events. Delays or missing releases introduced by the controlling client's gesture
  recognizers cannot always be reconstructed by the receiver.
- The existing module installer queues its shell commands asynchronously; its UI
  success result is not confirmation that the module was installed successfully.

## Regression surface

- `MainActivity.kt`: mode-specific input status, daemon startup, and root stop hook.
- `MainService.kt`: root input guards and move fast path; video handoff metadata;
  existing lock-screen state used by the frame callback to avoid per-frame IPC.
- `ffi.kt` and `libs/scrap/src/android/ffi.rs`: matching Android-only video JNI
  parameters and an owned-video handoff while Image memory remains valid.
- `libs/scrap/src/lib.rs`: Android/test-only module hook for the video handoff.
- `RootInputManager.kt`: root defaults, authorization, worker/shell lifecycle, and
  root event dispatch.
- `RootInputServer.kt`: root-only injection overload selection and event handling.
- `RootInputQueue.kt`: root-only command buffering and move coalescing.
- `server_model.dart`: new-install default and root authorization failure UI.
- `app/build.gradle` and `build-android-arm64.yml`: JVM queue tests before artifact
  upload, with JUnit confined to the test configuration.

The explicit accessibility input implementation, audio frame path, Rust network
protocol, codec negotiation, generated bridges, and submodules are unchanged.

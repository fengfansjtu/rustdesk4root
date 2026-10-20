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
- `MainService.kt`: root pointer/key entry points reject disabled or unavailable root.
- `RootInputManager.kt`: root defaults, authorization, worker/shell lifecycle, and
  root event dispatch.
- `RootInputServer.kt`: root-only injection overload selection and event handling.
- `RootInputQueue.kt`: root-only command buffering and move coalescing.
- `server_model.dart`: new-install default and root authorization failure UI.
- `app/build.gradle` and `build-android-arm64.yml`: JVM queue tests before artifact
  upload, with JUnit confined to the test configuration.

The explicit accessibility event dispatch implementation, Rust protocol, capture
implementation, generated bridges, and submodules are unchanged.

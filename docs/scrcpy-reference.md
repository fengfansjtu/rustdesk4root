# Android Surface encoding (scrcpy reference)

Reviewed official scrcpy revision `a60891aea193d92e7e5c3942700eca63f9d19a5f`.

On Android 10 and newer, the Android monitor service now supports a parallel
Surface backend. MediaProjection's existing VirtualDisplay renders directly to
MediaCodec's input Surface. Kotlin drains compressed H.264/H.265 packets on a
dedicated thread; Rust sends them using the existing RustDesk video messages.
This avoids application-side RGBA readback and CPU color conversion on this path.
The root app_process input daemon remains independent and uses asynchronous
InputManager injection; root mode does not require an accessibility service.

## Failure and congestion handling

- Prefer a hardware Surface encoder when hardware encoding is enabled. Try the
  compatible encoders for the negotiated codec and actual capture dimensions.
- A hardware creation/start/output error, or no first packet within five seconds,
  disables the hardware Surface stage until MainService is recreated. Renegotiate
  using software MediaCodec Surface encoders, including H.264 if software H.265
  is unavailable.
- If software Surface encoding is unavailable or fails, negotiate the existing
  software codec path with the owned ImageReader frame handoff. Devices below
  Android 10 retain the original backend. Ordinary unlocked capture does not use
  repeated shell screenshots.
- At most four compressed packets (each at most 8 MiB) wait for sending. On
  overflow, discard the queued GOP, request a new keyframe and resume at that
  keyframe. Do not send dependent P frames after dropping their references.
- Include Annex B codec configuration with every keyframe; keep timestamps in
  milliseconds for RustDesk. Reject unsupported layouts instead of sending an
  undecodable stream. Session IDs reject output from an old encoder after restart.
- Configure real-time priority, one-frame requested latency, zero B frames and a
  Surface input FPS limit. Apply quality/bitrate changes on the encoder thread;
  materially changed FPS restarts the encoder. Encoder support for optional
  latency settings remains device dependent.
- Preserve subscriber snapshots, frame acknowledgements, recording, display and
  privacy updates. Waiting for packets uses a condition variable; waiting for
  acknowledgements does not create a nested Tokio runtime.
- Screen-off, lockscreen fallback, rotation, pipeline reconstruction and service
  shutdown detach and stop the encoder. The retained ImageReader Surface is
  restored before releasing the codec Surface. Unlock can negotiate Surface
  encoding again. A still screen after initial output is not an encoder failure.

## Known limits and validation

The screenshot command requires a raw image; it switches to the existing software
path until MainService is recreated. A peer explicitly selecting VP8/VP9/AV1 also
uses the original path. The existing root lockscreen screenshot fallback is kept.
Software encoders without Surface support require raw ImageReader capture; the
input daemon and bounded frame handoff remain independent in that fallback.

Local validation: Rust 1.75 raw/encoded frame tests; Kotlin 2.1.21 compilation of
the new encoder against Android API 36; JUnit packetizer and root queue tests.
The new Rust Surface/JNI code also passed a JNI 0.21 type check with hardware
features on/off, using compile-only substitutes for the existing codec API in a
temporary check project. This is not a full application build.
The ARM64 workflow runs both Rust suites and both JUnit suites and builds the
complete APK. No Android 16 device was available locally, so hardware/software
codec selection, unlock, rotation, multi-touch and relay latency still need device
verification. A successful unit test or USB scrcpy result does not establish WAN
performance.

## Regression surface

- `MainService.kt`: Surface attachment, capabilities and lifecycle hooks are
  necessary to switch the existing VirtualDisplay without creating a second
  MediaProjection. Existing raw capture and root input implementations remain.
- `ffi.kt` and `libs/scrap/src/android/ffi.rs`: new typed JNI callbacks transport
  compressed packets, readiness and failures; service initialization resets the
  Android-only backend. Existing raw/audio JNI signatures are unchanged here.
- `libs/scrap/src/android/mod.rs` and `libs/scrap/src/lib.rs`: expose the Android
  backend and include the pure packet queue for Android and regression tests.
- `libs/scrap/src/common/codec.rs`: Android-only capability overrides are needed
  because the existing RAM encoder discovery requires NV12 and cannot select
  Surface-only encoders. Other platforms and unsupported Android versions retain
  the existing negotiation path.
- `src/server/video_service.rs`: one Android-only entry hook selects the parallel
  encoded stream loop; the original shared capture/encode loop is preserved.
- `.github/workflows/build-android-arm64.yml`: run the new packet queue and
  packetizer tests alongside the existing regression tests before APK upload.

## Official sources

- [Surface encoder](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/video/SurfaceEncoder.java)
- [Display to Surface](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/video/ScreenCapture.java)
- [Control thread](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/control/Controller.java)
- [Input injection](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/wrappers/InputManager.java)
- [Android MediaFormat options](https://developer.android.com/reference/android/media/MediaFormat)

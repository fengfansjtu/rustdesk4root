# scrcpy implementation reference

Reviewed official scrcpy revision `a60891aea193d92e7e5c3942700eca63f9d19a5f`.

| Area | scrcpy | This RustDesk Android build |
| --- | --- | --- |
| Capture and encode | Display capture renders into a MediaCodec input Surface, which produces compressed packets. | ImageReader produces RGBA; Rust converts pixels and selects a hardware RAM encoder or a software codec. |
| Hardware input | Surface color format avoids application-side raw pixel readback. | Hardware selection currently requires advertised NV12 support. Surface-only encoders do not qualify for that RAM path. |
| Frame backlog | The decoded client frame buffer replaces an unconsumed pending frame using reference swaps. Default video playback adds no jitter buffer. | One owned raw frame is pending; extra producer copies are skipped while busy, using the existing 100 ms expiry. Consumer buffers are swapped. These are different tradeoffs because raw ImageReader memory must be copied before Image.close. |
| Input | A separate control thread creates touch/key events and uses asynchronous InputManager injection. | The root app_process daemon already uses asynchronous injection. Root moves now avoid wake-state IPC and repeated daemon-start checks on the receiving thread. |

## Applied to the current fix

- Keep video ownership explicit across threads, bound pending frames, and reuse buffers.
- Remove blocking system-state queries from per-frame and root-move processing.
- Preserve the existing encoder negotiation and the RustDesk video protocol.

The current APK still uses the RGBA capture backend. A Surface encoder backend
would need to forward H.264/H.265 configuration packets, keyframes, timestamps,
resolution changes, bitrate changes, and stop/restart events into the existing
RustDesk session. It also needs a raw-capture fallback for unsupported codecs and
recording/session configurations. Simply enabling the dormant `useVP9` branch
does not implement that backend: its output sender is a TODO and its Surface is
configured for a different input color format.

For a future Android-specific backend, retain the raw path as fallback and keep
encoded-packet handling in its own module with thin hooks into the video service.
Validate on a real Android 16 device before making it the default. USB/local TCP
scrcpy results do not establish WAN relay performance.

## Official sources

- [Surface encoder and real-time MediaFormat options](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/video/SurfaceEncoder.java)
- [Screen capture to Surface](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/video/ScreenCapture.java)
- [Touch/key injection](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/control/Controller.java)
- [InputManager wrapper](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/wrappers/InputManager.java)
- [Pending decoded frame buffer](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/app/src/frame_buffer.c)
- [Video buffering and codec options](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/doc/video.md)

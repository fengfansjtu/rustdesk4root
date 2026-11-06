use crate::{
    android_encoded_frame::{EncodedFrames, EncodedPacket, PushResult},
    CodecFormat,
};
use hbb_common::{anyhow::anyhow, log, ResultType};
use jni::objects::JValue;
use std::{
    sync::{
        atomic::{AtomicI64, Ordering},
        Condvar, Mutex,
    },
    time::Duration,
};

#[derive(Clone, Copy, PartialEq, Eq)]
enum Mode {
    Hardware,
    Software,
    Raw,
}

struct State {
    api: bool,
    capabilities: [bool; 4],
    ready: bool,
    raw_override: bool,
    mode: Mode,
    session: i64,
    hardware: bool,
    stopped: bool,
    frames: EncodedFrames,
}

impl Default for State {
    fn default() -> Self {
        Self {
            api: false,
            capabilities: [false; 4],
            ready: false,
            raw_override: false,
            mode: Mode::Hardware,
            session: 0,
            hardware: false,
            stopped: true,
            frames: EncodedFrames::default(),
        }
    }
}

lazy_static::lazy_static! {
    static ref STATE: Mutex<State> = Mutex::new(State::default());
    static ref FRAMES_AVAILABLE: Condvar = Condvar::new();
}
static NEXT_SESSION: AtomicI64 = AtomicI64::new(1);

fn codec_index(format: &CodecFormat) -> Option<usize> {
    match format {
        CodecFormat::H264 => Some(0),
        CodecFormat::H265 => Some(1),
        _ => None,
    }
}

fn allow_hardware() -> bool {
    #[cfg(feature = "hwcodec")]
    return crate::codec::enable_hwcodec_option();
    #[cfg(not(feature = "hwcodec"))]
    false
}

fn renegotiate() {
    #[cfg(feature = "hwcodec")]
    crate::codec::Encoder::update(crate::codec::EncodingUpdate::Check);
}

pub(crate) fn reset() {
    *STATE.lock().unwrap() = State::default();
}

pub(crate) fn set_capabilities(api: bool, capabilities: [bool; 4]) {
    let changed = {
        let mut state = STATE.lock().unwrap();
        let changed = state.api != api || state.capabilities != capabilities;
        state.api = api;
        state.capabilities = capabilities;
        if changed {
            state.mode = Mode::Hardware;
            state.raw_override = false;
        }
        changed
    };
    if changed {
        renegotiate();
    }
}

pub(crate) fn set_ready(ready: bool) {
    let changed = {
        let mut state = STATE.lock().unwrap();
        let changed = state.ready != ready;
        if ready && !state.ready {
            state.mode = Mode::Hardware;
            state.raw_override = false;
        }
        state.ready = ready;
        changed
    };
    if changed {
        renegotiate();
    }
}

pub fn enabled() -> bool {
    cfg!(feature = "hwcodec") && STATE.lock().unwrap().api
}

pub fn ready() -> bool {
    let state = STATE.lock().unwrap();
    state.api && state.ready && !state.raw_override && state.mode != Mode::Raw
}

pub fn use_raw() {
    let changed = {
        let mut state = STATE.lock().unwrap();
        let changed = !state.raw_override;
        state.raw_override = true;
        changed
    };
    if changed {
        renegotiate();
    }
}

pub fn restore_after_raw() {
    let changed = {
        let mut state = STATE.lock().unwrap();
        let changed = state.raw_override;
        state.raw_override = false;
        changed
    };
    if changed {
        renegotiate();
    }
}

pub fn supported(format: &CodecFormat) -> bool {
    let Some(index) = codec_index(format) else {
        return false;
    };
    let hardware = allow_hardware();
    let state = STATE.lock().unwrap();
    cfg!(feature = "hwcodec")
        && state.api
        && !state.raw_override
        && state.mode != Mode::Raw
        && ((hardware && state.mode == Mode::Hardware && state.capabilities[index])
            || state.capabilities[index + 2])
}

pub fn hardware(format: &CodecFormat) -> bool {
    let Some(index) = codec_index(format) else {
        return false;
    };
    let allowed = allow_hardware();
    let state = STATE.lock().unwrap();
    allowed && state.mode == Mode::Hardware && state.capabilities[index]
}

pub(crate) fn stopped(session: i64, failed: bool) {
    let changed = {
        let mut state = STATE.lock().unwrap();
        if state.session != session || state.stopped {
            return;
        }
        state.stopped = true;
        state.frames.clear();
        if failed {
            state.mode = if state.hardware {
                Mode::Software
            } else {
                Mode::Raw
            };
        }
        failed
    };
    if changed {
        FRAMES_AVAILABLE.notify_all();
        log::warn!("Android Surface encoder failed; switching to software encoding");
        renegotiate();
    }
}

pub(crate) fn push(session: i64, data: Vec<u8>, pts_us: i64, key: bool) -> bool {
    let result = {
        let mut state = STATE.lock().unwrap();
        if state.session != session || state.stopped {
            return false;
        }
        state.frames.push(EncodedPacket { data, pts_us, key })
    };
    if result == PushResult::Invalid {
        stopped(session, true);
    }
    if result == PushResult::Accepted {
        FRAMES_AVAILABLE.notify_one();
    }
    result == PushResult::RequestKeyframe
}

pub struct Packet {
    pub data: Vec<u8>,
    pub pts_us: i64,
    pub key: bool,
}

pub struct Session {
    id: i64,
}

impl Session {
    pub fn start(
        format: &CodecFormat,
        width: usize,
        height: usize,
        bitrate: u32,
        fps: u32,
    ) -> ResultType<Self> {
        let codec = codec_index(format).ok_or_else(|| anyhow!("Unsupported Surface codec"))?;
        let hardware = hardware(format);
        let id = NEXT_SESSION.fetch_add(1, Ordering::Relaxed);
        {
            let mut state = STATE.lock().unwrap();
            state.session = id;
            state.hardware = hardware;
            state.stopped = false;
            state.frames.clear();
        }
        let session = Self { id };
        let result = super::ffi::call_surface_method(
            "rustStartSurfaceVideo",
            "(JIIIIIZ)Z",
            &[
                JValue::Long(id),
                JValue::Int(codec as i32),
                JValue::Int(width as i32),
                JValue::Int(height as i32),
                JValue::Int(bitrate as i32),
                JValue::Int(fps as i32),
                JValue::Bool(hardware.into()),
            ],
        );
        if !matches!(result, Ok(true)) {
            if result.is_err() && ready() {
                stopped(id, true);
            }
            return Err(anyhow!(
                "Could not start Android Surface encoder: {result:?}"
            ));
        }
        Ok(session)
    }

    pub fn poll(&self) -> ResultType<Option<Packet>> {
        let mut state = STATE.lock().unwrap();
        if state.session != self.id || state.stopped {
            return Err(anyhow!("Android Surface encoder stopped"));
        }
        let packet = match state.frames.take() {
            Some(packet) => Some(packet),
            None => {
                let (mut state, _) = FRAMES_AVAILABLE
                    .wait_timeout(state, Duration::from_millis(20))
                    .unwrap();
                if state.session != self.id || state.stopped {
                    return Err(anyhow!("Android Surface encoder stopped"));
                }
                state.frames.take()
            }
        };
        Ok(packet.map(|packet| Packet {
            data: packet.data,
            pts_us: packet.pts_us,
            key: packet.key,
        }))
    }

    pub fn update_bitrate(&self, bitrate: u32) -> ResultType<()> {
        match super::ffi::call_surface_method(
            "rustUpdateSurfaceVideo",
            "(JI)Z",
            &[JValue::Long(self.id), JValue::Int(bitrate as i32)],
        ) {
            Ok(true) => Ok(()),
            result => Err(anyhow!("Could not update Surface bitrate: {result:?}")),
        }
    }

    pub fn fail(&self) {
        stopped(self.id, true);
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        {
            let mut state = STATE.lock().unwrap();
            if state.session == self.id {
                state.session = 0;
                state.stopped = true;
                state.frames.clear();
            }
        }
        if let Err(error) = super::ffi::call_surface_method(
            "rustStopSurfaceVideo",
            "(J)Z",
            &[JValue::Long(self.id)],
        ) {
            log::warn!("Could not stop Android Surface encoder: {error}");
        }
    }
}

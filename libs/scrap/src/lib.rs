#[cfg(quartz)]
extern crate block;
#[macro_use]
extern crate cfg_if;
pub use hbb_common::libc;
#[cfg(dxgi)]
extern crate winapi;

pub use common::*;

#[cfg(quartz)]
pub mod quartz;

#[cfg(x11)]
pub mod x11;

#[cfg(all(x11, feature = "wayland"))]
pub mod wayland;

#[cfg(dxgi)]
pub mod dxgi;

#[cfg(target_os = "android")]
pub mod android;

#[cfg(any(target_os = "android", test))]
#[path = "android/video_frame.rs"]
mod android_video_frame;

#[cfg(any(target_os = "android", test))]
#[path = "android/encoded_frame.rs"]
mod android_encoded_frame;

mod common;

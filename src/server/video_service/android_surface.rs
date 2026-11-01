use super::*;
use base::message_proto::{EncodedVideoFrame, EncodedVideoFrames, VideoFrame};
use scrap::android::surface;

pub(super) fn run_if_supported(vs: &VideoService) -> Option<ResultType<()>> {
    let format = Encoder::negotiated_codec();
    if !vs.source.is_monitor()
        || !surface::enabled()
        || !matches!(format, CodecFormat::H264 | CodecFormat::H265)
    {
        return None;
    }
    if !surface::ready() {
        Encoder::update(scrap::codec::EncodingUpdate::Check);
        return Some(Err(anyhow!("SWITCH")));
    }
    Some(run(vs, format))
}

fn run(vs: &VideoService, format: CodecFormat) -> ResultType<()> {
    let sp = &vs.sp;
    check_change_scale(surface::hardware(&format))?;
    let c = get_capturer(vs.source, vs.idx, false)?;
    let (fps, mut quality, client_record) = {
        let mut qos = VIDEO_QOS.lock().unwrap();
        (qos.fps().clamp(1, 60), qos.ratio(), qos.record())
    };
    let mut bitrate =
        HwRamEncoder::calc_bitrate(c.width, c.height, quality, format == CodecFormat::H264);
    let session = match surface::Session::start(
        &format,
        c.width,
        c.height,
        bitrate.saturating_mul(1000),
        fps,
    ) {
        Ok(session) => session,
        Err(error) => {
            log::warn!("Surface encoder start: {error}");
            bail!("SWITCH");
        }
    };
    let record_incoming = config::option2bool(
        "allow-auto-record-incoming",
        &Config::get_option("allow-auto-record-incoming"),
    );
    let recorder = get_recorder(record_incoming, vs.idx, false);
    {
        let mut qos = VIDEO_QOS.lock().unwrap();
        qos.store_bitrate(bitrate);
        qos.set_support_changing_quality(&sp.name(), true);
    }
    sp.set_option_bool(OPTION_REFRESH, false);
    let mut controller = VideoFrameController::new(vs.idx);
    let mut last_packet = Instant::now();
    let mut last_check = Instant::now();
    let mut send_counter = 0;
    let mut first_pts = None;
    while sp.ok() {
        check_privacy_mode_changed(sp, vs.idx, &c)?;
        if !surface::ready() || Encoder::negotiated_codec() != format {
            bail!("SWITCH");
        }
        if sp.is_option_true(OPTION_REFRESH) {
            try_broadcast_display_changed(sp, vs.idx, &c, true)?;
            bail!("SWITCH");
        }
        if SCREENSHOTS
            .lock()
            .unwrap()
            .contains_key(&(vs.source, vs.idx))
        {
            // The existing screenshot consumer needs a raw frame, not a compressed packet.
            surface::use_raw();
            bail!("SWITCH");
        }
        if last_check.elapsed() >= Duration::from_secs(1) {
            last_check = Instant::now();
            try_broadcast_display_changed(sp, vs.idx, &c, false)?;
            let (new_quality, new_fps, new_record) = {
                let mut qos = VIDEO_QOS.lock().unwrap();
                qos.update_display_data(&sp.name(), send_counter);
                (qos.ratio(), qos.fps().clamp(1, 60), qos.record())
            };
            send_counter = 0;
            if new_record != client_record || new_fps.abs_diff(fps) > (fps / 3).max(2) {
                bail!("SWITCH");
            }
            if new_quality != quality {
                quality = new_quality;
                bitrate = HwRamEncoder::calc_bitrate(
                    c.width,
                    c.height,
                    quality,
                    format == CodecFormat::H264,
                );
                if let Err(error) = session.update_bitrate(bitrate.saturating_mul(1000)) {
                    session.fail();
                    log::warn!("Surface encoder bitrate: {error}");
                    bail!("SWITCH");
                }
                VIDEO_QOS.lock().unwrap().store_bitrate(bitrate);
            }
        }
        let packet = match session.poll() {
            Ok(Some(packet)) => packet,
            Ok(None) => {
                if first_pts.is_none() && last_packet.elapsed() >= Duration::from_secs(5) {
                    session.fail();
                    bail!("SWITCH");
                }
                continue;
            }
            Err(_) => {
                bail!("SWITCH");
            }
        };
        last_packet = Instant::now();
        sp.snapshot(|sps| {
            if sps.has_subscribes() {
                bail!("SWITCH");
            }
            Ok(())
        })?;
        let first = *first_pts.get_or_insert(packet.pts_us);
        let frames = EncodedVideoFrames {
            frames: vec![EncodedVideoFrame {
                data: packet.data.into(),
                pts: packet.pts_us.saturating_sub(first) / 1000,
                key: packet.key,
                ..Default::default()
            }]
            .into(),
            ..Default::default()
        };
        let mut video = VideoFrame::new();
        video.display = vs.idx as _;
        match format {
            CodecFormat::H264 => video.set_h264s(frames),
            _ => video.set_h265s(frames),
        }
        let mut message = Message::new();
        message.set_video_frame(video);
        if let Some(recorder) = recorder.lock().unwrap().as_mut() {
            recorder.write_message(&message, c.width, c.height);
        }
        controller.set_send(Instant::now(), sp.send_video_frame(message));
        send_counter += 1;
        // This service already runs on its own thread; drain ACKs without a nested runtime.
        while sp.ok()
            && !controller.send_conn_ids.is_empty()
            && controller.cur.elapsed() < Duration::from_secs(3)
        {
            check_privacy_mode_changed(sp, vs.idx, &c)?;
            let receiver = FRAME_FETCHED_NOTIFIERS
                .lock()
                .unwrap()
                .get(&vs.idx)
                .map(|n| n.1.clone());
            if let Some(receiver) = &receiver {
                if let Ok(mut receiver) = receiver.try_lock() {
                    while let Ok((id, _)) = receiver.try_recv() {
                        controller.send_conn_ids.remove(&id);
                    }
                }
            } else {
                break;
            }
            if !surface::ready() || sp.is_option_true(OPTION_REFRESH) {
                break;
            }
            if !controller.send_conn_ids.is_empty() {
                std::thread::sleep(Duration::from_millis(2));
            }
        }
        controller.reset();
        DISPLAY_CONN_IDS.lock().unwrap().remove(&vs.idx);
    }
    Ok(())
}

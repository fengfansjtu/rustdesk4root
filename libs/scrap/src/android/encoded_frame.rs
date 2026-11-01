use std::collections::VecDeque;

const MAX_PACKETS: usize = 4;
const MAX_PACKET_BYTES: usize = 8 * 1024 * 1024;

pub(crate) struct EncodedPacket {
    pub(crate) data: Vec<u8>,
    pub(crate) pts_us: i64,
    pub(crate) key: bool,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum PushResult {
    Accepted,
    Skipped,
    RequestKeyframe,
    Invalid,
}

pub(crate) struct EncodedFrames {
    frames: VecDeque<EncodedPacket>,
    needs_keyframe: bool,
}

impl Default for EncodedFrames {
    fn default() -> Self {
        Self {
            frames: VecDeque::new(),
            needs_keyframe: true,
        }
    }
}

impl EncodedFrames {
    pub(crate) fn clear(&mut self) {
        self.frames.clear();
        self.needs_keyframe = true;
    }

    pub(crate) fn push(&mut self, packet: EncodedPacket) -> PushResult {
        if packet.data.len() > MAX_PACKET_BYTES
            || !(packet.data.starts_with(&[0, 0, 0, 1]) || packet.data.starts_with(&[0, 0, 1]))
            || packet.pts_us < 0
        {
            return PushResult::Invalid;
        }
        if self.needs_keyframe && !packet.key {
            return PushResult::Skipped;
        }
        if self.frames.len() >= MAX_PACKETS {
            self.clear();
            if !packet.key {
                return PushResult::RequestKeyframe;
            }
        }
        self.needs_keyframe = false;
        self.frames.push_back(packet);
        PushResult::Accepted
    }

    pub(crate) fn take(&mut self) -> Option<EncodedPacket> {
        self.frames.pop_front()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn packet(key: bool, pts_us: i64) -> EncodedPacket {
        EncodedPacket {
            data: vec![0, 0, 0, 1, if key { 0x65 } else { 0x41 }],
            pts_us,
            key,
        }
    }

    #[test]
    fn stream_starts_with_a_keyframe_and_preserves_pts() {
        let mut frames = EncodedFrames::default();
        assert_eq!(frames.push(packet(false, 1)), PushResult::Skipped);
        assert_eq!(frames.push(packet(true, 2)), PushResult::Accepted);
        assert_eq!(frames.push(packet(false, 3)), PushResult::Accepted);
        let first = frames.take().unwrap();
        assert!(first.key);
        assert_eq!(first.pts_us, 2);
        assert_eq!(frames.take().unwrap().pts_us, 3);
    }

    #[test]
    fn congestion_resumes_at_a_fresh_keyframe() {
        let mut frames = EncodedFrames::default();
        assert_eq!(frames.push(packet(true, 0)), PushResult::Accepted);
        for pts in 1..MAX_PACKETS {
            assert_eq!(frames.push(packet(false, pts as i64)), PushResult::Accepted);
        }
        assert_eq!(frames.push(packet(false, 10)), PushResult::RequestKeyframe);
        assert!(frames.take().is_none());
        assert_eq!(frames.push(packet(false, 11)), PushResult::Skipped);
        assert_eq!(frames.push(packet(true, 12)), PushResult::Accepted);
        assert_eq!(frames.take().unwrap().pts_us, 12);
    }

    #[test]
    fn rejects_invalid_or_oversized_packets_without_losing_valid_data() {
        let mut frames = EncodedFrames::default();
        assert_eq!(frames.push(packet(true, 1)), PushResult::Accepted);
        let mut invalid = packet(true, 2);
        invalid.data = vec![1, 2, 3];
        assert_eq!(frames.push(invalid), PushResult::Invalid);
        let mut oversized = packet(true, 3);
        oversized.data.resize(MAX_PACKET_BYTES + 1, 0);
        assert_eq!(frames.push(oversized), PushResult::Invalid);
        assert_eq!(frames.take().unwrap().pts_us, 1);
    }
}

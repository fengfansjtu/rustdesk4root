use std::time::{Duration, Instant};

pub(crate) struct VideoFrame {
    data: Vec<u8>,
    ready: bool,
    enabled: bool,
    updated: Instant,
    timeout: Duration,
}

impl VideoFrame {
    pub(crate) fn new(timeout: Duration) -> Self {
        Self {
            data: Vec::new(),
            ready: false,
            enabled: false,
            updated: Instant::now(),
            timeout,
        }
    }

    pub(crate) fn set_enable(&mut self, enabled: bool) {
        self.enabled = enabled;
        self.ready = false;
    }

    pub(crate) fn update(
        &mut self,
        source: &[u8],
        width: usize,
        height: usize,
        stride: usize,
    ) -> bool {
        if !self.enabled || (self.ready && self.updated.elapsed() <= self.timeout) {
            return false;
        }
        let row_bytes = match width.checked_mul(4) {
            Some(bytes) if bytes > 0 && bytes <= stride && height > 0 => bytes,
            _ => return false,
        };
        let size = match row_bytes.checked_mul(height) {
            Some(size) => size,
            None => return false,
        };
        let required = (height - 1)
            .checked_mul(stride)
            .and_then(|offset| offset.checked_add(row_bytes));
        if required.map_or(true, |len| len > source.len()) {
            return false;
        }
        self.data.resize(size, 0);
        if stride == row_bytes {
            self.data.copy_from_slice(&source[..size]);
        } else {
            for (dst, src) in self
                .data
                .chunks_exact_mut(row_bytes)
                .zip(source.chunks(stride))
            {
                dst.copy_from_slice(&src[..row_bytes]);
            }
        }
        self.updated = Instant::now();
        self.ready = true;
        true
    }

    pub(crate) fn take(&mut self, dst: &mut Vec<u8>, last: &mut Vec<u8>) -> Option<()> {
        if !self.enabled || !self.ready {
            return None;
        }
        self.ready = false;
        if self.updated.elapsed() > self.timeout.max(Duration::from_millis(500))
            || self.data == *last
        {
            return None;
        }
        last.clear();
        last.extend_from_slice(&self.data);
        std::mem::swap(dst, &mut self.data);
        Some(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_survives_source_reuse_and_preserves_row_layout() {
        let mut frame = VideoFrame::new(Duration::from_secs(1));
        frame.set_enable(true);
        let mut source = vec![1, 2, 3, 4, 99, 99, 99, 99, 5, 6, 7, 8];
        assert!(frame.update(&source, 1, 2, 8));
        source.fill(0);
        let (mut dst, mut last) = (Vec::new(), Vec::new());
        assert_eq!(frame.take(&mut dst, &mut last), Some(()));
        assert_eq!(dst, vec![1, 2, 3, 4, 5, 6, 7, 8]);
    }

    #[test]
    fn slow_consumer_gets_one_complete_frame_without_backlog() {
        let mut frame = VideoFrame::new(Duration::from_secs(1));
        frame.set_enable(true);
        assert!(frame.update(&[1; 4], 1, 1, 4));
        assert!(!frame.update(&[2; 4], 1, 1, 4));
        let (mut dst, mut last) = (Vec::new(), Vec::new());
        assert_eq!(frame.take(&mut dst, &mut last), Some(()));
        assert_eq!(dst, vec![1; 4]);
        assert!(frame.update(&[3; 4], 1, 1, 4));
        assert_eq!(frame.take(&mut dst, &mut last), Some(()));
        assert_eq!(dst, vec![3; 4]);
        assert_eq!(frame.take(&mut dst, &mut last), None);
    }

    #[test]
    fn rejects_truncated_frames_and_clears_frames_on_stop() {
        let mut frame = VideoFrame::new(Duration::from_secs(1));
        frame.set_enable(true);
        assert!(!frame.update(&[0; 11], 1, 2, 8));
        assert!(frame.update(&[1; 4], 1, 1, 4));
        frame.set_enable(false);
        frame.set_enable(true);
        assert_eq!(frame.take(&mut Vec::new(), &mut Vec::new()), None);
    }
}

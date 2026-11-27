use std::{
    fmt::{Display, Error, Formatter},
    sync::OnceLock,
    time::{Duration, Instant},
};

fn stage_timer_enabled() -> bool {
    static ENABLED: OnceLock<bool> = OnceLock::new();
    *ENABLED.get_or_init(|| std::env::var_os("AEROLITH_STAGE_PROFILE").is_some())
}

/// A timer.
#[derive(Copy, Clone, Debug, Default)]
pub struct Timer {
    time: Duration,
    #[allow(dead_code)] // The field isn’t used if the `profiler` feature isn’t enabled.
    start: Option<Instant>,
}

impl Timer {
    /// Creates a new timer initialized to zero and not started.
    pub fn new() -> Self {
        Timer {
            time: Duration::from_secs(0),
            start: None,
        }
    }

    /// Resets the timer to 0.
    pub fn reset(&mut self) {
        self.time = Duration::from_secs(0)
    }

    /// Start the timer.
    pub fn start(&mut self) {
        if cfg!(feature = "profiler") || stage_timer_enabled() {
            self.time = Duration::from_secs(0);
            self.start = Some(Instant::now());
        }
    }

    /// Pause the timer.
    pub fn pause(&mut self) {
        if cfg!(feature = "profiler") || stage_timer_enabled() {
            if let Some(start) = self.start {
                self.time += Instant::now().duration_since(start);
            }
            self.start = None;
        }
    }

    /// Resume the timer.
    pub fn resume(&mut self) {
        if cfg!(feature = "profiler") || stage_timer_enabled() {
            self.start = Some(Instant::now());
        }
    }

    /// The measured time between the last `.start()` and `.pause()` calls.
    pub fn time(&self) -> Duration {
        self.time
    }

    /// The measured time in milliseconds between the last `.start()` and `.pause()` calls.
    pub fn time_ms(&self) -> f64 {
        self.time.as_secs_f64() * 1000.0
    }
}

impl Display for Timer {
    fn fmt(&self, f: &mut Formatter) -> Result<(), Error> {
        write!(f, "{}ms", self.time_ms())
    }
}

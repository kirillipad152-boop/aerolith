use crate::counters::Timer;
use std::fmt::{Display, Formatter, Result};
use std::sync::{
    atomic::{AtomicU64, Ordering},
    OnceLock,
};

#[doc(hidden)]
pub static AEROLITH_STAGE_ATOMICS: [AtomicU64; 20] = [
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
];

/// Performance counters related to constraints resolution.
#[derive(Default, Clone, Copy)]
pub struct SolverCounters {
    /// Number of constraints generated.
    pub nconstraints: usize,
    /// Number of contacts found.
    pub ncontacts: usize,
    /// Time spent for the resolution of the constraints (force computation).
    pub velocity_resolution_time: Timer,
    /// Time spent for the assembly of all the velocity constraints.
    pub velocity_assembly_time: Timer,
    /// Time spent for the update of the velocity of the bodies.
    pub velocity_update_time: Timer,
    /// Time spent to write force back to user-accessible data.
    pub velocity_writeback_time: Timer,
}

impl SolverCounters {
    /// Whether stage profiling is enabled.
    pub fn aerolith_stage_enabled() -> bool {
        static ENABLED: OnceLock<bool> = OnceLock::new();
        *ENABLED.get_or_init(|| std::env::var_os("AEROLITH_STAGE_PROFILE").is_some())
    }

    /// Increment an atomic stage counter.
    #[inline(always)]
    pub fn aerolith_add(idx: usize, val: u64) {
        AEROLITH_STAGE_ATOMICS[idx].fetch_add(val, Ordering::Relaxed);
    }

    /// Take and reset atomic solver stage counters.
    pub fn aerolith_take_stage_profile() -> [u64; 20] {
        let mut out = [0u64; 20];
        for (slot, dst) in AEROLITH_STAGE_ATOMICS.iter().zip(&mut out) {
            *dst = slot.swap(0, Ordering::Relaxed);
        }
        out
    }

    /// Creates a new counter initialized to zero.
    pub fn new() -> Self {
        SolverCounters {
            nconstraints: 0,
            ncontacts: 0,
            velocity_assembly_time: Timer::new(),
            velocity_resolution_time: Timer::new(),
            velocity_update_time: Timer::new(),
            velocity_writeback_time: Timer::new(),
        }
    }

    /// Reset all the counters to zero.
    pub fn reset(&mut self) {
        self.nconstraints = 0;
        self.ncontacts = 0;
        self.velocity_resolution_time.reset();
        self.velocity_assembly_time.reset();
        self.velocity_update_time.reset();
        self.velocity_writeback_time.reset();
    }
}

impl Display for SolverCounters {
    fn fmt(&self, f: &mut Formatter) -> Result {
        writeln!(f, "Number of contacts: {}", self.ncontacts)?;
        writeln!(f, "Number of constraints: {}", self.nconstraints)?;
        writeln!(f, "Velocity assembly time: {}", self.velocity_assembly_time)?;
        writeln!(
            f,
            "Velocity resolution time: {}",
            self.velocity_resolution_time
        )?;
        writeln!(f, "Velocity update time: {}", self.velocity_update_time)?;
        writeln!(
            f,
            "Velocity writeback time: {}",
            self.velocity_writeback_time
        )
    }
}

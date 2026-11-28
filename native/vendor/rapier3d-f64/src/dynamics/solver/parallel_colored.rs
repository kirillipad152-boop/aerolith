use std::cell::UnsafeCell;
use std::ops::Range;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use crate::dynamics::solver::contact_constraint::ConstraintsCounts;
use crate::dynamics::solver::{InteractionGroups, SolverVel};
use crate::dynamics::{IslandManager, RigidBodyHandle, RigidBodySet};
use crate::geometry::{ContactManifold, ContactManifoldIndex};
use crate::math::{Real, SIMD_WIDTH};

pub(crate) const INTRA_ISLAND_MIN_MANIFOLDS: usize = 32;
pub(crate) const MAX_WORKERS: usize = 16;
const MAX_COLORS: usize = 128;
/// How long a barrier is waited for by spinning, then by yielding the core, before the thread sleeps (in checks).
const SPINS: u32 = 2000;
const YIELDS: u32 = 200;

/// Guards `PhysicsPipeline` so at most one fixed-team barrier runs at a time in the process.
pub(crate) static INTRA_ISLAND_LOCK: AtomicBool = AtomicBool::new(false);

pub(crate) struct IntraIslandGuard(bool);

impl IntraIslandGuard {
    pub fn try_acquire() -> Option<Self> {
        INTRA_ISLAND_LOCK
            .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
            .ok()
            .map(|_| Self(true))
    }
}

impl Drop for IntraIslandGuard {
    fn drop(&mut self) {
        if self.0 {
            INTRA_ISLAND_LOCK.store(false, Ordering::Release);
        }
    }
}

#[derive(Copy, Clone, Debug)]
pub(crate) struct RangeCuts {
    pub start: usize,
    pub end: usize,
    pub cuts: [usize; MAX_WORKERS + 1],
}

impl Default for RangeCuts {
    fn default() -> Self {
        Self {
            start: 0,
            end: 0,
            cuts: [0; MAX_WORKERS + 1],
        }
    }
}

impl RangeCuts {
    #[inline(always)]
    pub fn len(self) -> usize {
        self.end - self.start
    }

    #[inline(always)]
    pub fn is_empty(self) -> bool {
        self.end <= self.start
    }

    /// Initializes sequential cuts (before snapping to multi-constraint manifold boundaries).
    pub fn init_linear(&mut self, start: usize, end: usize, threads: usize) {
        self.start = start;
        self.end = end;
        let len = end - start;
        for t in 0..=threads {
            self.cuts[t] = start + (len * t) / threads;
        }
    }

    /// Snaps worker cuts forward so multiple constraints from the same manifold stay on one thread.
    pub fn snap_boundaries(&mut self, threads: usize, mut same_as_prev: impl FnMut(usize) -> bool) {
        if self.end <= self.start || threads <= 1 {
            self.cuts[0] = self.start;
            self.cuts[1] = self.end;
            return;
        }
        self.cuts[0] = self.start;
        for t in 1..threads {
            let mut cut = self.cuts[t].max(self.cuts[t - 1]);
            while cut > self.cuts[t - 1] && cut < self.end && same_as_prev(cut) {
                cut += 1;
            }
            self.cuts[t] = cut;
        }
        self.cuts[threads] = self.end;
    }

    #[inline(always)]
    pub fn worker_slice(&self, tid: usize, threads: usize, phase: usize) -> Range<usize> {
        if self.end <= self.start {
            return 0..0;
        }
        if threads <= 1 {
            return self.start..self.end;
        }
        let t = (tid + phase) % threads;
        self.cuts[t]..self.cuts[t + 1]
    }
}

#[derive(Copy, Clone, Debug, Default)]
pub(crate) struct RangeIdx {
    pub start: usize,
    pub end: usize,
}

impl RangeIdx {
    #[inline(always)]
    pub fn worker_slice(self, tid: usize, threads: usize, phase: usize) -> Range<usize> {
        let len = self.end - self.start;
        if len == 0 {
            return 0..0;
        }
        let t = (tid + phase) % threads;
        let lo = self.start + (len * t) / threads;
        let hi = self.start + (len * (t + 1)) / threads;
        lo..hi
    }
}

#[derive(Copy, Clone, Debug, Default)]
pub(crate) struct ColorGroup {
    pub two_simd: RangeCuts,
    pub one_simd: RangeCuts,
    pub two_scalar: RangeCuts,
    pub one_scalar: RangeCuts,
}

#[derive(Default)]
pub(crate) struct ColoredPlan {
    pub active: bool,
    pub parallel_colors: Vec<ColorGroup>,
    pub tail: ColorGroup,
    pub two_simd_slots: Vec<u32>,
    pub one_simd_slots: Vec<u32>,
    pub two_scalar_slots: Vec<u32>,
    pub one_scalar_slots: Vec<u32>,
    // Scratch workspaces reused across ticks to avoid heap churn.
    bodies_color: Vec<u128>,
    two_colors: Vec<u8>,
    one_colors: Vec<u8>,
    sorted_two: Vec<usize>,
    sorted_one: Vec<usize>,
    tail_two: Vec<usize>,
    tail_one: Vec<usize>,
    simd_buckets: Vec<([usize; SIMD_WIDTH], usize)>,
    tmp_two_simd: Vec<usize>,
    tmp_two_scalar: Vec<usize>,
    tmp_one_simd: Vec<usize>,
    tmp_one_scalar: Vec<usize>,
}

impl ColoredPlan {
    pub fn clear(&mut self) {
        self.active = false;
        self.parallel_colors.clear();
        self.tail = ColorGroup::default();
    }

    pub fn group_colored_manifolds(
        &mut self,
        island_id: usize,
        islands: &IslandManager,
        bodies: &RigidBodySet,
        manifolds: &[&mut ContactManifold],
        two_body_interactions: &[ContactManifoldIndex],
        one_body_interactions: &[ContactManifoldIndex],
        two_groups: &mut InteractionGroups,
        one_groups: &mut InteractionGroups,
        min_par_constraints: usize,
    ) {
        self.clear();
        two_groups.clear_groups();
        one_groups.clear_groups();

        let num_island_bodies = islands.active_island(island_id).len();
        self.bodies_color.clear();
        self.bodies_color.resize(num_island_bodies, 0u128);
        self.two_colors.clear();
        self.two_colors.reserve(two_body_interactions.len());
        self.one_colors.clear();
        self.one_colors.reserve(one_body_interactions.len());

        let mut two_color_len = [0usize; MAX_COLORS + 1];
        let mut one_color_len = [0usize; MAX_COLORS + 1];

        let mut color_manifold = |manifold_i: usize| -> u8 {
            let m = &manifolds[manifold_i];
            let dyn_offset = |h: Option<RigidBodyHandle>| -> Option<usize> {
                let h = h?;
                let rb = &bodies[h];
                if rb.is_dynamic() && rb.ids.active_set_offset < num_island_bodies {
                    Some(rb.ids.active_set_offset)
                } else {
                    None
                }
            };
            let b1 = dyn_offset(m.data.rigid_body1);
            let b2 = dyn_offset(m.data.rigid_body2);
            let m1 = b1.map_or(0, |i| self.bodies_color[i]);
            let m2 = b2.map_or(0, |i| self.bodies_color[i]);
            let conflict = m1 | m2;
            let free = !conflict;
            if free == 0 {
                return MAX_COLORS as u8;
            }
            let c = free.trailing_zeros() as usize;
            let bit = 1u128 << c;
            if let Some(i) = b1 {
                self.bodies_color[i] |= bit;
            }
            if let Some(i) = b2 {
                self.bodies_color[i] |= bit;
            }
            c as u8
        };

        for &manifold_i in two_body_interactions {
            let c = color_manifold(manifold_i);
            two_color_len[c as usize] += 1;
            self.two_colors.push(c);
        }
        for &manifold_i in one_body_interactions {
            let c = color_manifold(manifold_i);
            one_color_len[c as usize] += 1;
            self.one_colors.push(c);
        }

        let mut two_spans = [0usize; MAX_COLORS + 2];
        let mut one_spans = [0usize; MAX_COLORS + 2];
        for c in 0..=MAX_COLORS {
            two_spans[c + 1] = two_spans[c] + two_color_len[c];
            one_spans[c + 1] = one_spans[c] + one_color_len[c];
        }

        self.sorted_two.resize(two_body_interactions.len(), 0);
        self.sorted_one.resize(one_body_interactions.len(), 0);
        let mut two_write = two_spans;
        for (&manifold_i, &c) in two_body_interactions.iter().zip(self.two_colors.iter()) {
            let ci = c as usize;
            self.sorted_two[two_write[ci]] = manifold_i;
            two_write[ci] += 1;
        }
        let mut one_write = one_spans;
        for (&manifold_i, &c) in one_body_interactions.iter().zip(self.one_colors.iter()) {
            let ci = c as usize;
            self.sorted_one[one_write[ci]] = manifold_i;
            one_write[ci] += 1;
        }

        self.tail_two.clear();
        self.tail_one.clear();

        let mut two_simd_off = 0usize;
        let mut two_scalar_off = 0usize;
        let mut one_simd_off = 0usize;
        let mut one_scalar_off = 0usize;
        let mut total_par_constraints = 0usize;

        for c in 0..MAX_COLORS {
            let two_slice = &self.sorted_two[two_spans[c]..two_spans[c + 1]];
            let one_slice = &self.sorted_one[one_spans[c]..one_spans[c + 1]];
            if two_slice.len() + one_slice.len() < min_par_constraints {
                self.tail_two.extend_from_slice(two_slice);
                self.tail_one.extend_from_slice(one_slice);
                continue;
            }

            let (n_two_simd, n_two_scalar) = Self::pack_color_simd(
                two_slice,
                manifolds,
                &mut self.simd_buckets,
                &mut self.tmp_two_simd,
                &mut self.tmp_two_scalar,
            );
            let (n_one_simd, n_one_scalar) = Self::pack_color_simd(
                one_slice,
                manifolds,
                &mut self.simd_buckets,
                &mut self.tmp_one_simd,
                &mut self.tmp_one_scalar,
            );
            let color_constraints = n_two_simd + n_two_scalar + n_one_simd + n_one_scalar;
            if color_constraints < min_par_constraints {
                self.tail_two.extend_from_slice(two_slice);
                self.tail_one.extend_from_slice(one_slice);
                continue;
            }

            two_groups.simd_interactions.extend_from_slice(&self.tmp_two_simd);
            two_groups.nongrouped_interactions.extend_from_slice(&self.tmp_two_scalar);
            one_groups.simd_interactions.extend_from_slice(&self.tmp_one_simd);
            one_groups.nongrouped_interactions.extend_from_slice(&self.tmp_one_scalar);

            let mut cg = ColorGroup::default();
            cg.two_simd.init_linear(two_simd_off, two_simd_off + n_two_simd, 1);
            cg.one_simd.init_linear(one_simd_off, one_simd_off + n_one_simd, 1);
            cg.two_scalar
                .init_linear(two_scalar_off, two_scalar_off + n_two_scalar, 1);
            cg.one_scalar
                .init_linear(one_scalar_off, one_scalar_off + n_one_scalar, 1);
            self.parallel_colors.push(cg);
            two_simd_off += n_two_simd;
            two_scalar_off += n_two_scalar;
            one_simd_off += n_one_simd;
            one_scalar_off += n_one_scalar;
            total_par_constraints += color_constraints;
        }

        // Overflow (>128 colors) always goes to the sequential tail.
        self.tail_two
            .extend_from_slice(&self.sorted_two[two_spans[MAX_COLORS]..two_spans[MAX_COLORS + 1]]);
        self.tail_one
            .extend_from_slice(&self.sorted_one[one_spans[MAX_COLORS]..one_spans[MAX_COLORS + 1]]);

        let tail_two_simd_start = two_groups.simd_interactions.len() / SIMD_WIDTH;
        let tail_two_scalar_start = two_groups.nongrouped_interactions.len();
        let tail_one_simd_start = one_groups.simd_interactions.len() / SIMD_WIDTH;
        let tail_one_scalar_start = one_groups.nongrouped_interactions.len();

        if !self.tail_two.is_empty() {
            two_groups.group_manifolds(island_id, islands, bodies, manifolds, &self.tail_two);
        }
        if !self.tail_one.is_empty() {
            one_groups.group_manifolds(island_id, islands, bodies, manifolds, &self.tail_one);
        }

        let tail_two_simd_end = two_groups.simd_interactions[tail_two_simd_start * SIMD_WIDTH..]
            .chunks_exact(SIMD_WIDTH)
            .map(|c| ConstraintsCounts::from_contacts(manifolds[c[0]]).num_constraints)
            .sum::<usize>();
        let tail_two_scalar_end = two_groups.nongrouped_interactions[tail_two_scalar_start..]
            .iter()
            .map(|&i| ConstraintsCounts::from_contacts(manifolds[i]).num_constraints)
            .sum::<usize>();
        let tail_one_simd_end = one_groups.simd_interactions[tail_one_simd_start * SIMD_WIDTH..]
            .chunks_exact(SIMD_WIDTH)
            .map(|c| ConstraintsCounts::from_contacts(manifolds[c[0]]).num_constraints)
            .sum::<usize>();
        let tail_one_scalar_end = one_groups.nongrouped_interactions[tail_one_scalar_start..]
            .iter()
            .map(|&i| ConstraintsCounts::from_contacts(manifolds[i]).num_constraints)
            .sum::<usize>();

        let mut tail_group = ColorGroup::default();
        tail_group
            .two_simd
            .init_linear(two_simd_off, two_simd_off + tail_two_simd_end, 1);
        tail_group
            .one_simd
            .init_linear(one_simd_off, one_simd_off + tail_one_simd_end, 1);
        tail_group
            .two_scalar
            .init_linear(two_scalar_off, two_scalar_off + tail_two_scalar_end, 1);
        tail_group
            .one_scalar
            .init_linear(one_scalar_off, one_scalar_off + tail_one_scalar_end, 1);
        self.tail = tail_group;

        self.active = !self.parallel_colors.is_empty() && total_par_constraints >= 16;
    }

    /// Groups already-disjoint manifolds in one color into 4-wide SIMD batches by contact count `k`.
    fn pack_color_simd(
        slice: &[usize],
        manifolds: &[&mut ContactManifold],
        buckets: &mut Vec<([usize; SIMD_WIDTH], usize)>,
        out_simd: &mut Vec<usize>,
        out_scalar: &mut Vec<usize>,
    ) -> (usize, usize) {
        out_simd.clear();
        out_scalar.clear();
        if slice.is_empty() {
            return (0, 0);
        }

        let max_k = slice
            .iter()
            .map(|&i| manifolds[i].data.num_active_contacts())
            .max()
            .unwrap_or(0);
        if max_k == 0 {
            return (0, 0);
        }

        #[cfg(feature = "simd-is-enabled")]
        {
            buckets.clear();
            buckets.resize(max_k + 1, ([0; SIMD_WIDTH], 0));
            let mut simd_constraints = 0usize;
            let mut scalar_constraints = 0usize;

            for &manifold_i in slice {
                let m = &manifolds[manifold_i];
                let k = m.data.num_active_contacts();
                if k == 0 {
                    continue;
                }
                let b = &mut buckets[k];
                b.0[b.1] = manifold_i;
                b.1 += 1;
                if b.1 == SIMD_WIDTH {
                    out_simd.extend_from_slice(&b.0);
                    simd_constraints += ConstraintsCounts::from_contacts(m).num_constraints;
                    b.1 = 0;
                }
            }
            for b in buckets.iter().take(max_k + 1) {
                for &manifold_i in &b.0[..b.1] {
                    out_scalar.push(manifold_i);
                    scalar_constraints +=
                        ConstraintsCounts::from_contacts(manifolds[manifold_i]).num_constraints;
                }
            }
            (simd_constraints, scalar_constraints)
        }

        #[cfg(not(feature = "simd-is-enabled"))]
        {
            let _ = buckets;
            let mut scalar_constraints = 0usize;
            for &manifold_i in slice {
                let m = &manifolds[manifold_i];
                if m.data.num_active_contacts() > 0 {
                    out_scalar.push(manifold_i);
                    scalar_constraints += ConstraintsCounts::from_contacts(m).num_constraints;
                }
            }
            (0, scalar_constraints)
        }
    }
}

#[derive(Copy, Clone)]
pub(crate) struct SyncPtr<T>(*mut T);

unsafe impl<T> Send for SyncPtr<T> {}
unsafe impl<T> Sync for SyncPtr<T> {}

impl<T> SyncPtr<T> {
    #[inline(always)]
    pub fn from_slice(slice: &mut [T]) -> Self {
        Self(slice.as_mut_ptr())
    }

    #[inline(always)]
    pub fn as_ptr(self) -> *mut T {
        self.0
    }

    #[inline(always)]
    pub unsafe fn slice_mut<'a>(self, range: Range<usize>) -> &'a mut [T] {
        if range.is_empty() {
            &mut []
        } else {
            std::slice::from_raw_parts_mut(self.0.add(range.start), range.end - range.start)
        }
    }
}

#[repr(align(64))]
#[derive(Copy, Clone, Default)]
pub(crate) struct WorkerPartial {
    pub changed: Real,
    pub fastest: Real,
}

#[repr(align(64))]
struct AlignedAtomicUsize(AtomicUsize);

#[derive(Copy, Clone)]
pub(crate) enum SweepAction {
    Done,
    Continue { beta: Real, restart: bool, init_p: bool },
}

pub(crate) struct TeamShared<S> {
    pub sweep_action: SweepAction,
    pub stiffen_more: bool,
    pub state: S,
}

pub(crate) struct TeamSync<S> {
    threads: usize,
    arrived: AlignedAtomicUsize,
    release_gen: AlignedAtomicUsize,
    aborted: AtomicBool,
    pub partials: [UnsafeCell<WorkerPartial>; MAX_WORKERS],
    pub shared: UnsafeCell<TeamShared<S>>,
}

unsafe impl<S> Sync for TeamSync<S> {}

impl<S> TeamSync<S> {
    pub fn new(threads: usize, state: S) -> Self {
        debug_assert!(threads >= 1 && threads <= MAX_WORKERS);
        Self {
            threads,
            arrived: AlignedAtomicUsize(AtomicUsize::new(0)),
            release_gen: AlignedAtomicUsize(AtomicUsize::new(0)),
            aborted: AtomicBool::new(false),
            partials: std::array::from_fn(|_| UnsafeCell::new(WorkerPartial::default())),
            shared: UnsafeCell::new(TeamShared {
                sweep_action: SweepAction::Continue {
                    beta: 0.0,
                    restart: false,
                    init_p: true,
                },
                stiffen_more: false,
                state,
            }),
        }
    }

    #[inline(always)]
    pub fn threads(&self) -> usize {
        self.threads
    }

    #[inline(always)]
    pub fn read_shared(&self) -> &TeamShared<S> {
        unsafe { &*self.shared.get() }
    }

    #[inline(always)]
    pub fn set_partial(&self, tid: usize, changed: Real, fastest: Real) {
        unsafe {
            *self.partials[tid].get() = WorkerPartial { changed, fastest };
        }
    }

    pub fn signal_abort(&self) {
        self.aborted.store(true, Ordering::Release);
    }

    /// Blocks until every worker reaches the barrier. The last arrival runs `on_last` cleanly before releasing all threads.
    #[inline(always)]
    pub fn barrier_with(&self, local_gen: &mut usize, on_last: impl FnOnce(&mut TeamShared<S>, &[WorkerPartial])) {
        let next_gen = local_gen.wrapping_add(1);
        *local_gen = next_gen;
        if self.arrived.0.fetch_add(1, Ordering::AcqRel) == self.threads - 1 {
            // SAFETY: every other thread is waiting on `release_gen == next_gen` after writing its own `partials[tid]`.
            let mut partials = [WorkerPartial::default(); MAX_WORKERS];
            for tid in 0..self.threads {
                partials[tid] = unsafe { *self.partials[tid].get() };
            }
            let shared = unsafe { &mut *self.shared.get() };
            on_last(shared, &partials[..self.threads]);
            self.arrived.0.store(0, Ordering::Relaxed);
            self.release_gen.0.store(next_gen, Ordering::Release);
        } else {
            // AEROLITH: a barrier lasts microseconds when every worker has a core, so spin for that. But when the game's own
            // threads (client, server, compiler) hold the cores, the worker everybody waits for is not running, and
            // spinners that never let go of theirs turn a 30 ms step into seconds: give the core back, and then sleep.
            let mut waited = 0u32;
            while self.release_gen.0.load(Ordering::Acquire) != next_gen {
                if self.aborted.load(Ordering::Relaxed) {
                    return;
                }
                waited += 1;
                if waited < SPINS {
                    std::hint::spin_loop();
                } else if waited < SPINS + YIELDS {
                    std::thread::yield_now();
                } else {
                    std::thread::sleep(std::time::Duration::from_micros(30));
                }
            }
        }
    }

    #[inline(always)]
    pub fn barrier(&self, local_gen: &mut usize) {
        self.barrier_with(local_gen, |_, _| {});
    }
}

pub(crate) struct WorkerPanicGuard<'a, S>(pub &'a TeamSync<S>);

impl<S> Drop for WorkerPanicGuard<'_, S> {
    fn drop(&mut self) {
        if std::thread::panicking() {
            self.0.signal_abort();
        }
    }
}

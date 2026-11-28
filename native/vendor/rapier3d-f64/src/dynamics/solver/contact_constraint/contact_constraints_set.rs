use crate::dynamics::solver::categorization::categorize_contacts;
use crate::dynamics::solver::contact_constraint::{
    GenericOneBodyConstraint, GenericOneBodyConstraintBuilder, GenericTwoBodyConstraint,
    GenericTwoBodyConstraintBuilder, OneBodyConstraint, OneBodyConstraintBuilder,
    OneBodyConstraintElement, TwoBodyConstraint, TwoBodyConstraintBuilder,
    TwoBodyConstraintElement,
};
use crate::dynamics::solver::parallel_colored::{
    self, ColorGroup, RangeIdx, SyncPtr, MAX_WORKERS,
};
use crate::dynamics::solver::solver_body::SolverBody;
use crate::dynamics::solver::solver_vel::SolverVel;
use crate::dynamics::solver::{reset_buffer, ConstraintTypes, SolverConstraintsSet};
use crate::dynamics::{
    ImpulseJoint, IntegrationParameters, IslandManager, JointAxesMask, MultibodyJointSet,
    RigidBodySet,
};
use crate::geometry::{ContactManifold, ContactManifoldIndex};
use crate::math::{Real, SimdReal, MAX_MANIFOLD_POINTS};
use crate::utils::SimdRealCopy;
use na::DVector;
use parry::math::DIM;
use simba::simd::{SimdComplexField, SimdPartialOrd, SimdValue};

#[derive(Copy, Clone, Debug)]
pub(crate) struct ImpulseSlot<N: SimdRealCopy> {
    pub old: [N; 3],
    pub p: [N; 3],
    pub k: [N; 3],
}

impl<N: SimdRealCopy> ImpulseSlot<N> {
    #[inline(always)]
    pub(crate) fn init<const D: usize>(now: [N; 3], r: [N; 3]) -> Self {
        let k = [N::splat(1.0) / r[0], r[1], r[2]];
        let zero = N::zero();
        Self {
            old: if D == 1 { [now[0], zero, zero] } else { now },
            p: [zero; 3],
            k,
        }
    }

    #[inline(always)]
    pub(crate) fn measure<const D: usize>(&mut self, now: [N; 3], init_p: bool) -> (N, N) {
        let (mut energy, mut fastest) = (N::zero(), N::zero());
        for idx in 0..D {
            let d = now[idx] - self.old[idx];
            let v = d * self.k[idx];
            energy += d * v;
            fastest = fastest.simd_max(v.simd_abs());
            if init_p {
                self.p[idx] = d;
            }
            // When `init_p`, `advance` is skipped so `old` stays `now`; otherwise `advance` reads `now - old`.
            if init_p {
                self.old[idx] = now[idx];
            }
        }
        (energy, fastest)
    }

    #[inline(always)]
    pub(crate) fn advance_raw<const D: usize>(&mut self, now: [&mut N; 3], beta: N) {
        for idx in 0..D {
            let d = *now[idx] - self.old[idx];
            *now[idx] += self.p[idx] * beta;
            self.p[idx] = self.p[idx] * beta + d;
            self.old[idx] = *now[idx];
        }
    }

    #[inline(always)]
    pub(crate) fn sync_old<const D: usize>(&mut self, now: [N; 3]) {
        for idx in 0..D {
            self.old[idx] = now[idx];
        }
    }
}

/// Non-aliasing disjoint-slice pointers into `ContactConstraintsSet` for parallel intra-island solving.
#[derive(Copy, Clone)]
pub(crate) struct ContactPtrs {
    pub two_scalar: SyncPtr<TwoBodyConstraint>,
    pub two_scalar_len: usize,
    pub one_scalar: SyncPtr<OneBodyConstraint>,
    pub one_scalar_len: usize,
    pub two_scalar_builders: SyncPtr<TwoBodyConstraintBuilder>,
    pub one_scalar_builders: SyncPtr<OneBodyConstraintBuilder>,
    #[cfg(feature = "simd-is-enabled")]
    pub two_simd: SyncPtr<TwoBodyConstraintSimd>,
    #[cfg(feature = "simd-is-enabled")]
    pub two_simd_len: usize,
    #[cfg(feature = "simd-is-enabled")]
    pub one_simd: SyncPtr<OneBodyConstraintSimd>,
    #[cfg(feature = "simd-is-enabled")]
    pub one_simd_len: usize,
    #[cfg(feature = "simd-is-enabled")]
    pub two_simd_builders: SyncPtr<TwoBodyConstraintBuilderSimd>,
    #[cfg(feature = "simd-is-enabled")]
    pub one_simd_builders: SyncPtr<SimdOneBodyConstraintBuilder>,
}

impl ContactPtrs {
    pub unsafe fn update_slice(
        self,
        params: &IntegrationParameters,
        substep_id: usize,
        multibodies: &MultibodyJointSet,
        solver_bodies: &[SolverBody],
        tid: usize,
        threads: usize,
    ) {
        let dt = substep_id as Real * params.dt;
        macro_rules! upd(($builders: ident, $constraints: ident, $len: expr) => {
            let r = RangeIdx { start: 0, end: $len }.worker_slice(tid, threads, 0);
            let bs = std::slice::from_raw_parts(self.$builders.as_ptr().add(r.start), r.end - r.start);
            let cs = self.$constraints.slice_mut(r);
            for (b, c) in bs.iter().zip(cs.iter_mut()) {
                b.update(params, dt, solver_bodies, multibodies, c);
            }
        });
        upd!(two_scalar_builders, two_scalar, self.two_scalar_len);
        upd!(one_scalar_builders, one_scalar, self.one_scalar_len);
        #[cfg(feature = "simd-is-enabled")]
        {
            upd!(two_simd_builders, two_simd, self.two_simd_len);
            upd!(one_simd_builders, one_simd, self.one_simd_len);
        }
    }

    pub unsafe fn warmstart_color(
        self,
        group: ColorGroup,
        tid: usize,
        threads: usize,
        phase: usize,
        vels: SyncPtr<SolverVel<Real>>,
    ) {
        let vptr = vels.as_ptr();
        for c in self.two_scalar.slice_mut(group.two_scalar.worker_slice(tid, threads, phase)) {
            c.warmstart_ptr(vptr);
        }
        for c in self.one_scalar.slice_mut(group.one_scalar.worker_slice(tid, threads, phase)) {
            c.warmstart_ptr(vptr);
        }
        #[cfg(feature = "simd-is-enabled")]
        {
            for c in self.two_simd.slice_mut(group.two_simd.worker_slice(tid, threads, phase)) {
                c.warmstart_ptr(vptr);
            }
            for c in self.one_simd.slice_mut(group.one_simd.worker_slice(tid, threads, phase)) {
                c.warmstart_ptr(vptr);
            }
        }
    }

    pub unsafe fn solve_friction_color(
        self,
        group: ColorGroup,
        tid: usize,
        threads: usize,
        phase: usize,
        vels: SyncPtr<SolverVel<Real>>,
    ) {
        let vptr = vels.as_ptr();
        for c in self.two_scalar.slice_mut(group.two_scalar.worker_slice(tid, threads, phase)) {
            c.solve_ptr(vptr, false, true);
        }
        for c in self.one_scalar.slice_mut(group.one_scalar.worker_slice(tid, threads, phase)) {
            c.solve_ptr(vptr, false, true);
        }
        #[cfg(feature = "simd-is-enabled")]
        {
            for c in self.two_simd.slice_mut(group.two_simd.worker_slice(tid, threads, phase)) {
                c.solve_ptr(vptr, false, true);
            }
            for c in self.one_simd.slice_mut(group.one_simd.worker_slice(tid, threads, phase)) {
                c.solve_ptr(vptr, false, true);
            }
        }
    }

    pub unsafe fn remove_bias_slice(self, tid: usize, threads: usize) {
        for c in self.two_scalar.slice_mut(RangeIdx { start: 0, end: self.two_scalar_len }.worker_slice(tid, threads, 0)) {
            c.remove_cfm_and_bias_from_rhs();
        }
        for c in self.one_scalar.slice_mut(RangeIdx { start: 0, end: self.one_scalar_len }.worker_slice(tid, threads, 0)) {
            c.remove_cfm_and_bias_from_rhs();
        }
        #[cfg(feature = "simd-is-enabled")]
        {
            for c in self.two_simd.slice_mut(RangeIdx { start: 0, end: self.two_simd_len }.worker_slice(tid, threads, 0)) {
                c.remove_cfm_and_bias_from_rhs();
            }
            for c in self.one_simd.slice_mut(RangeIdx { start: 0, end: self.one_simd_len }.worker_slice(tid, threads, 0)) {
                c.remove_cfm_and_bias_from_rhs();
            }
        }
    }

    pub unsafe fn stiffen_all(self) -> bool {
        let mut any = false;
        for c in self.two_scalar.slice_mut(0..self.two_scalar_len) {
            any |= c.stiffen();
        }
        for c in self.one_scalar.slice_mut(0..self.one_scalar_len) {
            any |= c.stiffen();
        }
        #[cfg(feature = "simd-is-enabled")]
        {
            for c in self.two_simd.slice_mut(0..self.two_simd_len) {
                any |= c.stiffen();
            }
            for c in self.one_simd.slice_mut(0..self.one_simd_len) {
                any |= c.stiffen();
            }
        }
        any
    }

    pub unsafe fn init_nncg_slots_slice<const D: usize>(
        self,
        plan: &parallel_colored::ColoredPlan,
        one_two: SyncPtr<ImpulseSlot<Real>>,
        one_one: SyncPtr<ImpulseSlot<Real>>,
        #[cfg(feature = "simd-is-enabled")] four_two: SyncPtr<ImpulseSlot<SimdReal>>,
        #[cfg(feature = "simd-is-enabled")] four_one: SyncPtr<ImpulseSlot<SimdReal>>,
        tid: usize,
        threads: usize,
    ) {
        macro_rules! fill(($cptr: expr, $len: expr, $prefix: expr, $sptr: expr) => {
            let r = RangeIdx { start: 0, end: $len }.worker_slice(tid, threads, 0);
            if !r.is_empty() {
                let s0 = $prefix[r.start] as usize;
                let s1 = $prefix[r.end] as usize;
                let cs = std::slice::from_raw_parts($cptr.as_ptr().add(r.start), r.end - r.start);
                let slots = $sptr.slice_mut(s0..s1);
                let mut at = 0usize;
                for c in cs {
                    let n = c.num_contacts as usize;
                    for (e, dst) in c.elements[..n].iter().zip(&mut slots[at..at + n]) {
                        let [t0, t1] = e.tangent_part.impulse.data.0[0];
                        *dst = ImpulseSlot::init::<D>(
                            [e.normal_part.impulse, t0, t1],
                            [e.normal_part.r, e.tangent_part.r[0], e.tangent_part.r[1]],
                        );
                    }
                    at += n;
                }
            }
        });
        fill!(self.two_scalar, self.two_scalar_len, plan.two_scalar_slots, one_two);
        fill!(self.one_scalar, self.one_scalar_len, plan.one_scalar_slots, one_one);
        #[cfg(feature = "simd-is-enabled")]
        {
            fill!(self.two_simd, self.two_simd_len, plan.two_simd_slots, four_two);
            fill!(self.one_simd, self.one_simd_len, plan.one_simd_slots, four_one);
        }
    }

    pub unsafe fn sweep_color<const D: usize>(
        self,
        plan: &parallel_colored::ColoredPlan,
        group: ColorGroup,
        one_two: SyncPtr<ImpulseSlot<Real>>,
        one_one: SyncPtr<ImpulseSlot<Real>>,
        #[cfg(feature = "simd-is-enabled")] four_two: SyncPtr<ImpulseSlot<SimdReal>>,
        #[cfg(feature = "simd-is-enabled")] four_one: SyncPtr<ImpulseSlot<SimdReal>>,
        tid: usize,
        threads: usize,
        phase: usize,
        vels: SyncPtr<SolverVel<Real>>,
        init_p: bool,
    ) -> (Real, Real) {
        let mut changed = 0.0;
        let mut fastest = 0.0 as Real;
        let vptr = vels.as_ptr();

        let ts_r = group.two_scalar.worker_slice(tid, threads, phase);
        if !ts_r.is_empty() {
            let s = plan.two_scalar_slots[ts_r.start] as usize..plan.two_scalar_slots[ts_r.end] as usize;
            let (c, f) = sweep_scalar_slice::<D>(self.two_scalar.slice_mut(ts_r), one_two.slice_mut(s), vptr, init_p);
            changed += c;
            fastest = fastest.max(f);
        }
        let os_r = group.one_scalar.worker_slice(tid, threads, phase);
        if !os_r.is_empty() {
            let s = plan.one_scalar_slots[os_r.start] as usize..plan.one_scalar_slots[os_r.end] as usize;
            let (c, f) = sweep_one_scalar_slice::<D>(self.one_scalar.slice_mut(os_r), one_one.slice_mut(s), vptr, init_p);
            changed += c;
            fastest = fastest.max(f);
        }
        #[cfg(feature = "simd-is-enabled")]
        {
            let mut wide_changed = SimdReal::splat(0.0);
            let mut wide_fastest = SimdReal::splat(0.0);
            let td_r = group.two_simd.worker_slice(tid, threads, phase);
            if !td_r.is_empty() {
                let s = plan.two_simd_slots[td_r.start] as usize..plan.two_simd_slots[td_r.end] as usize;
                let (c, f) = sweep_two_simd_slice::<D>(self.two_simd.slice_mut(td_r), four_two.slice_mut(s), vptr, init_p);
                wide_changed += c;
                wide_fastest = wide_fastest.simd_max(f);
            }
            let od_r = group.one_simd.worker_slice(tid, threads, phase);
            if !od_r.is_empty() {
                let s = plan.one_simd_slots[od_r.start] as usize..plan.one_simd_slots[od_r.end] as usize;
                let (c, f) = sweep_one_simd_slice::<D>(self.one_simd.slice_mut(od_r), four_one.slice_mut(s), vptr, init_p);
                wide_changed += c;
                wide_fastest = wide_fastest.simd_max(f);
            }
            changed += wide_changed.simd_horizontal_sum();
            fastest = fastest.max(wide_fastest.simd_horizontal_max());
        }
        (changed, fastest)
    }

    pub unsafe fn advance_color<const D: usize>(
        self,
        plan: &parallel_colored::ColoredPlan,
        group: ColorGroup,
        one_two: SyncPtr<ImpulseSlot<Real>>,
        one_one: SyncPtr<ImpulseSlot<Real>>,
        #[cfg(feature = "simd-is-enabled")] four_two: SyncPtr<ImpulseSlot<SimdReal>>,
        #[cfg(feature = "simd-is-enabled")] four_one: SyncPtr<ImpulseSlot<SimdReal>>,
        tid: usize,
        threads: usize,
        phase: usize,
        beta: Real,
        restart: bool,
    ) {
        macro_rules! adv(($cptr: expr, $range: expr, $prefix: expr, $sptr: expr, $b: expr) => {
            let r = $range.worker_slice(tid, threads, phase);
            if !r.is_empty() {
                let s = $prefix[r.start] as usize..$prefix[r.end] as usize;
                let mut at = 0usize;
                let slots = $sptr.slice_mut(s);
                for c in $cptr.slice_mut(r) {
                    let n = c.num_contacts as usize;
                    for (e, slot) in c.elements[..n].iter_mut().zip(&mut slots[at..at + n]) {
                        if restart {
                            let [t0, t1] = e.tangent_part.impulse.data.0[0];
                            slot.sync_old::<D>([e.normal_part.impulse, t0, t1]);
                        } else {
                            let [t0, t1] = &mut e.tangent_part.impulse.data.0[0];
                            slot.advance_raw::<D>([&mut e.normal_part.impulse, t0, t1], $b);
                        }
                    }
                    at += n;
                }
            }
        });
        adv!(self.two_scalar, group.two_scalar, plan.two_scalar_slots, one_two, beta);
        adv!(self.one_scalar, group.one_scalar, plan.one_scalar_slots, one_one, beta);
        #[cfg(feature = "simd-is-enabled")]
        {
            let wide_beta = SimdReal::splat(beta);
            adv!(self.two_simd, group.two_simd, plan.two_simd_slots, four_two, wide_beta);
            adv!(self.one_simd, group.one_simd, plan.one_simd_slots, four_one, wide_beta);
        }
    }
}

#[inline(always)]
unsafe fn sweep_scalar_slice<const D: usize>(
    constraints: &mut [TwoBodyConstraint],
    slots: &mut [ImpulseSlot<Real>],
    vptr: *mut SolverVel<Real>,
    init_p: bool,
) -> (Real, Real) {
    let (mut changed, mut fastest, mut at) = (0.0, 0.0 as Real, 0usize);
    for c in constraints {
        c.solve_ptr(vptr, true, D == 3);
        let n = c.num_contacts as usize;
        for (e, slot) in c.elements[..n].iter().zip(&mut slots[at..at + n]) {
            let [t0, t1] = e.tangent_part.impulse.data.0[0];
            let (ec, fc) = slot.measure::<D>([e.normal_part.impulse, t0, t1], init_p);
            changed += ec;
            fastest = fastest.max(fc);
        }
        at += n;
    }
    (changed, fastest)
}

#[inline(always)]
unsafe fn sweep_one_scalar_slice<const D: usize>(
    constraints: &mut [OneBodyConstraint],
    slots: &mut [ImpulseSlot<Real>],
    vptr: *mut SolverVel<Real>,
    init_p: bool,
) -> (Real, Real) {
    let (mut changed, mut fastest, mut at) = (0.0, 0.0 as Real, 0usize);
    for c in constraints {
        c.solve_ptr(vptr, true, D == 3);
        let n = c.num_contacts as usize;
        for (e, slot) in c.elements[..n].iter().zip(&mut slots[at..at + n]) {
            let [t0, t1] = e.tangent_part.impulse.data.0[0];
            let (ec, fc) = slot.measure::<D>([e.normal_part.impulse, t0, t1], init_p);
            changed += ec;
            fastest = fastest.max(fc);
        }
        at += n;
    }
    (changed, fastest)
}

#[cfg(feature = "simd-is-enabled")]
#[inline(always)]
unsafe fn sweep_two_simd_slice<const D: usize>(
    constraints: &mut [TwoBodyConstraintSimd],
    slots: &mut [ImpulseSlot<SimdReal>],
    vptr: *mut SolverVel<Real>,
    init_p: bool,
) -> (SimdReal, SimdReal) {
    let (mut changed, mut fastest, mut at) = (SimdReal::splat(0.0), SimdReal::splat(0.0), 0usize);
    for c in constraints {
        c.solve_ptr(vptr, true, D == 3);
        let n = c.num_contacts as usize;
        for (e, slot) in c.elements[..n].iter().zip(&mut slots[at..at + n]) {
            let [t0, t1] = e.tangent_part.impulse.data.0[0];
            let (ec, fc) = slot.measure::<D>([e.normal_part.impulse, t0, t1], init_p);
            changed += ec;
            fastest = fastest.simd_max(fc);
        }
        at += n;
    }
    (changed, fastest)
}

#[cfg(feature = "simd-is-enabled")]
#[inline(always)]
unsafe fn sweep_one_simd_slice<const D: usize>(
    constraints: &mut [OneBodyConstraintSimd],
    slots: &mut [ImpulseSlot<SimdReal>],
    vptr: *mut SolverVel<Real>,
    init_p: bool,
) -> (SimdReal, SimdReal) {
    let (mut changed, mut fastest, mut at) = (SimdReal::splat(0.0), SimdReal::splat(0.0), 0usize);
    for c in constraints {
        c.solve_ptr(vptr, true, D == 3);
        let n = c.num_contacts as usize;
        for (e, slot) in c.elements[..n].iter().zip(&mut slots[at..at + n]) {
            let [t0, t1] = e.tangent_part.impulse.data.0[0];
            let (ec, fc) = slot.measure::<D>([e.normal_part.impulse, t0, t1], init_p);
            changed += ec;
            fastest = fastest.simd_max(fc);
        }
        at += n;
    }
    (changed, fastest)
}

const _: usize = MAX_WORKERS;

#[cfg(feature = "simd-is-enabled")]
use {
    crate::dynamics::solver::contact_constraint::{
        OneBodyConstraintSimd, SimdOneBodyConstraintBuilder, TwoBodyConstraintBuilderSimd,
        TwoBodyConstraintSimd,
    },
    crate::math::SIMD_WIDTH,
};

#[derive(Debug)]
pub struct ConstraintsCounts {
    pub num_constraints: usize,
    #[allow(dead_code)] // Keep this around for now. Might be useful once we rework parallelism.
    pub num_jacobian_lines: usize,
}

impl ConstraintsCounts {
    pub fn from_contacts(manifold: &ContactManifold) -> Self {
        let rest = manifold.data.solver_contacts.len() % MAX_MANIFOLD_POINTS != 0;
        Self {
            num_constraints: manifold.data.solver_contacts.len() / MAX_MANIFOLD_POINTS
                + rest as usize,
            num_jacobian_lines: manifold.data.solver_contacts.len() * DIM,
        }
    }

    pub fn from_joint(joint: &ImpulseJoint) -> Self {
        let joint = &joint.data;
        let locked_axes = joint.locked_axes.bits();
        let motor_axes = joint.motor_axes.bits() & !locked_axes;
        let limit_axes = joint.limit_axes.bits() & !locked_axes;
        let coupled_axes = joint.coupled_axes.bits();

        let num_constraints = (motor_axes & !coupled_axes).count_ones() as usize
            + ((motor_axes & coupled_axes) & JointAxesMask::ANG_AXES.bits() != 0) as usize
            + ((motor_axes & coupled_axes) & JointAxesMask::LIN_AXES.bits() != 0) as usize
            + locked_axes.count_ones() as usize
            + (limit_axes & !coupled_axes).count_ones() as usize
            + ((limit_axes & coupled_axes) & JointAxesMask::ANG_AXES.bits() != 0) as usize
            + ((limit_axes & coupled_axes) & JointAxesMask::LIN_AXES.bits() != 0) as usize;
        Self {
            num_constraints,
            num_jacobian_lines: num_constraints,
        }
    }
}

#[derive(Copy, Clone, Debug)]
pub(crate) struct ContactConstraintTypes;

impl ConstraintTypes for ContactConstraintTypes {
    type OneBody = OneBodyConstraint;
    type TwoBodies = TwoBodyConstraint;
    type GenericOneBody = GenericOneBodyConstraint;
    type GenericTwoBodies = GenericTwoBodyConstraint;
    #[cfg(feature = "simd-is-enabled")]
    type SimdOneBody = OneBodyConstraintSimd;
    #[cfg(feature = "simd-is-enabled")]
    type SimdTwoBodies = TwoBodyConstraintSimd;

    type BuilderOneBody = OneBodyConstraintBuilder;
    type BuilderTwoBodies = TwoBodyConstraintBuilder;
    type GenericBuilderOneBody = GenericOneBodyConstraintBuilder;
    type GenericBuilderTwoBodies = GenericTwoBodyConstraintBuilder;
    #[cfg(feature = "simd-is-enabled")]
    type SimdBuilderOneBody = SimdOneBodyConstraintBuilder;
    #[cfg(feature = "simd-is-enabled")]
    type SimdBuilderTwoBodies = TwoBodyConstraintBuilderSimd;
}

pub type ContactConstraintsSet = SolverConstraintsSet<ContactConstraintTypes>;

impl ContactConstraintsSet {
    pub fn init_constraint_groups(
        &mut self,
        island_id: usize,
        islands: &IslandManager,
        bodies: &RigidBodySet,
        multibody_joints: &MultibodyJointSet,
        manifolds: &[&mut ContactManifold],
        manifold_indices: &[ContactManifoldIndex],
    ) {
        self.two_body_interactions.clear();
        self.one_body_interactions.clear();
        self.generic_two_body_interactions.clear();
        self.generic_one_body_interactions.clear();

        categorize_contacts(
            bodies,
            multibody_joints,
            manifolds,
            manifold_indices,
            &mut self.one_body_interactions,
            &mut self.two_body_interactions,
            &mut self.generic_one_body_interactions,
            &mut self.generic_two_body_interactions,
        );

        self.interaction_groups.clear_groups();
        self.interaction_groups.group_manifolds(
            island_id,
            islands,
            bodies,
            manifolds,
            &self.two_body_interactions,
        );

        self.one_body_interaction_groups.clear_groups();
        self.one_body_interaction_groups.group_manifolds(
            island_id,
            islands,
            bodies,
            manifolds,
            &self.one_body_interactions,
        );
    }

    pub fn init(
        &mut self,
        island_id: usize,
        islands: &IslandManager,
        bodies: &RigidBodySet,
        multibody_joints: &MultibodyJointSet,
        manifolds: &[&mut ContactManifold],
        manifold_indices: &[ContactManifoldIndex],
    ) {
        self.clear_constraints();
        self.clear_builders();
        self.color_plan.clear();

        self.init_constraint_groups(
            island_id,
            islands,
            bodies,
            multibody_joints,
            manifolds,
            manifold_indices,
        );

        self.build_all_constraints(bodies, multibody_joints, manifolds);
    }

    pub fn init_colored(
        &mut self,
        island_id: usize,
        islands: &IslandManager,
        bodies: &RigidBodySet,
        multibody_joints: &MultibodyJointSet,
        manifolds: &[&mut ContactManifold],
        manifold_indices: &[ContactManifoldIndex],
        min_par_constraints: usize,
    ) {
        self.clear_constraints();
        self.clear_builders();

        self.two_body_interactions.clear();
        self.one_body_interactions.clear();
        self.generic_two_body_interactions.clear();
        self.generic_one_body_interactions.clear();

        categorize_contacts(
            bodies,
            multibody_joints,
            manifolds,
            manifold_indices,
            &mut self.one_body_interactions,
            &mut self.two_body_interactions,
            &mut self.generic_one_body_interactions,
            &mut self.generic_two_body_interactions,
        );

        self.color_plan.group_colored_manifolds(
            island_id,
            islands,
            bodies,
            manifolds,
            &self.two_body_interactions,
            &self.one_body_interactions,
            &mut self.interaction_groups,
            &mut self.one_body_interaction_groups,
            min_par_constraints,
        );

        self.build_all_constraints(bodies, multibody_joints, manifolds);

        if self.color_plan.active {
            Self::fill_prefix(&self.velocity_constraints, &mut self.color_plan.two_scalar_slots);
            Self::fill_prefix(
                &self.velocity_one_body_constraints,
                &mut self.color_plan.one_scalar_slots,
            );
            #[cfg(feature = "simd-is-enabled")]
            {
                Self::fill_prefix(
                    &self.simd_velocity_constraints,
                    &mut self.color_plan.two_simd_slots,
                );
                Self::fill_prefix(
                    &self.simd_velocity_one_body_constraints,
                    &mut self.color_plan.one_simd_slots,
                );
            }
            let threads = rayon::current_num_threads().min(MAX_WORKERS);
            for cg in &mut self.color_plan.parallel_colors {
                let (s, e) = (cg.two_scalar.start, cg.two_scalar.end);
                cg.two_scalar.init_linear(s, e, threads);
                cg.two_scalar.snap_boundaries(threads, |i| {
                    self.velocity_constraints[i].manifold_id
                        == self.velocity_constraints[i - 1].manifold_id
                });
                let (s, e) = (cg.one_scalar.start, cg.one_scalar.end);
                cg.one_scalar.init_linear(s, e, threads);
                cg.one_scalar.snap_boundaries(threads, |i| {
                    self.velocity_one_body_constraints[i].manifold_id
                        == self.velocity_one_body_constraints[i - 1].manifold_id
                });
                #[cfg(feature = "simd-is-enabled")]
                {
                    let (s, e) = (cg.two_simd.start, cg.two_simd.end);
                    cg.two_simd.init_linear(s, e, threads);
                    cg.two_simd.snap_boundaries(threads, |i| {
                        self.simd_velocity_constraints[i].manifold_id
                            == self.simd_velocity_constraints[i - 1].manifold_id
                    });
                    let (s, e) = (cg.one_simd.start, cg.one_simd.end);
                    cg.one_simd.init_linear(s, e, threads);
                    cg.one_simd.snap_boundaries(threads, |i| {
                        self.simd_velocity_one_body_constraints[i].manifold_id
                            == self.simd_velocity_one_body_constraints[i - 1].manifold_id
                    });
                }
            }
            #[cfg(debug_assertions)]
            self.assert_colors_disjoint();
        }
    }

    #[cfg(debug_assertions)]
    fn assert_colors_disjoint(&self) {
        let mut seen = std::collections::HashSet::new();
        for (ci, group) in self.color_plan.parallel_colors.iter().enumerate() {
            seen.clear();
            let mut prev_mid = usize::MAX;
            for (i, c) in self.velocity_constraints[group.two_scalar.start..group.two_scalar.end]
                .iter()
                .enumerate()
            {
                if c.manifold_id == prev_mid {
                    continue;
                }
                prev_mid = c.manifold_id;
                assert!(seen.insert(c.solver_vel1), "dup two_scalar v1 color={ci} idx={i} v1={}", c.solver_vel1);
                assert!(seen.insert(c.solver_vel2), "dup two_scalar v2 color={ci} idx={i} v2={}", c.solver_vel2);
            }
            let mut prev_mid = usize::MAX;
            for c in &self.velocity_one_body_constraints[group.one_scalar.start..group.one_scalar.end] {
                if c.manifold_id == prev_mid {
                    continue;
                }
                prev_mid = c.manifold_id;
                assert!(seen.insert(c.solver_vel2), "dup one_scalar v2");
            }
            #[cfg(feature = "simd-is-enabled")]
            {
                let mut prev_mid = [usize::MAX; crate::math::SIMD_WIDTH];
                for c in &self.simd_velocity_constraints[group.two_simd.start..group.two_simd.end] {
                    if c.manifold_id == prev_mid {
                        continue;
                    }
                    prev_mid = c.manifold_id;
                    for &v in c.solver_vel1.iter().chain(c.solver_vel2.iter()) {
                        assert!(seen.insert(v), "dup two_simd v");
                    }
                }
                let mut prev_mid = [usize::MAX; crate::math::SIMD_WIDTH];
                for c in &self.simd_velocity_one_body_constraints[group.one_simd.start..group.one_simd.end] {
                    if c.manifold_id == prev_mid {
                        continue;
                    }
                    prev_mid = c.manifold_id;
                    for &v in &c.solver_vel2 {
                        assert!(seen.insert(v), "dup one_simd v");
                    }
                }
            }
        }
    }

    fn fill_prefix<T: ContactCount>(src: &[T], out: &mut Vec<u32>) {
        out.clear();
        out.reserve(src.len() + 1);
        let mut acc = 0u32;
        out.push(0);
        for c in src {
            acc += c.num_contacts();
            out.push(acc);
        }
    }

    fn build_all_constraints(
        &mut self,
        bodies: &RigidBodySet,
        multibody_joints: &MultibodyJointSet,
        manifolds: &[&mut ContactManifold],
    ) {
        let mut jacobian_id = 0;

        #[cfg(feature = "simd-is-enabled")]
        {
            self.simd_compute_constraints(bodies, manifolds);
        }
        self.compute_constraints(bodies, manifolds);
        self.compute_generic_constraints(bodies, multibody_joints, manifolds, &mut jacobian_id);

        #[cfg(feature = "simd-is-enabled")]
        {
            self.simd_compute_one_body_constraints(bodies, manifolds);
        }
        self.compute_one_body_constraints(bodies, manifolds);
        self.compute_generic_one_body_constraints(
            bodies,
            multibody_joints,
            manifolds,
            &mut jacobian_id,
        );
    }

    pub fn can_run_parallel(&self) -> bool {
        self.color_plan.active
            && self.generic_velocity_constraints.is_empty()
            && self.generic_velocity_one_body_constraints.is_empty()
    }

    pub fn ptrs(&mut self) -> ContactPtrs {
        ContactPtrs {
            two_scalar_len: self.velocity_constraints.len(),
            two_scalar: SyncPtr::from_slice(&mut self.velocity_constraints),
            one_scalar_len: self.velocity_one_body_constraints.len(),
            one_scalar: SyncPtr::from_slice(&mut self.velocity_one_body_constraints),
            two_scalar_builders: SyncPtr::from_slice(&mut self.velocity_constraints_builder),
            one_scalar_builders: SyncPtr::from_slice(&mut self.velocity_one_body_constraints_builder),
            #[cfg(feature = "simd-is-enabled")]
            two_simd_len: self.simd_velocity_constraints.len(),
            #[cfg(feature = "simd-is-enabled")]
            two_simd: SyncPtr::from_slice(&mut self.simd_velocity_constraints),
            #[cfg(feature = "simd-is-enabled")]
            one_simd_len: self.simd_velocity_one_body_constraints.len(),
            #[cfg(feature = "simd-is-enabled")]
            one_simd: SyncPtr::from_slice(&mut self.simd_velocity_one_body_constraints),
            #[cfg(feature = "simd-is-enabled")]
            two_simd_builders: SyncPtr::from_slice(&mut self.simd_velocity_constraints_builder),
            #[cfg(feature = "simd-is-enabled")]
            one_simd_builders: SyncPtr::from_slice(
                &mut self.simd_velocity_one_body_constraints_builder,
            ),
        }
    }

    #[cfg(feature = "simd-is-enabled")]
    fn simd_compute_constraints(
        &mut self,
        bodies: &RigidBodySet,
        manifolds_all: &[&mut ContactManifold],
    ) {
        let total_num_constraints = self
            .interaction_groups
            .simd_interactions
            .chunks_exact(SIMD_WIDTH)
            .map(|i| ConstraintsCounts::from_contacts(manifolds_all[i[0]]).num_constraints)
            .sum();

        unsafe {
            reset_buffer(
                &mut self.simd_velocity_constraints_builder,
                total_num_constraints,
            );
            reset_buffer(&mut self.simd_velocity_constraints, total_num_constraints);
        }

        let mut curr_start = 0;

        for manifolds_i in self
            .interaction_groups
            .simd_interactions
            .chunks_exact(SIMD_WIDTH)
        {
            let num_to_add =
                ConstraintsCounts::from_contacts(manifolds_all[manifolds_i[0]]).num_constraints;
            let manifold_id = gather![|ii| manifolds_i[ii]];
            let manifolds = gather![|ii| &*manifolds_all[manifolds_i[ii]]];

            TwoBodyConstraintBuilderSimd::generate(
                manifold_id,
                manifolds,
                bodies,
                &mut self.simd_velocity_constraints_builder[curr_start..],
                &mut self.simd_velocity_constraints[curr_start..],
            );

            curr_start += num_to_add;
        }

        assert_eq!(curr_start, total_num_constraints);
    }

    fn compute_constraints(
        &mut self,
        bodies: &RigidBodySet,
        manifolds_all: &[&mut ContactManifold],
    ) {
        let total_num_constraints = self
            .interaction_groups
            .nongrouped_interactions
            .iter()
            .map(|i| ConstraintsCounts::from_contacts(manifolds_all[*i]).num_constraints)
            .sum();

        unsafe {
            reset_buffer(
                &mut self.velocity_constraints_builder,
                total_num_constraints,
            );
            reset_buffer(&mut self.velocity_constraints, total_num_constraints);
        }

        let mut curr_start = 0;

        for manifold_i in &self.interaction_groups.nongrouped_interactions {
            let manifold = &manifolds_all[*manifold_i];
            let num_to_add = ConstraintsCounts::from_contacts(manifold).num_constraints;

            TwoBodyConstraintBuilder::generate(
                *manifold_i,
                manifold,
                bodies,
                &mut self.velocity_constraints_builder[curr_start..],
                &mut self.velocity_constraints[curr_start..],
            );

            curr_start += num_to_add;
        }
        assert_eq!(curr_start, total_num_constraints);
    }

    fn compute_generic_constraints(
        &mut self,
        bodies: &RigidBodySet,
        multibody_joints: &MultibodyJointSet,
        manifolds_all: &[&mut ContactManifold],
        jacobian_id: &mut usize,
    ) {
        let total_num_constraints = self
            .generic_two_body_interactions
            .iter()
            .map(|i| ConstraintsCounts::from_contacts(manifolds_all[*i]).num_constraints)
            .sum();

        self.generic_velocity_constraints_builder.resize(
            total_num_constraints,
            GenericTwoBodyConstraintBuilder::invalid(),
        );
        self.generic_velocity_constraints
            .resize(total_num_constraints, GenericTwoBodyConstraint::invalid());

        let mut curr_start = 0;

        for manifold_i in &self.generic_two_body_interactions {
            let manifold = &manifolds_all[*manifold_i];
            let num_to_add = ConstraintsCounts::from_contacts(manifold).num_constraints;

            GenericTwoBodyConstraintBuilder::generate(
                *manifold_i,
                manifold,
                bodies,
                multibody_joints,
                &mut self.generic_velocity_constraints_builder[curr_start..],
                &mut self.generic_velocity_constraints[curr_start..],
                &mut self.generic_jacobians,
                jacobian_id,
            );

            curr_start += num_to_add;
        }

        assert_eq!(curr_start, total_num_constraints);
    }

    fn compute_generic_one_body_constraints(
        &mut self,
        bodies: &RigidBodySet,
        multibody_joints: &MultibodyJointSet,
        manifolds_all: &[&mut ContactManifold],
        jacobian_id: &mut usize,
    ) {
        let total_num_constraints = self
            .generic_one_body_interactions
            .iter()
            .map(|i| ConstraintsCounts::from_contacts(manifolds_all[*i]).num_constraints)
            .sum();
        self.generic_velocity_one_body_constraints_builder.resize(
            total_num_constraints,
            GenericOneBodyConstraintBuilder::invalid(),
        );
        self.generic_velocity_one_body_constraints
            .resize(total_num_constraints, GenericOneBodyConstraint::invalid());

        let mut curr_start = 0;

        for manifold_i in &self.generic_one_body_interactions {
            let manifold = &manifolds_all[*manifold_i];
            let num_to_add = ConstraintsCounts::from_contacts(manifold).num_constraints;

            GenericOneBodyConstraintBuilder::generate(
                *manifold_i,
                manifold,
                bodies,
                multibody_joints,
                &mut self.generic_velocity_one_body_constraints_builder[curr_start..],
                &mut self.generic_velocity_one_body_constraints[curr_start..],
                &mut self.generic_jacobians,
                jacobian_id,
            );

            curr_start += num_to_add;
        }
        assert_eq!(curr_start, total_num_constraints);
    }

    #[cfg(feature = "simd-is-enabled")]
    fn simd_compute_one_body_constraints(
        &mut self,
        bodies: &RigidBodySet,
        manifolds_all: &[&mut ContactManifold],
    ) {
        let total_num_constraints = self
            .one_body_interaction_groups
            .simd_interactions
            .chunks_exact(SIMD_WIDTH)
            .map(|i| ConstraintsCounts::from_contacts(manifolds_all[i[0]]).num_constraints)
            .sum();

        unsafe {
            reset_buffer(
                &mut self.simd_velocity_one_body_constraints_builder,
                total_num_constraints,
            );
            reset_buffer(
                &mut self.simd_velocity_one_body_constraints,
                total_num_constraints,
            );
        }

        let mut curr_start = 0;

        for manifolds_i in self
            .one_body_interaction_groups
            .simd_interactions
            .chunks_exact(SIMD_WIDTH)
        {
            let num_to_add =
                ConstraintsCounts::from_contacts(manifolds_all[manifolds_i[0]]).num_constraints;
            let manifold_id = gather![|ii| manifolds_i[ii]];
            let manifolds = gather![|ii| &*manifolds_all[manifolds_i[ii]]];
            SimdOneBodyConstraintBuilder::generate(
                manifold_id,
                manifolds,
                bodies,
                &mut self.simd_velocity_one_body_constraints_builder[curr_start..],
                &mut self.simd_velocity_one_body_constraints[curr_start..],
            );
            curr_start += num_to_add;
        }
        assert_eq!(curr_start, total_num_constraints);
    }

    fn compute_one_body_constraints(
        &mut self,
        bodies: &RigidBodySet,
        manifolds_all: &[&mut ContactManifold],
    ) {
        let total_num_constraints = self
            .one_body_interaction_groups
            .nongrouped_interactions
            .iter()
            .map(|i| ConstraintsCounts::from_contacts(manifolds_all[*i]).num_constraints)
            .sum();

        unsafe {
            reset_buffer(
                &mut self.velocity_one_body_constraints_builder,
                total_num_constraints,
            );
            reset_buffer(
                &mut self.velocity_one_body_constraints,
                total_num_constraints,
            );
        }

        let mut curr_start = 0;

        for manifold_i in &self.one_body_interaction_groups.nongrouped_interactions {
            let manifold = &manifolds_all[*manifold_i];
            let num_to_add = ConstraintsCounts::from_contacts(manifold).num_constraints;

            OneBodyConstraintBuilder::generate(
                *manifold_i,
                manifold,
                bodies,
                &mut self.velocity_one_body_constraints_builder[curr_start..],
                &mut self.velocity_one_body_constraints[curr_start..],
            );

            curr_start += num_to_add;
        }
        assert_eq!(curr_start, total_num_constraints);
    }

    pub fn warmstart(
        &mut self,
        solver_vels: &mut [SolverVel<Real>],
        generic_solver_vels: &mut DVector<Real>,
    ) {
        let (jac, constraints) = self.iter_constraints_mut();
        for mut c in constraints {
            c.warmstart(jac, solver_vels, generic_solver_vels);
        }
    }

    #[profiling::function]
    pub fn solve_restitution(
        &mut self,
        solver_vels: &mut [SolverVel<Real>],
        generic_solver_vels: &mut DVector<Real>,
    ) {
        // AEROLITH: same order as iter_constraints_mut, without dispatching an enum for each constraint on every sweep.
        self.velocity_constraints.iter_mut().for_each(|c| c.solve(solver_vels, true, false));
        self.generic_velocity_constraints.iter_mut().for_each(|c| {
            c.solve(&self.generic_jacobians, solver_vels, generic_solver_vels, true, false)
        });
        #[cfg(feature = "simd-is-enabled")]
        self.simd_velocity_constraints.iter_mut().for_each(|c| c.solve(solver_vels, true, false));
        self.velocity_one_body_constraints.iter_mut().for_each(|c| c.solve(solver_vels, true, false));
        self.generic_velocity_one_body_constraints.iter_mut().for_each(|c| {
            c.solve(&self.generic_jacobians, generic_solver_vels, true, false)
        });
        #[cfg(feature = "simd-is-enabled")]
        self.simd_velocity_one_body_constraints.iter_mut().for_each(|c| c.solve(solver_vels, true, false));
    }

    #[profiling::function]
    pub fn solve_restitution_wo_bias(
        &mut self,
        solver_vels: &mut [SolverVel<Real>],
        generic_solver_vels: &mut DVector<Real>,
    ) {
        let (jac, constraints) = self.iter_constraints_mut();
        for mut c in constraints {
            c.remove_bias();
            c.solve_restitution(jac, solver_vels, generic_solver_vels);
        }
    }

    #[profiling::function]
    pub fn solve_friction(
        &mut self,
        solver_vels: &mut [SolverVel<Real>],
        generic_solver_vels: &mut DVector<Real>,
    ) {
        let (jac, constraints) = self.iter_constraints_mut();
        for mut c in constraints {
            c.solve_friction(jac, solver_vels, generic_solver_vels);
        }
    }

    /// AEROLITH: fits the stiffness of every contact to what it turned out to carry; true if some got much stiffer.
    pub fn stiffen(&mut self) -> bool {
        let mut any = false;
        self.velocity_constraints.iter_mut().for_each(|c| any |= c.stiffen());
        self.velocity_one_body_constraints.iter_mut().for_each(|c| any |= c.stiffen());
        #[cfg(feature = "simd-is-enabled")]
        {
            self.simd_velocity_constraints.iter_mut().for_each(|c| any |= c.stiffen());
            self.simd_velocity_one_body_constraints.iter_mut().for_each(|c| any |= c.stiffen());
        }
        any
    }

    /// AEROLITH: initializes NNCG impulse slots aligned with contact constraint order.
    pub fn init_nncg_slots<const D: usize>(
        &self,
        one: &mut Vec<ImpulseSlot<Real>>,
        four: &mut Vec<ImpulseSlot<SimdReal>>,
    ) {
        one.clear();
        four.clear();
        macro_rules! push_slots(($constraints: ident, $dst: ident) => {
            for c in &self.$constraints {
                for e in &c.elements[..c.num_contacts as usize] {
                    let [t0, t1] = e.tangent_part.impulse.data.0[0];
                    $dst.push(ImpulseSlot::init::<D>(
                        [e.normal_part.impulse, t0, t1],
                        [e.normal_part.r, e.tangent_part.r[0], e.tangent_part.r[1]],
                    ));
                }
            }
        });
        push_slots!(velocity_constraints, one);
        push_slots!(velocity_one_body_constraints, one);
        #[cfg(feature = "simd-is-enabled")]
        {
            push_slots!(simd_velocity_constraints, four);
            push_slots!(simd_velocity_one_body_constraints, four);
        }
    }

    /// AEROLITH: sweeps all contact constraints and measures residual energy and max velocity change in one pass.
    pub fn sweep_and_measure<const D: usize>(
        &mut self,
        solver_vels: &mut [SolverVel<Real>],
        one: &mut [ImpulseSlot<Real>],
        four: &mut [ImpulseSlot<SimdReal>],
        init_p: bool,
    ) -> (Real, Real) {
        let one_split = self
            .velocity_constraints
            .iter()
            .map(|c| c.num_contacts as usize)
            .sum::<usize>();
        let (one_two, one_one) = one.split_at_mut(one_split);

        let mut changed = 0.0;
        let mut fastest = 0.0 as Real;
        let mut wide_changed = SimdReal::splat(0.0);
        let mut wide_fastest = SimdReal::splat(0.0);

        let mut at = 0usize;
        for c in &mut self.velocity_constraints {
            c.solve(solver_vels, true, D == 3);
            let n = c.num_contacts as usize;
            for (e, slot) in c.elements[..n].iter().zip(&mut one_two[at..at + n]) {
                let [t0, t1] = e.tangent_part.impulse.data.0[0];
                let (ec, fc) = slot.measure::<D>([e.normal_part.impulse, t0, t1], init_p);
                changed += ec;
                fastest = fastest.max(fc);
            }
            at += n;
        }
        if D == 1 && !self.generic_velocity_constraints.is_empty() {
            for c in &mut self.generic_velocity_constraints {
                c.solve(&self.generic_jacobians, solver_vels, &mut DVector::zeros(0), true, false);
            }
        }

        let mut at = 0usize;
        for c in &mut self.velocity_one_body_constraints {
            c.solve(solver_vels, true, D == 3);
            let n = c.num_contacts as usize;
            for (e, slot) in c.elements[..n].iter().zip(&mut one_one[at..at + n]) {
                let [t0, t1] = e.tangent_part.impulse.data.0[0];
                let (ec, fc) = slot.measure::<D>([e.normal_part.impulse, t0, t1], init_p);
                changed += ec;
                fastest = fastest.max(fc);
            }
            at += n;
        }
        if D == 1 && !self.generic_velocity_one_body_constraints.is_empty() {
            for c in &mut self.generic_velocity_one_body_constraints {
                c.solve(&self.generic_jacobians, &mut DVector::zeros(0), true, false);
            }
        }

        #[cfg(feature = "simd-is-enabled")]
        {
            let four_split = self
                .simd_velocity_constraints
                .iter()
                .map(|c| c.num_contacts as usize)
                .sum::<usize>();
            let (four_two, four_one) = four.split_at_mut(four_split);

            let mut at = 0usize;
            for c in &mut self.simd_velocity_constraints {
                c.solve(solver_vels, true, D == 3);
                let n = c.num_contacts as usize;
                for (e, slot) in c.elements[..n].iter().zip(&mut four_two[at..at + n]) {
                    let [t0, t1] = e.tangent_part.impulse.data.0[0];
                    let (ec, fc) = slot.measure::<D>([e.normal_part.impulse, t0, t1], init_p);
                    wide_changed += ec;
                    wide_fastest = wide_fastest.simd_max(fc);
                }
                at += n;
            }

            let mut at = 0usize;
            for c in &mut self.simd_velocity_one_body_constraints {
                c.solve(solver_vels, true, D == 3);
                let n = c.num_contacts as usize;
                for (e, slot) in c.elements[..n].iter().zip(&mut four_one[at..at + n]) {
                    let [t0, t1] = e.tangent_part.impulse.data.0[0];
                    let (ec, fc) = slot.measure::<D>([e.normal_part.impulse, t0, t1], init_p);
                    wide_changed += ec;
                    wide_fastest = wide_fastest.simd_max(fc);
                }
                at += n;
            }
        }

        changed += wide_changed.simd_horizontal_sum();
        fastest = fastest.max(wide_fastest.simd_horizontal_max());
        (changed, fastest)
    }

    /// AEROLITH: advances impulse search directions and extrapolates impulses (or syncs `old` on restart).
    pub fn advance_nncg<const D: usize>(
        &mut self,
        one: &mut [ImpulseSlot<Real>],
        four: &mut [ImpulseSlot<SimdReal>],
        beta: Real,
        restart: bool,
    ) {
        let one_split = self
            .velocity_constraints
            .iter()
            .map(|c| c.num_contacts as usize)
            .sum::<usize>();
        let (one_two, one_one) = one.split_at_mut(one_split);

        macro_rules! step_slice(($constraints: ident, $slots: ident, $b: expr) => {
            let mut at = 0usize;
            for c in &mut self.$constraints {
                let n = c.num_contacts as usize;
                for (e, slot) in c.elements[..n].iter_mut().zip(&mut $slots[at..at + n]) {
                    if restart {
                        let [t0, t1] = e.tangent_part.impulse.data.0[0];
                        slot.sync_old::<D>([e.normal_part.impulse, t0, t1]);
                    } else {
                        let [t0, t1] = &mut e.tangent_part.impulse.data.0[0];
                        slot.advance_raw::<D>([&mut e.normal_part.impulse, t0, t1], $b);
                    }
                }
                at += n;
            }
        });
        step_slice!(velocity_constraints, one_two, beta);
        step_slice!(velocity_one_body_constraints, one_one, beta);
        #[cfg(feature = "simd-is-enabled")]
        {
            let wide_beta = SimdReal::splat(beta);
            let four_split = self
                .simd_velocity_constraints
                .iter()
                .map(|c| c.num_contacts as usize)
                .sum::<usize>();
            let (four_two, four_one) = four.split_at_mut(four_split);
            step_slice!(simd_velocity_constraints, four_two, wide_beta);
            step_slice!(simd_velocity_one_body_constraints, four_one, wide_beta);
        }
    }

    /// AEROLITH: the non-penetration and then the friction of one constraint after another.
    pub fn sweep(&mut self, solver_vels: &mut [SolverVel<Real>]) {
        self.velocity_constraints.iter_mut().for_each(|c| c.solve(solver_vels, true, true));
        #[cfg(feature = "simd-is-enabled")]
        self.simd_velocity_constraints.iter_mut().for_each(|c| c.solve(solver_vels, true, true));
        self.velocity_one_body_constraints.iter_mut().for_each(|c| c.solve(solver_vels, true, true));
        #[cfg(feature = "simd-is-enabled")]
        self.simd_velocity_one_body_constraints.iter_mut().for_each(|c| c.solve(solver_vels, true, true));
    }

    /// AEROLITH: from here on they are rigid and push nothing out.
    pub fn remove_bias(&mut self) {
        let (_, constraints) = self.iter_constraints_mut();
        for mut c in constraints {
            c.remove_bias();
        }
    }

    pub fn writeback_impulses(&mut self, manifolds_all: &mut [&mut ContactManifold]) {
        let (_, constraints) = self.iter_constraints_mut();
        for mut c in constraints {
            c.writeback_impulses(manifolds_all);
        }
    }

    #[profiling::function]
    pub fn update(
        &mut self,
        params: &IntegrationParameters,
        small_step_id: usize,
        multibodies: &MultibodyJointSet,
        solver_bodies: &[SolverBody],
    ) {
        macro_rules! update_contacts(
            ($builders: ident, $constraints: ident) => {
                for (builder, constraint) in self.$builders.iter().zip(self.$constraints.iter_mut()) {
                    builder.update(
                        &params,
                        small_step_id as Real * params.dt,
                        solver_bodies,
                        multibodies,
                        constraint,
                    );
                }
            }
        );

        update_contacts!(
            generic_velocity_constraints_builder,
            generic_velocity_constraints
        );
        update_contacts!(velocity_constraints_builder, velocity_constraints);
        #[cfg(feature = "simd-is-enabled")]
        update_contacts!(simd_velocity_constraints_builder, simd_velocity_constraints);

        update_contacts!(
            generic_velocity_one_body_constraints_builder,
            generic_velocity_one_body_constraints
        );
        update_contacts!(
            velocity_one_body_constraints_builder,
            velocity_one_body_constraints
        );
        #[cfg(feature = "simd-is-enabled")]
        update_contacts!(
            simd_velocity_one_body_constraints_builder,
            simd_velocity_one_body_constraints
        );
    }
}

trait ContactCount {
    fn num_contacts(&self) -> u32;
}
impl ContactCount for TwoBodyConstraint {
    fn num_contacts(&self) -> u32 {
        self.num_contacts as u32
    }
}
impl ContactCount for OneBodyConstraint {
    fn num_contacts(&self) -> u32 {
        self.num_contacts as u32
    }
}
#[cfg(feature = "simd-is-enabled")]
impl ContactCount for TwoBodyConstraintSimd {
    fn num_contacts(&self) -> u32 {
        self.num_contacts as u32
    }
}
#[cfg(feature = "simd-is-enabled")]
impl ContactCount for OneBodyConstraintSimd {
    fn num_contacts(&self) -> u32 {
        self.num_contacts as u32
    }
}

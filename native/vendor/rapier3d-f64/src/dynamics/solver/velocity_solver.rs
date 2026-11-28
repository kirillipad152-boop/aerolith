use super::contact_constraint::{ContactPtrs, ImpulseSlot};
use super::parallel_colored::{
    RangeIdx, SweepAction, SyncPtr, TeamSync, WorkerPanicGuard, MAX_WORKERS,
};
use super::{JointConstraintTypes, SolverConstraintsSet};
use crate::dynamics::solver::solver_body::SolverBody;
use crate::dynamics::{
    solver::{ContactConstraintTypes, SolverVel},
    IntegrationParameters, IslandManager, JointGraphEdge, JointIndex, MultibodyJointSet,
    MultibodyLinkId, RigidBodySet,
};
use crate::geometry::{ContactManifold, ContactManifoldIndex};
use crate::math::{Real, SimdReal};
use crate::prelude::RigidBodyVelocity;
use crate::utils::{SimdAngularInertia, SimdRealCopy};
use simba::simd::{SimdComplexField, SimdPartialOrd, SimdValue};
use na::DVector;

pub(crate) struct VelocitySolver {
    pub solver_bodies: Vec<SolverBody>,
    pub solver_vels: Vec<SolverVel<Real>>,
    pub solver_vels_increment: Vec<SolverVel<Real>>,
    pub generic_solver_vels: DVector<Real>,
    pub generic_solver_vels_increment: DVector<Real>,
    pub multibody_roots: Vec<MultibodyLinkId>,
    accelerator: Accelerator,
}

/// AEROLITH: nonsmooth nonlinear conjugate gradients over Gauss-Seidel sweeps (Silcowitz, Niebe, Erleben 2010). The sweeps
/// alone carry the weight of a heavy body through a light one in about as many of them as the one is heavier; this takes
/// the root of that, and ends in the same impulses.
#[derive(Default)]
struct Accelerator {
    one: Vec<ImpulseSlot<Real>>,
    four: Vec<ImpulseSlot<SimdReal>>,
    vels: Vec<SolverVel<Real>>,
    vel_direction: Vec<SolverVel<Real>>,
}

impl Accelerator {
    /// Sweeps at least once, and then until a sweep changes the relative velocity at no contact by more than `tolerance`.
    fn run<const D: usize>(
        &mut self,
        most: usize,
        tolerance: Real,
        contacts: &mut SolverConstraintsSet<ContactConstraintTypes>,
        vels: &mut [SolverVel<Real>],
    ) {
        contacts.init_nncg_slots::<D>(&mut self.one, &mut self.four);
        self.vels.clear();
        self.vels.extend_from_slice(vels);
        self.vel_direction.clear();
        let mut has_p = false;
        let mut last = Real::MAX;
        for i in 1..=most {
            let (changed, fastest) =
                contacts.sweep_and_measure::<D>(vels, &mut self.one, &mut self.four, !has_p);
            if i == most || fastest <= tolerance {
                return;
            }
            if !has_p {
                // `sweep_and_measure` already initialized `slot.p = d` and `slot.old = now`.
                has_p = true;
                last = changed;
                self.init_vel_direction(vels);
            } else if changed > last {
                has_p = false;
                last = changed;
                // Sync `slot.old = now` so next sweep sees `d = imp_{i+1} - imp_i`.
                contacts.advance_nncg::<D>(&mut self.one, &mut self.four, 0.0, true);
                self.vels.copy_from_slice(vels);
            } else {
                let beta = changed / last;
                last = changed;
                contacts.advance_nncg::<D>(&mut self.one, &mut self.four, beta, false);
                self.advance_vel_direction(vels, beta);
            }
        }
    }

    #[inline(always)]
    fn init_vel_direction(&mut self, vels: &[SolverVel<Real>]) {
        if self.vel_direction.len() != vels.len() {
            self.vel_direction.resize(vels.len(), SolverVel::zero());
        }
        for ((v, old), p) in vels.iter().zip(&mut self.vels).zip(&mut self.vel_direction) {
            *p = *v - *old;
            *old = *v;
        }
    }

    #[inline(always)]
    fn advance_vel_direction(&mut self, vels: &mut [SolverVel<Real>], beta: Real) {
        for ((v, old), p) in vels.iter_mut().zip(&mut self.vels).zip(&mut self.vel_direction) {
            let d = *v - *old;
            v.linear += p.linear * beta;
            v.angular += p.angular * beta;
            p.linear = p.linear * beta + d.linear;
            p.angular = p.angular * beta + d.angular;
            *old = *v;
        }
    }
}

struct ParallelAccelCtx<'a> {
    cptrs: ContactPtrs,
    plan: &'a super::parallel_colored::ColoredPlan,
    one_two: SyncPtr<ImpulseSlot<Real>>,
    one_one: SyncPtr<ImpulseSlot<Real>>,
    #[cfg(feature = "simd-is-enabled")]
    four_two: SyncPtr<ImpulseSlot<SimdReal>>,
    #[cfg(feature = "simd-is-enabled")]
    four_one: SyncPtr<ImpulseSlot<SimdReal>>,
}

struct ParallelCoordState {
    vels: SyncPtr<SolverVel<Real>>,
    vels_incr: SyncPtr<SolverVel<Real>>,
    vels_len: usize,
    bodies: SyncPtr<SolverBody>,
    accel_vels: SyncPtr<SolverVel<Real>>,
    accel_vel_dir: SyncPtr<SolverVel<Real>>,
    has_p: bool,
    last: Real,
    restarts: usize,
}

impl ParallelCoordState {
    #[inline(always)]
    unsafe fn vels_mut(&self) -> &mut [SolverVel<Real>] {
        self.vels.slice_mut(0..self.vels_len)
    }

    #[inline(always)]
    unsafe fn init_vel_direction(&self) {
        let vels = self.vels_mut();
        let old = self.accel_vels.slice_mut(0..self.vels_len);
        let dir = self.accel_vel_dir.slice_mut(0..self.vels_len);
        for ((v, o), p) in vels.iter().zip(old.iter_mut()).zip(dir.iter_mut()) {
            *p = *v - *o;
            *o = *v;
        }
    }

    #[inline(always)]
    unsafe fn advance_vel_direction(&self, beta: Real) {
        let vels = self.vels_mut();
        let old = self.accel_vels.slice_mut(0..self.vels_len);
        let dir = self.accel_vel_dir.slice_mut(0..self.vels_len);
        for ((v, o), p) in vels.iter_mut().zip(old.iter_mut()).zip(dir.iter_mut()) {
            let d = *v - *o;
            v.linear += p.linear * beta;
            v.angular += p.angular * beta;
            p.linear = p.linear * beta + d.linear;
            p.angular = p.angular * beta + d.angular;
            *o = *v;
        }
    }
}

#[inline(always)]
fn zero_slot<N: SimdRealCopy>() -> ImpulseSlot<N> {
    let z = N::zero();
    ImpulseSlot {
        old: [z; 3],
        p: [z; 3],
        k: [z; 3],
    }
}

fn integrate_body_slice(
    dt: Real,
    solver_vels: &[SolverVel<Real>],
    solver_bodies: &mut [SolverBody],
) {
    for (solver_vels, solver_body) in solver_vels.iter().zip(solver_bodies.iter_mut()) {
        let linvel = solver_vels.linear;
        let angvel = solver_body.sqrt_ii.transform_vector(solver_vels.angular);

        let mut new_vels = RigidBodyVelocity { linvel, angvel };
        new_vels = new_vels.apply_damping(dt, &solver_body.damping);
        let new_pos = new_vels.integrate(dt, &solver_body.position, &solver_body.local_com);
        solver_body.integrated_vels += new_vels;
        solver_body.position = new_pos;
        solver_body.world_com = new_pos * solver_body.local_com;
    }
}

impl VelocitySolver {
    pub fn new() -> Self {
        Self {
            solver_bodies: Vec::new(),
            solver_vels: Vec::new(),
            solver_vels_increment: Vec::new(),
            generic_solver_vels: DVector::zeros(0),
            generic_solver_vels_increment: DVector::zeros(0),
            multibody_roots: Vec::new(),
            accelerator: Accelerator::default(),
        }
    }

    pub fn init_constraints_colored(
        &self,
        island_id: usize,
        islands: &IslandManager,
        bodies: &mut RigidBodySet,
        multibodies: &mut MultibodyJointSet,
        manifolds_all: &mut [&mut ContactManifold],
        manifold_indices: &[ContactManifoldIndex],
        joints_all: &mut [JointGraphEdge],
        joint_indices: &[JointIndex],
        contact_constraints: &mut SolverConstraintsSet<ContactConstraintTypes>,
        joint_constraints: &mut SolverConstraintsSet<JointConstraintTypes>,
        min_par_constraints: usize,
    ) {
        contact_constraints.init_colored(
            island_id,
            islands,
            bodies,
            multibodies,
            manifolds_all,
            manifold_indices,
            min_par_constraints,
        );

        joint_constraints.init(
            island_id,
            islands,
            bodies,
            multibodies,
            joints_all,
            joint_indices,
        );
    }

    pub fn init_constraints(
        &self,
        island_id: usize,
        islands: &IslandManager,
        bodies: &mut RigidBodySet,
        multibodies: &mut MultibodyJointSet,
        manifolds_all: &mut [&mut ContactManifold],
        manifold_indices: &[ContactManifoldIndex],
        joints_all: &mut [JointGraphEdge],
        joint_indices: &[JointIndex],
        contact_constraints: &mut SolverConstraintsSet<ContactConstraintTypes>,
        joint_constraints: &mut SolverConstraintsSet<JointConstraintTypes>,
    ) {
        contact_constraints.init(
            island_id,
            islands,
            bodies,
            multibodies,
            manifolds_all,
            manifold_indices,
        );

        joint_constraints.init(
            island_id,
            islands,
            bodies,
            multibodies,
            joints_all,
            joint_indices,
        );
    }

    pub fn init_solver_velocities_and_solver_bodies(
        &mut self,
        params: &IntegrationParameters,
        island_id: usize,
        islands: &IslandManager,
        bodies: &mut RigidBodySet,
        multibodies: &mut MultibodyJointSet,
    ) {
        self.multibody_roots.clear();
        self.solver_bodies.clear();
        self.solver_bodies.resize(
            islands.active_island(island_id).len(),
            SolverBody::default(),
        );

        self.solver_vels_increment.clear();
        self.solver_vels_increment
            .resize(islands.active_island(island_id).len(), SolverVel::zero());
        self.solver_vels.clear();
        self.solver_vels
            .resize(islands.active_island(island_id).len(), SolverVel::zero());

        /*
         * Initialize solver bodies and delta-velocities (`solver_vels_increment`) with external forces (gravity etc):
         * NOTE: we compute this only once by neglecting changes of mass matrices.
         */

        // Assign solver ids to multibodies, and collect the relevant roots.
        // And init solver_vels for rigidb-bodies.
        let mut multibody_solver_id = 0;
        for handle in islands.active_island(island_id) {
            if let Some(link) = multibodies.rigid_body_link(*handle).copied() {
                let multibody = multibodies
                    .get_multibody_mut_internal(link.multibody)
                    .unwrap();

                if link.id == 0 || link.id == 1 && !multibody.root_is_dynamic {
                    multibody.solver_id = multibody_solver_id;
                    multibody_solver_id += multibody.ndofs();
                    self.multibody_roots.push(link);
                }
            } else {
                let rb = &bodies[*handle];
                let solver_vel = &mut self.solver_vels[rb.ids.active_set_offset];
                let solver_vel_incr = &mut self.solver_vels_increment[rb.ids.active_set_offset];
                let solver_body = &mut self.solver_bodies[rb.ids.active_set_offset];
                solver_body.copy_from(rb);

                // NOTE: `dvel.angular` is actually storing angular velocity delta multiplied
                //       by the square root of the inertia tensor:
                solver_vel_incr.angular =
                    rb.mprops.effective_world_inv_inertia_sqrt * rb.forces.torque * params.dt;
                solver_vel_incr.linear =
                    rb.forces.force.component_mul(&rb.mprops.effective_inv_mass) * params.dt;

                solver_vel.linear = rb.vels.linvel;
                // PERF: can we avoid the call to effective_angular_inertia_sqrt?
                solver_vel.angular = rb.mprops.effective_angular_inertia_sqrt() * rb.vels.angvel;
            }
        }

        // PERF: don’t reallocate at each iteration.
        self.generic_solver_vels_increment = DVector::zeros(multibody_solver_id);
        self.generic_solver_vels = DVector::zeros(multibody_solver_id);

        // init solver_vels for multibodies.
        for link in &self.multibody_roots {
            let multibody = multibodies
                .get_multibody_mut_internal(link.multibody)
                .unwrap();
            multibody.update_dynamics(params.dt, bodies);
            multibody.update_acceleration(bodies);

            let mut solver_vels_incr = self
                .generic_solver_vels_increment
                .rows_mut(multibody.solver_id, multibody.ndofs());
            let mut solver_vels = self
                .generic_solver_vels
                .rows_mut(multibody.solver_id, multibody.ndofs());

            solver_vels_incr.axpy(params.dt, &multibody.accelerations, 0.0);
            solver_vels.copy_from(&multibody.velocities);
        }
    }

    #[profiling::function]
    pub fn solve_constraints_parallel(
        &mut self,
        params: &IntegrationParameters,
        num_substeps: usize,
        bodies: &mut RigidBodySet,
        multibodies: &mut MultibodyJointSet,
        contact_constraints: &mut SolverConstraintsSet<ContactConstraintTypes>,
        joint_constraints: &mut SolverConstraintsSet<JointConstraintTypes>,
    ) {
        let no_joints = joint_constraints.two_body_interactions.is_empty()
            && joint_constraints.one_body_interactions.is_empty()
            && joint_constraints.generic_two_body_interactions.is_empty()
            && joint_constraints.generic_one_body_interactions.is_empty();
        let threads = rayon::current_num_threads().min(MAX_WORKERS);
        if threads < 2
            || !self.multibody_roots.is_empty()
            || !no_joints
            || !contact_constraints.can_run_parallel()
        {
            return self.solve_constraints(
                params,
                num_substeps,
                bodies,
                multibodies,
                contact_constraints,
                joint_constraints,
            );
        }

        let one_split = *contact_constraints.color_plan.two_scalar_slots.last().unwrap_or(&0) as usize;
        let one_total =
            one_split + *contact_constraints.color_plan.one_scalar_slots.last().unwrap_or(&0) as usize;
        self.accelerator.one.resize(one_total, zero_slot());
        #[cfg(feature = "simd-is-enabled")]
        let (four_split, four_total) = {
            let s = *contact_constraints.color_plan.two_simd_slots.last().unwrap_or(&0) as usize;
            let t = s + *contact_constraints.color_plan.one_simd_slots.last().unwrap_or(&0) as usize;
            self.accelerator.four.resize(t, zero_slot());
            (s, t)
        };
        #[cfg(feature = "simd-is-enabled")]
        let _ = four_total;

        let vels_len = self.solver_vels.len();
        self.accelerator.vels.resize(vels_len, SolverVel::zero());
        self.accelerator.vel_direction.resize(vels_len, SolverVel::zero());

        let cptrs = contact_constraints.ptrs();
        let plan = &contact_constraints.color_plan;
        let (one_two_slice, one_one_slice) = self.accelerator.one.split_at_mut(one_split);
        let one_two = SyncPtr::from_slice(one_two_slice);
        let one_one = SyncPtr::from_slice(one_one_slice);
        #[cfg(feature = "simd-is-enabled")]
        let (four_two, four_one) = {
            let (a, b) = self.accelerator.four.split_at_mut(four_split);
            (SyncPtr::from_slice(a), SyncPtr::from_slice(b))
        };
        let actx = ParallelAccelCtx {
            cptrs,
            plan,
            one_two,
            one_one,
            #[cfg(feature = "simd-is-enabled")]
            four_two,
            #[cfg(feature = "simd-is-enabled")]
            four_one,
        };
        let coord = ParallelCoordState {
            vels: SyncPtr::from_slice(&mut self.solver_vels),
            vels_incr: SyncPtr::from_slice(&mut self.solver_vels_increment),
            vels_len,
            bodies: SyncPtr::from_slice(&mut self.solver_bodies),
            accel_vels: SyncPtr::from_slice(&mut self.accelerator.vels),
            accel_vel_dir: SyncPtr::from_slice(&mut self.accelerator.vel_direction),
            has_p: false,
            last: Real::MAX,
            restarts: 0,
        };
        let sync = TeamSync::new(threads, coord);
        let empty_multibodies = MultibodyJointSet::new();
        let most = params.max_internal_iterations;
        let tol = params.internal_velocity_tolerance;

        let run_worker = |tid: usize| {
            let _guard = WorkerPanicGuard(&sync);
            let mut gen = 0usize;
            let num_par = plan.parallel_colors.len();

            for substep_id in 0..num_substeps {
                let is_last_substep = substep_id + 1 == num_substeps;

                // Update constraints across threads (reads solver_bodies only); last arrival adds solver_vels_increment.
                unsafe {
                    let sb = std::slice::from_raw_parts(
                        sync.read_shared().state.bodies.as_ptr(),
                        sync.read_shared().state.vels_len,
                    );
                    cptrs.update_slice(params, substep_id, &empty_multibodies, sb, tid, threads);
                }
                sync.barrier_with(&mut gen, |shared, _| {
                    unsafe {
                        let s = &shared.state;
                        let vels = s.vels.slice_mut(0..s.vels_len);
                        let incr = s.vels_incr.slice_mut(0..s.vels_len);
                        for (v, i) in vels.iter_mut().zip(incr.iter()) {
                            v.linear += i.linear;
                            v.angular += i.angular;
                        }
                    }
                    shared.stiffen_more = false;
                });

                if params.warmstart_coefficient != 0.0 {
                    let vptr = sync.read_shared().state.vels;
                    for c_idx in 0..num_par {
                        unsafe {
                            cptrs.warmstart_color(plan.parallel_colors[c_idx], tid, threads, c_idx, vptr);
                        }
                        if c_idx + 1 < num_par {
                            sync.barrier(&mut gen);
                        }
                    }
                    sync.barrier_with(&mut gen, |shared, _| unsafe {
                        cptrs.warmstart_color(plan.tail, 0, 1, 0, shared.state.vels);
                    });
                }

                for _pass in 0..4 {
                    unsafe {
                        run_accel_parallel::<3>(
                            &sync,
                            &actx,
                            tid,
                            threads,
                            most,
                            tol,
                            true,
                            &mut gen,
                        );
                    }
                    if !sync.read_shared().stiffen_more {
                        break;
                    }
                }

                if is_last_substep && params.num_additional_friction_iterations > 0 {
                    for _ in 0..params.num_additional_friction_iterations {
                        run_friction_parallel(&sync, &actx, tid, threads, &mut gen);
                    }
                }

                // Integrate positions and remove bias concurrently on disjoint worker slices.
                let r_b = RangeIdx { start: 0, end: vels_len }.worker_slice(tid, threads, 0);
                unsafe {
                    let s = &sync.read_shared().state;
                    let v_slice = s.vels.slice_mut(r_b.clone());
                    let b_slice = s.bodies.slice_mut(r_b);
                    integrate_body_slice(params.dt, v_slice, b_slice);
                }
                if params.num_internal_stabilization_iterations > 0 {
                    unsafe {
                        cptrs.remove_bias_slice(tid, threads);
                    }
                }
                sync.barrier(&mut gen);

                if params.num_internal_stabilization_iterations > 0 {
                    unsafe {
                        run_accel_parallel::<1>(
                            &sync,
                            &actx,
                            tid,
                            threads,
                            most,
                            tol,
                            false,
                            &mut gen,
                        );
                    }
                    run_friction_parallel(&sync, &actx, tid, threads, &mut gen);
                }
            }
        };

        if threads == 1 {
            run_worker(0);
        } else {
            rayon::scope(|s| {
                for tid in 1..threads {
                    s.spawn(move |_| {
                        enable_flush_to_zero!();
                        run_worker(tid);
                    });
                }
                enable_flush_to_zero!();
                run_worker(0);
            });
        }
    }

    #[profiling::function]
    pub fn solve_constraints(
        &mut self,
        params: &IntegrationParameters,
        num_substeps: usize,
        bodies: &mut RigidBodySet,
        multibodies: &mut MultibodyJointSet,
        contact_constraints: &mut SolverConstraintsSet<ContactConstraintTypes>,
        joint_constraints: &mut SolverConstraintsSet<JointConstraintTypes>,
    ) {
        for substep_id in 0..num_substeps {
            let is_last_substep = substep_id == num_substeps - 1;

            for (solver_vels, incr) in self
                .solver_vels
                .iter_mut()
                .zip(self.solver_vels_increment.iter())
            {
                solver_vels.linear += incr.linear;
                solver_vels.angular += incr.angular;
            }

            self.generic_solver_vels += &self.generic_solver_vels_increment;

            /*
             * Update & solve constraints.
             */
            joint_constraints.update(params, multibodies, &self.solver_bodies);
            contact_constraints.update(params, substep_id, multibodies, &self.solver_bodies);

            if params.warmstart_coefficient != 0.0 {
                contact_constraints.warmstart(&mut self.solver_vels, &mut self.generic_solver_vels);
            }

            for _ in 0..params.num_internal_pgs_iterations {
                joint_constraints.solve(&mut self.solver_vels, &mut self.generic_solver_vels);
            }
            for _ in 0..4 {
                self.accelerator.run::<3>(
                    params.max_internal_iterations,
                    params.internal_velocity_tolerance,
                    contact_constraints,
                    &mut self.solver_vels,
                );
                // An impact is carried by contacts that had nothing to carry before.
                if !contact_constraints.stiffen() {
                    break;
                }
            }

            if is_last_substep {
                for _ in 0..params.num_additional_friction_iterations {
                    contact_constraints
                        .solve_friction(&mut self.solver_vels, &mut self.generic_solver_vels);
                }
            }

            /*
             * Integrate positions.
             */
            self.integrate_positions(params, is_last_substep, bodies, multibodies);

            /*
             * Resolution without bias.
             */
            if params.num_internal_stabilization_iterations > 0 {
                for _ in 0..params.num_internal_stabilization_iterations {
                    joint_constraints
                        .solve_wo_bias(&mut self.solver_vels, &mut self.generic_solver_vels);
                }
                contact_constraints.remove_bias();
                // The unbiased sweeps solve the normal impulse only; friction is handled below.
                self.accelerator.run::<1>(
                    params.max_internal_iterations,
                    params.internal_velocity_tolerance,
                    contact_constraints,
                    &mut self.solver_vels,
                );

                contact_constraints
                    .solve_friction(&mut self.solver_vels, &mut self.generic_solver_vels);
            }
        }
    }

    #[profiling::function]
    pub fn integrate_positions(
        &mut self,
        params: &IntegrationParameters,
        is_last_substep: bool,
        bodies: &mut RigidBodySet,
        multibodies: &mut MultibodyJointSet,
    ) {
        integrate_body_slice(params.dt, &self.solver_vels, &mut self.solver_bodies);

        // Integrate multibody positions.
        for link in &self.multibody_roots {
            let multibody = multibodies
                .get_multibody_mut_internal(link.multibody)
                .unwrap();
            let solver_vels = self
                .generic_solver_vels
                .rows(multibody.solver_id, multibody.ndofs());
            multibody.velocities.copy_from(&solver_vels);
            multibody.integrate(params.dt);
            // PERF: don’t write back to the rigid-body poses `bodies` before the last step?
            multibody.forward_kinematics(bodies, false);
            multibody.update_rigid_bodies_internal(bodies, !is_last_substep, true, false);

            if !is_last_substep {
                // These are very expensive and not needed if we don’t
                // have to run another step.
                multibody.update_dynamics(params.dt, bodies);
                multibody.update_acceleration(bodies);

                let mut solver_vels_incr = self
                    .generic_solver_vels_increment
                    .rows_mut(multibody.solver_id, multibody.ndofs());
                solver_vels_incr.axpy(params.dt, &multibody.accelerations, 0.0);
            }
        }
    }

    pub fn writeback_bodies(
        &mut self,
        params: &IntegrationParameters,
        num_substeps: usize,
        islands: &IslandManager,
        island_id: usize,
        bodies: &mut RigidBodySet,
        multibodies: &mut MultibodyJointSet,
    ) {
        for handle in islands.active_island(island_id) {
            let link = if self.multibody_roots.is_empty() {
                None
            } else {
                multibodies.rigid_body_link(*handle).copied()
            };

            if let Some(link) = link {
                let multibody = multibodies
                    .get_multibody_mut_internal(link.multibody)
                    .unwrap();

                if link.id == 0 || link.id == 1 && !multibody.root_is_dynamic {
                    let solver_vels = self
                        .generic_solver_vels
                        .rows(multibody.solver_id, multibody.ndofs());
                    multibody.velocities.copy_from(&solver_vels);
                }
            } else {
                let rb = bodies.index_mut_internal(*handle);
                let solver_body = &self.solver_bodies[rb.ids.active_set_offset];
                let solver_vels = &self.solver_vels[rb.ids.active_set_offset];

                let dangvel = solver_body.sqrt_ii.transform_vector(solver_vels.angular);

                let mut new_vels = RigidBodyVelocity {
                    linvel: solver_vels.linear,
                    angvel: dangvel,
                };
                new_vels = new_vels.apply_damping(params.dt, &solver_body.damping);

                // NOTE: using integrated_vels instead of interpolation is interesting for
                //       high angular velocities. However, it is a bit inexact due to the
                //       solver integrating at intermediate sub-steps. Should we just switch
                //       to interpolation?
                rb.integrated_vels.linvel =
                    solver_body.integrated_vels.linvel / num_substeps as Real;
                rb.integrated_vels.angvel =
                    solver_body.integrated_vels.angvel / num_substeps as Real;
                rb.vels = new_vels;
                rb.pos.next_position = solver_body.position;
            }
        }
    }
}

fn run_friction_parallel(
    sync: &TeamSync<ParallelCoordState>,
    actx: &ParallelAccelCtx<'_>,
    tid: usize,
    threads: usize,
    gen: &mut usize,
) {
    let plan = actx.plan;
    let num_par = plan.parallel_colors.len();
    let vptr = sync.read_shared().state.vels;
    for c_idx in 0..num_par {
        unsafe {
            actx.cptrs
                .solve_friction_color(plan.parallel_colors[c_idx], tid, threads, c_idx, vptr);
        }
        if c_idx + 1 < num_par {
            sync.barrier(gen);
        }
    }
    sync.barrier_with(gen, |shared, _| unsafe {
        actx.cptrs.solve_friction_color(plan.tail, 0, 1, 0, shared.state.vels);
    });
}

unsafe fn run_accel_parallel<const D: usize>(
    sync: &TeamSync<ParallelCoordState>,
    actx: &ParallelAccelCtx<'_>,
    tid: usize,
    threads: usize,
    most: usize,
    tol: Real,
    do_stiffen: bool,
    gen: &mut usize,
) {
    let plan = actx.plan;
    let num_par = plan.parallel_colors.len();
    #[cfg(feature = "simd-is-enabled")]
    actx.cptrs.init_nncg_slots_slice::<D>(
        plan,
        actx.one_two,
        actx.one_one,
        actx.four_two,
        actx.four_one,
        tid,
        threads,
    );
    #[cfg(not(feature = "simd-is-enabled"))]
    actx.cptrs.init_nncg_slots_slice::<D>(plan, actx.one_two, actx.one_one, tid, threads);

    sync.barrier_with(gen, |shared, _| {
        let s = &mut shared.state;
        s.has_p = false;
        s.last = Real::MAX;
        s.restarts = 0;
        let vels = s.vels.slice_mut(0..s.vels_len);
        s.accel_vels.slice_mut(0..s.vels_len).copy_from_slice(vels);
        shared.sweep_action = SweepAction::Continue {
            beta: 0.0,
            restart: false,
            init_p: true,
        };
    });

    for iter in 1..=most {
        let action = sync.read_shared().sweep_action;
        let (beta, restart, init_p) = match action {
            SweepAction::Done => return,
            SweepAction::Continue { beta, restart, init_p } => (beta, restart, init_p),
        };

        if iter > 1 && (beta != 0.0 || restart) {
            for (c_idx, &group) in plan.parallel_colors.iter().enumerate() {
                #[cfg(feature = "simd-is-enabled")]
                actx.cptrs.advance_color::<D>(
                    plan,
                    group,
                    actx.one_two,
                    actx.one_one,
                    actx.four_two,
                    actx.four_one,
                    tid,
                    threads,
                    c_idx,
                    beta,
                    restart,
                );
                #[cfg(not(feature = "simd-is-enabled"))]
                actx.cptrs.advance_color::<D>(
                    plan,
                    group,
                    actx.one_two,
                    actx.one_one,
                    tid,
                    threads,
                    c_idx,
                    beta,
                    restart,
                );
            }
        }

        let vptr = sync.read_shared().state.vels;
        let (mut local_c, mut local_f) = (0.0, 0.0 as Real);
        for (c_idx, &group) in plan.parallel_colors.iter().enumerate() {
            #[cfg(feature = "simd-is-enabled")]
            let (cc, ff) = actx.cptrs.sweep_color::<D>(
                plan,
                group,
                actx.one_two,
                actx.one_one,
                actx.four_two,
                actx.four_one,
                tid,
                threads,
                c_idx,
                vptr,
                init_p,
            );
            #[cfg(not(feature = "simd-is-enabled"))]
            let (cc, ff) = actx.cptrs.sweep_color::<D>(
                plan,
                group,
                actx.one_two,
                actx.one_one,
                tid,
                threads,
                c_idx,
                vptr,
                init_p,
            );
            local_c += cc;
            local_f = local_f.max(ff);
            if c_idx + 1 < num_par {
                sync.barrier(gen);
            }
        }

        sync.set_partial(tid, local_c, local_f);
        sync.barrier_with(gen, |shared, partials| {
            #[cfg(feature = "simd-is-enabled")]
            let (mut changed, mut fastest) = actx.cptrs.sweep_color::<D>(
                plan,
                plan.tail,
                actx.one_two,
                actx.one_one,
                actx.four_two,
                actx.four_one,
                0,
                1,
                0,
                shared.state.vels,
                init_p,
            );
            #[cfg(not(feature = "simd-is-enabled"))]
            let (mut changed, mut fastest) = actx.cptrs.sweep_color::<D>(
                plan,
                plan.tail,
                actx.one_two,
                actx.one_one,
                0,
                1,
                0,
                shared.state.vels,
                init_p,
            );
            for p in partials {
                changed += p.changed;
                fastest = fastest.max(p.fastest);
            }

            let s = &mut shared.state;
            if iter == most || fastest <= tol {
                if do_stiffen {
                    shared.stiffen_more = actx.cptrs.stiffen_all();
                }
                shared.sweep_action = SweepAction::Done;
                return;
            }

            if !s.has_p {
                s.has_p = true;
                s.last = changed;
                s.init_vel_direction();
                shared.sweep_action = SweepAction::Continue {
                    beta: 0.0,
                    restart: false,
                    init_p: false,
                };
            } else if changed > s.last {
                s.restarts += 1;
                s.has_p = false;
                s.last = changed;
                let vels = s.vels_mut();
                s.accel_vels.slice_mut(0..s.vels_len).copy_from_slice(vels);
                adv_tail::<D>(actx, 0.0, true);
                shared.sweep_action = SweepAction::Continue {
                    beta: 0.0,
                    restart: true,
                    init_p: true,
                };
            } else {
                let beta = changed / s.last;
                s.last = changed;
                s.advance_vel_direction(beta);
                adv_tail::<D>(actx, beta, false);
                shared.sweep_action = SweepAction::Continue {
                    beta,
                    restart: false,
                    init_p: false,
                };
            }
        });

        if matches!(sync.read_shared().sweep_action, SweepAction::Done) {
            return;
        }
    }
}

#[inline(always)]
unsafe fn adv_tail<const D: usize>(actx: &ParallelAccelCtx<'_>, beta: Real, restart: bool) {
    #[cfg(feature = "simd-is-enabled")]
    actx.cptrs.advance_color::<D>(
        actx.plan,
        actx.plan.tail,
        actx.one_two,
        actx.one_one,
        actx.four_two,
        actx.four_one,
        0,
        1,
        0,
        beta,
        restart,
    );
    #[cfg(not(feature = "simd-is-enabled"))]
    actx.cptrs.advance_color::<D>(
        actx.plan,
        actx.plan.tail,
        actx.one_two,
        actx.one_one,
        0,
        1,
        0,
        beta,
        restart,
    );
}

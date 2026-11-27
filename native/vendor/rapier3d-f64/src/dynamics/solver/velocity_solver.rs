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
    one: Memory<Real>,
    four: Memory<SimdReal>,
    vels: Vec<SolverVel<Real>>,
    vel_direction: Vec<SolverVel<Real>>,
}

#[derive(Default)]
struct Memory<N> {
    /// The impulses before the last sweep, and which way they have been going.
    points: Vec<[[N; 2]; 3]>,
    at: usize,
}

impl<N: SimdRealCopy> Memory<N> {
    fn remember(&mut self, now: [&mut N; 3]) {
        self.points.push(now.map(|v| [*v, N::zero()]));
    }

    /// What the last sweep changed, as an energy (impulse times velocity) and as the largest change of velocity.
    fn changes(&mut self, now: [&mut N; 3], k: [N; 3]) -> (N, N) {
        let old = &self.points[self.at];
        self.at += 1;
        let (mut energy, mut fastest) = (N::zero(), N::zero());
        for j in 0..3 {
            let d = *now[j] - old[j][0];
            let v = d * k[j];
            energy += d * v;
            fastest = fastest.simd_max(v.simd_abs());
        }
        (energy, fastest)
    }

    fn advance(&mut self, now: [&mut N; 3], beta: N, keep: N) {
        for (value, [old, p]) in now.into_iter().zip(&mut self.points[self.at]) {
            let d = *value - *old;
            *value += *p * beta;
            *p = (*p * beta + d) * keep;
            *old = *value;
        }
        self.at += 1;
    }
}

impl Accelerator {
    /// Sweeps at least once, and then until a sweep changes the relative velocity at no contact by more than `tolerance`.
    fn run(
        &mut self,
        most: usize,
        tolerance: Real,
        contacts: &mut SolverConstraintsSet<ContactConstraintTypes>,
        vels: &mut [SolverVel<Real>],
        friction: bool,
    ) {
        self.one.points.clear();
        self.four.points.clear();
        contacts.each_impulse(|now, _| self.one.remember(now), |now, _| self.four.remember(now));
        self.vels.clear();
        self.vels.extend_from_slice(vels);
        self.vel_direction.clear();
        let mut last = Real::MAX;
        for i in 1..=most {
            if friction {
                contacts.sweep(vels);
            } else {
                contacts.solve_restitution(vels, &mut DVector::zeros(0));
            }
            let (mut changed, mut fastest) = (0.0, 0.0 as Real);
            let (mut wide_changed, mut wide_fastest) = (SimdReal::splat(0.0), SimdReal::splat(0.0));
            (self.one.at, self.four.at) = (0, 0);
            contacts.each_impulse(
                |now, k| {
                    let (c, f) = self.one.changes(now, k);
                    changed += c;
                    fastest = fastest.max(f);
                },
                |now, k| {
                    let (c, f) = self.four.changes(now, k);
                    wide_changed += c;
                    wide_fastest = wide_fastest.simd_max(f);
                },
            );
            changed += wide_changed.simd_horizontal_sum();
            fastest = fastest.max(wide_fastest.simd_horizontal_max());
            if i == most || fastest <= tolerance {
                return;
            }
            // Fletcher-Reeves; a direction that did not bring less to do is given up.
            let restart = changed > last;
            let beta = if restart { 0.0 } else { changed / last };
            let keep = if restart { 0.0 } else { 1.0 };
            last = changed;
            self.vel_direction.resize(vels.len(), SolverVel::zero());
            (self.one.at, self.four.at) = (0, 0);
            contacts.each_impulse(
                |now, _| self.one.advance(now, beta, keep),
                |now, _| self.four.advance(now, SimdReal::splat(beta), SimdReal::splat(keep)),
            );
            for ((v, old), p) in vels.iter_mut().zip(&mut self.vels).zip(&mut self.vel_direction) {
                let d = *v - *old;
                v.linear += p.linear * beta;
                v.angular += p.angular * beta;
                p.linear = (p.linear * beta + d.linear) * keep;
                p.angular = (p.angular * beta + d.angular) * keep;
                *old = *v;
            }
        }
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
                self.accelerator.run(
                    params.max_internal_iterations,
                    params.internal_velocity_tolerance,
                    contact_constraints,
                    &mut self.solver_vels,
                    true,
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
                self.accelerator.run(
                    params.max_internal_iterations,
                    params.internal_velocity_tolerance,
                    contact_constraints,
                    &mut self.solver_vels,
                    false,
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
        // Integrate positions.
        for (solver_vels, solver_body) in self.solver_vels.iter().zip(self.solver_bodies.iter_mut())
        {
            let linvel = solver_vels.linear;
            let angvel = solver_body.sqrt_ii.transform_vector(solver_vels.angular);

            let mut new_vels = RigidBodyVelocity { linvel, angvel };
            new_vels = new_vels.apply_damping(params.dt, &solver_body.damping);
            let new_pos =
                new_vels.integrate(params.dt, &solver_body.position, &solver_body.local_com);
            solver_body.integrated_vels += new_vels;
            solver_body.position = new_pos;
            solver_body.world_com = new_pos * solver_body.local_com;
        }

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

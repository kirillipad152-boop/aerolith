//! One block is one meter. The pose is the craft reference origin; linear velocity is at its COM.
//! Each 50 ms tick has four steps of motion. Contacts are looked for in each, or once a tick for slow, supported dry bodies.
//! COM motion is bounded by 80 m/s, rotation by 30 rad/s (4 m and 1.5 rad per tick) and by the spin at which the
//! farthest corner would turn at 80 m/s.

use std::collections::BTreeMap;
#[cfg(test)]
use std::collections::HashMap;
use std::num::NonZeroUsize;
use std::sync::LazyLock;

use rapier3d_f64::na::{Quaternion, Translation3, UnitQuaternion};
use rapier3d_f64::prelude::*;
use rapier3d_f64::rayon::{ThreadPool, ThreadPoolBuilder};

use contacts::{Contacts, FrictionGrid, Solids};
use crafts::Crafts;

mod bridge;
mod contacts;
mod crafts;
mod fluids;
#[cfg(test)]
mod tests;

type Vec3 = Vector<f64>;
type Pos = Point<f64>;
type Pose = Isometry<f64>;
type Result<T> = std::result::Result<T, Fault>;

#[derive(Debug)]
enum Fault {
    Argument(&'static str),
    State(&'static str),
    Jni(jni::errors::Error),
}

const MAX_BODIES: usize = 16_384;
// Up to four boxes and six exterior faces per box, plus one thrust point, per hull block.
const MAX_BOXES: usize = 1_048_576;
const MAX_SURFACES: usize = 2_097_152;
// One mass record for every shape of every block: up to four of a million blocks.
const MAX_PARTS: usize = 4_194_304;
// Eight boxes for every block of a chunk section.
const MAX_TERRAIN_TILE: usize = 32_768;
const MAX_WATER_TILE: usize = 4096;
const MAX_TERRAIN: usize = 262_144;
const MAX_WATER: usize = 262_144;
const MAX_WORLD_COORD: f64 = 32_000_000.0;
const MAX_LOCAL_COORD: f64 = 1024.0;
const MAX_MEDIUM_SPEED: f64 = 1000.0;
const SUBSTEPS: usize = 4;
const TICK: f64 = 0.05;
/// Bodies move in steps of this length whether the engine looks for contacts once a tick or every time.
const DT: f64 = TICK / SUBSTEPS as f64;
/// Slower than this (m/s, any point), bodies in contact are simulated in whole ticks. Coming down on something at this
/// speed means less than 5 cm of overlap before the solver sees it.
const CALM_SPEED: f64 = 1.0;
/// Contacts are solved until an iteration changes the relative velocity at none of them by more than this (m/s), or so
/// many times. A light body under a thousand times its weight takes a few dozen, most take one.
const CONTACT_TOLERANCE: f64 = 0.003;
const MAX_ITERATIONS: usize = 200;
/// A solver run that has to be held to the tick budget still makes this many sweeps.
const MIN_ITERATIONS: usize = 16;
/// A tick of the engine should take no longer than this (seconds; a tick of the game is 50 ms and the server waits up to 15 ms more), or the solver is allowed fewer sweeps for the next ones,
/// until it fits again. `AEROLITH_TICK_BUDGET_MS=0` turns this off. Calm and small worlds never come near it.
static TICK_BUDGET: LazyLock<f64> = LazyLock::new(|| {
    std::env::var("AEROLITH_TICK_BUDGET_MS")
        .ok()
        .and_then(|v| v.parse::<f64>().ok())
        .map_or(0.035, |ms| (ms / 1000.0).clamp(0.0, 10.0))
});

/// How many sweeps one solver run may make in the next tick, given how long the last one took: fewer when over the budget
/// (by a quarter each tick), and back to more, slowly, when it is well within. A budget of zero never limits.
fn next_sweeps(sweeps: usize, spent: f64, budget: f64) -> usize {
    if budget <= 0.0 {
        MAX_ITERATIONS
    } else if spent > budget {
        (sweeps - sweeps / 4).clamp(MIN_ITERATIONS, MAX_ITERATIONS)
    } else if spent < budget * 0.7 {
        (sweeps + sweeps / 8 + 1).clamp(MIN_ITERATIONS, MAX_ITERATIONS)
    } else {
        sweeps
    }
}

struct Sweeps(usize);

impl Default for Sweeps {
    fn default() -> Self {
        Self(MAX_ITERATIONS)
    }
}
/// The deepest a resting contact is pressed in by what it carries, in metres.
const CONTACT_SAG: f64 = 0.002;
/// Fewer awake bodies than this are faster done by one thread than handed around.
const MANY: usize = 48;
const GRAVITY: f64 = 9.81;
const MAX_SPEED: f64 = 80.0;
const MAX_SPIN: f64 = 30.0;
/// Contacts are foreseen this far ahead: the farthest a body travels in a fine step.
const SOFT_CCD: f64 = MAX_SPEED * TICK / SUBSTEPS as f64;
/// A contact nearer than this has been made; Rapier's own prediction distance.
const TOUCHING: f64 = 0.002;
const STATE_LEN: usize = 13;
/// A body handle, its state, and whether it is asleep.
#[cfg(test)]
const RECORD_LEN: usize = STATE_LEN + 2;

#[derive(Clone, Copy)]
struct BoxShape {
    center: Pos,
    half: Vec3,
}

impl BoxShape {
    fn parse(values: &[f64], max_coord: f64, max_half: f64) -> Result<Self> {
        bound(
            values[..3].iter().copied(),
            -max_coord,
            max_coord,
            "box center out of range",
        )?;
        bound(
            values[3..6].iter().copied(),
            0.001,
            max_half,
            "box half extent out of range",
        )?;
        Ok(Self {
            center: Pos::from(vec3(values)),
            half: vec3(&values[3..]),
        })
    }

    fn collider(self, friction: f64, restitution: f64) -> Result<ColliderBuilder> {
        bound([friction], 0.0, 10.0, "friction out of range")?;
        bound([restitution], 0.0, 1.0, "restitution out of range")?;
        Ok(
            ColliderBuilder::cuboid(self.half.x, self.half.y, self.half.z)
                .translation(self.center.coords)
                .friction(friction)
                .friction_combine_rule(CoefficientCombineRule::Min)
                .restitution(restitution)
                .active_hooks(ActiveHooks::MODIFY_SOLVER_CONTACTS),
        )
    }
}

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum SurfaceKind {
    Face,
    Foil,
    Thrust,
    /// A foil whose angle of attack the pilot's pitch stick changes.
    Elevator,
    /// A foil that turns the craft about its vertical axis.
    Rudder,
    /// A foil that rolls the craft: those on opposite sides of the centre of mass move opposite ways.
    Aileron,
    /// A foil that every craft has set by one lever, for more lift and more drag.
    Flap,
}

impl SurfaceKind {
    fn is_foil(self) -> bool {
        !matches!(self, Self::Face | Self::Thrust)
    }

    fn is_control(self) -> bool {
        matches!(
            self,
            Self::Elevator | Self::Rudder | Self::Aileron | Self::Flap
        )
    }
}

#[derive(Clone, Copy)]
struct Surface {
    kind: SurfaceKind,
    point: Pos,
    direction: Vec3,
    magnitude: f64,
}

impl Surface {
    fn parse(s: &[f64; 8]) -> Result<Self> {
        let kind = match s[0] {
            0.0 => SurfaceKind::Face,
            1.0 => SurfaceKind::Foil,
            2.0 => SurfaceKind::Thrust,
            3.0 => SurfaceKind::Elevator,
            4.0 => SurfaceKind::Rudder,
            5.0 => SurfaceKind::Aileron,
            6.0 => SurfaceKind::Flap,
            _ => return Err(Fault::Argument("unknown surface kind")),
        };
        bound(
            s[1..4].iter().copied(),
            -MAX_LOCAL_COORD,
            MAX_LOCAL_COORD,
            "surface point out of range",
        )?;
        let max = if matches!(kind, SurfaceKind::Thrust) {
            1.0e9
        } else {
            4096.0
        };
        bound([s[7]], 0.0, max, "surface magnitude out of range")?;
        let mut direction = vec3(&s[4..]);
        let scale = direction.amax();
        if scale == 0.0 {
            return Err(Fault::Argument("surface direction must be nonzero"));
        }
        direction = (direction / scale).normalize();
        if kind.is_foil() {
            if direction.z.abs() > 1.0e-6 {
                return Err(Fault::Argument(
                    "foil normal must be perpendicular to the local +Z chord",
                ));
            }
            direction.z = 0.0;
            direction.normalize_mut();
        }
        Ok(Self {
            kind,
            point: Pos::from(vec3(&s[1..])),
            direction,
            magnitude: s[7],
        })
    }
}

struct Craft {
    id: i64,
    handle: RigidBodyHandle,
    boxes: Vec<BoxShape>,
    surfaces: Vec<Surface>,
    /// Equivalent forces at the area centroids in uniform air, for pure translation only.
    translation_surfaces: Vec<Surface>,
    frozen: Option<RigidBodyVelocity>,
    throttle: f64,
    /// How far the flaps are down, from 0 to 1.
    flaps: f64,
    angular_target: Vec3,
    thrust_leverage: f64,
    /// The farthest any point of the hull is from its center of mass.
    radius: f64,
    /// Half the thickness of its thinnest box. Moving less than this between steps it cannot pass through anything.
    thin: f64,
    /// The safety limit of its spin: no corner faster than `MAX_SPEED`.
    max_spin: f64,
    /// Forces during the coming tick and where they act: what stands on the craft.
    loads: Vec<(Vec3, Pos)>,
    /// Whether it was awake at any point of the tick being simulated.
    moved: bool,
}

/// Where a tick goes, printed to stderr every 100 ticks when AEROLITH_PROFILE is set. Off by default.
#[derive(Default)]
struct Profile {
    enabled: Option<bool>,
    ticks: u32,
    forces: std::time::Duration,
    pipeline: std::time::Duration,
    rest: std::time::Duration,
    wakes: u32,
}

/// Shared by all worlds, and two cores are left for the game.
static WORKERS: LazyLock<Option<ThreadPool>> = LazyLock::new(|| {
    // Two cores are left for the game, the server's tick and the client's frame: workers that spin at barriers while those run
    // on their cores lose far more than a third worker brings (4 cores: 3 workers 114 ms, 2 workers 46 ms a step with the game
    // busy). AEROLITH_THREADS sets the number outright; 1 turns the worker pool off.
    let threads = std::env::var("AEROLITH_THREADS")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .map_or_else(
            || std::thread::available_parallelism().map_or(1, |n| n.get().saturating_sub(2).clamp(1, 8)),
            |n| n.clamp(1, 16),
        );
    ThreadPoolBuilder::new()
        .num_threads(threads.min(8))
        .thread_name(|i| format!("aerolith-physics-{i}"))
        .build()
        .ok()
        .filter(|pool| pool.current_num_threads() > 1)
});

thread_local! {
    /// Only the calling thread, for work that is not worth waking others for.
    static ALONE: Option<ThreadPool> =
        ThreadPoolBuilder::new().num_threads(1).use_current_thread().build().ok();
}

fn with_workers(bodies: usize, work: impl FnOnce() + Send) {
    match &*WORKERS {
        Some(pool) if bodies >= MANY => pool.install(work),
        _ => ALONE.with(|pool| match pool {
            Some(pool) => pool.install(work),
            None => work(),
        }),
    }
}

#[derive(Default)]
struct World {
    profile: Profile,
    /// The most sweeps a solver run may make; lowered while ticks take longer than the budget.
    sweeps: Sweeps,
    /// Bodies or colliders were added, removed or replaced since the last tick, so the engine has to look at them once.
    dirty: bool,
    crafts: Crafts,
    terrain: BTreeMap<i64, Vec<(BoxShape, ColliderHandle)>>,
    terrain_count: usize,
    water: fluids::WaterGrid,
    starts: Vec<(RigidBodyHandle, Pos, UnitQuaternion<f64>, f64)>,
    fluid_scratch: fluids::Scratch,
    pipeline: PhysicsPipeline,
    bodies: RigidBodySet,
    colliders: ColliderSet,
    contacts: Contacts,
    islands: IslandManager,
    broad_phase: BroadPhaseMultiSap,
    narrow_phase: NarrowPhase,
    impulse_joints: ImpulseJointSet,
    multibody_joints: MultibodyJointSet,
    ccd: CCDSolver,
}

impl World {
    /// A box is its center, half extents, friction (-1 for a grid) and restitution.
    /// Mass boxes supply center, half extents and density independently of contact geometry.
    fn add_body(
        &mut self,
        id: i64,
        state: &[f64],
        boxes: &[f64],
        mut friction_cells: &[f64],
        masses: &[f64],
        surfaces: &[f64],
    ) -> Result<()> {
        if self.crafts.len() >= MAX_BODIES {
            return Err(Fault::State("world body limit reached"));
        }
        if state.len() != STATE_LEN || !state.iter().all(|x| x.is_finite()) {
            return Err(Fault::Argument(
                "state must contain exactly 13 finite doubles",
            ));
        }
        bound(
            state[..3].iter().copied(),
            -MAX_WORLD_COORD,
            MAX_WORLD_COORD,
            "body origin out of range",
        )?;
        let q = Quaternion::new(state[6], state[3], state[4], state[5]);
        let scale = q.coords.amax();
        if scale == 0.0 {
            return Err(Fault::Argument("state quaternion must be nonzero"));
        }
        let pose = Pose::from_parts(
            Translation3::from(vec3(state)),
            UnitQuaternion::new_normalize(q / scale),
        );
        let boxes = records::<8>(boxes, MAX_BOXES)?;
        if boxes.is_empty() {
            return Err(Fault::Argument("a body needs at least one collider"));
        }
        if friction_cells.len() > MAX_BOXES {
            return Err(Fault::Argument("too many friction cells"));
        }
        // No seams, per-cell friction or bounce to fix. Rapier still runs the hook if the other collider requests it.
        let plain = boxes.len() == 1 && friction_cells.is_empty() && boxes[0][7] == 0.0;
        let boxes: Vec<_> = boxes
            .iter()
            .map(|b| {
                let shape = BoxShape::parse(b, MAX_LOCAL_COORD, MAX_LOCAL_COORD)?;
                let grid = if b[6] == -1.0 {
                    Some(FrictionGrid::read(shape, &mut friction_cells)?)
                } else {
                    None
                };
                let mu = grid.as_ref().map_or(b[6], |g| g.cells[0]);
                let mut collider = shape.collider(mu, b[7])?.density(0.0);
                if plain {
                    collider = collider.active_hooks(ActiveHooks::empty());
                }
                Ok((shape, collider.build(), grid))
            })
            .collect::<Result<_>>()?;
        if !friction_cells.is_empty() {
            return Err(Fault::Argument("unused friction cells"));
        }
        let mass = mass_properties(records::<7>(masses, MAX_PARTS)?)?;
        let surfaces: Vec<_> = records::<8>(surfaces, MAX_SURFACES)?
            .iter()
            .map(Surface::parse)
            .collect::<Result<_>>()?;

        let handle = self.bodies.insert(
            RigidBodyBuilder::dynamic()
                .position(pose)
                .linvel(limit(vec3(&state[7..]), MAX_SPEED))
                .soft_ccd_prediction(SOFT_CCD)
                .additional_mass_properties(mass)
                .build(),
        );
        let boxes: Vec<BoxShape> = boxes
            .into_iter()
            .map(|(shape, collider, grid)| {
                let collider =
                    self.colliders
                        .insert_with_parent(collider, handle, &mut self.bodies);
                if let Some(grid) = grid {
                    self.contacts.friction.insert(collider, grid);
                }
                shape
            })
            .collect();
        if boxes.len() > 1 {
            self.contacts.hulls.insert(handle, Solids::for_hull(&boxes));
        }
        let body = &mut self.bodies[handle];
        body.recompute_mass_properties_from_colliders(&self.colliders);
        let com = body.local_center_of_mass();
        let thrust_leverage = surfaces
            .iter()
            .filter(|s| matches!(s.kind, SurfaceKind::Thrust))
            .map(|s| (s.point - com).norm() * s.magnitude)
            .sum();
        let radius = boxes
            .iter()
            .map(|b| (b.center - com).norm() + b.half.norm())
            .chain(surfaces.iter().map(|s| (s.point - com).norm()))
            .fold(0.0, f64::max);
        let max_spin = MAX_SPIN.min(MAX_SPEED / radius);
        body.set_angvel(limit(vec3(&state[10..]), max_spin), true);
        let thin = boxes.iter().map(|b| b.half.min()).fold(f64::MAX, f64::min);
        self.dirty = true;
        self.crafts.insert(Craft {
            id,
            radius,
            thin,
            max_spin,
            moved: false,
            loads: Vec::new(),
            handle,
            boxes,
            translation_surfaces: fluids::translation_surfaces(&surfaces),
            surfaces,
            frozen: None,
            throttle: 0.0,
            flaps: 0.0,
            angular_target: Vec3::zeros(),
            thrust_leverage,
        });
        Ok(())
    }

    fn remove_body(&mut self, id: i64) -> Result<()> {
        let craft = self
            .crafts
            .remove(id)
            .ok_or(Fault::Argument("unknown body handle"))?;
        self.dirty = true;
        for collider in self.bodies[craft.handle].colliders() {
            self.contacts.friction.remove(collider);
        }
        self.contacts.hulls.remove(&craft.handle);
        self.bodies.remove(
            craft.handle,
            &mut self.islands,
            &mut self.colliders,
            &mut self.impulse_joints,
            &mut self.multibody_joints,
            true,
        );
        Ok(())
    }

    fn craft_mut(&mut self, id: i64) -> Result<&mut Craft> {
        self.crafts
            .get_mut(&id)
            .ok_or(Fault::Argument("unknown body handle"))
    }

    fn set_active(&mut self, id: i64, active: bool) -> Result<()> {
        let craft = self
            .crafts
            .get_mut(&id)
            .ok_or(Fault::Argument("unknown body handle"))?;
        let body = &mut self.bodies[craft.handle];
        self.dirty = true;
        if active {
            if let Some(vels) = craft.frozen.take() {
                body.set_body_type(RigidBodyType::Dynamic, true);
                body.set_vels(vels, true);
            }
        } else if craft.frozen.is_none() {
            craft.frozen = Some(*body.vels());
            body.set_body_type(RigidBodyType::Fixed, false);
        }
        Ok(())
    }

    fn set_throttle(&mut self, id: i64, throttle: f64) -> Result<()> {
        bound([throttle], 0.0, 1.0, "throttle must be in [0, 1]")?;
        self.craft_mut(id)?.throttle = throttle;
        Ok(())
    }

    fn set_flaps(&mut self, id: i64, flaps: f64) -> Result<()> {
        bound([flaps], 0.0, 1.0, "flaps must be in [0, 1]")?;
        self.craft_mut(id)?.flaps = flaps;
        Ok(())
    }

    fn set_controls(&mut self, id: i64, pitch: f64, yaw: f64, roll: f64) -> Result<()> {
        bound([pitch, yaw, roll], -1.0, 1.0, "controls must be in [-1, 1]")?;
        self.craft_mut(id)?.angular_target = Vec3::new(-pitch, yaw, roll);
        Ok(())
    }

    fn impulse(&mut self, id: i64, impulse: Vec3) -> Result<()> {
        bound(
            impulse.iter().copied(),
            -1.0e15,
            1.0e15,
            "impulse out of range",
        )?;
        let craft = self
            .crafts
            .get_mut(&id)
            .ok_or(Fault::Argument("unknown body handle"))?;
        let body = &mut self.bodies[craft.handle];
        if let Some(vels) = &mut craft.frozen {
            vels.linvel = limit(vels.linvel + impulse / body.mass(), MAX_SPEED);
        } else {
            body.apply_impulse(impulse, true);
            cap_velocities(body, craft.max_spin)?;
        }
        Ok(())
    }

    fn staff_pull(&mut self, id: i64, impulse: Vec3, orientation: Quaternion<f64>) -> Result<()> {
        bound(
            impulse.iter().copied(),
            -1.0e15,
            1.0e15,
            "impulse out of range",
        )?;
        if !(0.5..=2.0).contains(&orientation.norm_squared()) {
            return Err(Fault::Argument("invalid staff orientation"));
        }
        let craft = self
            .crafts
            .get(&id)
            .ok_or(Fault::Argument("unknown body handle"))?;
        if craft.frozen.is_some() {
            return Ok(());
        }
        let body = &mut self.bodies[craft.handle];
        let error = UnitQuaternion::new_normalize(orientation) * body.rotation().inverse();
        let desired = limit(error.scaled_axis() * 8.0, craft.max_spin.min(3.0));
        // Use world-space inertia so the motor turns different hulls at the same rate without setting their pose.
        let torque =
            body.mass_properties().effective_angular_inertia() * ((desired - body.angvel()) * 0.8);
        body.apply_impulse(impulse, true);
        body.apply_torque_impulse(torque, true);
        cap_velocities(body, craft.max_spin)
    }

    fn wake(&mut self, id: i64) -> Result<()> {
        let craft = self
            .crafts
            .get(&id)
            .ok_or(Fault::Argument("unknown body handle"))?;
        if craft.frozen.is_none() {
            self.bodies[craft.handle].wake_up(true);
        }
        Ok(())
    }

    /// Applies a world-space impulse at a world-space point, so an off-center load also torques the craft.
    fn impulse_at(&mut self, id: i64, impulse: Vec3, point: Pos) -> Result<()> {
        bound_impulse(impulse, point)?;
        let craft = self
            .crafts
            .get_mut(&id)
            .ok_or(Fault::Argument("unknown body handle"))?;
        let body = &mut self.bodies[craft.handle];
        if let Some(vels) = &mut craft.frozen {
            // A frozen craft keeps its saved momentum; only the linear part is meaningful there.
            vels.linvel = limit(vels.linvel + impulse / body.mass(), MAX_SPEED);
        } else {
            body.apply_impulse_at_point(impulse, point, true);
            cap_velocities(body, craft.max_spin)?;
        }
        Ok(())
    }

    /// Something stands on the craft during the next tick, and passes this impulse on over the whole of it. A still load
    /// does not disturb a body at rest, which settled under its weight already.
    fn load_at(&mut self, id: i64, impulse: Vec3, point: Pos, still: bool) -> Result<()> {
        bound_impulse(impulse, point)?;
        let craft = self
            .crafts
            .get_mut(&id)
            .ok_or(Fault::Argument("unknown body handle"))?;
        if craft.frozen.is_some() {
            return Ok(());
        }
        let body = &mut self.bodies[craft.handle];
        if !still {
            body.wake_up(true);
        }
        if !body.is_sleeping() {
            craft.loads.push((impulse / TICK, point));
        }
        Ok(())
    }

    fn set_terrain(&mut self, key: i64, values: &[f64]) -> Result<()> {
        let records = records::<8>(values, MAX_TERRAIN_TILE)?;
        let total = self.terrain_count - self.terrain.get(&key).map_or(0, Vec::len) + records.len();
        if total > MAX_TERRAIN {
            return Err(Fault::State("world terrain collider limit reached"));
        }
        let replacement = records
            .iter()
            .map(|r| {
                let shape = BoxShape::parse(r, MAX_WORLD_COORD, 1024.0)?;
                Ok((shape, shape.collider(r[6], r[7])?))
            })
            .collect::<Result<Vec<_>>>()?;
        self.dirty = true;
        for (shape, handle) in self.terrain.remove(&key).unwrap_or_default() {
            self.contacts.ground.remove(key, shape);
            self.colliders
                .remove(handle, &mut self.islands, &mut self.bodies, true);
        }
        if !replacement.is_empty() {
            let placed = replacement.into_iter().map(|(shape, collider)| {
                self.contacts.ground.add(key, shape);
                (shape, self.colliders.insert(collider.build()))
            });
            let placed = placed.collect();
            self.terrain.insert(key, placed);
        }
        self.terrain_count = total;
        Ok(())
    }

    /// Simulates one tick. A record holds the body handle, its state and whether it is now asleep; bodies that slept
    /// through the whole tick, and inactive ones, have not moved and are left out.
    fn step(&mut self, wind: Vec3) -> Result<Vec<f64>> {
        self.advance(wind)?;
        let mut output = Vec::new();
        for (id, craft) in &self.crafts {
            let body = &self.bodies[craft.handle];
            let asleep = body.is_sleeping();
            if craft.frozen.is_none() && (craft.moved || !asleep) {
                output.push(*id as f64);
                output.extend_from_slice(&state_of(body, body.vels()));
                output.push(f64::from(u8::from(asleep)));
            }
        }
        Ok(output)
    }

    /// Whether everything awake lies on something, slowly, with nothing but weight acting on it.
    fn calm(&self, wind: Vec3) -> bool {
        !self.dirty
            && wind == Vec3::zeros()
            && self.crafts.values().all(|craft| {
                let body = &self.bodies[craft.handle];
                craft.frozen.is_some()
                    || body.is_sleeping()
                    || (craft.throttle == 0.0
                        && craft.loads.is_empty()
                        && body.linvel().norm() + body.angvel().norm() * craft.radius < CALM_SPEED
                        && self.supported(body)
                        && !self.water.near(body.center_of_mass().coords, craft.radius))
            })
    }

    fn supported(&self, body: &RigidBody) -> bool {
        body.colliders().iter().any(|&col| {
            self.narrow_phase.contact_pairs_with(col).any(|pair| {
                pair.has_any_active_contact
                    && pair.manifolds.iter().any(|m| {
                        let up = if pair.collider2 == col {
                            m.data.normal.y
                        } else {
                            -m.data.normal.y
                        };
                        up > 0.0 && m.points.iter().any(|p| p.data.impulse > 0.0)
                    })
            })
        })
    }

    fn advance(&mut self, wind: Vec3) -> Result<()> {
        bound(
            wind.iter().copied(),
            -MAX_MEDIUM_SPEED,
            MAX_MEDIUM_SPEED,
            "wind out of range",
        )?;
        for craft in self.crafts.as_mut_slice() {
            craft.moved = false;
            let body = &self.bodies[craft.handle];
            if craft.frozen.is_none()
                && body.is_sleeping()
                && (wind != Vec3::zeros()
                    || craft.throttle != 0.0
                    || self
                        .water
                        .changed_near(body.center_of_mass().coords, craft.radius))
            {
                self.bodies[craft.handle].wake_up(true);
                self.profile.wakes += 1;
            }
        }
        let tick_start = std::time::Instant::now();
        let substeps = if self.calm(wind) { 1 } else { SUBSTEPS };
        let dt = TICK / substeps as f64;
        let params = IntegrationParameters {
            dt,
            num_solver_iterations: NonZeroUsize::new(SUBSTEPS / substeps)
                .unwrap_or(NonZeroUsize::MIN),
            num_internal_pgs_iterations: 1,
            num_internal_stabilization_iterations: 1,
            max_internal_iterations: self.sweeps.0,
            internal_velocity_tolerance: CONTACT_TOLERANCE,
            normalized_contact_sag: CONTACT_SAG,
            max_ccd_substeps: 1,
            // Bodies that end up overlapping are pushed apart at this speed. Rapier's default of 10 m/s launches them.
            normalized_max_corrective_velocity: 1.0,
            ..Default::default()
        };
        let start = &mut self.starts;
        let profiling = *self
            .profile
            .enabled
            .get_or_insert_with(|| std::env::var_os("AEROLITH_PROFILE").is_some());
        let mut mark = std::time::Instant::now();
        let dirty = std::mem::take(&mut self.dirty);
        for sub in 0..substeps {
            start.clear();
            for craft in self.crafts.as_mut_slice() {
                if craft.frozen.is_some() || self.bodies[craft.handle].is_sleeping() {
                    continue;
                }
                let body = &mut self.bodies[craft.handle];
                craft.moved = true;
                start.push((
                    craft.handle,
                    *body.center_of_mass(),
                    *body.rotation(),
                    craft.max_spin,
                ));
                fluids::apply_forces(body, craft, &self.water, wind, dt, &mut self.fluid_scratch)?;
                cap_velocities(body, craft.max_spin)?;
                // Foreseen contacts stop a body at the surface, but only along its linear path. The exact sweep rebuilds a
                // tree of every collider in the world and ignores shorter moves than this anyway.
                let sweep = body.linvel().norm() + body.angvel().norm() * craft.radius;
                body.enable_ccd(sweep * dt >= craft.thin);
            }
            if profiling {
                self.profile.forces += mark.elapsed();
                mark = std::time::Instant::now();
            }
            // Nothing is awake and nothing was touched: a step would find every contact as it left it.
            if start.is_empty() && !(dirty && sub == 0) {
                break;
            }
            // Gravity is among the forces of each body, which are weighed against what carries it.
            let stage_prof = rapier3d_f64::counters::SolverCounters::aerolith_stage_enabled();
            if stage_prof {
                self.pipeline.counters.enable();
            }
            with_workers(start.len(), || {
                self.pipeline.step(
                    &Vec3::zeros(),
                    &params,
                    &mut self.islands,
                    &mut self.broad_phase,
                    &mut self.narrow_phase,
                    &mut self.bodies,
                    &mut self.colliders,
                    &mut self.impulse_joints,
                    &mut self.multibody_joints,
                    &mut self.ccd,
                    None,
                    &self.contacts,
                    &(),
                );
            });
            if stage_prof {
                use std::sync::atomic::{AtomicU64, Ordering};
                pub static STAGE: [AtomicU64; 10] = [
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
                let c = &self.pipeline.counters;
                let ns = |d: std::time::Duration| d.as_nanos() as u64;
                STAGE[0].fetch_add(ns(c.cd.broad_phase_time.time()), Ordering::Relaxed);
                STAGE[1].fetch_add(ns(c.cd.narrow_phase_time.time()), Ordering::Relaxed);
                STAGE[2].fetch_add(ns(c.stages.island_construction_time.time()), Ordering::Relaxed);
                STAGE[3].fetch_add(ns(c.stages.solver_time.time()), Ordering::Relaxed);
                STAGE[4].fetch_add(ns(c.solver.velocity_resolution_time.time()), Ordering::Relaxed);
                STAGE[5].fetch_add(ns(c.stages.update_time.time()), Ordering::Relaxed);
                STAGE[6].fetch_add(ns(c.stages.ccd_time.time()), Ordering::Relaxed);
                STAGE[7].fetch_add(ns(c.stages.user_changes.time()), Ordering::Relaxed);
                STAGE[8].fetch_add(self.islands.active_dynamic_bodies().len() as u64, Ordering::Relaxed);
                STAGE[9].fetch_add(self.narrow_phase.contact_graph().interactions().count() as u64, Ordering::Relaxed);
                if sub + 1 == substeps && (self.profile.ticks + 1).is_multiple_of(100) {
                    let m = |i: usize| STAGE[i].swap(0, Ordering::Relaxed) as f64 / 100.0e6;
                    let avg = |i: usize| STAGE[i].swap(0, Ordering::Relaxed) as f64 / (100.0 * substeps as f64);
                    let s = rapier3d_f64::counters::SolverCounters::aerolith_take_stage_profile();
                    let sm = |i: usize| s[i] as f64 / 100.0e6;
                    eprintln!(
                        "STAGES/tick(ms): bp={:.2} np={:.2} isl={:.2} sol={:.2}(par={:.2}) upd={:.2} ccd={:.2} usr={:.2} | awake/sub={:.1} pairs/sub={:.0}",
                        m(0), m(1), m(2), m(3), m(4), m(5), m(6), m(7), avg(8), avg(9)
                    );
                    eprintln!(
                        "SOLVER_SUM(cpu-ms/tick): initC={:.2} updC={:.2} warm={:.2} bias={:.2}(sweep={:.2},accel={:.2}) stiff={:.2} int={:.2} stab={:.2}(sweep={:.2}) stf={:.2} | biasCalls={} biasSweeps={} stiffCalls={} stabCalls={} stabSweeps={} onePts={} fourGrps={}",
                        sm(0), sm(2), sm(3), sm(4), sm(5), sm(6), sm(7), sm(8), sm(9), sm(17), sm(10),
                        s[12]/100, s[13]/100, s[14]/100, s[15]/100, s[16]/100, s[18]/100, s[19]/100
                    );
                }
            }
            if profiling {
                self.profile.pipeline += mark.elapsed();
                mark = std::time::Instant::now();
            }
            for &(handle, com, rotation, max_spin) in start.iter() {
                let body = &mut self.bodies[handle];
                cap_velocities(body, max_spin)?;
                cap_motion(body, com, rotation, dt, max_spin)?;
            }
            if profiling {
                self.profile.rest += mark.elapsed();
                mark = std::time::Instant::now();
            }
        }
        self.sweeps.0 = next_sweeps(self.sweeps.0, tick_start.elapsed().as_secs_f64(), *TICK_BUDGET);
        self.water.forget_changes();
        self.crafts
            .as_mut_slice()
            .iter_mut()
            .for_each(|c| c.loads.clear());
        if profiling {
            self.profile.ticks += 1;
            if self.profile.ticks.is_multiple_of(100) {
                let p = &self.profile;
                let per = |d: std::time::Duration| d.as_secs_f64() * 1000.0 / p.ticks as f64;
                let (mut fastest, mut calm) = (0.0_f64, f64::MAX);
                for craft in self.crafts.values() {
                    let body = &self.bodies[craft.handle];
                    fastest =
                        fastest.max(body.linvel().norm() + body.angvel().norm() * craft.radius);
                    calm = calm.min(body.activation().time_since_can_sleep);
                }
                eprintln!(
                    "NATIVE wakes={} bodies={} colliders={} awake={} fastest={:.4} m/s least_calm_s={:.2} forces={:.3} ms pipeline={:.3} ms caps={:.3} ms per tick",
                    self.profile.wakes,
                    self.crafts.len(),
                    self.colliders.len(),
                    self.islands.active_dynamic_bodies().len(),
                    fastest,
                    calm,
                    per(p.forces),
                    per(p.pipeline),
                    per(p.rest)
                );
                self.profile = Profile {
                    enabled: Some(true),
                    ..Profile::default()
                };
            }
        }
        Ok(())
    }
}

fn mass_properties(boxes: &[[f64; 7]]) -> Result<MassProperties> {
    let mut mass = 0.0;
    let mut first = Vec3::zeros();
    let mut parsed = Vec::with_capacity(boxes.len());
    for b in boxes {
        let shape = BoxShape::parse(b, MAX_LOCAL_COORD, MAX_LOCAL_COORD)?;
        bound([b[6]], 0.001, 100_000.0, "density out of range")?;
        let own = 8.0 * shape.half.product() * b[6];
        mass += own;
        first += shape.center.coords * own;
        parsed.push((shape, own));
    }
    if mass <= 0.0 {
        return Err(Fault::Argument("a body needs mass"));
    }
    let com = first / mass;
    let mut inertia = Matrix::<f64>::zeros();
    for (shape, own) in parsed {
        let arm = shape.center.coords - com;
        let squares = shape.half.component_mul(&shape.half) / 3.0 + arm.component_mul(&arm);
        inertia += Matrix::from_diagonal(&Vec3::repeat(own * squares.sum()))
            - Matrix::from_diagonal(&(squares * own));
        for i in 0..3 {
            for j in 0..3 {
                if i != j {
                    inertia[(i, j)] -= own * arm[i] * arm[j];
                }
            }
        }
    }
    Ok(MassProperties::with_inertia_matrix(
        Pos::from(com),
        mass,
        inertia,
    ))
}

fn state_of(body: &RigidBody, vels: &RigidBodyVelocity) -> [f64; STATE_LEN] {
    let (p, q) = (body.translation(), body.rotation().coords);
    let (v, w) = (vels.linvel, vels.angvel);
    [
        p.x, p.y, p.z, q.x, q.y, q.z, q.w, v.x, v.y, v.z, w.x, w.y, w.z,
    ]
}

fn cap_velocities(body: &mut RigidBody, max_spin: f64) -> Result<()> {
    let (v, w) = (*body.linvel(), *body.angvel());
    if !v.iter().chain(w.iter()).all(|x| x.is_finite()) {
        return Err(Fault::State("physics produced a non-finite velocity"));
    }
    body.set_linvel(limit(v, MAX_SPEED), false);
    body.set_angvel(limit(w, max_spin), false);
    Ok(())
}

// Contact position corrections can overshoot even with limited velocities.
fn cap_motion(
    body: &mut RigidBody,
    start: Pos,
    rotation: UnitQuaternion<f64>,
    dt: f64,
    max_spin: f64,
) -> Result<()> {
    let travel = body.center_of_mass() - start;
    let current = body.rotation();
    if !travel
        .iter()
        .chain(current.coords.iter())
        .all(|x| x.is_finite())
    {
        return Err(Fault::State("physics produced a non-finite pose"));
    }
    let distance = MAX_SPEED * dt;
    let angle = rotation.angle_to(current);
    if travel.norm_squared() > distance * distance || angle > max_spin * dt {
        let com = start + limit(travel, distance);
        let rot = rotation.slerp(current, (max_spin * dt / angle).min(1.0));
        body.set_position(
            Pose::from_parts(
                Translation3::from(com.coords - rot * body.local_center_of_mass().coords),
                rot,
            ),
            false,
        );
    }
    Ok(())
}

fn bound_impulse(impulse: Vec3, point: Pos) -> Result<()> {
    bound(
        impulse.iter().copied(),
        -1.0e15,
        1.0e15,
        "impulse out of range",
    )?;
    bound(
        point.coords.iter().copied(),
        -MAX_WORLD_COORD,
        MAX_WORLD_COORD,
        "impulse point out of range",
    )
}

fn vec3(values: &[f64]) -> Vec3 {
    Vec3::new(values[0], values[1], values[2])
}

fn limit(vector: Vec3, max: f64) -> Vec3 {
    if vector.norm_squared() > max * max {
        let direction = vector / vector.amax();
        direction * (max / direction.norm())
    } else {
        vector
    }
}

fn bound(
    values: impl IntoIterator<Item = f64>,
    min: f64,
    max: f64,
    error: &'static str,
) -> Result<()> {
    if values.into_iter().all(|value| (min..=max).contains(&value)) {
        Ok(())
    } else {
        Err(Fault::Argument(error))
    }
}

fn records<const N: usize>(values: &[f64], max: usize) -> Result<&[[f64; N]]> {
    let (records, remainder) = values.as_chunks::<N>();
    if records.len() > max || !remainder.is_empty() || !values.iter().all(|x| x.is_finite()) {
        return Err(Fault::Argument(
            "invalid record count, length, or non-finite value",
        ));
    }
    Ok(records)
}

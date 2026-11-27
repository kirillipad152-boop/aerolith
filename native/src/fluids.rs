use std::collections::HashSet;

use rustc_hash::FxHashMap as HashMap;
use std::f64::consts::{FRAC_PI_2, PI};

use rapier3d_f64::parry::bounding_volume::Aabb;
use rapier3d_f64::prelude::RigidBody;

use super::*;
use submerged::{BoxFrame, OrientedBox, Polygons};

mod submerged;

const AIR_DENSITY: f64 = 1.225;
const WATER_DENSITY: f64 = 1000.0;

type Block = [i32; 3];

struct WaterCell {
    height: f64,
    flow: Vec3,
}

#[derive(Default)]
pub(super) struct WaterGrid {
    tiles: HashMap<i64, Vec<Block>>,
    cells: HashMap<Block, Vec<(i64, WaterCell)>>,
    count: usize,
    /// The boxes in which water appeared, went or changed since the last tick; a body asleep there is woken.
    changed: Vec<Area>,
    /// The box around the water of each tile.
    areas: HashMap<i64, Area>,
}

#[derive(Clone, Copy)]
struct Area {
    low: Vec3,
    high: Vec3,
}

impl Area {
    fn around<'a>(blocks: impl Iterator<Item = &'a Block>) -> Option<Self> {
        blocks
            .map(|b| {
                let low = Vec3::new(b[0].into(), b[1].into(), b[2].into());
                let high = low + Vec3::repeat(1.0);
                Self { low, high }
            })
            .reduce(|a, b| Self {
                low: a.low.inf(&b.low),
                high: a.high.sup(&b.high),
            })
    }

    fn reaches(&self, center: Vec3, reach: f64) -> bool {
        (0..3).all(|i| self.high[i] >= center[i] - reach && self.low[i] <= center[i] + reach)
    }
}

impl WaterGrid {
    pub(super) fn replace(&mut self, key: i64, values: &[f64]) -> Result<()> {
        let cells = records::<7>(values, MAX_WATER_TILE)?;
        let total = self.count - self.tiles.get(&key).map_or(0, Vec::len) + cells.len();
        if total > MAX_WATER {
            return Err(Fault::State("world water cell limit reached"));
        }
        let mut seen = HashSet::with_capacity(cells.len());
        let mut replacement = Vec::with_capacity(cells.len());
        for &[x, y, z, h, fx, fy, fz] in cells {
            bound(
                [x, y, z],
                -MAX_WORLD_COORD,
                MAX_WORLD_COORD,
                "water cell position out of range",
            )?;
            if [x, y, z].iter().any(|v| v.fract() != 0.0) || h <= 0.0 || h > 1.0 {
                return Err(Fault::Argument(
                    "water needs integer coordinates and height in (0, 1]",
                ));
            }
            bound(
                [fx, fy, fz],
                -MAX_MEDIUM_SPEED,
                MAX_MEDIUM_SPEED,
                "water flow out of range",
            )?;
            let block = [x as i32, y as i32, z as i32];
            if !seen.insert(block) {
                return Err(Fault::Argument("duplicate block in water tile"));
            }
            replacement.push((
                block,
                WaterCell {
                    height: h,
                    flow: Vec3::new(fx, fy, fz),
                },
            ));
        }
        self.changed.extend(self.areas.remove(&key));
        if let Some(area) = Area::around(replacement.iter().map(|(block, _)| block)) {
            self.changed.push(area);
            self.areas.insert(key, area);
        }
        if let Some(old) = self.tiles.remove(&key) {
            for block in old {
                let contributions = self.cells.get_mut(&block).unwrap();
                contributions.retain(|(owner, _)| *owner != key);
                if contributions.is_empty() {
                    self.cells.remove(&block);
                }
            }
        }
        if !replacement.is_empty() {
            let blocks = replacement
                .into_iter()
                .map(|(block, cell)| {
                    self.cells.entry(block).or_default().push((key, cell));
                    block
                })
                .collect();
            self.tiles.insert(key, blocks);
        }
        self.count = total;
        Ok(())
    }

    /// Whether water changed within `reach` of `center` since the last tick.
    pub(super) fn changed_near(&self, center: Vec3, reach: f64) -> bool {
        self.changed.iter().any(|area| area.reaches(center, reach))
    }

    /// Whether there may be water within `reach` of `center`.
    pub(super) fn near(&self, center: Vec3, reach: f64) -> bool {
        self.areas.values().any(|area| area.reaches(center, reach))
    }

    pub(super) fn forget_changes(&mut self) {
        self.changed.clear();
    }

    fn cell(&self, block: Block) -> Option<&WaterCell> {
        // Neighboring tiles can overlap during replacement; use their union, not double displacement.
        self.cells
            .get(&block)?
            .iter()
            .max_by(|(ka, a), (kb, b)| a.height.total_cmp(&b.height).then_with(|| kb.cmp(ka)))
            .map(|(_, cell)| cell)
    }

    fn contains(&self, point: Pos) -> bool {
        let block = std::array::from_fn(|i| point[i].floor() as i32);
        self.cell(block)
            .is_some_and(|cell| point.y < f64::from(block[1]) + cell.height)
    }

    /// A spinning hull has different velocities across its wetted volume, even when its center is stationary.
    fn sample_box(
        &self,
        pose: &Pose,
        shape: BoxShape,
        frame: &BoxFrame,
        spinning: bool,
        out: &mut Scratch,
    ) {
        let center = pose * shape.center;
        if !spinning {
            out.wet
                .extend(self.displace(center, shape.half, frame, &mut out.polygons));
            return;
        }
        let half = shape.half;
        let full_volume = 8.0 * half.product();
        let extent = frame.world_half(half);
        let start = (center.coords - extent).map(|v| v.floor() as i32);
        let end = (center.coords + extent).map(|v| v.ceil() as i32);
        let prism = self
            .uniform_slab(center.y, extent.y, start, end)
            .and_then(|(lo, hi, flow)| {
                if lo == -extent.y && hi == extent.y {
                    return Some((center, half, flow));
                }
                // Clipping flat water against a level box still leaves an exact rectangular prism.
                let up = pose.rotation.inverse_transform_vector(&Vec3::y());
                let axis = (0..3)
                    .find(|&a| (0..3).all(|b| a == b || up[b].abs() <= 16.0 * f64::EPSILON))?;
                let mut wet_half = half;
                wet_half[axis] = (hi - lo) * 0.5;
                Some((center + Vec3::y() * ((hi + lo) * 0.5), wet_half, flow))
            });
        for octant in 0..8 {
            let sign = Vec3::from_fn(|axis, _| if octant & (1 << axis) == 0 { -1.0 } else { 1.0 });
            if let Some((at, wet_half, flow)) = prism {
                // Two Gauss points per axis, equal weights; no further water-cell lookups.
                let point = at + pose.rotation * wet_half.component_mul(&sign) / 3.0_f64.sqrt();
                out.wet.extend(WetBox::new(
                    point,
                    flow,
                    wet_half.product(),
                    half,
                    full_volume,
                ));
            } else {
                let h = half * 0.5;
                if let Some(mut wet) = self.displace(
                    center + pose.rotation * h.component_mul(&sign),
                    h,
                    frame,
                    &mut out.polygons,
                ) {
                    // Partition the parent's drag area by wet volume, not the areas of the artificial cuts.
                    wet.half = half;
                    wet.fraction = wet.volume / full_volume;
                    out.wet.push(wet);
                }
            }
        }
    }

    fn displace(
        &self,
        center: Pos,
        half: Vec3,
        frame: &BoxFrame,
        polygons: &mut Polygons,
    ) -> Option<WetBox> {
        let world_half = frame.world_half(half);
        let start = (center.coords - world_half).map(|v| v.floor() as i32);
        let end = (center.coords + world_half).map(|v| v.ceil() as i32);
        let full_volume = 8.0 * half.product();
        if !frame.aligned
            && let Some((low, high, flow)) = self.uniform_slab(center.y, world_half.y, start, end)
        {
            let (volume, offset) = if low == -world_half.y && high == world_half.y {
                (full_volume, Vec3::zeros())
            } else {
                let slab = Aabb::new(
                    Pos::new(-world_half.x, low, -world_half.z),
                    Pos::new(world_half.x, high, world_half.z),
                );
                OrientedBox::new(frame, half, world_half).intersection(&slab, polygons)
            };
            return WetBox::new(center + offset, flow, volume, half, full_volume);
        }
        let mut oriented = None;
        let mut volume = 0.0;
        let mut centroid = Vec3::zeros();
        let mut flow = Vec3::zeros();
        let mut integrate = |lo: Vec3, hi: Vec3, current: Vec3| {
            let span = hi - lo;
            if span.min() <= 0.0 {
                return;
            }
            let (overlap, offset) = if frame.aligned {
                (span.product(), (lo + hi) * 0.5)
            } else {
                let region = Aabb::new(Pos::from(lo), Pos::from(hi));
                oriented
                    .get_or_insert_with(|| OrientedBox::new(frame, half, world_half))
                    .intersection(&region, polygons)
            };
            volume += overlap;
            centroid += offset * overlap;
            flow += current * overlap;
        };
        for x in start.x..end.x {
            // Merge a strip when adjacent strips differ in level or flow but this one is uniform.
            if !frame.aligned
                && end.x.saturating_sub(start.x) > 1
                && (end.y.saturating_sub(start.y) > 1 || end.z.saturating_sub(start.z) > 1)
                && let Some((low, high, current)) = self.uniform_slab(
                    center.y,
                    world_half.y,
                    Vector::new(x, start.y, start.z),
                    Vector::new(x + 1, end.y, end.z),
                )
            {
                let local_x = f64::from(x) - center.x;
                integrate(
                    Vec3::new(local_x.max(-world_half.x), low, -world_half.z),
                    Vec3::new((local_x + 1.0).min(world_half.x), high, world_half.z),
                    current,
                );
                continue;
            }
            for y in start.y..end.y {
                for z in start.z..end.z {
                    let Some(cell) = self.cell([x, y, z]) else {
                        continue;
                    };
                    let low = Pos::new(x.into(), y.into(), z.into()) - center;
                    let high = low + Vec3::new(1.0, cell.height, 1.0);
                    integrate(low.sup(&-world_half), high.inf(&world_half), cell.flow);
                }
            }
        }
        if volume > 0.0 {
            WetBox::new(
                center + centroid / volume,
                flow / volume,
                volume,
                half,
                full_volume,
            )
        } else {
            None
        }
    }

    // Identical contiguous columns are one slab. Don't merge gaps or distinct currents.
    fn uniform_slab(
        &self,
        center_y: f64,
        half_y: f64,
        start: Vector<i32>,
        end: Vector<i32>,
    ) -> Option<(f64, f64, Vec3)> {
        let mut slab = None;
        for x in start.x..end.x {
            for z in start.z..end.z {
                let mut column = None;
                for y in start.y..end.y {
                    let Some(cell) = self.cell([x, y, z]) else {
                        continue;
                    };
                    let floor = f64::from(y) - center_y;
                    let low = floor.max(-half_y);
                    let high = (floor + cell.height).min(half_y);
                    if high <= low {
                        continue;
                    }
                    if let Some((_, end, flow)) = &mut column {
                        if *end != low || *flow != cell.flow {
                            return None;
                        }
                        *end = high;
                    } else {
                        column = Some((low, high, cell.flow));
                    }
                }
                let column = column?;
                if slab.is_some_and(|previous| previous != column) {
                    return None;
                }
                slab = Some(column);
            }
        }
        slab
    }
}

struct WetBox {
    point: Pos,
    flow: Vec3,
    fraction: f64,
    volume: f64,
    half: Vec3,
}

impl WetBox {
    fn new(point: Pos, flow: Vec3, volume: f64, half: Vec3, full_volume: f64) -> Option<Self> {
        let volume = volume.min(full_volume);
        (volume > 0.0).then_some(Self {
            point,
            flow,
            fraction: volume / full_volume,
            volume,
            half,
        })
    }
}

#[derive(Default)]
pub(super) struct Scratch {
    wet: Vec<WetBox>,
    polygons: Polygons,
}

/// In uniform air, equal normals on a translating body see the same pressure. Their force
/// and moment are therefore exactly those of one face at the area-weighted centroid.
pub(super) fn translation_surfaces(surfaces: &[Surface]) -> Vec<Surface> {
    let mut groups = BTreeMap::new();
    // A control surface leans by an amount that depends on where it is, so it cannot be merged with another.
    let mut controls = Vec::new();
    for s in surfaces {
        if matches!(s.kind, SurfaceKind::Thrust) || s.magnitude == 0.0 {
            continue;
        }
        if s.kind.is_control() {
            controls.push(*s);
            continue;
        }
        let normal: [u64; 3] = std::array::from_fn(|i| {
            if s.direction[i] == 0.0 {
                0
            } else {
                s.direction[i].to_bits()
            }
        });
        let group = groups.entry((s.kind, normal)).or_insert(Surface {
            point: Pos::origin(),
            magnitude: 0.0,
            ..*s
        });
        group.point.coords += s.point.coords * s.magnitude;
        group.magnitude += s.magnitude;
    }
    groups
        .into_values()
        .map(|mut s| {
            s.point.coords /= s.magnitude;
            s
        })
        .chain(controls)
        .collect()
}

pub(super) fn apply_forces(
    body: &mut RigidBody,
    craft: &Craft,
    water: &WaterGrid,
    wind: Vec3,
    dt: f64,
    scratch: &mut Scratch,
) -> Result<()> {
    let pose = *body.position();
    let com = *body.center_of_mass();
    let mut force = -Vec3::y() * (GRAVITY * body.mass());
    let mut torque = Vec3::zeros();
    scratch.wet.clear();
    let near_water = water.near(com.coords, craft.radius);
    if near_water {
        let frame = BoxFrame::new(&pose.rotation);
        let spinning = *body.angvel() != Vec3::zeros();
        for &shape in &craft.boxes {
            water.sample_box(&pose, shape, &frame, spinning, scratch);
        }
        for wet in &scratch.wet {
            let buoyancy = Vec3::y() * (WATER_DENSITY * GRAVITY * wet.volume);
            force += buoyancy;
            torque += (wet.point - com).cross(&buoyancy);
        }
    }
    if craft.throttle > 0.0 {
        for surface in craft
            .surfaces
            .iter()
            .filter(|s| matches!(s.kind, SurfaceKind::Thrust))
        {
            let thrust = (pose.rotation * surface.direction) * (craft.throttle * surface.magnitude);
            force += thrust;
            torque += (pose * surface.point - com).cross(&thrust);
        }
        let authority = craft.throttle * craft.thrust_leverage;
        if authority > 0.0 {
            // Neutral stick requests zero rate, not zero torque.
            let rate_error =
                craft.angular_target - pose.rotation.inverse_transform_vector(body.angvel());
            let inertia = body
                .mass_properties()
                .local_mprops
                .reconstruct_inertia_matrix();
            // Powered attitude actuators have an aggregate torque budget of thrust times lever arm.
            torque += pose.rotation * limit(inertia * (4.0 * rate_error), authority);
        }
    }
    for &(load, point) in &craft.loads {
        force += load;
        torque += (point - com).cross(&load);
    }
    body.reset_forces(false);
    body.reset_torques(false);
    body.add_force(force, false);
    body.add_torque(torque, false);
    // All faces see one velocity field, not the result of the previous face's impulse.
    let (linear, angular) = (*body.linvel(), *body.angvel());
    let velocity_at = |point: &Pos| linear + angular.cross(&(point - com));
    let mut drag = FluidImpulse::default();
    for wet_box in &scratch.wet {
        let relative = velocity_at(&wet_box.point) - wet_box.flow;
        let speed = relative.norm();
        if speed > 0.0 {
            let direction = pose
                .rotation
                .inverse_transform_vector(&(relative / speed))
                .abs();
            let h = &wet_box.half;
            // shortcut: submerged box contributions omit shielding by its neighboring boxes;
            // use wetted exterior faces if large hulls need more accurate hydrodynamic drag.
            let area =
                4.0 * direction.dot(&Vec3::new(h.y * h.z, h.z * h.x, h.x * h.y)) * wet_box.fraction;
            let impulse = -relative * (0.5 * WATER_DENSITY * area * speed * dt);
            drag.add(wet_box.point - com, relative, impulse);
        }
    }
    if linear == wind && angular == Vec3::zeros() {
        drag.apply(body);
        return Ok(());
    }
    let surfaces = if !near_water && angular == Vec3::zeros() {
        &craft.translation_surfaces
    } else {
        &craft.surfaces
    };
    // One change of frame for the whole body: normals, lever arms and the foil chord are already local.
    let air_velocity = pose.rotation.inverse_transform_vector(&(linear - wind));
    let local_spin = pose.rotation.inverse_transform_vector(&angular);
    let local_com = body.local_center_of_mass();
    let mut air = FluidImpulse::default();
    for surface in surfaces {
        if matches!(surface.kind, SurfaceKind::Thrust) {
            continue;
        }
        if near_water && water.contains(pose * surface.point) {
            continue;
        }
        let arm = surface.point - local_com;
        let relative = air_velocity + local_spin.cross(&arm);
        let normal = surface.direction;
        let force = match surface.kind {
            SurfaceKind::Face => {
                let normal_speed = relative.dot(&normal);
                if normal_speed <= 0.0 {
                    continue;
                }
                -normal * (0.5 * AIR_DENSITY * 1.1 * normal_speed * normal_speed)
            }
            SurfaceKind::Foil => foil_force(relative, normal, Vec3::z()),
            SurfaceKind::Thrust => unreachable!(),
            _ => foil_force_flapped(
                relative,
                normal,
                Vec3::z(),
                deflection(craft, surface, &arm),
            ),
        };
        air.add(arm, relative, force * (surface.magnitude * dt));
    }
    drag.work += air.work;
    drag.total += pose.rotation * air.total;
    drag.moment += pose.rotation * air.moment;
    drag.apply(body);
    Ok(())
}

/// Most a pilot's stick lowers a control surface's trailing edge.
const MAX_DEFLECTION: f64 = 25.0 * PI / 180.0;
/// Most the flaps are lowered.
const MAX_FLAP_DEFLECTION: f64 = 30.0 * PI / 180.0;
/// How much of a flap's angle becomes angle of attack: the share of the chord that moves.
const FLAP_EFFECTIVENESS: f64 = 0.5;
/// A surface this close to the axis it should turn the craft about cannot do so.
const MIN_LEVER: f64 = 0.05;
const FOIL_SLOPE: f64 = 4.8;
const STALL: f64 = 15.0 * PI / 180.0;

/// How far a control surface's trailing edge is lowered now, in radians; positive means more lift along its normal. The stick
/// asks for a torque about an axis of the craft, and the surface is moved whichever way its lift, at its distance from the
/// centre of mass, makes that torque: so a tail, a canard, and ailerons on both wings all answer the same stick correctly.
fn deflection(craft: &Craft, surface: &Surface, arm: &Vec3) -> f64 {
    let (axis, wanted) = match surface.kind {
        SurfaceKind::Flap => return craft.flaps * MAX_FLAP_DEFLECTION,
        SurfaceKind::Elevator => (0, craft.angular_target.x),
        SurfaceKind::Rudder => (1, craft.angular_target.y),
        SurfaceKind::Aileron => (2, craft.angular_target.z),
        _ => return 0.0,
    };
    let lever = arm.cross(&surface.direction)[axis];
    if lever.abs() < MIN_LEVER {
        return 0.0;
    }
    lever.signum() * wanted * MAX_DEFLECTION
}

pub(super) fn foil_force(relative: Vec3, normal: Vec3, chord: Vec3) -> Vec3 {
    foil_force_flapped(relative, normal, chord, 0.0)
}

/// The force of a foil whose trailing edge is lowered by `flap` radians (negative: raised). That bends the camber, which moves
/// the lift curve up by the effective angle and costs drag; the angle at which the airflow lets go stays that of the chord,
/// so a flap raises the most lift a wing can give and does not make it stall sooner.
pub(super) fn foil_force_flapped(relative: Vec3, normal: Vec3, chord: Vec3, flap: f64) -> Vec3 {
    let speed = relative.norm();
    if speed < 1.0e-9 {
        return Vec3::zeros();
    }
    let direction = relative / speed;
    let vn = relative.dot(&normal);
    let vc = relative.dot(&chord);
    let alpha = (-vn).atan2(vc.abs());
    let shift = FOIL_SLOPE * FLAP_EFFECTIVENESS * flap;
    let cl = if alpha.abs() <= STALL {
        FOIL_SLOPE * alpha + shift
    } else {
        let remaining = (FRAC_PI_2 - alpha.abs()) / (FRAC_PI_2 - STALL);
        let faded = remaining.powi(3);
        (FOIL_SLOPE * STALL * alpha.signum() + shift) * faded
    };
    let cd = 0.025 + 0.10 * cl * cl + 1.10 * alpha.sin().powi(2) + 0.5 * flap * flap;
    let lift_direction = (normal - direction * normal.dot(&direction))
        .try_normalize(1.0e-9)
        .unwrap_or_else(Vec3::zeros);
    0.5 * AIR_DENSITY
        * (lift_direction * (cl * (vn * vn + vc * vc)) - direction * (cd * speed * speed))
}

/// Sum while visiting faces; no per-face impulses need to be stored or replayed.
#[derive(Default)]
struct FluidImpulse {
    work: f64,
    total: Vec3,
    moment: Vec3,
}

impl FluidImpulse {
    fn add(&mut self, arm: Vec3, relative: Vec3, impulse: Vec3) {
        self.work -= relative.dot(&impulse);
        self.total += impulse;
        self.moment += arm.cross(&impulse);
    }

    fn apply(self, body: &mut RigidBody) {
        let Self {
            work,
            total,
            moment,
        } = self;
        if work <= 0.0 {
            return;
        }
        let mass = body.mass_properties();
        let response = total.dot(&mass.effective_inv_mass.component_mul(&total))
            + (mass.effective_world_inv_inertia_sqrt * moment).norm_squared();
        // The effective-mass bound includes rotation, so the discrete impulse cannot add energy in the fluid frame.
        let scale = work / (work + response);
        body.apply_impulse(total * scale, false);
        body.apply_torque_impulse(moment * scale, false);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn foil_sign_reversal_stall_and_perpendicular_lift() {
        let velocity =
            |angle: f64, sign: f64| Vec3::new(0.0, -angle.sin(), sign * angle.cos()) * 40.0;
        let lift = |v: Vec3| {
            let force = foil_force(v, Vec3::y(), Vec3::z());
            force - v * (v.dot(&force) / v.norm_squared())
        };
        let easy = lift(velocity(10_f64.to_radians(), 1.0));
        let reverse = lift(velocity(10_f64.to_radians(), -1.0));
        assert!(easy.y > 0.0 && reverse.y > 0.0 && easy.z > 0.0 && reverse.z < 0.0);
        assert!(lift(velocity(-10_f64.to_radians(), 1.0)).y < 0.0);
        let at_stall = lift(velocity(15_f64.to_radians(), 1.0));
        let after_stall = lift(velocity(45_f64.to_radians(), 1.0));
        assert!(after_stall.norm() < 0.5 * at_stall.norm());
        let vertical = Vec3::new(0.0, -40.0, 0.0);
        assert!(lift(vertical).norm() < 1.0e-10);
        assert!(foil_force(vertical, Vec3::y(), Vec3::z()).y > 0.0);
        assert_eq!(
            foil_force(Vec3::zeros(), Vec3::y(), Vec3::z()),
            Vec3::zeros()
        );
    }

    #[test]
    fn strong_off_center_drag_dissipates_total_kinetic_energy() {
        let mut w = World::default();
        let state = [0., 0., 0., 0., 0., 0., 1., 80., 0., 0., 0., 0., 10.];
        let cube = [0., 0., 0., 0.5, 0.5, 0.5, 100., 0., 0.];
        w.add_uniform(1, &state, &cube, &[]).unwrap();
        let body = &mut w.bodies[w.crafts[&1].handle];
        let point = Pos::new(0., 0.5, 0.);
        let before = body.kinetic_energy();
        let relative = body.velocity_at_point(&point);
        let mut drag = FluidImpulse::default();
        drag.add(point - body.center_of_mass(), relative, -1.0e6 * relative);
        drag.apply(body);
        assert!(body.kinetic_energy() < before);
        assert!(body.velocity_at_point(&point).x > 0.0);
        assert!(body.angvel().z > 10.0); // The positive torque still extracts total energy.
    }

    #[test]
    fn displacement_integrates_height_center_and_flow_across_cells_and_keys() {
        let mut water = WaterGrid::default();
        water.replace(0, &[0., 0., 0., 0.5, 3., 0., 0.]).unwrap();
        water.replace(1, &[1., 0., 0., 0.25, -1., 0., 0.]).unwrap();
        water.replace(2, &[0., 0., 0., 0.10, 100., 0., 0.]).unwrap();
        let displaced = water
            .displace(
                Pos::new(1.0, 0.5, 0.5),
                Vec3::new(1.0, 0.5, 0.5),
                &BoxFrame::new(&UnitQuaternion::identity()),
                &mut Polygons::default(),
            )
            .unwrap();
        assert!((displaced.volume - 0.75).abs() < 1.0e-12);
        assert!((displaced.point.x - 5.0 / 6.0).abs() < 1.0e-12);
        assert!((displaced.point.y - 5.0 / 24.0).abs() < 1.0e-12);
        assert!((displaced.flow.x - 5.0 / 3.0).abs() < 1.0e-12);
        water.replace(0, &[]).unwrap();
        assert_eq!(water.cell([0, 0, 0]).unwrap().height, 0.10);
        assert_eq!(water.cell([1, 0, 0]).unwrap().height, 0.25);
    }

    #[test]
    fn rotating_drag_samples_preserve_displacement_and_its_first_moment() {
        let mut water = WaterGrid::default();
        let shape = BoxShape {
            center: Pos::origin(),
            half: Vec3::new(0.8, 0.6, 0.7),
        };
        for irregular in [false, true] {
            let mut cells = Vec::new();
            for x in -2..2 {
                for z in -2..2 {
                    if irregular && x < 0 && z >= 0 {
                        continue;
                    }
                    cells.extend([
                        x as f64,
                        0.,
                        z as f64,
                        if irregular && x > 0 { 0.4 } else { 1. },
                        if irregular { x as f64 } else { 0. },
                        0.,
                        0.,
                    ]);
                }
            }
            water.replace(1, &cells).unwrap();
            for roll in [0., 0.36] {
                let pose = Pose::from_parts(
                    Translation3::new(0.1, 0.75, -0.25),
                    UnitQuaternion::from_euler_angles(roll, 0.47, roll),
                );
                let frame = BoxFrame::new(&pose.rotation);
                let mut scratch = Scratch::default();
                water.sample_box(&pose, shape, &frame, false, &mut scratch);
                let whole = scratch.wet.pop().unwrap();
                water.sample_box(&pose, shape, &frame, true, &mut scratch);
                let mut volume = 0.;
                let (mut moment, mut flow) = (Vec3::zeros(), Vec3::zeros());
                for sample in &scratch.wet {
                    volume += sample.volume;
                    moment += sample.point.coords * sample.volume;
                    flow += sample.flow * sample.volume;
                }
                assert!((volume - whole.volume).abs() < 1e-10);
                assert!((moment / volume - whole.point.coords).norm() < 1e-10);
                assert!((flow / volume - whole.flow).norm() < 1e-10);
            }
        }
    }

    #[test]
    fn tilted_box_in_flat_water_has_the_analytical_centroid() {
        let mut water = WaterGrid::default();
        let mut cells = Vec::new();
        for x in [-1., 0.] {
            cells.extend_from_slice(&[x, -1., 0., 1., 0.75, 0., 0.]);
            cells.extend_from_slice(&[x, 0., 0., 0.5, 0.75, 0., 0.]);
        }
        water.replace(0, &cells).unwrap();
        let k = 0.75_f64;
        let rotation = UnitQuaternion::from_axis_angle(&Vec3::z_axis(), k.atan());
        let center = Pos::new(0., 0.5, 0.5);
        let half = Vec3::new(0.25, 0.5, 0.5);
        let box_frame = BoxFrame::new(&rotation);
        let displaced = water
            .displace(center, half, &box_frame, &mut Polygons::default())
            .unwrap();
        assert!(
            water
                .uniform_slab(
                    center.y,
                    box_frame.world_half(half).y,
                    Vector::new(-1, -1, 0),
                    Vector::new(1, 2, 1),
                )
                .is_some()
        );
        // In hull coordinates, the wet interval is [-hy, -k*x] for every x.
        let expected_local = Vec3::new(
            -k * half.x.powi(2) / (3.0 * half.y),
            (k.powi(2) * half.x.powi(2) / 3.0 - half.y.powi(2)) / (2.0 * half.y),
            0.0,
        );
        assert!((displaced.volume - 0.25).abs() < 1.0e-12);
        assert!((displaced.point - (center + rotation * expected_local)).norm() < 1.0e-12);
        assert_eq!(displaced.flow, Vec3::new(0.75, 0., 0.));
    }

    #[test]
    fn rotated_shoreline_displaces_only_the_triangle_inside_water() {
        let mut water = WaterGrid::default();
        let shore = 29_000_000.0;
        let north = [shore - 1., 0., -1., 1., -2., 0., 0.];
        let south = [shore - 1., 0., 0., 1., 4., 0., 0.];
        water.replace(0, &[north, south].concat()).unwrap();
        let frame = BoxFrame::new(&UnitQuaternion::from_axis_angle(
            &Vec3::y_axis(),
            45_f64.to_radians(),
        ));
        let half = Vec3::repeat(0.5);
        let radius = 0.5_f64.sqrt();
        let center = Pos::new(shore + radius / 2., 0.5, 0.);
        let mut polygons = Polygons::default();
        let displaced = water.displace(center, half, &frame, &mut polygons).unwrap();
        // In plan view the shoreline cuts a triangle whose base is twice its width.
        let width = radius - (center.x - shore);
        assert!((displaced.volume - width.powi(2)).abs() < 1.0e-12);
        assert!((displaced.point.x - (shore - width / 3.)).abs() < 1.0e-8);
        assert!((displaced.point.y - center.y).abs() < 1.0e-12);
        assert!(displaced.point.z.abs() < 1.0e-12);
        assert!((displaced.flow.x - 1.0).abs() < 1.0e-12);

        water.replace(0, &north).unwrap();
        // The corner of the enclosing AABB is wet, but the actual diamond misses that cell.
        assert!(
            water
                .displace(Pos::new(shore + 0.4, 0.5, 0.4), half, &frame, &mut polygons)
                .is_none()
        );
    }

    #[test]
    fn slabs_and_cells_agree_for_a_multiaxis_tilt() {
        let mut water = WaterGrid::default();
        let mut cells = Vec::new();
        for x in [-1., 0.] {
            for z in [-1., 0.] {
                cells.extend_from_slice(&[x, -1., z, 1., 0., 0., 0.]);
            }
        }
        let center = Pos::origin();
        let half = Vec3::new(0.5, 0.35, 0.25);
        let frame = BoxFrame::new(&UnitQuaternion::from_euler_angles(0.43, -0.55, 1.0));
        let mut polygons = Polygons::default();
        water.replace(0, &cells).unwrap();
        let slab = water.displace(center, half, &frame, &mut polygons).unwrap();
        cells[4] = 1.0;
        water.replace(0, &cells).unwrap();
        let mixed_flow = water.displace(center, half, &frame, &mut polygons).unwrap();
        assert!(mixed_flow.flow.x > 0.0);
        assert!((mixed_flow.volume - slab.volume).abs() < 1.0e-12);
        assert!((mixed_flow.point - slab.point).norm() < 1.0e-12);

        for cell in cells.chunks_exact_mut(7) {
            cell[1] = 0.;
            cell[4] = 0.;
        }
        water.replace(0, &cells).unwrap();
        let top = water.displace(center, half, &frame, &mut polygons).unwrap();
        let half_volume = 4.0 * half.product();
        assert!((top.volume - half_volume).abs() < 1.0e-12);
        assert!((slab.volume - half_volume).abs() < 1.0e-12);
        assert!((top.point.coords + slab.point.coords).norm() < 1.0e-12);
    }
}

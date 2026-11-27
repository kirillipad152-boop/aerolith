use rustc_hash::FxHashMap as HashMap;

use super::*;

/// Edge of a lookup cell, in metres.
const CELL: f64 = 4.0;
/// A box spanning more cells than this is looked at for every point instead.
const MAX_CELLS: f64 = 512.0;
/// A contact normal this far off a face of the box is still about that face.
const FACE: f64 = 0.3;
/// Points are probed this far off a face, and this far from its edges.
const PROBE: f64 = 0.01;
const INSET: f64 = 0.02;
/// Towards the middle of the other box, whose place nothing else can fill.
const INWARD: f64 = 0.05;

#[derive(Clone, Copy, PartialEq)]
struct Solid {
    owner: i64,
    low: Vec3,
    high: Vec3,
}

/// Boxes that fill space together, found by the points in them.
#[derive(Default)]
pub(super) struct Solids {
    cells: HashMap<[i32; 3], Vec<Solid>>,
    wide: Vec<Solid>,
}

impl Solids {
    fn cell(point: Vec3) -> [i32; 3] {
        std::array::from_fn(|i| (point[i] / CELL).floor() as i32)
    }

    fn cells(solid: &Solid) -> Option<impl Iterator<Item = [i32; 3]> + use<>> {
        let (a, b) = (Self::cell(solid.low), Self::cell(solid.high));
        let count: f64 = (0..3)
            .map(|i| f64::from(b[i]) - f64::from(a[i]) + 1.0)
            .product();
        (count <= MAX_CELLS).then(move || {
            (a[0]..=b[0]).flat_map(move |x| {
                (a[1]..=b[1]).flat_map(move |y| (a[2]..=b[2]).map(move |z| [x, y, z]))
            })
        })
    }

    pub(super) fn add(&mut self, owner: i64, shape: BoxShape) {
        let solid = Solid {
            owner,
            low: shape.center.coords - shape.half,
            high: shape.center.coords + shape.half,
        };
        match Self::cells(&solid) {
            Some(cells) => cells.for_each(|c| self.cells.entry(c).or_default().push(solid)),
            None => self.wide.push(solid),
        }
    }

    pub(super) fn remove(&mut self, owner: i64, shape: BoxShape) {
        let low = shape.center.coords - shape.half;
        let high = shape.center.coords + shape.half;
        match Self::cells(&Solid { owner, low, high }) {
            Some(cells) => cells.for_each(|c| {
                if let Some(list) = self.cells.get_mut(&c) {
                    list.retain(|s| s.owner != owner);
                    if list.is_empty() {
                        self.cells.remove(&c);
                    }
                }
            }),
            None => self.wide.retain(|s| s.owner != owner),
        }
    }

    #[cfg(test)]
    pub(super) fn is_empty(&self) -> bool {
        self.cells.is_empty() && self.wide.is_empty()
    }

    fn contains(&self, point: Vec3) -> bool {
        let inside = |s: &Solid| (0..3).all(|i| s.low[i] < point[i] && point[i] < s.high[i]);
        self.wide.iter().any(inside)
            || self
                .cells
                .get(&Self::cell(point))
                .is_some_and(|list| list.iter().any(inside))
    }

    /// Whether the side of a box that `outward` points out of is covered by another box at `point`. Such a side does not
    /// exist: whatever slides over the seam between two boxes must not be stopped by it. What is caught inside, though,
    /// may come out on any side.
    fn covered(&self, collider: &Collider, point: Pos, outward: Vec3, other: &Collider) -> bool {
        let Some(cuboid) = collider.shape().as_cuboid() else {
            return false;
        };
        let (half, pose) = (cuboid.half_extents, collider.position());
        let center = collider
            .position_wrt_parent()
            .unwrap_or(pose)
            .translation
            .vector;
        let local = pose.inverse_transform_point(&point).coords;
        let normal = pose.inverse_transform_vector(&outward);
        let middle = pose
            .inverse_transform_point(&Pos::from(other.position().translation.vector))
            .coords;
        let covered = (0..3).filter(|&a| normal[a].abs() > FACE).any(|a| {
            let probe = Vec3::from_fn(|i, _| {
                if i == a {
                    (half[a] + PROBE).copysign(normal[a])
                } else {
                    let edge = (half[i] - INSET).max(0.0);
                    (local[i] + (middle[i] - local[i]).clamp(-INWARD, INWARD)).clamp(-edge, edge)
                }
            });
            self.contains(center + probe)
        });
        covered && !self.contains(center + middle)
    }
}

/// Friction of each block of a hull box made of several materials.
pub(super) struct FrictionGrid {
    center: Pos,
    first: [i32; 3],
    size: [usize; 3],
    pub(super) cells: Box<[f64]>,
}

impl FrictionGrid {
    pub(super) fn read(shape: BoxShape, data: &mut &[f64]) -> Result<Self> {
        // Same half-open block cells as Hull.firstCell/lastCell. Hull-local block centers are integers.
        let first =
            std::array::from_fn(|i| (shape.center[i] - shape.half[i] + 0.5 + 1e-6).floor() as i32);
        let size: [usize; 3] = std::array::from_fn(|i| {
            let last = (shape.center[i] + shape.half[i] + 0.5 - 1e-6).floor() as i32;
            (last - first[i] + 1) as usize
        });
        let n = size
            .iter()
            .copied()
            .try_fold(1usize, usize::checked_mul)
            .ok_or(Fault::Argument("friction grid too large"))?;
        let (cells, rest) = data
            .split_at_checked(n)
            .ok_or(Fault::Argument("incomplete friction grid"))?;
        bound(cells.iter().copied(), 0.0, 10.0, "friction out of range")?;
        *data = rest;
        Ok(Self {
            center: shape.center,
            first,
            size,
            cells: cells.into(),
        })
    }

    fn at(&self, collider: &Collider, point: Pos) -> f64 {
        let p = collider.position().inverse_transform_point(&point).coords + self.center.coords;
        let [x, y, z] = std::array::from_fn(|i| {
            ((p[i] + 0.5).floor() as i32 - self.first[i]).clamp(0, self.size[i] as i32 - 1) as usize
        });
        self.cells[(z * self.size[1] + y) * self.size[0] + x]
    }
}

/// What contacts depend on besides the two colliders.
#[derive(Default)]
pub(super) struct Contacts {
    pub(super) friction: HashMap<ColliderHandle, FrictionGrid>,
    pub(super) ground: Solids,
    /// Hulls of more than one box, in their own frames.
    pub(super) hulls: HashMap<RigidBodyHandle, Solids>,
}

impl Contacts {
    fn solids(&self, body: Option<RigidBodyHandle>) -> Option<&Solids> {
        body.map_or(Some(&self.ground), |handle| self.hulls.get(&handle))
    }
}

impl PhysicsHooks for Contacts {
    fn modify_solver_contacts(&self, context: &mut ContactModificationContext) {
        let a = &context.colliders[context.collider1];
        let b = &context.colliders[context.collider2];
        let (left, right) = (
            self.friction.get(&context.collider1),
            self.friction.get(&context.collider2),
        );
        let (around_a, around_b) = (
            self.solids(context.rigid_body1),
            self.solids(context.rigid_body2),
        );
        let normal = *context.normal;
        context.solver_contacts.retain_mut(|contact| {
            // A foreseen contact takes away exactly the speed a bounce would have returned.
            if contact.restitution > 0.0 && contact.dist >= TOUCHING {
                return false;
            }
            // Rapier reports the midpoint; recover the surface point on each box (both skins are zero).
            let half_gap = normal * (contact.dist * 0.5);
            let (on_a, on_b) = (contact.point - half_gap, contact.point + half_gap);
            if around_a.is_some_and(|s| s.covered(a, on_a, normal, b))
                || around_b.is_some_and(|s| s.covered(b, on_b, -normal, a))
            {
                return false;
            }
            if left.is_some() || right.is_some() {
                let mu1 = left.map_or_else(|| a.friction(), |g| g.at(a, on_a));
                let mu2 = right.map_or_else(|| b.friction(), |g| g.at(b, on_b));
                contact.friction = mu1.min(mu2);
            }
            true
        });
    }
}

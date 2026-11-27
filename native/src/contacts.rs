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

impl Solid {
    fn new(owner: i64, shape: BoxShape) -> Self {
        Self {
            owner,
            low: shape.center.coords - shape.half,
            high: shape.center.coords + shape.half,
        }
    }
}

/// Boxes that fill space together, found by the points in them.
#[derive(Default)]
pub(super) struct Solids {
    cells: HashMap<[i32; 3], Vec<Solid>>,
    wide: Vec<Solid>,
}

impl Solids {
    pub(super) fn for_hull(boxes: &[BoxShape]) -> Self {
        let mut solids = Self::default();
        // Checking a few boxes costs less than building and probing a grid for a deck with a mast or side walls.
        if boxes.len() <= 8 {
            solids.wide = boxes.iter().map(|&shape| Solid::new(0, shape)).collect();
        } else {
            boxes.iter().for_each(|&shape| solids.add(0, shape));
        }
        solids
    }

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
        let solid = Solid::new(owner, shape);
        match Self::cells(&solid) {
            Some(cells) => cells.for_each(|c| self.cells.entry(c).or_default().push(solid)),
            None => self.wide.push(solid),
        }
    }

    pub(super) fn remove(&mut self, owner: i64, shape: BoxShape) {
        match Self::cells(&Solid::new(owner, shape)) {
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
}

/// Geometry shared by all points of a contact manifold on one side.
struct SeamProbe<'a> {
    solids: &'a Solids,
    pose: &'a Pose,
    half: Vec3,
    center: Vec3,
    normal: Vec3,
    middle: Vec3,
    other_inside: Option<bool>,
}

impl<'a> SeamProbe<'a> {
    fn new(
        solids: &'a Solids,
        collider: &'a Collider,
        outward: Vec3,
        other: &Collider,
    ) -> Option<Self> {
        let pose = collider.position();
        Some(Self {
            solids,
            pose,
            half: collider.shape().as_cuboid()?.half_extents,
            center: collider
                .position_wrt_parent()
                .unwrap_or(pose)
                .translation
                .vector,
            normal: pose.inverse_transform_vector(&outward),
            middle: pose
                .inverse_transform_point(&Pos::from(other.position().translation.vector))
                .coords,
            other_inside: None,
        })
    }

    /// An internal face must not stop sliding over the seam, but a box caught inside can come out on any side.
    fn covered(&mut self, point: Pos) -> bool {
        let local = self.pose.inverse_transform_point(&point).coords;
        let covered = (0..3).filter(|&a| self.normal[a].abs() > FACE).any(|a| {
            let probe = Vec3::from_fn(|i, _| {
                if i == a {
                    (self.half[a] + PROBE).copysign(self.normal[a])
                } else {
                    let edge = (self.half[i] - INSET).max(0.0);
                    (local[i] + (self.middle[i] - local[i]).clamp(-INWARD, INWARD))
                        .clamp(-edge, edge)
                }
            });
            self.solids.contains(self.center + probe)
        });
        covered
            && !*self
                .other_inside
                .get_or_insert_with(|| self.solids.contains(self.center + self.middle))
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
        if context.solver_contacts.is_empty() {
            return;
        }
        let a = &context.colliders[context.collider1];
        let b = &context.colliders[context.collider2];
        let (left, right) = (
            self.friction.get(&context.collider1),
            self.friction.get(&context.collider2),
        );
        let normal = *context.normal;
        let mut around_a = self
            .solids(context.rigid_body1)
            .and_then(|s| SeamProbe::new(s, a, normal, b));
        let mut around_b = self
            .solids(context.rigid_body2)
            .and_then(|s| SeamProbe::new(s, b, -normal, a));
        context.solver_contacts.retain_mut(|contact| {
            // A foreseen contact takes away exactly the speed a bounce would have returned.
            if contact.restitution > 0.0 && contact.dist >= TOUCHING {
                return false;
            }
            // Rapier reports the midpoint; recover the surface point on each box (both skins are zero).
            let half_gap = normal * (contact.dist * 0.5);
            let (on_a, on_b) = (contact.point - half_gap, contact.point + half_gap);
            if around_a.as_mut().is_some_and(|s| s.covered(on_a))
                || around_b.as_mut().is_some_and(|s| s.covered(on_b))
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

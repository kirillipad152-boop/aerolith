use rapier3d_f64::na::{Matrix3, Rotation3};
use rapier3d_f64::parry::bounding_volume::Aabb;
use rapier3d_f64::parry::query::details::clip_halfspace_polygon;

use super::{Pos, UnitQuaternion, Vec3};

// Outward winding for Parry Aabb::vertices(), in order of the lower/upper planes of each axis.
const FACES: [[usize; 4]; 6] = [
    [0, 4, 7, 3],
    [1, 2, 6, 5],
    [0, 1, 5, 4],
    [3, 7, 6, 2],
    [0, 3, 2, 1],
    [4, 5, 6, 7],
];
const NORMALS: [Vec3; 6] = [
    Vec3::new(-1., 0., 0.),
    Vec3::new(1., 0., 0.),
    Vec3::new(0., -1., 0.),
    Vec3::new(0., 1., 0.),
    Vec3::new(0., 0., -1.),
    Vec3::new(0., 0., 1.),
];

#[derive(Default)]
pub(super) struct Polygons {
    face: Vec<Pos>,
    workspace: Vec<Pos>,
}

pub(super) struct BoxFrame {
    rotation: Rotation3<f64>,
    absolute: Matrix3<f64>,
    pub(super) aligned: bool,
}

impl BoxFrame {
    pub(super) fn new(rotation: &UnitQuaternion<f64>) -> Self {
        let rotation = rotation.to_rotation_matrix();
        let absolute = rotation.matrix().abs();
        let aligned = absolute
            .row_iter()
            .all(|row| (row.sum() - 1.0).abs() <= 16.0 * f64::EPSILON);
        Self {
            rotation,
            absolute,
            aligned,
        }
    }

    pub(super) fn world_half(&self, half: Vec3) -> Vec3 {
        self.absolute * half
    }
}

pub(super) struct OrientedBox<'a> {
    frame: &'a BoxFrame,
    local: Aabb,
    world_vertices: [Pos; 8],
    world_half: Vec3,
}

impl<'a> OrientedBox<'a> {
    pub(super) fn new(frame: &'a BoxFrame, half: Vec3, world_half: Vec3) -> Self {
        let local = Aabb::from_half_extents(Pos::origin(), half);
        let world_vertices = local.vertices().map(|p| frame.rotation * p);
        Self {
            frame,
            local,
            world_vertices,
            world_half,
        }
    }

    // Both boxes use coordinates relative to the hull-box center, not the Minecraft world origin.
    pub(super) fn intersection(&self, water: &Aabb, scratch: &mut Polygons) -> (f64, Vec3) {
        let cuts: [bool; 6] = std::array::from_fn(|i| {
            if i % 2 == 0 {
                water.mins[i / 2] > -self.world_half[i / 2]
            } else {
                water.maxs[i / 2] < self.world_half[i / 2]
            }
        });
        let mut moments = Moments::default();
        // The intersection boundary consists of faces from each box clipped by the other box.
        for indices in FACES {
            scratch.face.clear();
            scratch.face.extend(indices.map(|i| self.world_vertices[i]));
            for (i, &cut) in cuts.iter().enumerate() {
                if cut {
                    let plane = if i % 2 == 0 { &water.mins } else { &water.maxs };
                    clip_halfspace_polygon(
                        plane,
                        &NORMALS[i],
                        &scratch.face,
                        &mut scratch.workspace,
                    );
                    std::mem::swap(&mut scratch.face, &mut scratch.workspace);
                    if scratch.face.len() < 3 {
                        break;
                    }
                }
            }
            moments.add_face(&scratch.face);
        }
        let water_vertices = water.vertices();
        for (i, &cut) in cuts.iter().enumerate() {
            // A coincident face is already counted on the hull; only add water planes that cut it.
            if cut {
                scratch.face.clear();
                scratch.face.extend(FACES[i].map(|j| {
                    self.frame
                        .rotation
                        .inverse_transform_point(&water_vertices[j])
                }));
                self.local
                    .clip_polygon_with_workspace(&mut scratch.face, &mut scratch.workspace);
                for vertex in &mut scratch.face {
                    *vertex = self.frame.rotation * *vertex;
                }
                moments.add_face(&scratch.face);
            }
        }
        moments.measure()
    }
}

#[derive(Default)]
struct Moments {
    origin: Option<Pos>,
    determinant_sum: f64,
    weighted_offset: Vec3,
}

impl Moments {
    fn add_face(&mut self, face: &[Pos]) {
        if face.len() < 3 {
            return;
        }
        // A boundary vertex anchor makes the tetrahedral volumes nonnegative, avoiding cancellation.
        let origin = *self.origin.get_or_insert(face[0]);
        let a = face[0] - origin;
        for edge in face[1..].windows(2) {
            let b = edge[0] - origin;
            let c = edge[1] - origin;
            let determinant = a.dot(&b.cross(&c)).max(0.0);
            self.determinant_sum += determinant;
            self.weighted_offset += (a + b + c) * (0.25 * determinant);
        }
    }

    fn measure(self) -> (f64, Vec3) {
        match self.origin {
            Some(origin) if self.determinant_sum > 0.0 => (
                self.determinant_sum / 6.0,
                origin.coords + self.weighted_offset / self.determinant_sum,
            ),
            _ => (0.0, Vec3::zeros()),
        }
    }
}

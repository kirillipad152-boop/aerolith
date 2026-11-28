use super::*;

impl World {
    /// A body of boxes given as center, half extents, density, friction and restitution.
    pub(super) fn add_uniform(
        &mut self,
        id: i64,
        state: &[f64],
        boxes: &[f64],
        surfaces: &[f64],
    ) -> Result<()> {
        let (mut shapes, mut masses) = (Vec::new(), Vec::new());
        for b in records::<9>(boxes, MAX_BOXES)? {
            shapes.extend_from_slice(&b[..6]);
            shapes.extend_from_slice(&b[7..]);
            masses.extend_from_slice(&b[..7]);
        }
        self.add_body(id, state, &shapes, &[], &masses, surfaces)
    }
}

/// One tick, then the state and mass of the given bodies whether or not they moved.
fn step(w: &mut World, wind: Vec3, ids: &[i64]) -> Result<Vec<f64>> {
    w.advance(wind)?;
    let mut out = Vec::new();
    for id in ids {
        let craft = &w.crafts[id];
        let body = &w.bodies[craft.handle];
        out.extend_from_slice(&state_of(
            body,
            craft.frozen.as_ref().unwrap_or(body.vels()),
        ));
        out.push(body.mass());
    }
    Ok(out)
}

/// A state and the mass after it.
const OUTPUT_LEN: usize = STATE_LEN + 1;
const ZERO: Vec3 = Vec3::new(0., 0., 0.);
const CUBE: [f64; 9] = [0., 0., 0., 0.5, 0.5, 0.5, 100., 0.8, 0.];
const TINY_BOX: [f64; 9] = [0., 0., 0., 0.1, 0.1, 0.1, 100., 0., 0.];

fn state(x: f64, y: f64, z: f64) -> [f64; STATE_LEN] {
    [x, y, z, 0., 0., 0., 1., 0., 0., 0., 0., 0., 0.]
}

fn run(w: &mut World, body: i64, ticks: usize) -> Vec<f64> {
    for _ in 1..ticks {
        w.advance(ZERO).unwrap();
    }
    step(w, ZERO, &[body]).unwrap()
}

fn near(actual: f64, expected: f64, tolerance: f64) {
    assert!(
        (actual - expected).abs() <= tolerance,
        "{actual} != {expected}"
    );
}

#[test]
fn freefall_uses_si_and_advances_exactly_one_tick_per_call() {
    let mut w = World::default();
    w.add_uniform(1, &state(0., 100., 0.), &CUBE, &[]).unwrap();
    let s = run(&mut w, 1, 20);
    near(s[8], -9.81, 1.0e-10);
    near(s[1], 100.0 - 0.5 * 9.81 * (1.0 + DT), 1.0e-10);
    near(s[13], 100., 1.0e-10);
    assert_eq!(&s[3..7], &[0., 0., 0., 1.]);
}

#[test]
fn staff_turns_a_body_with_torque_and_leaves_its_pose_to_the_solver() {
    let mut w = World::default();
    w.add_uniform(1, &state(0., 100., 0.), &CUBE, &[]).unwrap();
    let handle = w.crafts[&1].handle;
    assert!(
        w.staff_pull(1, ZERO, Quaternion::new(f64::NAN, 0., 0., 0.))
            .is_err()
    );
    let target = UnitQuaternion::from_axis_angle(&Vec3::y_axis(), std::f64::consts::FRAC_PI_2);
    w.staff_pull(1, ZERO, -target.into_inner()).unwrap();
    assert_eq!(*w.bodies[handle].rotation(), UnitQuaternion::identity());
    assert!(w.bodies[handle].angvel().y > 0.0);
    for _ in 0..80 {
        w.staff_pull(1, ZERO, target.into_inner()).unwrap();
        w.advance(ZERO).unwrap();
    }
    assert!(w.bodies[handle].rotation().angle_to(&target) < 0.01);
    near(w.bodies[handle].linvel().y, -80.0 * TICK * GRAVITY, 1.0e-9);
}

#[test]
fn a_wall_is_not_ground_and_a_new_load_or_missing_floor_requires_fine_steps() {
    let (mut w, _) = resting_world(&[]);
    for _ in 0..8 {
        w.advance(ZERO).unwrap();
    }
    assert!(w.calm(ZERO), "ground supporting the cube should qualify");
    w.load_at(1, Vec3::x() * 25., Pos::new(0., 1., 0.), false)
        .unwrap();
    assert!(!w.calm(ZERO), "a new off-center load needs the fine solver");
    w.advance(ZERO).unwrap();
    w.set_terrain(1, &[]).unwrap();
    assert!(!w.calm(ZERO), "cached contacts predate the removed floor");

    let mut w = World::default();
    w.set_terrain(1, &[-1., 10., 0., 0.5, 10., 5., 0., 0.])
        .unwrap();
    let mut s = state(0., 10., 0.);
    s[7] = -0.1;
    w.add_uniform(1, &s, &CUBE, &[]).unwrap();
    let first = run(&mut w, 1, 1);
    assert!(!w.calm(ZERO));
    let second = run(&mut w, 1, 1);
    near(
        second[1] - first[1],
        first[8] * TICK - 0.5 * GRAVITY * TICK * (TICK + DT),
        1.0e-9,
    );
}

#[test]
fn slow_supported_slide_agrees_with_four_fine_steps_and_coulomb_stopping_distance() {
    let slide = |fine: bool| {
        let mut w = World::default();
        w.set_terrain(1, &[0., -0.5, 0., 20., 0.5, 20., 0.1, 0.])
            .unwrap();
        let mut s = state(0., 0.5, 0.);
        s[7] = 0.8;
        w.add_uniform(1, &s, &[0., 0., 0., 1., 0.5, 1., 500., 0.5, 0.], &[])
            .unwrap();
        let mut coarse_used = false;
        for _ in 0..60 {
            if fine {
                w.dirty = true;
            } // Revalidate contacts each tick: the original four-step path.
            coarse_used |= w.calm(ZERO) && !w.bodies[w.crafts[&1].handle].is_sleeping();
            w.advance(ZERO).unwrap();
        }
        assert_eq!(coarse_used, !fine);
        let b = &w.bodies[w.crafts[&1].handle];
        near(b.linvel().norm(), 0.0, 1e-5);
        b.translation().x
    };
    let coarse = slide(false);
    near(coarse, slide(true), 0.01);
    near(coarse, 0.8 * 0.8 / (2. * 0.1 * GRAVITY), 0.01);
}

#[test]
fn grouped_air_pressure_preserves_force_and_torque_and_does_not_group_wet_or_spinning_faces() {
    let faces = [
        [0., 0.5, -0.35, -0.4, 1., 0., 0., 0.2],
        [0., 0.5, 0.35, 0.2, 1., 0., 0., 0.8],
        [1., 0., 0., -0.2, 0., 1., 0., 0.3],
        [1., 0., 0., 0.4, 0., 1., 0., 0.5],
    ]
    .concat();
    for (spin, water) in [(0., false), (2., false), (0., true)] {
        let simulate = |grouped: bool| {
            let mut w = World::default();
            let mut s = state(0., 20., 0.);
            s[7..10].copy_from_slice(&[4., -2., 7.]);
            s[12] = spin;
            w.add_uniform(1, &s, &CUBE, &faces).unwrap();
            if water {
                w.water.replace(1, &[0., 20., 0., 1., 0., 0., 0.]).unwrap();
            }
            let craft = w.crafts.get_mut(&1).unwrap();
            assert_eq!(craft.translation_surfaces.len(), 2);
            if !grouped {
                craft.translation_surfaces = craft.surfaces.clone();
            }
            step(&mut w, Vec3::new(1., -0.25, -1.), &[1]).unwrap()
        };
        for (result, reference) in simulate(true).iter().zip(simulate(false)) {
            near(*result, reference, 1.0e-10);
        }
    }
}

#[test]
fn compound_mass_com_full_inertia_and_com_impulse_are_physical() {
    let mut w = World::default();
    let a = [-1., 0., 0., 0.5, 0.5, 0.5, 100., 0.7, 0.];
    let b = [1., 0., 0., 0.5, 0.5, 0.5, 300., 0.7, 0.];
    let mut s = state(0., 100., 0.);
    s[4] = 0.5_f64.sqrt();
    s[6] = 0.5_f64.sqrt();
    w.add_uniform(1, &s, &[a, b].concat(), &[]).unwrap();
    let body = &w.bodies[w.crafts[&1].handle];
    near(body.mass(), 400., 1.0e-10);
    near(body.local_center_of_mass().x, 0.5, 1.0e-10);
    near(body.center_of_mass().z, -0.5, 1.0e-10);
    let inertia = body
        .mass_properties()
        .local_mprops
        .reconstruct_inertia_matrix();
    near(inertia[(0, 0)], 400. / 6., 1.0e-10);
    near(inertia[(1, 1)], 400. / 6. + 300., 1.0e-10);
    near(inertia[(2, 2)], 400. / 6. + 300., 1.0e-10);
    w.impulse(1, Vec3::new(800., 0., 0.)).unwrap();
    let out = run(&mut w, 1, 1);
    near(out[0], 0.1, 1.0e-10);
    near(out[7], 2., 1.0e-10);
    assert_eq!(&out[10..13], &[0., 0., 0.]);

    let mut offset = World::default();
    let c = [1., 1., 0., 0.5, 0.5, 0.5, 100., 0.7, 0.];
    offset.add_uniform(2, &s, &[a, c].concat(), &[]).unwrap();
    let inertia = offset.bodies[offset.crafts[&2].handle]
        .mass_properties()
        .local_mprops
        .reconstruct_inertia_matrix();
    near(inertia[(0, 1)], -100., 1.0e-10);
    near(inertia[(1, 0)], -100., 1.0e-10);
}

#[test]
fn keyed_terrain_collides_and_friction_stops_sliding() {
    let mut w = World::default();
    let floor = [0., -0.5, 0., 10., 0.5, 10., 0.9, 0.];
    let far_wall = [50., 10., 0., 0.05, 10., 5., 0.8, 0.];
    w.set_terrain(5, &floor).unwrap();
    w.set_terrain(-1, &far_wall).unwrap();
    assert!(w.set_terrain(5, &[0.; 8]).is_err());
    w.set_terrain(-1, &[]).unwrap();
    let mut s = state(0., 4., 0.);
    s[7] = 3.;
    w.add_uniform(1, &s, &CUBE, &[]).unwrap();
    let out = run(&mut w, 1, 80);
    near(out[1], 0.5, 0.025);
    near(out[7], 0., 0.03);
    w.set_terrain(5, &far_wall).unwrap();
    let out = run(&mut w, 1, 20);
    assert!(out[1] < -3.0);
}

/// Lowest height of the origin of a two-cube body dropped with spin on a one-cube-thick floor.
fn drop_spinning(height: f64, spin: Vec3, horizontal: f64) -> f64 {
    let mut w = World::default();
    w.set_terrain(1, &[0., -0.5, 0., 600., 0.5, 600., 0.7, 0.])
        .unwrap();
    let mut s = state(0., height, 0.);
    s[7] = horizontal;
    s[10..13].copy_from_slice(spin.as_slice());
    let boxes = [
        [-0.5, 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
        [0.5, 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
    ]
    .concat();
    w.add_uniform(1, &s, &boxes, &[]).unwrap();
    let mut lowest = f64::MAX;
    for _ in 0..240 {
        lowest = lowest.min(step(&mut w, ZERO, &[1]).unwrap()[1]);
    }
    lowest
}

/// Stacks a dropped two-cube body on a resting three-cube body and reports the worst spin and the final speeds.
fn stack(offset: f64, yaw: f64, hull_pieces: bool) -> (f64, f64, f64) {
    let mut w = World::default();
    w.set_terrain(1, &[0., -0.5, 0., 600., 0.5, 600., 0.7, 0.])
        .unwrap();
    let cube = |x: f64| [x, 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08];
    let line = |from: i32, to: i32| -> Vec<f64> {
        if hull_pieces {
            // One merged box, like a hull whose blocks share a material.
            let (a, b) = (f64::from(from), f64::from(to));
            vec![
                (a + b) / 2.,
                0.,
                0.,
                (b - a) / 2. + 0.5,
                0.5,
                0.5,
                55.,
                0.8,
                0.08,
            ]
        } else {
            (from..=to).flat_map(|x| cube(f64::from(x))).collect()
        }
    };
    w.add_uniform(1, &state(0., 0.5, 0.), &line(-1, 1), &[])
        .unwrap();
    let mut s = state(offset, 4., 0.3);
    s[3..7].copy_from_slice(&[0., (yaw / 2.).sin(), 0., (yaw / 2.).cos()]);
    w.add_uniform(2, &s, &line(0, 1), &[]).unwrap();
    let mut worst_spin = 0.0_f64;
    let mut last = vec![];
    for _ in 0..240 {
        last = step(&mut w, ZERO, &[1, 2]).unwrap();
        for body in 0..2 {
            let o = body * OUTPUT_LEN;
            let spin = Vec3::new(last[o + 10], last[o + 11], last[o + 12]).norm();
            worst_spin = worst_spin.max(spin);
        }
    }
    let speed = |o: usize| Vec3::new(last[o + 7], last[o + 8], last[o + 9]).norm();
    (worst_spin, speed(0), speed(OUTPUT_LEN))
}

/// Height range of a cube sliding along a floor built either from unit boxes or one merged box.
fn slide_over_seams(merged: bool, yaw: f64) -> f64 {
    slide(merged, yaw, true)
}

fn slide(merged: bool, yaw: f64, ccd: bool) -> f64 {
    let mut w = World::default();
    let floor: Vec<f64> = if merged {
        vec![0., -0.5, 0., 40., 0.5, 2., 0.2, 0.]
    } else {
        (-40..40)
            .flat_map(|x| [f64::from(x) + 0.5, -0.5, 0., 0.5, 0.5, 2., 0.2, 0.])
            .collect()
    };
    for (i, chunk) in floor.chunks(8).enumerate() {
        w.set_terrain(i as i64, chunk).unwrap();
    }
    let mut s = state(-30., 0.5, 0.);
    s[3..7].copy_from_slice(&[0., (yaw / 2.).sin(), 0., (yaw / 2.).cos()]);
    s[7] = 8.;
    w.add_uniform(1, &s, &CUBE, &[]).unwrap();
    let handle = w.crafts[&1].handle;
    w.bodies[handle].enable_ccd(ccd);
    let (mut low, mut high) = (f64::MAX, f64::MIN);
    for i in 0..60 {
        let y = step(&mut w, ZERO, &[1]).unwrap()[1];
        if i > 5 {
            low = low.min(y);
            high = high.max(y);
        }
    }
    high - low
}

// A row of separate boxes makes a sliding body hop about one run in four (a Rapier ghost contact at the seams, with
// or without CCD), so the Java side merges adjacent same-material cubes. One merged box must be perfectly smooth.
#[test]
fn sliding_over_a_merged_box_does_not_hop() {
    for yaw in [0., 0.2, 0.4] {
        let hop = slide_over_seams(true, yaw);
        assert!(hop < 0.01, "merged floor hop {hop} (yaw {yaw})");
    }
}

struct PileResult {
    lowest: f64,
    worst_speed: f64,
    worst_spin: f64,
    settled_at: Option<usize>,
}

/// Drops eight hulls of different shapes one after another onto a one-block-thick floor.
fn pile(seed: u64, ticks: usize) -> PileResult {
    let mut rng = seed
        .wrapping_mul(6364136223846793005)
        .wrapping_add(1442695040888963407);
    let mut next = move || {
        rng = rng
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        ((rng >> 33) as f64) / f64::from(1u32 << 31)
    };
    let mut w = World::default();
    w.set_terrain(1, &[0., -0.5, 0., 600., 0.5, 600., 0.7, 0.])
        .unwrap();
    let shapes: [Vec<f64>; 4] = [
        vec![0., 0., 0., 1.5, 0.5, 0.5, 55., 0.8, 0.08],
        vec![0., 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
        vec![0., 0., 0., 1., 0.5, 0.5, 55., 0.8, 0.08],
        [
            vec![-0.5, 0., 0., 1., 0.5, 0.5, 55., 0.8, 0.08],
            vec![0.5, 1., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
        ]
        .concat(),
    ];
    let ids: Vec<i64> = (1..=8).collect();
    for id in &ids {
        let (yaw, tilt) = (next() * std::f64::consts::TAU, (next() - 0.5));
        let q = UnitQuaternion::from_euler_angles(tilt, yaw, tilt * 0.5);
        let mut s = state(
            (next() - 0.5) * 6.,
            3. + 1.2 * *id as f64,
            (next() - 0.5) * 6.,
        );
        s[3..7].copy_from_slice(q.coords.as_slice());
        s[7] = (next() - 0.5) * 3.;
        s[9] = (next() - 0.5) * 3.;
        s[10] = (next() - 0.5) * 4.;
        s[12] = (next() - 0.5) * 4.;
        w.add_uniform(*id, &s, &shapes[*id as usize % 4], &[])
            .unwrap();
    }
    let mut result = PileResult {
        lowest: f64::MAX,
        worst_speed: 0.,
        worst_spin: 0.,
        settled_at: None,
    };
    for tick in 0..ticks {
        let out = step(&mut w, ZERO, &ids).unwrap();
        let mut fastest = 0.0_f64;
        for body in 0..ids.len() {
            let o = body * OUTPUT_LEN;
            let speed = Vec3::new(out[o + 7], out[o + 8], out[o + 9]).norm();
            let spin = Vec3::new(out[o + 10], out[o + 11], out[o + 12]).norm();
            result.lowest = result.lowest.min(out[o + 1]);
            result.worst_spin = result.worst_spin.max(spin);
            if tick > 20 {
                result.worst_speed = result.worst_speed.max(speed);
            }
            fastest = fastest.max(speed + spin * 0.5);
        }
        if fastest < 0.12 && result.settled_at.is_none() && tick > 40 {
            result.settled_at = Some(tick);
        } else if fastest >= 0.5 {
            result.settled_at = None;
        }
    }
    result
}

#[test]
fn a_pile_of_hulls_never_tunnels_explodes_or_fails_to_settle() {
    for seed in 1..=4 {
        let r = pile(seed, 600);
        assert!(
            r.lowest > 0.2,
            "seed {seed}: a body sank through the floor ({})",
            r.lowest
        );
        assert!(
            r.worst_speed < 22.,
            "seed {seed}: speed spike {}",
            r.worst_speed
        );
        assert!(
            r.worst_spin < 30.,
            "seed {seed}: spin spike {}",
            r.worst_spin
        );
        assert!(
            r.settled_at.is_some(),
            "seed {seed}: the pile never came to rest"
        );
    }
}

/// Exposed unit faces of a 3x1x1 line of cubes, as the Java hull builder produces them.
fn line_faces() -> Vec<f64> {
    let mut faces = vec![];
    for x in [-1., 0., 1.] {
        faces.extend([0., x, 0.5, 0., 0., 1., 0., 1.]);
        faces.extend([0., x, -0.5, 0., 0., -1., 0., 1.]);
        faces.extend([0., x, 0., 0.5, 0., 0., 1., 1.]);
        faces.extend([0., x, 0., -0.5, 0., 0., -1., 1.]);
    }
    faces.extend([0., 1.5, 0., 0., 1., 0., 0., 1.]);
    faces.extend([0., -1.5, 0., 0., -1., 0., 0., 1.]);
    faces
}

#[test]
fn a_symmetric_hull_falls_flat_through_the_air() {
    let mut w = World::default();
    let boxes = [
        [-1., 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
        [0., 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
        [1., 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
    ]
    .concat();
    w.add_uniform(1, &state(0., 400., 0.), &boxes, &line_faces())
        .unwrap();
    let mut out = vec![];
    for _ in 0..120 {
        out = step(&mut w, ZERO, &[1]).unwrap();
    }
    let spin = Vec3::new(out[10], out[11], out[12]).norm();
    let tilt = Vec3::new(out[3], out[4], out[5]).norm();
    assert!(
        spin < 1.0e-6,
        "drag on a symmetric hull must not spin it, got {spin} rad/s"
    );
    assert!(
        tilt < 1.0e-6,
        "drag on a symmetric hull must not tilt it, got {tilt}"
    );
}

/// Largest per-tick displacement of the origin of two stacked hulls once they have been at rest for a while.
fn resting_jitter() -> f64 {
    let mut w = World::default();
    w.set_terrain(1, &[0., -0.5, 0., 600., 0.5, 600., 0.7, 0.])
        .unwrap();
    let line = [
        [-1., 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
        [0., 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
        [1., 0., 0., 0.5, 0.5, 0.5, 55., 0.8, 0.08],
    ]
    .concat();
    let merged = [0., 0., 0., 1.5, 0.5, 0.5, 55., 0.8, 0.08];
    w.add_uniform(1, &state(0., 0.5, 0.), &merged, &line_faces())
        .unwrap();
    w.add_uniform(2, &state(0.2, 1.5, 0.1), &line, &line_faces())
        .unwrap();
    let mut last = step(&mut w, ZERO, &[1, 2]).unwrap();
    let mut worst = 0.0_f64;
    for tick in 0..400 {
        let out = step(&mut w, ZERO, &[1, 2]).unwrap();
        if tick > 200 {
            for body in 0..2 {
                let o = body * OUTPUT_LEN;
                let moved = Vec3::new(
                    out[o] - last[o],
                    out[o + 1] - last[o + 1],
                    out[o + 2] - last[o + 2],
                )
                .norm();
                worst = worst.max(moved);
            }
        }
        last = out;
    }
    worst
}

#[test]
fn stacked_hulls_at_rest_do_not_visibly_jitter() {
    let worst = resting_jitter();
    println!("REST JITTER {worst:.6} m per tick");
    assert!(worst < 2.0e-4, "resting hulls move {worst} m per tick");
}

/// Drops a one-cube body from `height` onto the ground, or onto a resting cube, and returns the fastest upward speed it ever has.
fn rebound(height: f64, restitution: f64, onto_body: bool) -> f64 {
    let mut w = World::default();
    w.set_terrain(1, &[0., -0.5, 0., 600., 0.5, 600., 0.7, restitution])
        .unwrap();
    let mut shape = CUBE;
    shape[8] = restitution;
    let mut ids = vec![1];
    if onto_body {
        w.add_uniform(2, &state(0., 0.5, 0.), &shape, &[]).unwrap();
        ids.push(2);
    }
    w.add_uniform(1, &state(0., height + 1.5, 0.), &shape, &[])
        .unwrap();
    let mut up = 0.0_f64;
    for _ in 0..600 {
        let out = step(&mut w, ZERO, &ids).unwrap();
        for i in 0..ids.len() {
            up = up.max(out[i * OUTPUT_LEN + 8]);
        }
    }
    up
}

#[test]
fn blocks_dropped_from_a_great_height_do_not_bounce() {
    for height in [10., 60., 250.] {
        for onto_body in [false, true] {
            let up = rebound(height, 0.0, onto_body);
            assert!(
                up < 0.5,
                "a block dropped from {height} m rebounded at {up} m/s (onto a body: {onto_body})"
            );
        }
    }
}

#[test]
fn bodies_that_overlap_separate_gently_instead_of_being_launched() {
    let mut w = World::default();
    w.set_terrain(1, &[0., -0.5, 0., 600., 0.5, 600., 0.7, 0.])
        .unwrap();
    w.add_uniform(1, &state(0., 0.5, 0.), &CUBE, &[]).unwrap();
    w.add_uniform(2, &state(0.4, 0.5, 0.), &CUBE, &[]).unwrap();
    let mut fastest = 0.0_f64;
    for _ in 0..60 {
        let out = step(&mut w, ZERO, &[1, 2]).unwrap();
        for body in 0..2 {
            let o = body * OUTPUT_LEN;
            fastest = fastest.max(Vec3::new(out[o + 7], out[o + 8], out[o + 9]).norm());
        }
    }
    assert!(
        fastest < 4.0,
        "overlapping bodies were thrown apart at {fastest} m/s"
    );
}

#[test]
fn hulls_stack_and_settle_without_gaining_energy() {
    for merged in [false, true] {
        for (offset, yaw) in [(0., 0.), (0.4, 0.3), (-0.7, 1.0), (1.2, 0.5)] {
            let (spin, a, b) = stack(offset, yaw, merged);
            assert!(
                spin < 12.0 && a < 0.05 && b < 0.05,
                "merged={merged} offset={offset} yaw={yaw}: worst spin {spin}, rest speeds {a} {b}"
            );
        }
    }
}

#[test]
fn fast_spinning_bodies_do_not_tunnel_through_a_thin_floor() {
    for height in [5., 80.] {
        for spin in [0., 10., 25.] {
            for horizontal in [0., 15.] {
                let lowest = drop_spinning(height, Vec3::new(spin, 0.4 * spin, spin), horizontal);
                assert!(
                    lowest > -0.1,
                    "h={height} spin={spin} v={horizontal}: lowest origin y {lowest}"
                );
            }
        }
    }
}

#[test]
fn point_impulse_moves_com_like_a_com_impulse_and_adds_torque_by_lever_arm() {
    let mut w = World::default();
    w.add_uniform(1, &state(0., 100., 0.), &CUBE, &[]).unwrap();
    w.add_uniform(2, &state(10., 100., 0.), &CUBE, &[]).unwrap();
    // 100 kg cube: a point 0.5 m above the COM pushed along +X spins it about -Z.
    w.impulse_at(1, Vec3::new(100., 0., 0.), Pos::new(0., 100.5, 0.))
        .unwrap();
    w.impulse(2, Vec3::new(100., 0., 0.)).unwrap();
    let out = step(&mut w, ZERO, &[1, 2]).unwrap();
    near(out[7], out[OUTPUT_LEN + 7], 1.0e-9);
    assert_eq!(&out[OUTPUT_LEN + 10..OUTPUT_LEN + 13], &[0., 0., 0.]);
    // Angular impulse is 100 * 0.5 N*m*s and a unit cube of mass m has inertia m / 6.
    near(out[12], -100. * 0.5 / (100. / 6.), 1.0e-6);
    assert!(
        w.impulse_at(1, Vec3::new(f64::NAN, 0., 0.), Pos::origin())
            .is_err()
    );
}

#[test]
fn slicker_material_controls_friction_on_both_sides_of_contact() {
    fn slide(hull_mu: f64, ground_mu: f64) -> f64 {
        let mut w = World::default();
        w.set_terrain(1, &[0., -0.5, 0., 10., 0.5, 10., ground_mu, 0.])
            .unwrap();
        let mut s = state(0., 0.5, 0.);
        s[7] = 3.0;
        let mut hull = CUBE;
        hull[7] = hull_mu;
        w.add_uniform(1, &s, &hull, &[]).unwrap();
        run(&mut w, 1, 20)[7]
    }
    let ice = slide(0.8, 0.035);
    assert!((2.5..2.85).contains(&ice), "ice slide: {ice}");
    near(slide(0.035, 0.8), ice, 1.0e-9);
    near(slide(0.8, 0.0), 3.0, 1.0e-8);
    assert!(slide(0.8, 0.7).abs() < 0.05);
}

#[test]
fn ccd_prevents_fast_terrain_and_body_tunneling() {
    let mut w = World::default();
    let mut left = state(-1.5, 5., 0.);
    left[7] = 80.;
    w.add_uniform(1, &left, &TINY_BOX, &[]).unwrap();
    w.set_terrain(0, &[0., 5., 0., 0.03, 2., 2., 0., 0.])
        .unwrap();
    assert!(run(&mut w, 1, 1)[0] < -0.08);

    let mut w = World::default();
    let mut right = state(1.5, 5., 0.);
    right[7] = -80.;
    w.add_uniform(1, &left, &TINY_BOX, &[]).unwrap();
    w.add_uniform(2, &right, &TINY_BOX, &[]).unwrap();
    let states = step(&mut w, ZERO, &[2, 1, 2]).unwrap();
    assert!(states[0] - states[OUTPUT_LEN] > 0.15, "{states:?}");
    assert_eq!(&states[..OUTPUT_LEN], &states[2 * OUTPUT_LEN..]);
}

#[test]
fn restitution_changes_the_collision_response() {
    fn hit(restitution: f64) -> f64 {
        let mut w = World::default();
        let mut s = state(-1., 5., 0.);
        s[7] = 10.;
        let mut shape = CUBE;
        shape[7] = 0.;
        shape[8] = restitution;
        w.add_uniform(1, &s, &shape, &[]).unwrap();
        w.set_terrain(1, &[1., 5., 0., 1., 2., 2., 0., restitution])
            .unwrap();
        run(&mut w, 1, 3)[7]
    }
    assert!(hit(0.0).abs() < 0.2);
    assert!(hit(0.9) < -8.0);
}

#[test]
fn still_air_drag_opposes_travel_scales_with_speed_and_accepts_wind() {
    let mut w = World::default();
    let faces = [
        0., 0., 0., 0., 1., 0., 0., 1., 0., 0., 0., 0., -1., 0., 0., 1.,
    ];
    for (id, speed) in [(1, 10.), (2, -20.), (3, 0.)] {
        let mut s = state(0., 40., id as f64 * 4.);
        s[7] = speed;
        w.add_uniform(id, &s, &CUBE, &faces).unwrap();
    }
    let out = step(&mut w, ZERO, &[1, 2, 3]).unwrap();
    let slow_loss = 10. - out[7];
    let fast_loss = 20. + out[OUTPUT_LEN + 7];
    assert!(slow_loss > 0. && fast_loss > 3.8 * slow_loss);
    assert_eq!(out[2 * OUTPUT_LEN + 7], 0.);
    let out = step(&mut w, Vec3::new(12., 0., 0.), &[3]).unwrap();
    assert!(out[7] > 0.);
}

#[test]
fn hydrostatics_float_sink_and_apply_off_center_buoyancy() {
    let mut w = World::default();
    let mut water = Vec::new();
    for x in -2..=5 {
        for y in -2..=0 {
            for z in -1..=1 {
                water.extend_from_slice(&[
                    f64::from(x),
                    f64::from(y),
                    f64::from(z),
                    1.,
                    0.,
                    0.,
                    0.,
                ]);
            }
        }
    }
    w.water.replace(1, &water).unwrap();
    let mut light = CUBE;
    light[6] = 500.;
    let mut dense = CUBE;
    dense[6] = 1700.;
    w.add_uniform(1, &state(0.5, 1., 0.5), &light, &[]).unwrap();
    w.add_uniform(2, &state(4.5, 1., 0.5), &dense, &[]).unwrap();
    let out = step(&mut w, ZERO, &[1, 2]).unwrap();
    near(out[1], 1., 1.0e-10);
    near(out[8], 0., 1.0e-10);
    assert!(out[OUTPUT_LEN + 8] < -0.3);

    let mut w = World::default();
    w.water.replace(1, &[0., 0., 0., 1., 0., 0., 0.]).unwrap();
    let mut hull = CUBE;
    hull[3] = 1.0;
    hull[6] = 500.;
    w.add_uniform(1, &state(1., 0.5, 0.5), &hull, &[]).unwrap();
    assert!(run(&mut w, 1, 1)[12] < -0.25);
}

#[test]
fn water_drag_tracks_current_without_overshooting_or_creating_energy() {
    fn submerged(flow: f64, speed: f64) -> World {
        let mut w = World::default();
        let mut water = Vec::new();
        for x in -2..=4 {
            water.extend_from_slice(&[f64::from(x), 0., 0., 1., flow, 0., 0.]);
        }
        w.water.replace(1, &water).unwrap();
        let mut hull = CUBE;
        hull[6] = 1000.;
        let mut s = state(0.0, 0.5, 0.5);
        s[7] = speed;
        w.add_uniform(1, &s, &hull, &[]).unwrap();
        w
    }
    let out = run(&mut submerged(0., 80.), 1, 1);
    assert!(out[7] > 0. && out[7] < 35.);
    near(out[1], 0.5, 1.0e-10);
    let out = run(&mut submerged(4., 0.), 1, 1);
    assert!(out[7] > 0. && out[7] < 4.);
}

#[test]
fn a_spinning_submerged_hull_loses_rotation_without_a_sideways_kick() {
    let mut w = World::default();
    let mut cells = Vec::new();
    for x in -3..3 {
        for z in -3..3 {
            cells.extend([x as f64, 0., z as f64, 1., 0., 0., 0.]);
        }
    }
    w.water.replace(0, &cells).unwrap();
    let mut s = state(0., 0.5, 0.);
    s[11] = 2.0;
    let hull = [0., 0., 0., 1., 0.25, 1., 1000., 0., 0.];
    w.add_uniform(1, &s, &hull, &[]).unwrap();
    let mut last = s[11];
    for _ in 0..20 {
        let frame = run(&mut w, 1, 1);
        assert!(
            frame[11] > 0.0 && frame[11] < last,
            "spin: {last} -> {}",
            frame[11]
        );
        near(vec3(&frame[7..]).norm(), 0.0, 1.0e-8);
        last = frame[11];
    }
}

#[test]
fn inactive_craft_keeps_mass_and_momentum_and_stays_solid() {
    let mut w = World::default();
    let mut saved = state(0., 10., 0.);
    saved[7] = 2.;
    saved[11] = 0.5;
    w.add_uniform(1, &saved, &CUBE, &[]).unwrap();
    w.advance(ZERO).unwrap();
    w.set_active(1, false).unwrap();
    w.set_active(1, false).unwrap();
    let frozen = run(&mut w, 1, 1);

    let mut incoming = state(-1.5, frozen[1], 0.);
    incoming[7] = 80.;
    w.add_uniform(2, &incoming, &TINY_BOX, &[]).unwrap();
    let frame = step(&mut w, ZERO, &[1, 2]).unwrap();
    assert_eq!(&frame[..OUTPUT_LEN], &frozen);
    near(frozen[7], 2., 1.0e-10);
    near(frozen[11], 0.5, 1.0e-10);
    near(frozen[13], 100., 1.0e-10);
    assert!(frame[OUTPUT_LEN] < -0.45);

    w.remove_body(2).unwrap();
    w.impulse(1, Vec3::x() * 100.).unwrap();
    w.set_active(1, true).unwrap();
    let resumed = run(&mut w, 1, 1);
    near(resumed[0] - frozen[0], 0.15, 1.0e-8);
    near(resumed[7], 3., 1.0e-10);
    near(resumed[8] - frozen[8], -9.81 * 0.05, 1.0e-10);
    near(resumed[11], 0.5, 1.0e-10);
}

#[test]
fn powered_controls_apply_local_torque_and_damp_at_neutral_stick() {
    let thrust = [
        2., -0.5, 0., 0., 0., 0., 1., 100., 2., 0.5, 0., 0., 0., 0., 1., 100.,
    ];
    for (controls, expected) in [
        ([1., 0., 0.], -Vec3::x()),
        ([0., 1., 0.], Vec3::y()),
        ([0., 0., 1.], Vec3::z()),
    ] {
        let mut w = World::default();
        let mut s = state(0., 100., 0.);
        s[4] = 0.5_f64.sqrt();
        s[6] = 0.5_f64.sqrt();
        let rotation = UnitQuaternion::new_normalize(Quaternion::new(s[6], 0., s[4], 0.));
        w.add_uniform(1, &s, &CUBE, &thrust).unwrap();
        w.set_controls(1, controls[0], controls[1], controls[2])
            .unwrap();
        let strain = run(&mut w, 1, 1);
        near(vec3(&strain[10..]).norm(), 0., 1.0e-10);
        w.set_throttle(1, 1.).unwrap();
        let powered = run(&mut w, 1, 1);
        let rate = vec3(&powered[10..]);
        assert!(rate.dot(&(rotation * expected)) > 0.1);
        assert!(rate.norm() < 1.);
        w.set_controls(1, 0., 0., 0.).unwrap();
        let damped = vec3(&run(&mut w, 1, 1)[10..]);
        let factor = (1.0 - 4.0 * DT).powi(SUBSTEPS as i32);
        assert!((damped - rate * factor).norm() < 1.0e-8);
        w.set_throttle(1, 0.).unwrap();
        assert!((vec3(&run(&mut w, 1, 1)[10..]) - damped).norm() < 1.0e-8);
    }

    let mut w = World::default();
    let mut s = state(0., 100., 0.);
    s[12] = 20.;
    w.add_uniform(1, &s, &CUBE, &thrust).unwrap();
    w.set_throttle(1, 0.05).unwrap();
    // 100 N m at full power, 100/6 kg m² inertia around Z.
    near(run(&mut w, 1, 1)[12], 20. - 5. * 0.05 / (100. / 6.), 1.0e-8);
}

#[test]
fn speed_caps_bound_input_impulses_and_realized_sweep() {
    let mut w = World::default();
    let mut s = state(0., 100., 0.);
    s[7] = f64::MAX;
    s[9] = f64::MAX;
    s[10] = -f64::MAX;
    let thrust = [2., 0., 1., 0., 0., 0., 1., 1.0e9];
    w.add_uniform(1, &s, &CUBE, &thrust).unwrap();
    let b = &w.bodies[w.crafts[&1].handle];
    near(b.linvel().norm(), 80., 1.0e-10);
    assert_eq!(b.angvel().x, -30.);
    w.impulse(1, Vec3::x() * 1.0e12).unwrap();
    w.set_throttle(1, 1.).unwrap();
    let s = run(&mut w, 1, 1);
    assert!((vec3(&s) - Vec3::new(0., 100., 0.)).norm() <= 4.0 + 1.0e-10);
    assert!(vec3(&s[7..]).norm() <= 80.0 + 1.0e-10);
    assert!(vec3(&s[10..]).norm() <= 30.0 + 1.0e-10);
}

#[test]
fn malformed_records_ranges_and_unknown_ids_do_not_mutate_world() {
    let mut w = World::default();
    assert!(w.add_uniform(1, &[0.; STATE_LEN], &CUBE, &[]).is_err());
    assert!(
        w.add_uniform(1, &state(0., 1., 0.), &CUBE[..8], &[])
            .is_err()
    );
    assert!(
        w.add_uniform(1, &state(0., 1., 0.), &CUBE, &[0.; 8])
            .is_err()
    );
    assert!(w.crafts.is_empty());
    assert!(w.bodies.is_empty());
    w.add_uniform(1, &state(0., 1., 0.), &CUBE, &[]).unwrap();
    assert!(w.set_controls(1, 1.1, 0., 0.).is_err());
    assert!(w.set_throttle(1, f64::NAN).is_err());
    assert!(w.impulse(1, Vec3::new(0., f64::INFINITY, 0.)).is_err());
    assert!(w.water.replace(1, &[0.5, 0., 0., 1., 0., 0., 0.]).is_err());
    assert!(w.set_throttle(-1, 0.).is_err());
    assert!(w.wake(-1).is_err());
    let out = run(&mut w, 1, 1);
    near(out[8], -GRAVITY * 0.05, 1.0e-10);
    w.remove_body(1).unwrap();
    assert!(w.set_throttle(1, 0.).is_err());
    assert!(w.step(ZERO).unwrap().is_empty());
}

/// Milliseconds per tick (four sub-steps) for `bodies` rafts resting on the ground, each made of `boxes_per_craft` colliders and
/// `surfaces` aerodynamic faces. Run with `cargo test --release bench_ -- --ignored --nocapture`.
fn rafts_ms_per_tick(bodies: usize, boxes_per_craft: usize, surfaces: usize, ticks: usize) -> f64 {
    let mut w = World::default();
    w.set_terrain(1, &[0., -0.5, 0., 600., 0.5, 600., 0.7, 0.])
        .unwrap();
    let mut boxes = vec![0., 0., 0., 8., 0.5, 8., 480., 0.55, 0.];
    for i in 1..boxes_per_craft {
        let side = (i % 4) as f64;
        boxes.extend([
            side * 2. - 3.,
            1.0,
            if i % 2 == 0 { -7.5 } else { 7.5 },
            1.,
            0.5,
            0.5,
            480.,
            0.55,
            0.,
        ]);
    }
    let mut faces = Vec::new();
    for i in 0..surfaces {
        faces.extend([
            0.,
            (i % 16) as f64 - 8.,
            0.5,
            (i / 16 % 16) as f64 - 8.,
            0.,
            1.,
            0.,
            1.,
        ]);
    }
    let ids: Vec<i64> = (1..=bodies as i64).collect();
    for (i, id) in ids.iter().enumerate() {
        let columns = (bodies as f64).sqrt().ceil() as usize;
        let mut s = state(
            (i % columns) as f64 * 20. - 100.,
            0.6,
            (i / columns) as f64 * 20. - 100.,
        );
        s[1] = 0.55;
        w.add_uniform(*id, &s, &boxes, &faces).unwrap();
    }
    for _ in 0..60 {
        step(&mut w, ZERO, &ids).unwrap();
    }
    let start = std::time::Instant::now();
    for _ in 0..ticks {
        step(&mut w, ZERO, &ids).unwrap();
    }
    start.elapsed().as_secs_f64() * 1000.0 / ticks as f64
}

#[test]
#[ignore]
fn bench_rafts() {
    for (bodies, boxes, surfaces) in [
        (1, 7, 0),
        (16, 7, 0),
        (16, 7, 900),
        (16, 60, 900),
        (48, 7, 300),
        (64, 7, 900),
    ] {
        let ms = rafts_ms_per_tick(bodies, boxes, surfaces, 100);
        eprintln!(
            "BENCH {bodies:>3} bodies, {boxes:>3} boxes, {surfaces:>4} faces each: {ms:7.3} ms per tick"
        );
    }
}

fn resting_world(faces: &[f64]) -> (World, [f64; STATE_LEN]) {
    let mut w = World::default();
    w.set_terrain(1, &[0., -0.5, 0., 600., 0.5, 600., 0.7, 0.])
        .unwrap();
    let mut s = state(0., 0.5, 0.);
    s[1] = 0.5;
    w.add_uniform(1, &s, &CUBE, faces).unwrap();
    (w, s)
}

#[test]
fn a_hull_at_rest_falls_asleep_and_stays_where_it_is() {
    let (mut w, _) = resting_world(&[]);
    let mut last = run(&mut w, 1, 1);
    for _ in 0..400 {
        last = run(&mut w, 1, 1);
    }
    let handle = w.crafts[&1].handle;
    assert!(
        w.bodies[handle].is_sleeping(),
        "a resting hull never fell asleep"
    );
    let y = last[1];
    for _ in 0..200 {
        last = run(&mut w, 1, 1);
    }
    assert!((last[1] - y).abs() < 1.0e-9, "a sleeping hull moved");
    assert!(
        Vec3::new(last[7], last[8], last[9]).norm() < 1.0e-3,
        "a sleeping hull stored speed"
    );
}

#[test]
fn a_sleeping_hull_wakes_for_an_impulse_without_having_piled_up_gravity() {
    let (mut w, _) = resting_world(&[]);
    for _ in 0..400 {
        run(&mut w, 1, 1);
    }
    let handle = w.crafts[&1].handle;
    assert!(w.bodies[handle].is_sleeping());
    // Ten more seconds asleep must not store ten seconds of falling.
    for _ in 0..200 {
        run(&mut w, 1, 1);
    }
    w.impulse(1, Vec3::x() * 100.).unwrap();
    let after = run(&mut w, 1, 1);
    assert!(after[7] > 0.5, "the impulse did nothing, vx = {}", after[7]);
    assert!(
        after[8].abs() < 0.5,
        "a woken hull had stored vertical speed {}",
        after[8]
    );
}

#[test]
fn a_sleeping_hull_wakes_and_falls_when_its_ground_goes() {
    let (mut w, _) = resting_world(&[]);
    for _ in 0..400 {
        run(&mut w, 1, 1);
    }
    let handle = w.crafts[&1].handle;
    assert!(w.bodies[handle].is_sleeping());
    // The ground is replaced by a box far away.
    w.set_terrain(1, &[500., -0.5, 500., 1., 0.5, 1., 0.7, 0.])
        .unwrap();
    let mut y = f64::MAX;
    for _ in 0..40 {
        y = run(&mut w, 1, 1)[1];
    }
    assert!(
        y < 0.0,
        "a hull stayed in the air over removed ground, y = {y}"
    );
}

#[test]
fn wind_wakes_a_sleeping_hull() {
    let (mut w, _) = resting_world(&[0., -0.5, 0., 0., -1., 0., 0., 1.]);
    for _ in 0..400 {
        run(&mut w, 1, 1);
    }
    let handle = w.crafts[&1].handle;
    assert!(w.bodies[handle].is_sleeping());
    let mut vx = 0.0;
    for _ in 0..60 {
        vx = step(&mut w, Vec3::new(60., 0., 0.), &[1]).unwrap()[7];
    }
    assert!(vx > 0.0, "wind did not move a sleeping hull");
}

#[test]
fn water_arriving_under_a_sleeping_hull_wakes_it() {
    let (mut w, _) = resting_world(&[]);
    for _ in 0..400 {
        run(&mut w, 1, 1);
    }
    let handle = w.crafts[&1].handle;
    assert!(w.bodies[handle].is_sleeping());
    // A pool appears around the cube: it is light (100 kg/m3), so it must rise out of the sleep and float.
    let mut cells = Vec::new();
    for x in -2..=2 {
        for z in -2..=2 {
            cells.extend([f64::from(x), 0., f64::from(z), 1., 0., 0., 0.]);
            cells.extend([f64::from(x), 1., f64::from(z), 1., 0., 0., 0.]);
        }
    }
    w.water.replace(7, &cells).unwrap();
    let mut y = 0.0;
    for _ in 0..80 {
        y = run(&mut w, 1, 1)[1];
    }
    assert!(
        y > 0.7,
        "a sleeping hull did not float when water came, y = {y}"
    );
}

#[test]
fn only_bodies_that_moved_are_reported_and_falling_asleep_is_reported_once() {
    let (mut w, _) = resting_world(&[]);
    let mut last = Vec::new();
    let mut ticks = 0;
    loop {
        let out = w.step(ZERO).unwrap();
        if out.is_empty() {
            break;
        }
        assert_eq!(out.len(), RECORD_LEN);
        assert_eq!(out[0], 1.0);
        last = out;
        ticks += 1;
        assert!(ticks < 400, "a resting hull never fell asleep");
    }
    assert_eq!(
        last[RECORD_LEN - 1],
        1.0,
        "the last report must say that the body sleeps"
    );
    w.wake(1).unwrap();
    let out = w.step(ZERO).unwrap();
    assert_eq!(out.len(), RECORD_LEN);
    assert_eq!(out[RECORD_LEN - 1], 0.0);
    w.set_active(1, false).unwrap();
    assert!(w.step(ZERO).unwrap().is_empty());
}

#[test]
fn a_load_that_stands_still_neither_wakes_nor_speeds_up_a_sleeping_hull() {
    let (mut w, _) = resting_world(&[]);
    for _ in 0..400 {
        run(&mut w, 1, 1);
    }
    let handle = w.crafts[&1].handle;
    for _ in 0..50 {
        w.load_at(1, Vec3::new(0., -34., 0.), Pos::new(0.2, 1., 0.), true)
            .unwrap();
        assert!(w.step(ZERO).unwrap().is_empty());
    }
    assert!(w.bodies[handle].is_sleeping());
    assert_eq!(w.bodies[handle].linvel().norm(), 0.0);
}

#[test]
fn a_hull_under_a_still_load_can_fall_asleep() {
    let (mut w, _) = resting_world(&[]);
    for _ in 0..400 {
        w.load_at(1, Vec3::new(0., -34., 0.), Pos::new(0.2, 1., 0.), true)
            .unwrap();
        w.step(ZERO).unwrap();
    }
    let handle = w.crafts[&1].handle;
    assert!(w.bodies[handle].is_sleeping());
}

#[test]
fn water_draining_from_under_a_sleeping_hull_lets_it_fall() {
    let mut w = World::default();
    let mut cells = Vec::new();
    for x in -3..=3 {
        for z in -3..=3 {
            for y in 0..3 {
                cells.extend([f64::from(x), f64::from(y), f64::from(z), 1., 0., 0., 0.]);
            }
        }
    }
    w.water.replace(7, &cells).unwrap();
    w.add_uniform(1, &state(0.5, 2.9, 0.5), &CUBE, &[]).unwrap();
    for _ in 0..1200 {
        w.step(ZERO).unwrap();
    }
    let handle = w.crafts[&1].handle;
    let before = w.bodies[handle].translation().y;
    assert!(before > 2.0, "the hull does not float, y = {before}");
    w.water.replace(7, &[]).unwrap();
    for _ in 0..20 {
        w.step(ZERO).unwrap();
    }
    let after = w.bodies[handle].translation().y;
    assert!(after < before - 2.0, "y went from {before} to {after}");
}

#[test]
fn merged_hull_keeps_the_mass_and_inertia_of_its_blocks_and_the_friction_of_each_face() {
    // Two blocks, stone under wood, as one box against the same two as boxes of their own.
    let low = [0., 0., 0., 0.5, 0.5, 0.5, 2000., 0.9, 0.];
    let high = [0., 1., 0., 0.5, 0.5, 0.5, 500., 0.1, 0.];
    let merged = [0., 0.5, 0., 0.5, 1., 0.5, -1., 0.];
    let masses = [&low[..7], &high[..7]].concat();
    let mut apart = World::default();
    apart
        .add_uniform(1, &state(0., 1., 0.), &[low, high].concat(), &[])
        .unwrap();
    let mut one = World::default();
    one.add_body(1, &state(0., 1., 0.), &merged, &[0.9, 0.1], &masses, &[])
        .unwrap();
    let (a, b) = (
        &apart.bodies[apart.crafts[&1].handle],
        &one.bodies[one.crafts[&1].handle],
    );
    near(a.mass(), b.mass(), 1.0e-9);
    near(
        a.local_center_of_mass().y,
        b.local_center_of_mass().y,
        1.0e-9,
    );
    near(b.local_center_of_mass().y - merged[1], -0.3, 1.0e-9);
    let tensor = |b: &RigidBody| {
        b.mass_properties()
            .local_mprops
            .reconstruct_inertia_matrix()
    };
    assert!((tensor(a) - tensor(b)).norm() < 1.0e-6);

    // The face that lies on the ground decides how far it slides.
    let slide = |upside_down: bool| {
        let mut w = World::default();
        w.set_terrain(1, &[0., -0.5, 0., 200., 0.5, 200., 2., 0.])
            .unwrap();
        let mut s = state(0., if upside_down { 1.0 } else { 0.0 }, 0.);
        let flat = [0., 0.5, 0., 1.5, 0.5, 1.5, -1., 0.];
        let layers = [0.9, 0.9, 0.9, 0.1, 0.1, 0.1].repeat(3);
        if upside_down {
            (s[3], s[6]) = (1., 0.);
        }
        s[7] = 5.;
        w.add_body(
            1,
            &s,
            &flat,
            &layers,
            &[0., 0.5, 0., 1.5, 0.5, 1.5, 500.],
            &[],
        )
        .unwrap();
        for _ in 0..200 {
            w.step(ZERO).unwrap();
        }
        w.bodies[w.crafts[&1].handle].translation().x
    };
    let expected = |mu: f64| 25. / (2. * mu * GRAVITY);
    near(slide(false), expected(0.9), 0.15);
    near(slide(true), expected(0.1), 0.6);
}

#[test]
fn two_skids_on_one_rotated_deck_feel_the_material_directly_under_each() {
    let mut w = World::default();
    // Deck spans x=-1..1, z=-5..5 in hull-local block cells; the left lane is ice.
    let mut deck = state(38., 2., -50.);
    deck[3..7].copy_from_slice(
        UnitQuaternion::from_axis_angle(&Vec3::y_axis(), std::f64::consts::FRAC_PI_2)
            .coords
            .as_slice(),
    );
    let shape = [0., 0., 0., 1.5, 0.5, 5.5, -1., 0.];
    let mass = [0., 0., 0., 1.5, 0.5, 5.5, 800.];
    let cells = [0.05, 0.5, 0.85].repeat(11);
    for invalid in [&cells[..32], &[0.; 34][..], &[f64::NAN; 33][..]] {
        assert!(w.add_body(1, &deck, &shape, invalid, &mass, &[]).is_err());
        assert!(w.crafts.is_empty() && w.colliders.is_empty() && w.contacts.friction.is_empty());
    }
    w.add_body(1, &deck, &shape, &cells, &mass, &[]).unwrap();
    w.set_active(1, false).unwrap();
    for (id, z) in [(2, -49.), (3, -51.)] {
        let mut s = state(36., 2.75, z);
        s[7] = 4.0;
        w.add_uniform(id, &s, &[0., 0., 0., 0.3, 0.25, 0.3, 500., 1., 0.], &[])
            .unwrap();
    }
    for _ in 0..10 {
        w.advance(ZERO).unwrap();
    }
    let vx = |id| w.bodies[w.crafts[&id].handle].linvel().x;
    assert!(
        vx(2) > 3.5 && vx(3).abs() < 0.3,
        "ice {}, rough {}",
        vx(2),
        vx(3)
    );
    w.remove_body(1).unwrap();
    assert!(w.contacts.friction.is_empty());
}

/// Platforms dropped onto ground made of tiles, as the game makes it: milliseconds per tick while they fall, and while
/// they lie and have yet to fall asleep. Run with `cargo test --release bench_ -- --ignored --nocapture`.
fn bench_drop(rows: usize, side: f64, gap: f64, tile: i32) -> (f64, f64) {
    let mut w = World::default();
    let reach = (rows as f64 * (side + gap)) as i32 / tile + 2;
    for x in -1..reach {
        for z in -1..reach {
            let half = f64::from(tile) / 2.;
            let at = |v: i32| f64::from(v * tile) + half;
            let mut boxes = Vec::new();
            for (y, h, mu) in [(-0.5, 0.5, 0.6), (-2.0, 1.0, 0.65), (-3.5, 0.5, 0.7)] {
                boxes.extend([at(x), y, at(z), half, h, half, mu, 0.]);
            }
            w.set_terrain(i64::from(x * 10_000 + z), &boxes).unwrap();
        }
    }
    let half = side / 2.;
    for x in 0..rows {
        for z in 0..rows {
            let at = |v: usize| v as f64 * (side + gap) + half;
            let cube = [0., 0., 0., half, 0.5, half, 480., 0.55, 0.];
            w.add_uniform(
                (x * 100 + z + 1) as i64,
                &state(at(x), 20.5, at(z)),
                &cube,
                &[],
            )
            .unwrap();
        }
    }
    let mut time = |ticks: usize| {
        let start = std::time::Instant::now();
        for _ in 0..ticks {
            w.step(ZERO).unwrap();
        }
        start.elapsed().as_secs_f64() * 1000. / ticks as f64
    };
    (time(38), time(40))
}

#[test]
#[ignore]
fn bench_drops() {
    for (name, rows, side, gap) in [
        ("platforms", 4, 16., 2.),
        ("many", 8, 4., 2.),
        ("big", 1, 60., 0.),
    ] {
        for tile in [8, 16] {
            let (fall, lie) = bench_drop(rows, side, gap, tile);
            eprintln!(
                "BENCH {name:>10} tiles of {tile:>2}: falling {fall:7.3}, lying {lie:7.3} ms per tick"
            );
        }
    }
}

/// Deepest a metre cube gets into a metre cube in its way, either fixed or coming at it, starting `gap` m away.
fn deepest(gap: f64, speed: f64, head_on: bool) -> f64 {
    let mut w = World::default();
    let hull = [0., 0., 0., 0.5, 0.5, 0.5, 480., 0.55, 0.];
    let mut s = state(-gap - 0.5, 5., 0.);
    s[7] = speed;
    w.add_uniform(1, &s, &hull, &[]).unwrap();
    let mut other = state(0.5, 5., 0.);
    other[7] = -speed;
    if head_on {
        w.add_uniform(2, &other, &hull, &[]).unwrap();
    } else {
        w.set_terrain(0, &[0.5, 5., 0., 0.5, 0.5, 0.5, 0.6, 0.])
            .unwrap();
    }
    (0..10).fold(0.0, |worst: f64, _| {
        w.advance(ZERO).unwrap();
        let x = |id| w.bodies[w.crafts[&id].handle].translation().x;
        let other = if head_on { x(2) } else { 0.5 };
        worst.max(1.0 - (other - x(1)))
    })
}

#[test]
fn fast_hulls_stop_at_the_surface_of_what_they_hit() {
    for head_on in [false, true] {
        for speed in [5., 20., 39., 60., 80.] {
            for i in 0..12 {
                // Not so far that it falls past what it should hit.
                let gap = (0.3 + 0.23 * f64::from(i)).min(speed * 0.2);
                let depth = deepest(gap, speed, head_on);
                assert!(
                    depth < 0.06,
                    "{speed} m/s from {gap} m away, head on {head_on}: {depth} m deep"
                );
            }
        }
    }
}

#[test]
fn a_thin_wide_plate_does_not_pass_through_a_thin_floor_however_it_lands() {
    for speed in [0., 15., 38., 79.] {
        for tilt in [0.05, 0.4, 1.2] {
            for spin in [0., 3., 12., 30.] {
                let mut w = World::default();
                w.set_terrain(1, &[0., -0.1, 0., 40., 0.1, 40., 0.6, 0.])
                    .unwrap();
                let mut s = state(0., 6., 0.);
                let q = UnitQuaternion::from_euler_angles(tilt, 0.3, 0.);
                s[3..7].copy_from_slice(q.coords.as_slice());
                s[8] = -speed;
                s[10] = spin;
                w.add_uniform(1, &s, &[0., 0., 0., 4., 0.1, 4., 200., 0.5, 0.], &[])
                    .unwrap();
                for _ in 0..100 {
                    let y = run(&mut w, 1, 1)[1];
                    assert!(
                        y > -0.1,
                        "speed {speed} tilt {tilt} spin {spin}: plate at {y}"
                    );
                }
            }
        }
    }
}

/// A platform sliding over ground in one piece or in tiles: how much it hops, and how fast it still is.
fn slide_over_tiles(tiled: bool, speed: f64, yaw: f64) -> (f64, f64) {
    let mut w = World::default();
    if tiled {
        for x in -2..20 {
            for z in -3..3 {
                let at = |v: i32| f64::from(v * 8) + 4.;
                w.set_terrain(
                    i64::from(x * 100 + z),
                    &[at(x), -4., at(z), 4., 4., 4., 0.3, 0.],
                )
                .unwrap();
            }
        }
    } else {
        w.set_terrain(1, &[72., -4., 0., 88., 4., 24., 0.3, 0.])
            .unwrap();
    }
    let mut s = state(0., 0.5, 0.);
    s[4] = (yaw / 2.).sin();
    s[6] = (yaw / 2.).cos();
    s[7] = speed;
    w.add_uniform(1, &s, &[0., 0., 0., 1.5, 0.5, 1.5, 480., 0.55, 0.], &[])
        .unwrap();
    let (mut low, mut high, mut vx) = (f64::MAX, f64::MIN, 0.);
    for _ in 0..30 {
        let out = run(&mut w, 1, 1);
        low = low.min(out[1]);
        high = high.max(out[1]);
        vx = out[7];
    }
    (high - low, vx)
}

#[test]
fn foreseen_contacts_do_not_catch_on_the_seams_between_terrain_tiles() {
    for speed in [4., 12., 30., 60.] {
        for yaw in [0., 0.2, 0.7] {
            let (hop, vx) = slide_over_tiles(true, speed, yaw);
            let (_, smooth) = slide_over_tiles(false, speed, yaw);
            println!(
                "SEAMS {speed} m/s yaw {yaw}: hop {hop:.4} m, {vx:.3} against {smooth:.3} m/s"
            );
            assert!(hop < 0.03 && (vx - smooth).abs() < 0.3);
        }
    }
}

#[test]
fn ground_still_carries_a_hull_that_leans_on_a_wall() {
    // Floor, a wall standing on it, and a second floor tile that the hull reaches across.
    let mut w = World::default();
    w.set_terrain(1, &[-4., -0.5, 0., 4., 0.5, 4., 0.6, 0.])
        .unwrap();
    w.set_terrain(2, &[4., -0.5, 0., 4., 0.5, 4., 0.6, 0.])
        .unwrap();
    w.set_terrain(3, &[2.5, 1., 0., 0.5, 1., 4., 0.6, 0.])
        .unwrap();
    let mut s = state(0.45, 0.52, 0.);
    s[7] = 3.;
    w.add_uniform(1, &s, &[0., 0., 0., 1.5, 0.5, 1.5, 480., 0.1, 0.], &[])
        .unwrap();
    for _ in 0..200 {
        // Pressed against the wall all the while.
        w.impulse(1, Vec3::x() * 300.).unwrap();
        let out = run(&mut w, 1, 1);
        let tilt = Vec3::new(out[3], out[4], out[5]).norm();
        assert!(
            out[1] > 0.48 && out[1] < 0.53 && out[0] < 0.53 && tilt < 0.01,
            "{out:?}"
        );
    }
    w.set_terrain(1, &[]).unwrap();
    w.set_terrain(2, &[]).unwrap();
    w.set_terrain(3, &[]).unwrap();
    assert!(w.contacts.ground.is_empty());
    for _ in 0..20 {
        w.advance(ZERO).unwrap();
    }
    assert!(run(&mut w, 1, 1)[1] < -1., "removed ground still carries");
}

#[test]
fn a_cube_slides_over_deck_seams_with_or_without_an_index() {
    for half in [3, 12] {
        let deck: Vec<f64> = (-half..half)
            .flat_map(|x| [f64::from(x) + 0.5, 0., 0., 0.5, 0.5, 2., 480., 0.2, 0.])
            .collect();
        for yaw in [0., 0.3, 0.7] {
            let mut w = World::default();
            w.add_uniform(1, &state(0., 2., 0.), &deck, &[]).unwrap();
            w.set_active(1, false).unwrap();
            let mut s = state(1. - f64::from(half), 3., 0.);
            s[4] = (yaw / 2_f64).sin();
            s[6] = (yaw / 2_f64).cos();
            s[7] = 10.;
            w.add_uniform(2, &s, &CUBE, &[]).unwrap();
            for tick in 1..=30.min(4 * half - 4) {
                let out = run(&mut w, 2, 1);
                near(out[1], 3., 0.01);
                near(out[7], 10. - 0.2 * GRAVITY * 0.05 * f64::from(tick), 0.1);
            }
            w.remove_body(1).unwrap();
            assert!(w.contacts.hulls.is_empty());
        }
    }
}

#[test]
fn a_hull_caught_inside_the_ground_comes_out_of_it() {
    let mut w = World::default();
    for (i, y) in [-0.5, -1.5, -2.5].into_iter().enumerate() {
        w.set_terrain(i as i64, &[0., y, 0., 8., 0.5, 8., 0.6, 0.])
            .unwrap();
    }
    w.add_uniform(1, &state(0., -0.2, 0.), &CUBE, &[]).unwrap();
    let mut y = 0.;
    for _ in 0..200 {
        y = run(&mut w, 1, 1)[1];
        assert!(y > -0.3, "sank to {y}");
    }
    near(y, 0.5, 0.02);
}

#[test]
fn no_corner_turns_faster_than_the_speed_limit() {
    let mut w = World::default();
    let mut s = state(0., 50., 0.);
    s[11] = 30.;
    let plate = [0., 0., 0., 8., 0.5, 8., 480., 0.5, 0.];
    w.add_uniform(1, &s, &plate, &[]).unwrap();
    let craft = &w.crafts[&1];
    near(
        w.bodies[craft.handle].angvel().norm() * craft.radius,
        MAX_SPEED,
        1.0e-9,
    );
    // Blocks are not slowed down by it.
    w.add_uniform(2, &s, &CUBE, &[]).unwrap();
    near(
        w.bodies[w.crafts[&2].handle].angvel().norm(),
        MAX_SPIN,
        1.0e-9,
    );
}

#[test]
fn many_stacks_carry_their_load_and_settle_reproducibly() {
    // 144 stacks of four: enough moving colliders to exercise parallel broad-phase box calculation, too.
    let build = || {
        let mut w = World::default();
        w.set_terrain(1, &[0., -0.5, 0., 240., 0.5, 20., 0.7, 0.])
            .unwrap();
        for i in 0..576 {
            let (column, level) = (i / 4, i % 4);
            let mut cube = CUBE;
            cube[6] = if level == 3 { 6000. } else { 300. };
            let x = f64::from(column) * 3. - 216. + 0.03 * f64::from(level % 2);
            w.add_uniform(
                i64::from(i) + 1,
                &state(x, 0.5 + f64::from(level), 0.),
                &cube,
                &[],
            )
            .unwrap();
        }
        w
    };
    let (mut a, mut b) = (build(), build());
    for _ in 0..200 {
        a.advance(ZERO).unwrap();
        b.advance(ZERO).unwrap();
    }
    for (id, craft) in &a.crafts {
        let (p, q) = (
            a.bodies[craft.handle].position(),
            b.bodies[b.crafts[id].handle].position(),
        );
        // Rapier itself takes pairs in no fixed order, so not to the last bit.
        let apart = (p.translation.vector - q.translation.vector).norm();
        assert!(apart < 1.0e-3, "body {id} is {apart} m elsewhere");
        let level = (id - 1) % 4;
        let sink = 0.5 + level as f64 - p.translation.y;
        assert!((-0.01..0.1).contains(&sink), "body {id} sank {sink}");
        assert!(a.bodies[craft.handle].is_sleeping());
    }
}

/// Deepest overlap of any two bodies, and of any body and the ground, at the contacts the engine last found.
fn overlaps(w: &World) -> (f64, f64) {
    let (mut bodies, mut ground) = (0.0_f64, 0.0_f64);
    for m in w.narrow_phase.contact_pairs().flat_map(|p| &p.manifolds) {
        let depth = m.points.iter().map(|p| -p.dist).fold(0.0, f64::max);
        if m.data.rigid_body1.is_some() && m.data.rigid_body2.is_some() {
            bodies = bodies.max(depth);
        } else {
            ground = ground.max(depth);
        }
    }
    (bodies, ground)
}

fn level_ground() -> World {
    let mut w = World::default();
    for x in -8..8 {
        for z in -8..8 {
            let at = |v: i64| v as f64 * 16. + 8.;
            w.set_terrain(x * 100 + z, &[at(x), -8., at(z), 8., 8., 8., 0.6, 0.])
                .unwrap();
        }
    }
    w
}

const WOOL: f64 = 55.;
const OAK: f64 = 480.;
const GOLD: f64 = 19_300.;

/// Runs `ticks` and fails if anything overlaps by more than `depth`, is not asleep in the end, or twitches once `calm` ticks
/// have passed: no body may change its velocity by more than 0.05 m/s from one tick to the next.
fn must_settle(name: &str, w: &mut World, ticks: usize, calm: usize, depth: f64) {
    let mut last: HashMap<i64, Vec3> = HashMap::new();
    for tick in 0..ticks {
        w.advance(ZERO).unwrap();
        let (bodies, ground) = overlaps(w);
        assert!(
            bodies < depth && ground < depth,
            "{name}, tick {tick}: {bodies} m into a body, {ground} m into the ground"
        );
        for (id, craft) in &w.crafts {
            let body = &w.bodies[craft.handle];
            let v = if body.is_sleeping() {
                Vec3::zeros()
            } else {
                *body.linvel()
            };
            let change = last.insert(*id, v).map_or(0.0, |old| (v - old).norm());
            assert!(
                tick < calm || change < 0.05,
                "{name}, tick {tick}: body {id} twitched by {change} m/s"
            );
        }
    }
    for (id, craft) in &w.crafts {
        assert!(
            w.bodies[craft.handle].is_sleeping(),
            "{name}: body {id} does not come to rest"
        );
    }
}

#[test]
fn heavy_crafts_piled_on_a_light_one_neither_sink_into_it_nor_twitch() {
    for (name, raft, load, count) in [
        ("1 of gold on oak", OAK, GOLD, 1),
        ("10 of gold on oak", OAK, GOLD, 10),
        ("10 of gold on wool", WOOL, GOLD, 10),
        ("30 of gold on oak", OAK, GOLD, 30),
        ("30 of oak on oak", OAK, OAK, 30),
        ("10 of wool on gold", GOLD, WOOL, 10),
    ] {
        let mut w = level_ground();
        let deck = [0., 0., 0., 2.5, 0.5, 2.5, raft, 0.55, 0.];
        w.add_uniform(1, &state(0., 0.5, 0.), &deck, &[]).unwrap();
        for i in 0..count {
            let cube = [0., 0., 0., 1.5, 1.5, 1.5, load, 0.45, 0.];
            let y = 2.5 + 3.005 * i as f64;
            w.add_uniform(2 + i, &state(0., y, 0.), &cube, &[]).unwrap();
        }
        must_settle(name, &mut w, 160, 60, 0.03);
        let top = w.bodies[w.crafts[&(1 + count)].handle].translation().y;
        near(
            top,
            2.5 + 3. * (count - 1) as f64,
            0.004 * (count + 1) as f64,
        );
    }
}

#[test]
fn layers_of_heavy_crafts_dropped_on_a_light_one_settle() {
    for (name, side, height, columns, layers) in [
        ("80 large of gold", 5., 3., 2, 20),
        ("288 blocks of gold", 1., 1., 6, 8),
    ] {
        let mut w = level_ground();
        let raft = [0., 0., 0., 8., 0.5, 8., OAK, 0.55, 0.];
        w.add_uniform(1, &state(0., 0.5, 0.), &raft, &[]).unwrap();
        let mut id = 2;
        for layer in 0..layers {
            for x in 0..columns {
                for z in 0..columns {
                    let at = |i: i32| (f64::from(i) - f64::from(columns - 1) / 2.) * (side + 1.);
                    let cube = [
                        0.,
                        0.,
                        0.,
                        side / 2.,
                        height / 2.,
                        side / 2.,
                        GOLD,
                        0.45,
                        0.,
                    ];
                    let y = 1.5 + height / 2. + (height + 1.) * f64::from(layer);
                    w.add_uniform(id, &state(at(x), y, at(z)), &cube, &[])
                        .unwrap();
                    id += 1;
                }
            }
        }
        must_settle(name, &mut w, 200, 120, 0.06);
    }
}

#[test]
fn side_push_moves_all_144_rafts() {
    let mut w = World::default();
    w.set_terrain(1, &[140., -0.5, 0., 180., 0.5, 48., 0.06, 0.])
        .unwrap();
    let bar = [0., 0., 0., 0.5, 0.5, 6.7, 9000., 0.06, 0.];
    let cube = [0., 0., 0., 0.5, 0.5, 0.5, OAK, 0.06, 0.];
    w.add_uniform(1, &state(0., 0.5, 0.), &bar, &[]).unwrap();
    let x_at = |i| 1.14 + f64::from(i) * 1.04;
    for x in 0..12 {
        for z in 0..12 {
            let id = i64::from(x * 12 + z + 2);
            w.add_uniform(
                id,
                &state(x_at(x), 0.5, (f64::from(z) - 5.5) * 1.12),
                &cube,
                &[],
            )
            .unwrap();
        }
    }
    for _ in 0..25 {
        w.advance(ZERO).unwrap();
    }
    let bar = w.crafts[&1].handle;
    for _ in 0..30 {
        let body = &w.bodies[bar];
        let impulse = (12. - body.linvel().x) * body.mass();
        w.impulse(1, Vec3::x() * impulse).unwrap();
        w.advance(ZERO).unwrap();
    }
    for i in 0..144 {
        let body = &w.bodies[w.crafts[&i64::from(i + 2)].handle];
        assert!(
            body.translation().x > x_at(i / 12) + 2.0,
            "raft {i} was not pushed: {:?}",
            body.translation()
        );
    }
}

#[test]
fn a_heavy_craft_wakes_a_light_one_asleep_and_does_not_enter_it() {
    for height in [0.001, 2., 20.] {
        let mut w = level_ground();
        let deck = [0., 0., 0., 2.5, 0.5, 2.5, OAK, 0.55, 0.];
        w.add_uniform(1, &state(0., 0.5, 0.), &deck, &[]).unwrap();
        for _ in 0..150 {
            w.advance(ZERO).unwrap();
        }
        assert!(w.bodies[w.crafts[&1].handle].is_sleeping());
        let cube = [0., 0., 0., 1.5, 1.5, 1.5, GOLD, 0.45, 0.];
        w.add_uniform(2, &state(0., 2.5 + height, 0.), &cube, &[])
            .unwrap();
        must_settle(&format!("from {height} m"), &mut w, 250, 150, 0.08);
        near(w.bodies[w.crafts[&2].handle].translation().y, 2.5, 0.01);
    }
}

#[test]
fn crafts_of_many_boxes_stacked_askew_lie_still() {
    // Decks of two materials, each turned and shifted against the one below: contacts cross the seams of both.
    let deck = |n: i32| -> Vec<f64> {
        (0..n * n)
            .flat_map(|i| {
                let (x, z) = (i % n, i / n);
                let mu = if (x + z) % 2 == 0 { 0.5 } else { 0.7 };
                let at = |v: i32| f64::from(v - n / 2);
                [at(x), 0., at(z), 0.5, 0.5, 0.5, OAK, mu, 0.]
            })
            .collect()
    };
    for shove in [0., 1.] {
        let mut w = level_ground();
        for i in 0..6 {
            let mut s = state(0.3 * i as f64, 0.52 + 1.05 * i as f64, 0.21 * i as f64);
            let turned = UnitQuaternion::from_euler_angles(0., 0.35 * i as f64, 0.);
            s[3..7].copy_from_slice(turned.coords.as_slice());
            s[7] = shove * f64::from(u8::from(i > 0));
            w.add_uniform(i + 1, &s, &deck(5), &[]).unwrap();
        }
        must_settle("decks askew", &mut w, 200, 60, 0.03);
    }
}

#[test]
fn every_point_of_a_contact_has_the_friction_of_the_block_under_it() {
    // Half of the sole is ice: half the friction, whichever half it is, and the rough side drags behind.
    let slide = |cells: [f64; 2]| {
        let mut w = World::default();
        w.set_terrain(1, &[0., -0.5, 0., 100., 0.5, 100., 2., 0.])
            .unwrap();
        let mut s = state(0., 0.5, 0.);
        s[7] = 3.;
        let shape = [0., 0., 0.5, 0.5, 0.5, 1., -1., 0.];
        let mass = [0., 0., 0.5, 0.5, 0.5, 1., 500.];
        w.add_body(1, &s, &shape, &cells, &mass, &[]).unwrap();
        let out = run(&mut w, 1, 4);
        (out[7], out[11])
    };
    let (left, right) = (slide([0., 0.8]), slide([0.8, 0.]));
    near(left.0, 3. - 0.4 * GRAVITY * 0.2, 0.1);
    near(left.0, right.0, 0.02);
    near(left.1, -right.1, 0.02);
    assert!(left.1.abs() > 0.3, "no turn: {left:?} {right:?}");
}

/// What `/aerolith spawn 10000 1 1 1` makes, in one go: columns of blocks in layers, landing on one another.
#[test]
#[ignore]
fn bench_swarm() {
    let count: i64 = std::env::var("N").map_or(10_000, |n| n.parse().unwrap());
    let mut w = World::default();
    for x in -1..8 {
        for z in -1..8 {
            let at = |v: i64| v as f64 * 16. + 8.;
            w.set_terrain(x * 100 + z, &[at(x), -8., at(z), 8., 8., 8., 0.6, 0.])
                .unwrap();
        }
    }
    for i in 0..count {
        let (x, z, layer) = (i % 48, i / 48 % 48, i / (48 * 48));
        let at = state(
            x as f64 * 2. + 0.5,
            2.5 + layer as f64 * 2.,
            z as f64 * 2. + 0.5,
        );
        let block = [0., 0., 0., 0.5, 0.5, 0.5, OAK, 0.55, 0.];
        w.add_uniform(i + 1, &at, &block, &[]).unwrap();
    }
    for (phase, ticks) in [
        ("first", 1),
        ("falling", 10),
        ("landing", 20),
        ("lying", 20),
        ("settling", 30),
        ("asleep", 60),
    ] {
        let mut worst = 0.0_f64;
        let start = std::time::Instant::now();
        for _ in 0..ticks {
            let tick = std::time::Instant::now();
            w.step(ZERO).unwrap();
            worst = worst.max(tick.elapsed().as_secs_f64() * 1000.);
        }
        let mean = start.elapsed().as_secs_f64() * 1000. / f64::from(ticks);
        eprintln!("BENCH {count} blocks {phase:>9}: {mean:7.2} ms per tick, worst {worst:7.2}");
    }
}

/// A craft of 3 000 kg, so that the surfaces' own limit on what one tick of air may do is far away and their effects add up.
const HEAVY: [f64; 9] = [0., 0., 0., 0.5, 0.5, 0.5, 3000., 0.8, 0.];

/// One tick of a craft flying along +Z at 10 m/s with `kind` surfaces of one square metre at `at`, the stick at `stick`
/// (pitch, yaw, roll) and the flaps at `flaps`: its linear and angular velocity afterwards.
fn control_tick(
    kind: f64,
    at: &[[f64; 3]],
    normal: [f64; 3],
    stick: [f64; 3],
    flaps: f64,
) -> (Vec3, Vec3) {
    let mut surfaces = Vec::new();
    for p in at {
        surfaces.extend_from_slice(&[kind, p[0], p[1], p[2], normal[0], normal[1], normal[2], 1.0]);
    }
    let mut w = World::default();
    let mut s = state(0., 100., 0.);
    s[9] = 10.;
    w.add_uniform(1, &s, &HEAVY, &surfaces).unwrap();
    w.set_controls(1, stick[0], stick[1], stick[2]).unwrap();
    w.set_flaps(1, flaps).unwrap();
    let out = run(&mut w, 1, 1);
    (vec3(&out[7..10]), vec3(&out[10..13]))
}

#[test]
fn control_surfaces_turn_the_craft_the_way_the_stick_asks_wherever_they_are() {
    const ELEVATOR: f64 = 3.;
    const RUDDER: f64 = 4.;
    const AILERON: f64 = 5.;
    let (up, side) = ([0., 1., 0.], [1., 0., 0.]);
    // Pulling back lifts the nose, which is a turn about -X, whether the elevator is behind the centre of mass or ahead of it.
    for at in [[[0., 0., -3.]], [[0., 0., 3.]]] {
        let back = control_tick(ELEVATOR, &at, up, [1., 0., 0.], 0.).1;
        let forward = control_tick(ELEVATOR, &at, up, [-1., 0., 0.], 0.).1;
        assert!(
            back.x < -1.0e-3 && forward.x > 1.0e-3,
            "pitch {back:?} {forward:?}"
        );
        assert!(back.y.abs() < 1.0e-9 && back.z.abs() < 1.0e-9);
    }
    // Yaw to the left is a turn about +Y.
    for at in [[[0., 0., -3.]], [[0., 0., 3.]]] {
        let left = control_tick(RUDDER, &at, side, [0., 1., 0.], 0.).1;
        let right = control_tick(RUDDER, &at, side, [0., -1., 0.], 0.).1;
        assert!(
            left.y > 1.0e-3 && right.y < -1.0e-3,
            "yaw {left:?} {right:?}"
        );
    }
    // Rolling to the right is a turn about +Z; each aileron of a pair on opposite wings pulls its own way and the two add up.
    let pair = [[3., 0., 0.], [-3., 0., 0.]];
    let one = control_tick(AILERON, &pair[..1], up, [0., 0., 1.], 0.).1.z;
    let both = control_tick(AILERON, &pair, up, [0., 0., 1.], 0.).1;
    let reverse = control_tick(AILERON, &pair, up, [0., 0., -1.], 0.).1;
    assert!(
        one > 1.0e-3 && both.z > 1.7 * one && reverse.z < -1.7 * one,
        "roll {one} {both:?} {reverse:?}"
    );
    // What a neutral stick leaves is the air meeting the tail as the craft falls: a fraction of what the stick does.
    let tail = [[0., 0., -3.]];
    let neutral = control_tick(ELEVATOR, &tail, up, [0., 0., 0.], 0.).1;
    let pulled = control_tick(ELEVATOR, &tail, up, [1., 0., 0.], 0.).1;
    assert!(
        neutral.norm() < 0.2 * pulled.norm(),
        "{neutral:?} {pulled:?}"
    );
    // A wing that is no control surface ignores the stick, and one at the centre of mass has no lever to use.
    let wing = |stick: f64| control_tick(1., &tail, up, [stick, 0., 0.], 0.).1;
    assert_eq!(wing(0.), wing(1.));
    let pivot = |stick: f64| control_tick(ELEVATOR, &[[0., 0., 0.]], up, [stick, 0., 0.], 0.).1;
    assert_eq!(pivot(0.), pivot(1.));
}

#[test]
fn lowered_flaps_add_lift_and_drag_and_do_not_stall_the_wing_sooner() {
    let at = [[0., 0., 0.]];
    let tick = |flaps: f64| control_tick(6., &at, [0., 1., 0.], [0., 0., 0.], flaps).0;
    let (flat, half, down) = (tick(0.), tick(0.5), tick(1.));
    // The first tick of gravity is the same for all; what differs is lift (up) and drag (against the flight, +Z).
    assert!(
        half.y > flat.y + 1.0e-5 && down.y > half.y + 1.0e-5,
        "{flat:?} {half:?} {down:?}"
    );
    assert!(
        half.z < flat.z && down.z < half.z,
        "{flat:?} {half:?} {down:?}"
    );
    // Raised flaps (a negative setting) are not offered: the lever only goes down.
    assert!(
        World::default().set_flaps(1, -0.1).is_err() && World::default().set_flaps(1, 1.1).is_err()
    );
    assert!(
        World::default().set_flaps(1, 0.).is_err(),
        "an unknown craft has no flaps"
    );
    // With the flaps down, the wing still gives lift past the angle where a clean one lets go.
    let steep = |flap: f64| {
        fluids::foil_force_flapped(Vec3::new(0., -0.45, 1.), Vec3::y(), Vec3::z(), flap).y
    };
    assert!(steep(30_f64.to_radians()) > steep(0.0));
    // A clean foil is what it was.
    let v = Vec3::new(0., -0.2, 1.) * 30.;
    assert_eq!(
        fluids::foil_force(v, Vec3::y(), Vec3::z()),
        fluids::foil_force_flapped(v, Vec3::y(), Vec3::z(), 0.0)
    );
}

#[test]
fn sealed_cavity_buoyancy_floats_heavy_shell_without_interior_drag() {
    let mut water = Vec::new();
    for x in -4..=4 {
        for y in -6..=0 {
            for z in -4..=4 {
                water.extend_from_slice(&[x as f64, y as f64, z as f64, 1., 0., 0., 0.]);
            }
        }
    }
    // 5x5x5 outer shell of 1600 kg/m³ enclosing a 3x3x3 sealed interior air cavity (27 m³):
    // solid volume = 98 m³ (mass = 156,800 kg). Unsealed displaces <= 98 t and sinks;
    // sealed displaces 125 t * g > 156.8? With density = 1200: solid = 117.6 t < 125 t (floats) > 98 t (sinks!).
    let mut boxes = Vec::new();
    let mut masses = Vec::new();
    for x in -2_i32..=2 {
        for y in -2_i32..=2 {
            for z in -2_i32..=2 {
                if x.abs() < 2 && y.abs() < 2 && z.abs() < 2 {
                    continue;
                }
                boxes.extend_from_slice(&[f64::from(x), f64::from(y), f64::from(z), 0.5, 0.5, 0.5, 0.5, 0.0]);
                masses.extend_from_slice(&[f64::from(x), f64::from(y), f64::from(z), 0.5, 0.5, 0.5, 1200.0]);
            }
        }
    }
    let cavity = [0., 0., 0., 1.5, 1.5, 1.5];

    let mut sealed = World::default();
    sealed.water.replace(1, &water).unwrap();
    sealed
        .add_body_with_cavities(1, &state(0.5, -2.0, 0.5), &boxes, &[], &masses, &[], &cavity)
        .unwrap();

    let mut breached = World::default();
    breached.water.replace(1, &water).unwrap();
    breached
        .add_body_with_cavities(1, &state(0.5, -2.0, 0.5), &boxes, &[], &masses, &[], &[])
        .unwrap();

    let sealed_out = run(&mut sealed, 1, 180);
    let breached_out = run(&mut breached, 1, 180);
    // Water surface is y = +1.0, hull height = 5.0 (half 2.5), mass = 117.6 t -> draft = 4.704 m -> COM_y_eq = -1.204 m.
    assert!(
        (sealed_out[1] + 1.204).abs() < 0.15 && sealed_out[8].abs() < 0.2,
        "sealed hull should float near equilibrium y=-1.204, got y={} vy={}",
        sealed_out[1],
        sealed_out[8]
    );
    assert!(
        breached_out[1] < -4.0,
        "breached hull without sealed cavity must sink, got y={} vy={}",
        breached_out[1],
        breached_out[8]
    );
}

#[test]
fn face_skin_friction_damps_tangential_motion_and_smooth_stall_curve() {
    let mut w = World::default();
    // Long plate only with +/-Y faces: tangential airflow along +Z now creates skin-friction drag
    // and spin about Y damps even though no X/Z face normal opposes it.
    let top_bottom = [
        0., 0., 0.5, 0., 0., 1., 0., 20.,
        0., 0., -0.5, 0., 0., -1., 0., 20.,
    ];
    let mut s = state(0., 200., 0.);
    s[9] = 40.0;
    s[11] = 8.0;
    w.add_uniform(1, &s, &CUBE, &top_bottom).unwrap();
    let out = step(&mut w, ZERO, &[1]).unwrap();
    assert!(
        out[9] < 40.0,
        "skin friction must slow tangential airflow along a plate: vz={}",
        out[9]
    );
    assert!(
        out[11] < 8.0,
        "skin friction must damp spin parallel to large faces: wy={}",
        out[11]
    );

    // Post-stall lift decays smoothly across 15° -> 45° without abrupt spikes.
    let mut prev = f64::MAX;
    for deg in 16..=45 {
        let a = (deg as f64).to_radians();
        let f = fluids::foil_force(Vec3::new(0.0, -a.sin(), a.cos()) * 30.0, Vec3::y(), Vec3::z());
        let lift_mag = (f - Vec3::new(0.0, -a.sin(), a.cos())
            * (f.dot(&Vec3::new(0.0, -a.sin(), a.cos()))))
        .norm();
        assert!(
            lift_mag <= prev + 1.0e-6,
            "lift jumped upward at {deg}°: {lift_mag} > {prev}"
        );
        prev = lift_mag;
    }
}

#[test]
fn floating_light_rafts_damp_spin_and_settle_without_spinning_up() {
    let mut water = Vec::new();
    for x in -4..=4 {
        for y in -3..=0 {
            for z in -4..=4 {
                water.extend_from_slice(&[x as f64, y as f64, z as f64, 1., 0., 0., 0.]);
            }
        }
    }
    let mut w = World::default();
    w.water.replace(1, &water).unwrap();
    // 3x1x3 very light wool raft (55 kg/m³) given an initial small yaw + pitch disturbance in water.
    let shape = [0., 0., 0., 1.5, 0.5, 1.5, 0.8, 0.0];
    let mass = [0., 0., 0., 1.5, 0.5, 1.5, 55.0];
    let mut s = state(0.5, 0.8, 0.5);
    s[3] = 0.04;
    s[6] = (1.0_f64 - 0.04 * 0.04).sqrt();
    s[11] = 0.5;
    w.add_body(1, &s, &shape, &[], &mass, &[]).unwrap();
    let out = run(&mut w, 1, 200);
    let spin = vec3(&out[10..13]).norm();
    assert!(
        spin < 0.08,
        "floating light raft failed to damp spin or spun up: spin={spin}"
    );
}

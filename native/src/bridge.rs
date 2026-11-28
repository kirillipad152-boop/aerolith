use std::any::Any;
use std::sync::{LazyLock, Mutex, MutexGuard};

use jni::errors::{Error as JniError, ErrorPolicy, Result as JniResult};
use jni::objects::{JClass, JDoubleArray, JPrimitiveArray, TypeArray};
use jni::strings::JNIString;
use jni::sys::{jboolean, jdouble, jlong};
use jni::{Env, EnvUnowned, jni_str};

use super::*;

const MAX_WORLDS: usize = 64;

#[derive(Default)]
struct Registry {
    next: i64,
    worlds: BTreeMap<i64, World>,
}

impl Registry {
    fn allocate(&mut self) -> Result<i64> {
        self.next = self
            .next
            .checked_add(1)
            .ok_or(Fault::State("native handles exhausted"))?;
        Ok(self.next)
    }

    fn world(&mut self, handle: i64) -> Result<&mut World> {
        self.worlds
            .get_mut(&handle)
            .ok_or(Fault::Argument("unknown world handle"))
    }
}

static REGISTRY: LazyLock<Mutex<Registry>> = LazyLock::new(Mutex::default);

fn registry() -> Result<MutexGuard<'static, Registry>> {
    REGISTRY
        .lock()
        .map_err(|_| Fault::State("native physics registry is poisoned"))
}

fn in_world<T>(id: i64, f: impl FnOnce(&mut World) -> Result<T>) -> Result<T> {
    f(registry()?.world(id)?)
}

impl From<JniError> for Fault {
    fn from(error: JniError) -> Self {
        Self::Jni(error)
    }
}

struct JavaException;

impl<T: Default> ErrorPolicy<T, Fault> for JavaException {
    type Captures<'local: 'native, 'native> = ();

    fn on_error<'local: 'native, 'native>(
        env: &mut Env<'local>,
        _: &mut (),
        fault: Fault,
    ) -> JniResult<T> {
        if !env.exception_check() {
            let state = jni_str!("java/lang/IllegalStateException");
            let (class, msg) = match fault {
                Fault::Argument(message) => (
                    jni_str!("java/lang/IllegalArgumentException"),
                    JNIString::new(message),
                ),
                Fault::State(message) => (state, JNIString::new(message)),
                Fault::Jni(error) => (state, JNIString::new(error.to_string())),
            };
            match env.throw_new(class, msg) {
                Err(JniError::JavaException) | Ok(()) => (),
                Err(other) => return Err(other),
            }
        }
        Ok(T::default())
    }

    fn on_panic<'local: 'native, 'native>(
        env: &mut Env<'local>,
        _: &mut (),
        _: Box<dyn Any + Send>,
    ) -> JniResult<T> {
        Self::on_error(env, &mut (), Fault::State("native physics panicked"))
    }
}

fn read_array<T: TypeArray + Default>(
    env: &Env,
    array: &JPrimitiveArray<T>,
    max: usize,
) -> Result<Vec<T>> {
    if array.as_raw().is_null() {
        return Err(Fault::Argument("native physics arrays must not be null"));
    }
    let len = array.len(env)?;
    if len > max {
        return Err(Fault::Argument(
            "native physics array exceeds its maximum length",
        ));
    }
    let mut data = vec![T::default(); len];
    if len > 0 {
        array.get_region(env, 0, &mut data)?;
    }
    Ok(data)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_createWorld<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
) -> jlong {
    env.with_env(|_| {
        let mut registry = registry()?;
        if registry.worlds.len() >= MAX_WORLDS {
            return Err(Fault::State("native world limit reached"));
        }
        let id = registry.allocate()?;
        registry.worlds.insert(id, World::default());
        Ok(id)
    })
    .resolve::<JavaException>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_destroyWorld<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
) {
    env.with_env(|_| {
        registry()?
            .worlds
            .remove(&world)
            .ok_or(Fault::Argument("unknown world handle"))?;
        Ok(())
    })
    .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_addBody<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    state: JDoubleArray<'a>,
    boxes: JDoubleArray<'a>,
    friction_cells: JDoubleArray<'a>,
    masses: JDoubleArray<'a>,
    surfaces: JDoubleArray<'a>,
) -> jlong {
    env.with_env(|env| {
        let state = read_array(env, &state, STATE_LEN)?;
        let boxes = read_array(env, &boxes, 8 * MAX_BOXES)?;
        let cells = read_array(env, &friction_cells, MAX_BOXES)?;
        let masses = read_array(env, &masses, 7 * MAX_PARTS)?;
        let surfaces = read_array(env, &surfaces, 8 * MAX_SURFACES)?;
        let mut registry = registry()?;
        let id = registry.allocate()?;
        registry
            .world(world)?
            .add_body(id, &state, &boxes, &cells, &masses, &surfaces)?;
        Ok(id)
    })
    .resolve::<JavaException>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_addBodyWithCavities<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    state: JDoubleArray<'a>,
    boxes: JDoubleArray<'a>,
    friction_cells: JDoubleArray<'a>,
    masses: JDoubleArray<'a>,
    surfaces: JDoubleArray<'a>,
    cavities: JDoubleArray<'a>,
) -> jlong {
    env.with_env(|env| {
        let state = read_array(env, &state, STATE_LEN)?;
        let boxes = read_array(env, &boxes, 8 * MAX_BOXES)?;
        let cells = read_array(env, &friction_cells, MAX_BOXES)?;
        let masses = read_array(env, &masses, 7 * MAX_PARTS)?;
        let surfaces = read_array(env, &surfaces, 8 * MAX_SURFACES)?;
        let cavities = read_array(env, &cavities, 6 * MAX_BOXES)?;
        let mut registry = registry()?;
        let id = registry.allocate()?;
        registry.world(world)?.add_body_with_cavities(
            id, &state, &boxes, &cells, &masses, &surfaces, &cavities,
        )?;
        Ok(id)
    })
    .resolve::<JavaException>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_removeBody<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
) {
    env.with_env(|_| in_world(world, |w| w.remove_body(body)))
        .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_setActive<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
    active: jboolean,
) {
    env.with_env(|_| in_world(world, |w| w.set_active(body, active)))
        .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_setThrottle<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
    throttle: jdouble,
) {
    env.with_env(|_| in_world(world, |w| w.set_throttle(body, throttle)))
        .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_setFlaps<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
    flaps: jdouble,
) {
    env.with_env(|_| in_world(world, |w| w.set_flaps(body, flaps)))
        .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_setControls<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
    pitch: jdouble,
    yaw: jdouble,
    roll: jdouble,
) {
    env.with_env(|_| in_world(world, |w| w.set_controls(body, pitch, yaw, roll)))
        .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_impulse<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
    ix: jdouble,
    iy: jdouble,
    iz: jdouble,
) {
    env.with_env(|_| in_world(world, |w| w.impulse(body, Vec3::new(ix, iy, iz))))
        .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_staffPull<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
    ix: jdouble,
    iy: jdouble,
    iz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) {
    env.with_env(|_| {
        in_world(world, |w| {
            w.staff_pull(body, Vec3::new(ix, iy, iz), Quaternion::new(qw, qx, qy, qz))
        })
    })
    .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_impulseAt<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
    ix: jdouble,
    iy: jdouble,
    iz: jdouble,
    px: jdouble,
    py: jdouble,
    pz: jdouble,
) {
    env.with_env(|_| {
        in_world(world, |w| {
            w.impulse_at(body, Vec3::new(ix, iy, iz), Pos::new(px, py, pz))
        })
    })
    .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_loadAt<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
    ix: jdouble,
    iy: jdouble,
    iz: jdouble,
    px: jdouble,
    py: jdouble,
    pz: jdouble,
    still: jboolean,
) {
    env.with_env(|_| {
        in_world(world, |w| {
            w.load_at(body, Vec3::new(ix, iy, iz), Pos::new(px, py, pz), still)
        })
    })
    .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_wake<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    body: jlong,
) {
    env.with_env(|_| in_world(world, |w| w.wake(body)))
        .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_setTerrain<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    key: jlong,
    boxes: JDoubleArray<'a>,
) {
    env.with_env(|env| {
        let data = read_array(env, &boxes, 8 * MAX_TERRAIN_TILE)?;
        in_world(world, |w| w.set_terrain(key, &data))
    })
    .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_setWater<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    key: jlong,
    cells: JDoubleArray<'a>,
) {
    env.with_env(|env| {
        let data = read_array(env, &cells, 7 * MAX_WATER_TILE)?;
        in_world(world, |w| w.water.replace(key, &data))
    })
    .resolve::<JavaException>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerolith_NativePhysics_step<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    world: jlong,
    wind_x: jdouble,
    wind_y: jdouble,
    wind_z: jdouble,
) -> JDoubleArray<'a> {
    env.with_env(|env| {
        let data = in_world(world, |w| w.step(Vec3::new(wind_x, wind_y, wind_z)))?;
        let array = env.new_double_array(data.len())?;
        if !data.is_empty() {
            array.set_region(env, 0, &data)?;
        }
        Ok(array)
    })
    .resolve::<JavaException>()
}

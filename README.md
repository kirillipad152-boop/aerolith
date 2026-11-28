# Aerolith 0.4

A mod for **Minecraft Java 26.3**, **NeoForge 26.3.0.118** and **Java 25**. Java handles the world and the players, and the dynamics are computed by Rapier 3D in Rust, in double precision. Neither Sable nor Create is required. This is the port to the newest Minecraft and has no Create support; the Minecraft 1.21.1 version with Create is on the `main` branch.

Download the `-mc26.3.jar` asset from [Releases](https://github.com/kirillipad152-boop/aerolith/releases), not the source archive. Put it in the `mods` folder of a NeoForge profile and remove any older Aerolith jar. The release includes **Linux x86-64 (glibc 2.34+)** and **Windows x86-64 (Windows 10 or later)** natives. Use a matching x86-64 JVM; no Rust, compiler, or separate DLL is needed to play. On a dedicated server both the server and the clients need the jar. For macOS or a native ARM JVM, build on that platform.

Aerolith turns any structure you build into a **physics craft**: a rigid body with a mass, a centre of mass and an inertia tensor derived from its blocks. It collides with the world and with other crafts, floats on water, carries players and mobs, and is edited in place like ordinary blocks.

## Getting started

1. Make a **Craft Assembler**: put an amethyst shard, a copper ingot and a stick in a vertical column of the crafting grid (shard on top). In creative mode it is in the *Tools & Utilities* tab.
2. Build something on open ground.
3. With the assembler in hand, **use** it on one corner of the structure, then **sneak-use** it on the opposite corner. Every block in the box becomes one physics craft. Open water in the box stays in the world, and so does the ground the structure stands on if you leave it out of the box.
4. When it has come to rest, **sneak-use** the assembler on the craft to turn it back into ordinary blocks. The craft is aligned to the nearest straight direction first, and the action refuses to overwrite anything in the way.

### The Physics Assembler (survival)

The **Physics Assembler** block (crafted from iron ingots, an amethyst block, copper ingots and a piston) is the survival way to make a craft. Use it to turn the structure it is part of into a craft: every block that touches it, and the blocks touching those, within its range. Sneak-use it with an empty hand to cycle the range (1, 2, 4, 8, 16, 32 or 50 blocks on each axis from itself; 50 is a cube of just over a million blocks). Used again once it is part of a craft, it turns the craft back into blocks.

A craft holds up to **1,000,000 blocks** and spans up to 255 blocks on each axis. At most 16,384 crafts are loaded per dimension. These are limits on size and count, not a promise of 20 TPS at full load.

### What to build from

Any block with a collision shape can be part of a craft. Density comes from the material, so a craft's mass is the sum of its blocks' volumes times their densities, not the item weight from the game:

| Material | Density (kg/m³) |
|---|---|
| Wool | 55 |
| Planks, bamboo mosaic, wooden fences | 480 |
| Logs | 650 |
| Dirt / sand / gravel | 1,350 / 1,600 / 1,700 |
| Stone, concrete, ceramics, glass | 1,450 to 2,750 |
| Stone ore / deepslate ore | 2,600 / 2,850 |
| Coal / diamond blocks | 1,350 / 3,510 |
| Copper | 8,960 |
| Iron / gold | 7,870 / 19,300 |
| Unknown solid material (including modded blocks) | 800 |

Vanilla blocks without individual entries use the density of their material family, inferred from their registered block sound. The densities and block volumes are approximations. Blocks without physical shape (e.g. torches and redstone dust) and onboard water do not add mass.

Slabs, stairs and other partial blocks are not treated as full cubes. Fences, walls and closed gates keep the vanilla 1.5-block barrier for players and mobs, but rigid crafts touch only their visible geometry. An open gate still has mass. Fences reconnect to their new neighbours when a craft is assembled or docked. Uniform blocks of a hull are merged into large collider boxes of the same mass so that bodies do not catch on the seams between blocks.

### Editing an assembled craft in motion

A craft does not have to be turned back into blocks to change it. The block under the crosshair is outlined in the craft's own frame, even when the craft is rotated.

- **Aiming** works on the real shapes of the craft's blocks, not on its bounding box. You can break blocks of a craft inside another craft's ring, and the world stays reachable through the holes of a tilted hull.
- **Middle click** on a craft block picks that block like in vanilla: it gives you the block in creative mode and takes it from your inventory in survival.
- **Debug view** (F3+B): white shows the exact block shapes, light blue the colliders the physics receives, and a yellow cube with three axes the centre of mass. The axes extend past the hull so they are visible through blocks.
- The client tells the server which block and face it saw, and the server accepts that if the block exists and is within reach. Clicks therefore are not lost while the craft moves and the client draws it a few ticks late.
- **Left click** breaks a block. In **creative** mode it breaks at once (every 5 ticks while held), with no drop. In **survival** hold the button: cracks follow the block, and particles fly when it is hit and when it breaks. The time depends on the block's hardness and your tool, the tool wears, and the drop follows normal harvest rules. **A hit pushes the craft** along your view direction at the point of impact.
- **Right click with a block in hand** places it in the neighbouring cell on the face you aim at. Slabs and stairs take the top or bottom half from the aim point. The first time a block goes into a new chunk of a craft, the client creates that chunk immediately, so you need not rejoin to see it.
- A craft stays in one piece as long as its occupied cells touch, face to face, along an edge or at a corner. The physical shapes need not touch: two slabs in adjacent cells can belong to the same craft with air between their surfaces, and two blocks that meet only diagonally are one craft. If breaking a block disconnects part of a craft, that part becomes a **craft of its own** with the same velocity and spin. The largest piece stays the main craft. When the dimension has no free cells left, the extra pieces fall apart into item drops.
- The owner or an operator can break, place and use blocks. Any block the game allows can be placed, but not into terrain, a living entity or another craft, and an ordinary world block cannot be placed inside a craft.

### Players and mobs on board

Players collide with the craft and are carried by its deck, but standing, walking and running into its side do not push, weigh down or tip it. Deliberate hits and the Tow Staff still move a craft. Mobs still load and push the hull based on their size. Assembling or placing a block refuses to overlap another craft, because the physics would throw both out.

On slanted decks you walk along the surface without losing speed (up to about 60°), step up low ledges (up to 0.6 m), and do not slide down a slope while standing. Going down you stay attached to the surface. Blocks do not bounce when they fall (only slime is elastic); the solver is tuned so that after a 70 m/s impact the residual bounce is under 0.5 m/s. If the physics stops because of an error it restarts up to five times (operators get a message) without losing crafts or their blocks.

Walking on a deck is computed by the client. The server rejects a step only if it ends deeply (more than 0.2 m) inside the hull, so standing on a deck causes no rollbacks.

### Tow Staff (creative)

Take the **Tow Staff** from *Tools & Utilities* or with `/give @s aerolith:tow_staff`. Hold it in either hand and aim at one of your crafts from outside, up to **128 blocks** away.

| Default input | Action |
|---|---|
| Right click | Grab the craft, then move it with your gaze. Click again to release. Grabbing a pinned craft also unpins it. |
| Left click | Pin the held or aimed-at craft, or unpin it. Pins survive world restarts. |
| Mouse wheel | Change the distance to the held craft; hold Sprint for larger steps. |
| Hold Tab and move the mouse | Rotate the held craft instead of turning the camera. Rebind in Controls. |

Putting the staff away releases the craft. The staff uses Rapier forces instead of teleporting, so obstacles stay solid and a released craft keeps its momentum. The controls and item texture are based on [Physics Staff for 26.3](https://github.com/kirillipad152-boop/physics-staff-26.3); the texture is MIT-licensed (see `META-INF/AEROLITH-PHYSICS-STAFF-LICENSE.txt`). This does not require Sable.

Other commands (operator): `/aerolith wind <x> <y> <z>` sets the wind speed in m/s in the current dimension until you leave the world or restart the server; `/aerolith inspect <selector>` shows mass, linear and angular velocity; `/aerolith spawn`, `/aerolith perf` and `/aerolith bench` are described under *Performance*.

### Friction

The coefficient `mu` is dimensionless. For a contact between two physical surfaces Rapier takes the **smaller** of their coefficients, so smooth ice stays slippery even under a wool hull; `0.0` turns off contact friction (air and water drag remain). Settings affect contacts of bodies with the world and with each other, not vanilla player acceleration.

Edit `config/aerolith-server.toml`, or create a file of the same name in the world's `serverconfig`, and reopen the world. The range is `0.0` to `3.0`. `overrides` take priority over material groups and apply to every state of the named block:

```toml
[friction]
ice = 0.035
wood = 0.55
stone = 0.70
overrides = ["minecraft:packed_ice=0.015", "minecraft:oak_planks=0.82"]
```

## What the engine computes

- **Units:** a block is a metre, time is in seconds, mass in kg, gravity 9.81 m/s². Mass and the inertia tensor come from the volumes and densities of the parts, and the centre of mass need not coincide with the visual centre. Densities are estimates of the materials.
- **Contacts:** the world and crafts use the blocks' `VoxelShape`s, including slabs and stairs. Voids are not filled by a common shell. In a merged hull collider the friction is taken from the block under each contact point and not averaged over the deck; different restitution stays with separate colliders. A contact is predicted over the path of one substep, so a body stops at a surface: a cube at 80 m/s enters a wall by 3 cm. Exact motion checking (CCD) turns on when a body travels more than its half-thickness in a substep. A face covered by a neighbouring box gives no contact, so a hull does not catch on the seams of terrain and decks.
- **People, mobs and drops:** obstacles are tested against the rotated shapes of the hull. You can stand on a deck and walk through real openings. Boarding and walking on a craft have no physical feedback for players; the weight and footsteps of mobs still act at the support point. A dropped item is carried by the deck but puts no such load on it. At very sharp turns the carrying between ticks stays approximate.
- **Air:** pressure grows with the square of the relative speed. Covered faces and covered parts of faces get no external force. This is a flat-plate drag model, not a simulation of air vortices.
- **Water:** the engine intersects the rotated colliders with the filled part of the water cells. The volume and centre of the intersection give the buoyancy force `rho*g*V` and its moment. For a rotating box the drag is computed at several points of the submerged part, so water also brakes rotation about a fixed centre. Level and current come from Minecraft; this is an approximate drag model without waves or filling of cavities.
- **Integration:** a game tick is `0.05 s`. For fast collisions, loads and water the engine uses four substeps of `0.0125 s`. If all active bodies move slowly on dry support with no extra load, the solver may take a single contact step. Sleeping bodies wake from a push, wind, or a change of support or water. An unloaded area and `/tick freeze` pause the simulation. Safety limits are `80 m/s` for the centre of mass and for any point of the hull, and `30 rad/s`: a large hull rotates slower so its edge does not exceed 80 m/s. With 48 or more active bodies the work is split across up to eight cores, leaving two for the game (the server's tick and the client's frame). A large group of touching bodies is solved by several threads at once: contacts that share no body run in parallel, and the solver stops at the same tolerance as on one thread.
- **Off the server tick:** the engine steps on a thread of its own while the rest of the tick goes on, and crafts take its poses one tick later. If a step has not finished by the next tick (the server waits up to 15 ms for it), the game carries on and the crafts keep their poses: an overloaded engine runs in slow motion (`server.late` in `/aerolith perf` counts such ticks) instead of holding the server up. `-Daerolith.syncPhysics=true` steps on the server thread again.
- **Tick budget:** when a step takes longer than 35 ms the solver is allowed a quarter fewer sweeps for the next one (never fewer than 16 per run, the normal limit is 200), and gets them back when steps are well within 40 ms again. Calm and small worlds never get near it. Settings by environment variable: `AEROLITH_TICK_BUDGET_MS` (`0` turns the limit off) and `AEROLITH_THREADS` (number of physics threads, `1` for none).

## Craft blocks are real blocks

Each craft keeps its blocks as ordinary blocks in a separate dimension, `aerolith:plot`: a craft has a cell of its own, and the world inside it follows vanilla rules. So what works in the world works on a raft:

- **Chests, barrels, hoppers, furnaces, crafting tables, anvils and other menus** open with right click, and items are saved in the craft together with the block. If a chest is broken, its contents spill next to the craft.
- **Redstone:** levers, buttons, lamps, repeaters, pistons, doors and trapdoors work inside the craft, and their sounds are heard on it. There is no redstone connection between a craft and the world.
- **Fluids** flow by vanilla rules **within the bounds of the craft's blocks**. Even through a hole in the side the flow does not leave the outermost cell. A bucket can pour and scoop water or lava. You can swim in water on board, currents push entities, and air is used under water. Water adds no mass to the craft; underwater camera fog is not supported yet.
- Blocks with a special shape (torches, levers, signs, carpets, fences, glass) are aimed at and broken as in the world. The facing of placed blocks (furnace, stairs, door) is computed in the craft's frame, so on a rotated craft they face correctly.
- **TNT** appears after ignition as an ordinary flashing entity with smoke in the world next to the craft and inherits its velocity. It collides with the deck, explodes in the world, and can damage the blocks of the craft it was lit on if it explodes nearby. Explosion resistance in the world and on board is computed separately; ordinary TNT lit in the world does not destroy a craft's hull.

What does not work or is not saved:

- Entities: item frames, armour stands, minecarts, boats and spawn eggs cannot be placed on a craft (the item is not spent). Moving entities out of blocks into the world is implemented for primed TNT and drops, but not for every block that creates entities.
- Editing sign text: the editing window does not open, because the block entity exists only in the craft's dimension.
- **Assembling** takes all blocks of the chosen area with the data of chests, furnaces and so on, but **open water** in the area stays in the world, so the sea does not travel with a raft. **Disassembling** returns the blocks with their data; water poured into a craft is not returned to the world.
- If part of a craft breaks off, it becomes a separate craft with its own blocks. Water left with pieces that have no blocks disappears.
- Crafts saved by older versions are moved into cells automatically when first loaded.
- A new dimension counts as "experimental" for a world: when you first load an existing world, the vanilla screen asks you to confirm once. The storage is in `dimensions/aerolith/plot` in the world folder and must not be deleted: all craft blocks go with it.

This is not an implementation of interactive sub-levels. Thrown items collide with a hull but are not Rapier bodies of their own, and there is no separate projectile collision. Test in a new world: ordinary assembly and placement failures are rolled back, but the writing of world regions and Minecraft entities is not atomic if the process is killed.

## Performance

On a loaded area an operator can create rafts with `/aerolith spawn <count> <x> <y> <z> [block]`. For example `/aerolith spawn 10000 1 1 1` creates separate one-block wooden bodies, and `/aerolith spawn 1 32 32 32 minecraft:stone` one raft of 32,768 blocks. The command works in batches and skips occupied and unloaded places. In a test world remove the result with `/kill @e[tag=aerolith_spawned]`.

On an empty flat area **in a test world** take a baseline: run `/aerolith perf reset`, wait 15 seconds, then run `/aerolith perf`. Without changing the simulation distance or position, run `/aerolith bench 16 16 300`: 16 temporary rafts of 16×16 over 300 measured ticks. Add `water` at the end to pour water **inside** the rafts. Do not leave the area during the run. The commands need operator rights; the test rafts are removed afterwards.

- In the report `server.tick` is the server tick time (the budget is **50 ms** at 20 TPS), `server.native-step` is the Rust physics step including the JNI call, and `server.terrain` is the update of world colliders and water. These times are nested, not additive. Look at `n`, `avg` and `worst` separately for the fall and for the last third, when the rafts have usually come to rest. The synchronous creation of rafts is shown separately as `Spawn`.
- `/aerolithperf` measures the CPU time of preparing the crafts for drawing, not FPS and not GPU time. It prints and resets only client-side measurements.
- `AEROLITH_BENCH_CUBES=256 ./gradlew runGameTestServer` times whole server ticks with that many one-block rafts pushing each other (add `JAVA_TOOL_OPTIONS=-Daerolith.syncPhysics=true` to compare with the engine on the server thread).
- `AEROLITH_BENCH=1 ./gradlew runGameTestServer` runs four scenarios without a client, including one 60×60 raft. The results are in the server log. `AEROLITH_PROFILE=1` at server start enables detailed Rust engine timings on stderr; leave it off when comparing ordinary performance.
- To measure the Rust side alone: `cargo test --release --locked --manifest-path native/Cargo.toml bench_rafts -- --ignored --nocapture`.
- For dense active contacts between one-block rafts, compile `tools/CubeRaftsBench.java` against the mod JAR and run it with JDK 25:
  ```sh
  jar=build/libs/aerolith-0.4.7.jar
  javac -proc:none -cp "$jar" -d build/cube-bench tools/CubeRaftsBench.java
  java --enable-native-access=ALL-UNNAMED -cp "build/cube-bench:$jar" dev.aerolith.CubeRaftsBench 80 128 256 512
  ```
  This measures the native step, not the whole Minecraft tick. On Windows, use `;` instead of `:` in the classpath.

### Comparison with Sable

`tools/bench_server.py` runs the same scenes on a dedicated server with one of the mods and records the time with vanilla `/tick sprint` and `/tick query`. Every row checks that all bodies landed and shows the load from other processes. It needs `JAVA_HOME` of a JDK and `./gradlew prepareServerRun`.

```bash
python3 tools/bench_server.py sable SABLE.jar NEW_DIR_1 3
python3 tools/bench_server.py aerolith build/libs/aerolith-0.4.0.jar NEW_DIR_2 3
python3 tools/bench_server.py compare NEW_DIR_1/result.json NEW_DIR_2/result.json
```

Ten thousand bodies at identical coordinates, Aerolith against Sable 2.0.6, two runs each on an i5-3570K, Java 21, 2 GB heap, no players, `randomTickSpeed=0`, default physics settings. Both mods got separate oak blocks 2 blocks apart: four full 48×48 layers and 784 blocks in the fifth. For Sable the blocks were placed at Aerolith's spawn coordinates and `/sable assemble shatter area` was applied. The start coordinates matched, and after every run all 10,000 bodies were still in the scene and had landed.

| Phase | Aerolith | Sable |
|---|---:|---:|
| Creation with frozen physics, s | 10.00 | 12.05, of which 0.50 placing blocks |
| First 80 ticks of falling, ms/tick | 86.96 | 190.60 |
| Next 200 ticks, ms/tick | 10.24 | 82.16 |
| 180 ticks at rest, ms/tick | 9.02 | 60.54 |

On this scene Aerolith needs 2.2 times less time per tick while falling and 6.7 times less at rest. While falling neither mod fits the 50 ms/tick budget. Outside load was about 1 to 1.4 of 4 cores, so this is not a measurement on a fully idle machine. The figures apply to these scenes and this machine.

## Building and tests

You need JDK 25 (`JAVA_HOME` must point to it) and Rust with `cargo` (the build is checked on Rust 1.95). The first build downloads Gradle, NeoForge and the Rust dependencies.

```bash
./gradlew build
./gradlew runClient
./gradlew runGameTestServer
cargo test --locked --manifest-path native/Cargo.toml
```

To build a release jar with both Linux and Windows natives, run `pip install ziglang==0.17.1` and `./gradlew build -PwindowsNatives` on x86-64 Linux. Set `AEROLITH_ZIG_PYTHON` to the Python executable that has Zig if it is not `python3`. On Windows, run `dev.aerolith.NativeSmoke` with `build/classes/java/test` and the built jar on the classpath to check that the packaged DLL loads and runs the physics.

`build` compiles and packs the native library and checks JNI from the final jar. `runGameTestServer` creates a separate test world and does not touch your normal saves. To build from source on Windows x86-64, use `.\gradlew.bat build` with the Rust MSVC toolchain and the Visual Studio C++ Build Tools (including the Windows SDK). A Windows source build ships the Windows library; use the release asset if you need both platforms.

### If the client crashes at startup with `There is no OpenGL context current`

This is a failure of the NeoForge loader and the graphics stack, not of Aerolith. To check: remove **all** jars from `mods` and start the client; the crash remains, because it happens in `fml_earlydisplay` before the world loads and before any mod code runs. No Aerolith classes appear in the stack.

On Wayland with some NVIDIA drivers GLFW cannot create a context through Wayland/EGL. A working path is X11: run a separate Xwayland and start the game through it (`Xwayland :1 -geometry 1920x1080 -nolisten tcp &`, then launch with `env -u WAYLAND_DISPLAY DISPLAY=:1`; in Prism set this as *Settings → Custom commands → Wrapper command*). Turning off `earlyWindowControl` alone is not enough without a working X11, because then the ordinary window fails.

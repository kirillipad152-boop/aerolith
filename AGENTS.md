# Notes for whoever builds or releases this project

- **Every jar that is released or handed over must carry the Windows x86-64 native library as well as the Linux one.**
  Build with `./gradlew build -PwindowsNatives` (Linux/macOS; needs `pip install ziglang`, and `AEROLITH_ZIG_PYTHON` pointing at the
  Python that has it if it is not `python3`), then check that `unzip -l build/libs/*.jar` lists both
  `natives/linux-x86_64/libaerolith_physics.so` and `natives/windows-x86_64/aerolith_physics.dll`. The user plays on Windows.
- The Windows library is cross-built (`tools/build_windows_native.sh`, target `x86_64-pc-windows-gnu`, Zig as the linker). It cannot be run
  on the Linux build machine, so say so when reporting; check its exports (`llvm-objdump -p`, 15 `Java_dev_aerolith_NativePhysics_*`).
- `cargo build --locked` must keep working for both targets: a crate that only the Windows build needs still has to be in
  `native/Cargo.lock`, and its licence in `META-INF/AEROLITH-NATIVE-NOTICES.txt`.
- A change to the physics must not change falling, friction, sliding, bounce or buoyancy; the Rust tests and the game tests are the check.

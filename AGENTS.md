# Notes for whoever builds or releases this project

- **Every jar that is released or handed over must carry the Windows x86-64 native library as well as the Linux one.**
  Build with `./gradlew build -PwindowsNatives` (x86-64 Linux; needs `pip install ziglang==0.17.1`, and `AEROLITH_ZIG_PYTHON` pointing at the
  Python that has it if it is not `python3`), then check that `unzip -l build/libs/*.jar` lists both
  `natives/linux-x86_64/libaerolith_physics.so` and `natives/windows-x86_64/aerolith_physics.dll`. The user plays on Windows.
- Run `NativeSmoke` against the exact release jar with a Windows JVM before publishing. Prefer a Windows runner; report if the check
  ran under Wine instead.
- `cargo build --locked` must keep working for both targets: a crate that only the Windows build needs still has to be in
  `native/Cargo.lock`, and its licence in `META-INF/AEROLITH-NATIVE-NOTICES.txt`.
- A change to the physics must not change falling, friction, sliding, bounce or buoyancy; the Rust tests and the game tests are the check.

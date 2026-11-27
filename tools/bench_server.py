"""The same scenes on the same vanilla dedicated server, timed by vanilla's own /tick sprint and /tick query.
Usage: JAVA_HOME=JDK21 bench_server.py sable|aerolith MOD.jar NEW_DIRECTORY REPEATS [SCENE...]
Every row says how busy other programs kept the machine ("foreign", in cores); discard rows where it is not about zero."""
import hashlib
import json
import os
import queue
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

Y = -40
CLEAN = [f"fill {x} {y} {z} {x + 31} {y + 7} {z + 31} minecraft:air" for x in (0, 32, 64) for z in (0, 32, 64)
         for y in range(-60, -20, 8)]
# name: boxes as x, y, z and sizes, and the second material of a chequered one
SCENES = {
    "platforms": ([(x * 18, Y, z * 18, 16, 1, 16) for x in range(4) for z in range(4)], None),
    "big": ([(0, Y, 0, 60, 1, 60)], None),
    "pile": ([(x * 4, Y + y * 4, z * 4, 3, 3, 3) for x in range(4) for z in range(4) for y in range(4)], None),
    "checker": ([(x * 10, Y, z * 10, 8, 2, 8) for x in range(4) for z in range(4)], "stone"),
    "many": ([(x * 6, Y, z * 6, 4, 1, 4) for x in range(8) for z in range(8)], None),
    # As many separate bodies as the published Sable / Sable + Jolt comparison has.
    "swarm": ([(x * 2, Y, z * 2, 1, 1, 1) for x in range(44) for z in range(35)], None),
}


def main(kind, jar, directory, repeats, picked):
    repo = Path(__file__).resolve().parents[1]
    args = repo / "build/moddev"
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    for required in (java, jar, args / "serverRunVmArgs.txt"):
        if not required.is_file():
            raise SystemExit(f"Missing {required}; use JDK 21 and ./gradlew prepareServerRun")
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "mods").mkdir()
    shutil.copyfile(jar, directory / "mods" / jar.name)
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text(
        "level-type=minecraft\\:flat\ngenerate-structures=false\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\n"
        "online-mode=false\nspawn-protection=0\nview-distance=6\nsimulation-distance=6\ndifficulty=peaceful\nserver-port=0\n"
        "max-tick-time=-1\nallow-flight=true\nlevel-seed=1\nsync-chunk-writes=false\nfunction-permission-level=4\n"
        "server-ip=127.0.0.1\n")
    functions = directory / "world/datapacks/bench/data/bench/function"
    functions.mkdir(parents=True)
    (directory / "world/datapacks/bench/pack.mcmeta").write_text('{"pack":{"pack_format":48,"description":"bench"}}')
    for scene, (boxes, second) in SCENES.items():
        build, assemble = [], []
        for x, y, z, sx, sy, sz in boxes:
            area = f"{x} {y} {z} {x + sx - 1} {y + sy - 1} {z + sz - 1}"
            build.append(f"fill {area} minecraft:oak_planks")
            build += [f"setblock {x + i} {y + j} {z + k} minecraft:{second}" for i in range(sx) for j in range(sy) for k in range(sz)
                      if second and (i + j + k) % 2]
            assemble.append(f"sable assemble area {area}" if kind == "sable" else f"aerolith assemble {area}")
        (functions / f"{scene}_build.mcfunction").write_text("\n".join(build) + "\n")
        (functions / f"{scene}_go.mcfunction").write_text("\n".join(assemble) + "\n")
    (functions / "clean.mcfunction").write_text("\n".join(CLEAN) + "\n")

    proc = subprocess.Popen([java, "-Xms2g", "-Xmx2g", f"@{args}/serverRunVmArgs.txt", "-cp", args / "artifacts/neoforge-21.1.249.jar",
                             f"@{args}/serverRunProgramArgs.txt"], cwd=directory,
                            env={k: v for k, v in os.environ.items() if k != "MOD_CLASSES"}, stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()

    def read():
        with (directory / "console.log").open("w") as log:
            for line in proc.stdout:
                log.write(line)
                lines.put((time.perf_counter(), line.rstrip()))
        lines.put((time.perf_counter(), None))

    threading.Thread(target=read, daemon=True).start()

    def wait(pattern, timeout=180, collect=None):
        end = time.monotonic() + timeout
        while True:
            t, line = lines.get(timeout=max(0.01, end - time.monotonic()))
            if line is None:
                raise RuntimeError("The server died, see console.log")
            match = re.search(pattern, line)
            if match:
                return t, match
            if collect is not None:
                collect.append(line)

    def send(*commands):
        proc.stdin.write("".join(c + "\n" for c in commands))
        proc.stdin.flush()

    def done(*commands, collect=None):
        send(*commands, "say DONE")
        return wait(r"\] DONE", collect=collect)[0]

    def query():
        send("tick query")
        average = wait(r"Average time per tick: ([\d.]+)")[1]
        rest = wait(r"P50: ([\d.]+)\s*ms,? P95: ([\d.]+)\s*ms,? P99: ([\d.]+)")[1]
        return [float(average[1]), *map(float, rest.groups())]

    def sprint(ticks):
        send(f"tick sprint {ticks}")
        return float(wait(r"Sprint completed with [\d.,]+ ticks per second, or ([\d.]+) ms per tick", 900)[1][1])

    hertz = os.sysconf("SC_CLK_TCK")

    def own():
        fields = Path(f"/proc/{proc.pid}/stat").read_text().rsplit(")", 1)[1].split()
        return (int(fields[11]) + int(fields[12])) / hertz

    def busy():
        f = list(map(int, Path("/proc/stat").read_text().split("\n", 1)[0].split()[1:9]))
        return (sum(f) - f[3] - f[4]) / hertz

    def heights():
        found = []
        done("sable info @e" if kind == "sable" else "execute as @e[type=aerolith:craft] run data get entity @s Pos[1]", collect=found)
        pattern = r"Position: \S+ (-?[\d.E-]+) " if kind == "sable" else r"entity data: (-?[\d.E-]+)d"
        return [float(m[1]) for m in (re.search(pattern, line) for line in found) if m]

    def remove():
        done("sable remove @e" if kind == "sable" else "kill @e[type=aerolith:craft]")
        time.sleep(2)
        done("function bench:clean")
        time.sleep(1)

    def run(scene, mode):
        done(f"function bench:{scene}_build")
        time.sleep(2)
        busy0, own0, wall0 = busy(), own(), time.monotonic()
        t0 = done()
        row = {"mode": mode, "assemble_ms": round((done(f"function bench:{scene}_go") - t0) * 1000, 1)}
        if mode == "sprint":
            row["fall60"] = sprint(60)
            row["settle140"] = sprint(140)
            sprint(300)
            cpu = own()
            row["rest400"] = sprint(400)
            row["rest_cpu_ms"] = round((own() - cpu) / 400 * 1000, 3)
        else:
            time.sleep(5.2)
            row["drop"] = query()
            time.sleep(15)
            row["rest"] = query()
        row["foreign"] = round((busy() - busy0 - own() + own0) / (time.monotonic() - wall0), 2)
        y = heights()
        row["count"], row["y"] = len(y), [round(min(y, default=0), 2), round(max(y, default=0), 2)]
        remove()
        row["ok"] = len(y) == len(SCENES[scene][0]) and -60.2 < min(y) and max(y) < -44
        return row

    result = {"kind": kind, "jar": jar.name, "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}
    try:
        wait(r"Done \(", 240)
        done("gamerule randomTickSpeed 0", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
             "gamerule maxCommandChainLength 1000000", "forceload add -32 -32 127 127")
        time.sleep(8)
        # Both mods get the same time to be compiled before anything counts.
        run("many", "sprint")
        run("checker", "sprint")
        result["idle"] = [sprint(200) for _ in range(5)]
        print(kind, "idle", result["idle"], flush=True)
        for scene in picked:
            rows = result[scene] = []
            for _ in range(repeats):
                for mode in ("sprint", "natural"):
                    rows.append(run(scene, mode))
                    print(kind, scene, json.dumps(rows[-1]), flush=True)
    finally:
        (directory / "result.json").write_text(json.dumps(result, indent=1))
        send("stop")
        try:
            proc.wait(90)
        except subprocess.TimeoutExpired:
            proc.kill()


def compare(*files):
    data = [json.loads(Path(f).read_text()) for f in files]
    for scene in SCENES:
        if not all(scene in d for d in data):
            continue
        print(f"\n{scene}: median [min..max]" + "".join(f" | {d['jar']}" for d in data))
        for key, mode, index in (("assemble_ms", "natural", None), ("fall60", "sprint", None), ("settle140", "sprint", None),
                                 ("rest400", "sprint", None), ("rest_cpu_ms", "sprint", None), ("drop", "natural", 0), ("drop", "natural", 2),
                                 ("drop", "natural", 3), ("rest", "natural", 0), ("rest", "natural", 2), ("rest", "natural", 3)):
            label = key + ("" if index is None else (" avg", " p50", " p95", " p99")[index])
            out = f"  {label:12}"
            middles = []
            for d in data:
                values = [r[key] if index is None else r[key][index] for r in d[scene] if r["mode"] == mode and r["ok"]]
                if not values:
                    out += f"{'-':>30}"
                    continue
                middles.append(statistics.median(values))
                out += f"{middles[-1]:10.2f} [{min(values):.2f}..{max(values):.2f}]".rjust(30)
            if len(middles) == 2 and middles[1]:
                out += f"   x{middles[0] / middles[1]:.2f}"
            print(out)
        for d in data:
            bad = [r for r in d[scene] if not r["ok"]]
            print(f"  {d['jar']}: {len(d[scene])} rows, failed {len(bad)}, foreign load {max(r['foreign'] for r in d[scene])} cores at most")


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "compare":
        compare(*sys.argv[2:])
    elif len(sys.argv) >= 5 and sys.argv[1] in ("sable", "aerolith") and set(sys.argv[5:]) <= set(SCENES):
        main(sys.argv[1], Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve(), int(sys.argv[4]), sys.argv[5:] or list(SCENES))
    else:
        raise SystemExit(__doc__ + "\nCompare results: bench_server.py compare A/result.json B/result.json [C/result.json ...]")

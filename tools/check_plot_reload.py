"""Boot a fresh dedicated world three times. Usage: check_plot_reload.py OLD.jar NEW.jar NEW_DIRECTORY"""
import itertools
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from contextlib import contextmanager
from pathlib import Path


def main(old, new, directory):
    args = Path(__file__).resolve().parents[1] / "build/moddev"
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    for required in (java, old, new, args / "serverRunVmArgs.txt", args / "serverRunProgramArgs.txt"):
        if not required.is_file():
            raise SystemExit(f"Missing {required}; use JDK 21 and ./gradlew prepareServerRun")
    directory.mkdir(parents=True, exist_ok=False)
    mods = directory / "mods"
    mods.mkdir()
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft\\:flat\nlevel-seed=1\n"
        "generate-structures=false\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\ndifficulty=peaceful\n")
    craft = "@e[type=aerolith:craft,limit=1]"
    plot = "execute in aerolith:plot"
    chest = "20000000 128 20000000"
    at = "2056 -30 2056"

    @contextmanager
    def boot(jar, name):
        shutil.copyfile(jar, mods / "aerolith.jar")
        proc = subprocess.Popen([java, "-Xmx1500m", f"@{args}/serverRunVmArgs.txt", "-cp",
                                 args / "artifacts/neoforge-21.1.249.jar", f"@{args}/serverRunProgramArgs.txt"],
                                cwd=directory, env={k: v for k, v in os.environ.items() if k != "MOD_CLASSES"},
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        lines = queue.Queue()

        def read():
            with (directory / f"{name}.log").open("w") as log:
                for line in proc.stdout:
                    log.write(line)
                    lines.put(line.rstrip())
            lines.put(None)

        reader = threading.Thread(target=read, daemon=True)
        reader.start()

        def until(marker):
            end = time.monotonic() + 150
            output = []
            while True:
                line = lines.get(timeout=max(0.01, end - time.monotonic()))
                if line is None:
                    raise RuntimeError(f"Server exited: see {name}.log")
                if marker in line:
                    return "\n".join(output)
                output.append(line)

        count = itertools.count()

        def command(*commands):
            marker = f"QA_END_{next(count)}."
            proc.stdin.write("\n".join((*commands, "say " + marker)) + "\n")
            proc.stdin.flush()
            return until(marker)

        try:
            until("Done (")
            yield command
        finally:
            if proc.poll() is None:
                proc.stdin.write("stop\n")
                proc.stdin.flush()
                try:
                    proc.wait(60)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
                    raise
            reader.join()
            if proc.returncode:
                raise RuntimeError(f"{name}: server exited with {proc.returncode}")

    def load_site(cmd, entity):
        cmd("forceload add 2056 2056")
        check = f"if entity {craft}" if entity else f"if loaded {at}"
        end = time.monotonic() + 60
        while "QA_READY" not in cmd(f"execute {check} run say QA_READY"):
            if time.monotonic() > end:
                raise RuntimeError(f"Test site never became ready: {check}")
            time.sleep(0.2)

    def check_chest(cmd):
        data = cmd(f"{plot} run data get block {chest} Items")
        assert 'minecraft:diamond' in data and re.search(r'\bcount:\s*7\b', data), data

    with boot(old, "old-writes") as cmd:
        load_site(cmd, False)
        cmd(f'setblock {at} minecraft:chest{{Items:[{{Slot:2b,id:"minecraft:diamond",count:7}}]}}',
            "setblock 2057 -30 2056 minecraft:oak_planks", f"aerolith assemble {at} 2057 -30 2056")
        load_site(cmd, True)
        check_chest(cmd)
        cmd("save-all flush")

    with boot(new, "new-reads") as cmd:
        load_site(cmd, True)
        check_chest(cmd)
        cmd(f"tag {craft} add original", "tick freeze")
        probe = "@e[type=aerolith:craft,tag=!original,limit=1]"
        cmd("setblock 2056 80 2056 minecraft:stone", "aerolith assemble 2056 80 2056 2056 80 2056")

        def height():
            output = cmd(f"data get entity {probe} Pos[1]")
            match = re.search(r"entity data:\s*(-?[\d.]+)d", output)
            assert match, output
            return float(match[1])

        y = height()
        time.sleep(0.35)
        assert y == 80.5 and height() == y, "Physics advanced while ticks were frozen"
        cmd("tick step 1")
        time.sleep(0.35)
        stepped = height()
        assert abs(y - stepped - 0.015328125) < 0.0001 and height() == stepped, "Expected exactly one gravity tick"
        cmd("kill @e[type=aerolith:craft]", "tick unfreeze", f"setblock {at} minecraft:gold_block", f"aerolith assemble {at} {at}")
        assert "QA_GOLD" in cmd(f"{plot} if block {chest} minecraft:gold_block run say QA_GOLD")
        cmd("save-all flush")

    with boot(new, "new-reopens") as cmd:
        load_site(cmd, True)
        assert "QA_GOLD" in cmd(f"{plot} if block {chest} minecraft:gold_block run say QA_GOLD")
        assert "QA_EMPTY" in cmd(f"{plot} if block 20000001 128 20000000 minecraft:air run say QA_EMPTY")
    print(f"PASS: chest inventory, one frozen step and recycled plot across restarts. Logs: {directory}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    main(*(Path(arg).resolve() for arg in sys.argv[1:]))

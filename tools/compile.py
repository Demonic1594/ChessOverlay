"""Compiles the app's Kotlin where the Android Gradle plugin cannot run (as on
a phone), with the standalone Kotlin compiler: in seconds, without a CI run.

    python3 tools/compile.py          # core, the app, its JVM tests and its Android tests
    python3 tools/compile.py --run    # then run core's tests too

It needs a JDK (17 or later) and puts about 300 MB under build/toolchain: the
Kotlin compiler, Android's android.jar (platform 35; its checksum is checked)
and the app's dependencies. Which those are it reads from
compile-classpath.txt: the app's classpaths as Gradle resolves them, written by
the build workflow (its artifact compile-classpath; the file here is a copy,
to be replaced by that artifact when a dependency changes).

This checks that the code compiles. It is not the build: resources are not
compiled (a stub R stands in for theirs), and nothing of Android runs.
"""
import argparse
import hashlib
import io
import os
import pathlib
import re
import subprocess
import sys
import urllib.request
import zipfile

REPO = pathlib.Path(__file__).resolve().parent.parent
TOOLCHAIN = REPO / "build/toolchain"
ANDROID_JAR_SHA1 = "0bb560a90a7a2cbd0dd8348224d518b638fe7949"   # platform-35_r02.zip
CONFIGURATIONS = ("debugCompileClasspath", "debugUnitTestCompileClasspath", "debugAndroidTestCompileClasspath")
# Provided by the compiler itself, or by another artifact here (the Android
# ONNX Runtime stands in for the desktop one the JVM tests name).
# Left out: the compiler brings the Kotlin standard library; the coroutines
# have their JVM artifact next to them; and the Android ONNX Runtime stands in
# for the desktop one that the JVM tests name (the same classes).
SKIP = ("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlin:kotlin-stdlib-common", "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8", "org.jetbrains.kotlin:kotlin-reflect", "org.jetbrains.kotlin:kotlin-bom",
        "org.jetbrains.kotlinx:kotlinx-coroutines-bom", "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "com.microsoft.onnxruntime:onnxruntime")


def download(url, target):
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        print("downloading", url)
        data = urllib.request.urlopen(url, timeout=600).read()
        target.write_bytes(data)
    return target


def unzip(zip_path, where, chmod_x=False):
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(where)
    if chmod_x:
        for f in where.rglob("*"):
            if f.is_file():
                f.chmod(0o755)


def kotlinc():
    version = re.search(r'id\("org\.jetbrains\.kotlin\.jvm"\) version "([^"]+)"', (REPO / "build.gradle.kts").read_text())[1]
    home = TOOLCHAIN / f"kotlinc-{version}" / "kotlinc"
    if not (home / "bin/kotlinc").exists():
        unzip(download(f"https://github.com/JetBrains/kotlin/releases/download/v{version}/kotlin-compiler-{version}.zip",
                       TOOLCHAIN / f"kotlin-compiler-{version}.zip"), home.parent, chmod_x=True)
    return home


def android_jar():
    jar = TOOLCHAIN / "android-35/android.jar"
    if not jar.exists():
        z = download("https://dl.google.com/android/repository/platform-35_r02.zip", TOOLCHAIN / "platform-35_r02.zip")
        if hashlib.sha1(z.read_bytes()).hexdigest() != ANDROID_JAR_SHA1:
            sys.exit("platform-35_r02.zip is not the file this script was written for: its SHA-1 differs")
        unzip(z, TOOLCHAIN)
    return jar


def repositories(group):
    if group.startswith(("androidx.", "com.android", "com.google.testing", "com.google.android")):
        yield f"https://dl.google.com/dl/android/maven2/{group.replace('.', '/')}/"
    yield f"https://repo1.maven.org/maven2/{group.replace('.', '/')}/"


def coordinates():
    """group, artifact, version of everything on the app's compile classpaths (compile-classpath.txt), each at its resolved version.

    Gradle's tree shows every version asked for; conflicts it resolves to the highest, so that is the one taken."""
    newest = {}

    def key(version):
        nums = [int(n) for n in re.findall(r"\d+", version)]
        return nums, not re.search(r"[a-z]", version)   # a release is newer than a pre-release of the same number

    current = None
    for line in (pathlib.Path(__file__).parent / "compile-classpath.txt").read_text().splitlines():
        if re.match(r"^\w+ - ", line):
            current = line.split(" ")[0]
        if current not in CONFIGURATIONS:
            continue
        m = re.search(r"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([0-9][A-Za-z0-9_.\-+]*)", line)
        if m and f"{m[1]}:{m[2]}" not in SKIP:
            if (m[1], m[2]) not in newest or key(m[3]) > key(newest[(m[1], m[2])]):
                newest[(m[1], m[2])] = m[3]
    return sorted((g, a, v) for (g, a), v in newest.items())


def libraries():
    """The dependencies as jars: an .aar's classes.jar is taken out of it."""
    lib = TOOLCHAIN / "libs"
    jars = []
    for group, artifact, version in coordinates():
        target = lib / f"{artifact}-{version}.jar"
        if not target.exists():
            for base in repositories(group):
                for ext in ("aar", "jar"):
                    url = f"{base}{artifact}/{version}/{artifact}-{version}.{ext}"
                    try:
                        data = urllib.request.urlopen(url, timeout=300).read()
                    except Exception:
                        continue
                    if ext == "aar":
                        with zipfile.ZipFile(io.BytesIO(data)) as z:
                            data = z.read("classes.jar")
                            if "R.txt" in z.namelist() and "AndroidManifest.xml" in z.namelist():
                                package = re.search(rb'package="([^"]+)"', z.read("AndroidManifest.xml"))
                                if package:
                                    (target.parent / f"{artifact}-{version}.R.kt").write_text(
                                        library_r(package.group(1).decode(), z.read("R.txt").decode("utf-8", "replace")))
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
                    break
                if target.exists():
                    break
            else:
                print("not found:", group, artifact, version)
                continue
        jars.append(target)
    return jars


def library_r(package, r_txt):
    """A library's R from its R.txt ("int string title = 0x7f040001;"), as AGP generates it for each dependency."""
    fields = {}
    for kind, name, value in re.findall(r"int\s+(\w+)\s+(\w+)\s*=\s*(0x[0-9a-fA-F]+)\s*;", r_txt):
        fields.setdefault(kind, []).append((name, value))
    lines = ["// Made by tools/compile.py from the library's R.txt.", '@file:Suppress("unused")', "", f"package {package}", "", "object R {"]
    for kind in sorted(fields):
        lines.append(f"    object {kind} {{")
        for name, value in fields[kind]:
            lines.append(f"        const val `{name}`: Int = {value}")
        lines.append("    }")
    return "\n".join(lines) + "}\n"


def stub_r():
    """R with every resource name (ids, layouts, drawables, and what values/*.xml defines), and with what the code names."""
    kinds = {"layout": "layout", "drawable": "drawable", "mipmap-anydpi-v26": "mipmap", "color": "color", "xml": "xml"}
    names = {}

    def add(kind, name):
        if kind.isidentifier():
            names.setdefault(kind, set()).add(name)

    res = REPO / "app/src/main/res"
    for f in res.rglob("*.xml"):
        if f.parent.name == "values":
            for kind, name in re.findall(r'<(\w+)\s+name="([^"]+)"', f.read_text()):
                if ":" in name:
                    continue   # a framework attribute set in a style
                add({"declare-styleable": "styleable", "item": "attr"}.get(kind, kind), name.replace(".", "_") if kind == "style" else name)
        elif f.parent.name in kinds:
            add(kinds[f.parent.name], f.stem)
        for name in re.findall(r'@\+id/(\w+)', f.read_text(errors="replace")):
            add("id", name)
    for f in (REPO / "app/src").rglob("*.kt"):
        for kind, name in re.findall(r"\bR\.(\w+)\.(\w+)", f.read_text(errors="replace")):
            add(kind, name)
    lines = ["// Made by tools/compile.py: names only, so that the code compiles without compiled resources.",
             '@file:Suppress("unused", "ConstValName")', "", "package io.github.chessoverlay", "", "object R {"]
    n = 0x7f010000
    for kind in sorted(names):
        lines.append(f"    object {kind} {{")
        for name in sorted(names[kind]):
            lines.append(f"        const val `{name}`: Int = 0x{n:x}")
            n += 1
        lines.append("    }")
    lines.append("}")
    out = TOOLCHAIN / "generated/R.kt"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n")
    return out


# What --run does not run: the overlay previews need fonts (java.awt), which a phone's JDK has not.
NOT_RUN = ("OverlayPreviewTest",)


def sources():
    found = []
    for root in ("core/src/main/java", "app/src/main/java", "app/src/test/java", "app/src/androidTest/java"):
        found += sorted((REPO / root).rglob("*.kt"))
    return [stub_r()] + sorted((TOOLCHAIN / "libs").glob("*.R.kt")) + found


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--run", action="store_true", help="also run core's tests")
    ap.add_argument("--jvm-arg", action="append", default=[], help="for the compiler's JVM, e.g. -XX:+UseSerialGC")
    args = ap.parse_args()
    TOOLCHAIN.mkdir(parents=True, exist_ok=True)
    compiler = kotlinc()
    cp = [android_jar()] + libraries()
    out = TOOLCHAIN / "classes"
    out.mkdir(exist_ok=True)
    files = sources()
    print(f"compiling {len(files)} Kotlin files with {len(cp)} libraries")
    base = [str(compiler / "bin/kotlinc"), "-jvm-target", "17", "-no-reflect", "-nowarn"]
    for a in args.jvm_arg or ["-Xmx1500m", "-XX:+UseSerialGC"]:
        base.append("-J" + a)
    # core's tests are compiled on their own: they and the app's have classes of the same names.
    result = subprocess.run(base + ["-classpath", ":".join(str(p) for p in cp), "-d", str(out)] + [str(f) for f in files])
    if result.returncode or not args.run:
        sys.exit(result.returncode)
    libs = TOOLCHAIN / "libs"
    tests = sorted((REPO / "core/src/test/java").rglob("*.kt"))
    test_out = TOOLCHAIN / "test-classes"
    test_out.mkdir(exist_ok=True)
    print(f"compiling {len(tests)} of core's test files")
    if subprocess.run(base + ["-classpath", f"{out}:{libs}/junit-4.13.2.jar:{libs}/hamcrest-core-1.3.jar",
                              "-d", str(test_out)] + [str(f) for f in tests]).returncode:
        sys.exit(1)
    classes = []
    for f in tests:
        classes += [f"io.github.chessoverlay.{n}" for n in re.findall(r"^class (\w+)\b", f.read_text(), re.M) if n not in NOT_RUN]
    print(f"running core's tests ({len(classes)} classes; not {", ".join(NOT_RUN)}: it needs fonts)")
    stdlib = next((compiler / "lib").glob("kotlin-stdlib.jar"))
    java = str(pathlib.Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    sys.exit(subprocess.run([java] + args.jvm_arg + ["-Djava.awt.headless=true", "-Dpreview.dir=" + str(TOOLCHAIN / "preview"),
                                                     "-cp", f"{test_out}:{out}:{stdlib}:{libs}/junit-4.13.2.jar:{libs}/hamcrest-core-1.3.jar",
                                                     "org.junit.runner.JUnitCore"] + classes).returncode)


if __name__ == "__main__":
    main()

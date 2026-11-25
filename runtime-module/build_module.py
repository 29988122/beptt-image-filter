#!/usr/bin/env python3
"""Build the standalone Xposed APK. Signing credentials remain outside the source tree."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile


def run(*args, **kwargs):
    subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("deps", "build-tools", "java-home", "work", "out", "keystore", "password-file"):
        parser.add_argument("--" + flag, required=True, type=Path)
    parser.add_argument("--alias", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    javac = args.java_home / "bin/javac"
    android = args.deps / "android.jar"
    api = args.deps / "api-102.0.0.jar"
    libs = [args.deps / name for name in (
        "service-102.0.0.jar", "interface-102.0.0.jar", "dexlib2-2.5.2.jar",
        "guava-27.1-android.jar", "failureaccess-1.0.1.jar")]
    sources = list((root / "src").rglob("*.java"))
    rules = work / "BepttUrlRules.java"
    rules.write_text("package net.beptt.imagefilter;\n\n" +
                     (root.parent / "patch/src/BepttUrlRules.java").read_text(encoding="utf-8"), encoding="utf-8")
    classes = work / "classes"
    classes.mkdir()
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    run(javac, "--release", "17", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, [android, api, *libs])),
        "-d", classes, *sources, rules)
    class_jar = work / "module.jar"
    with zipfile.ZipFile(class_jar, "w") as jar:
        for item in sorted(classes.rglob("*.class")):
            jar.write(item, item.relative_to(classes))
    dex = work / "dex"
    dex.mkdir()
    run(args.build_tools / "d8", "--min-api", "26", "--lib", android, "--classpath", api,
        "--output", dex, class_jar, *libs, env=env)
    compiled_resources = work / "res.zip"
    aapt = args.build_tools / "aapt2"
    run(aapt, "compile", "--dir", root / "res", "-o", compiled_resources)
    unsigned = work / "unsigned.apk"
    run(aapt, "link", "-o", unsigned, "--manifest", root / "AndroidManifest.xml", "-I", android, compiled_resources)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as apk:
        for item in sorted(dex.glob("classes*.dex")):
            apk.write(item, item.name)
        for item in sorted((root / "metadata").rglob("*")):
            if item.is_file():
                apk.write(item, item.relative_to(root / "metadata"))
    aligned = work / "aligned.apk"
    run(args.build_tools / "zipalign", "-f", "-P", "16", "4", unsigned, aligned)
    signer = args.build_tools / "apksigner"
    run(signer, "sign", "--ks", args.keystore, "--ks-key-alias", args.alias,
        "--ks-pass", "file:" + str(args.password_file), "--v4-signing-enabled", "false",
        "--out", args.out, aligned, env=env)
    run(signer, "verify", "--verbose", "--print-certs", args.out, env=env)
    run(args.build_tools / "zipalign", "-c", "-P", "16", "4", args.out)


if __name__ == "__main__":
    main()

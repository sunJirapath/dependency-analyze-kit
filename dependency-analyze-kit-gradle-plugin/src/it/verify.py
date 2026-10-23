"""Gradle integration test: runs src/it/multi-project against the plugin and probe installed in mavenLocal.

Usage: python3 verify.py [gradle-executable]   (run `mvn install -DskipTests -Dinvoker.skip=true` first)
"""
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODULE = HERE.parent.parent
WORK = MODULE / "target" / "it" / "multi-project"
PROBES = WORK / "app" / "build" / "dependency-analyze-kit" / "test"
REPORT = WORK / "app" / "build" / "dependency-analyze-kit" / "report.json"
GRADLE = sys.argv[1] if len(sys.argv) > 1 else "gradle"
VERSION = ET.parse(MODULE / "pom.xml").find("{http://maven.apache.org/POM/4.0.0}parent/{http://maven.apache.org/POM/4.0.0}version").text


def gradle(step, *extra):
    args = [GRADLE, "-p", str(WORK), "--configuration-cache", "--console=plain", f"-PdakVersion={VERSION}", "check", *extra]
    result = subprocess.run(args, capture_output=True, text=True)
    out = result.stdout + result.stderr
    (MODULE / "target" / "it" / f"{step}.log").write_text(out)
    check(result.returncode == 0, f"{step}: gradle exited {result.returncode}", out)
    return out


def check(condition, message, context=""):
    if not condition:
        sys.exit(f"FAIL: {message}\n{context}")


def report():
    data = json.loads(REPORT.read_text())
    deps = {d["dependency"]: d for d in data["dependencies"]}
    return data, deps, json.dumps(data, indent=2)


def expect(deps, pretty, key, scope, verdict):
    check(key in deps, f"{key} not in report", pretty)
    d = deps[key]
    check((d["scope"], d["verdict"]) == (scope, verdict),
          f"{key}: expected {scope}/{verdict} but was {d['scope']}/{d['verdict']} ({d['summary']})", pretty)
    return d


def contexts():
    return sorted(p.name for p in PROBES.glob("context-*.json"))


shutil.rmtree(WORK, ignore_errors=True)
shutil.copytree(HERE / "multi-project", WORK)

# 1. cold run: stores the configuration cache, runs the test, probe report consumed
out = gradle("run1")
check("Configuration cache entry stored." in out, "run1: configuration cache entry not stored", out)
check("> Task :app:test\n" in out, "run1: :app:test did not execute", out)
data, deps, pretty = report()
check(data["probeReports"] == 1, f"run1: probeReports={data['probeReports']}", pretty)
check(len(contexts()) == 1, f"run1: probe files {contexts()}")
# multi-module: the sibling project is a declared, used compile dependency
lib = expect(deps, pretty, "it:lib", "compile", "USED_BYTECODE")
check(any(e["detail"] == "it:lib: it.lib.Greeter" for e in lib["evidence"]), "it:lib: no Greeter evidence", pretty)
# BOM: the platform() import is not reported, the dependencies it manages are
check(not any("spring-boot-dependencies" in k for k in deps), "platform() BOM reported", pretty)
expect(deps, pretty, "org.springframework.boot:spring-boot-starter", "compile", "USED_BYTECODE")
# scopes: implementation -> compile, runtimeOnly -> runtime
expect(deps, pretty, "org.apache.commons:commons-lang3", "compile", "UNUSED_CANDIDATE")
json_starter = expect(deps, pretty, "org.springframework.boot:spring-boot-starter-json", "runtime", "UNUSED_CANDIDATE")
# auto-configuration evidence only comes from a probe report
check(any(e["kind"] == "AUTOCONFIG_MATCHED" and "JacksonAutoConfiguration matched" in e["detail"]
          for e in json_starter["evidence"]), "starter-json: no probe evidence", pretty)
first = REPORT.read_text()

# 2. no changes: configuration cache reused, tests not forced by the analysis
out = gradle("run2")
check("Configuration cache entry reused." in out, "run2: configuration cache entry not reused", out)
check("> Task :app:test UP-TO-DATE" in out, "run2: :app:test not UP-TO-DATE", out)
check("> Task :app:dependencyAnalyzeKit UP-TO-DATE" in out, "run2: :app:dependencyAnalyzeKit not UP-TO-DATE", out)
check(REPORT.read_text() == first, "run2: report changed")

# 3. a test rerun must empty its probe directory first: an unparseable stale report would fail the analysis.
# --rerun forces it, since Gradle ignores files it did not create when checking outputs.
(PROBES / "context-stale.json").write_text("{ stale")
out = gradle("run3", ":app:test", "--rerun")
check("> Task :app:test\n" in out, "run3: :app:test did not rerun", out)
files = contexts()
check(len(files) == 1 and files[0] != "context-stale.json", f"run3: probe files {files}")
data, deps, pretty = report()
check(data["probeReports"] == 1, f"run3: probeReports={data['probeReports']}", pretty)
check(json.loads(first) == data, "run3: report differs from run1", pretty)

print("Gradle IT passed: multi-project, BOM, scopes, configuration cache, up-to-date, probe lifecycle")

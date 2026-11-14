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


def gradle(step, *extra, succeeds=True):
    args = [GRADLE, "-p", str(WORK), "--configuration-cache", "--console=plain", f"-PdakVersion={VERSION}", "check", *extra]
    result = subprocess.run(args, capture_output=True, text=True)
    out = result.stdout + result.stderr
    (MODULE / "target" / "it" / f"{step}.log").write_text(out)
    check((result.returncode == 0) == succeeds, f"{step}: gradle exited {result.returncode}, expected success={succeeds}", out)
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

# Compile the extension block exactly as documented, without relying on generated Kotlin accessors.
readme = (MODULE.parent / "README.md").read_text(encoding="utf-8")
extension_block = 'extensions.configure<io.github.sunjirapath.dak.gradle.DakExtension>("dak") {'
literal_block = extension_block + readme.split(extension_block, 1)[1].split("\n}", 1)[0] + "\n}"
check(literal_block in (WORK / "app" / "build.gradle.kts").read_text(), "README extension block differs from fixture")

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
# A constraint on a transitive provider is version policy, not a declared dependency or ownership root.
check("com.zaxxer:HikariCP" not in deps, "constraint-only provider reported as a dependency", pretty)
expect(deps, pretty, "org.springframework.boot:spring-boot-starter", "compile", "USED_BYTECODE")
expect(deps, pretty, "org.springframework.boot:spring-boot-starter-jdbc", "runtime", "USED_BEAN")
# scopes: implementation -> compile, runtimeOnly -> runtime
expect(deps, pretty, "org.apache.commons:commons-lang3", "compile", "UNUSED_CANDIDATE")
check(sum(d["dependency"] == "org.apache.commons:commons-lang3" for d in data["dependencies"]) == 1,
      "constrained real dependency must still be reported exactly once", pretty)
json_starter = expect(deps, pretty, "org.springframework.boot:spring-boot-starter-json", "runtime", "UNUSED_CANDIDATE")
# auto-configuration evidence only comes from a probe report
check(any(e["kind"] == "AUTOCONFIG_MATCHED" and "JacksonAutoConfiguration matched" in e["detail"]
          for e in json_starter["evidence"]), "starter-json: no probe evidence", pretty)
first = REPORT.read_text()

# 2. no changes: configuration cache reused, but evidence must be produced again.
out = gradle("run2")
check("Configuration cache entry reused." in out, "run2: configuration cache entry not reused", out)
check("> Task :app:test\n" in out, "run2: :app:test did not execute", out)
check("> Task :app:dependencyAnalyzeKit\n" in out, "run2: analysis did not execute", out)
check(json.loads(REPORT.read_text()) == json.loads(first), "run2: report changed")

# 3. a test rerun must empty its probe directory first: an unparseable stale report would fail the analysis.
(PROBES / "context-stale.json").write_text("{ stale")
out = gradle("run3", ":app:test", "--rerun")
check("> Task :app:test\n" in out, "run3: :app:test did not rerun", out)
files = contexts()
check(len(files) == 1 and files[0] != "context-stale.json", f"run3: probe files {files}")
data, deps, pretty = report()
check(data["probeReports"] == 1, f"run3: probeReports={data['probeReports']}", pretty)
check(json.loads(first) == data, "run3: report differs from run1", pretty)

# 4. A valid gate run proves that the next failures are about evidence, not unrelated candidates.
service = WORK / "app" / "src" / "main" / "java" / "it" / "ReportService.java"
original_service = service.read_text()
test = WORK / "app" / "src" / "test" / "java" / "it" / "ContextTest.java"
original_test = test.read_text()
incomplete = "Incomplete dependency analysis: current full-context probe evidence required"
for step, flags in [
    ("excluded-test", ["-x", ":app:test"]),
    ("disabled-test", ["-PdisableTest"]),
    ("only-if-test", ["-PskipTest"]),
    ("no-source-test", ["-PnoSourceTest"]),
    ("removed-task", ["-x", ":app:test"]),
]:
    service.write_text(original_service)
    seed_flags = ["-PextraTest"] if step == "removed-task" else []
    gradle(f"{step}-seed", "-PdakGate", *seed_flags)
    if step == "removed-task":
        check(list(PROBES.parent.glob("removedTask/context-*.json")), "removed task did not produce evidence")
    # No application bean consumer remains; JDK DataSource references cannot credit the JDBC starter.
    service.unlink()
    if step == "no-source-test":
        test.unlink()
    out = gradle(step, "-PdakGate", *flags, succeeds=False)
    check(incomplete in out, f"{step}: did not fail closed", out)
    data, deps, pretty = report()
    check(data["probeReports"] == 0, f"{step}: stale evidence consumed", pretty)
    expect(deps, pretty, "org.springframework.boot:spring-boot-starter-jdbc", "runtime", "UNKNOWN")
    check(not list(PROBES.parent.glob("**/context-*.json")), f"{step}: orphan evidence survived cleanup")
    test.write_text(original_test)

# Fresh evidence observes the removed injection and must no longer credit the JDBC starter.
out = gradle("removed-injection", "-PdakGate", succeeds=False)
check("unused dependency candidate(s)" in out, "removed-injection: expected unused failure", out)
data, deps, pretty = report()
expect(deps, pretty, "org.springframework.boot:spring-boot-starter-jdbc", "runtime", "UNUSED_CANDIDATE")

# 5. No full context: neither absent evidence nor a slice-only probe may pass the gate.
service.write_text(original_service)
# Use a plain Spring context for partial evidence without depending on Boot's test-slice package layout.
test.write_text('''package it;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
@SpringBootTest(classes = ContextTest.Slice.class)
class ContextTest {
    @Configuration static class Slice {}
    @Test void contextLoads() {}
}
''')
out = gradle("slice-only", "-PdakGate", succeeds=False)
check(incomplete in out, "slice-only: did not fail closed", out)
data, deps, pretty = report()
check(data["probeReports"] > 0, "slice-only: probe not wired", pretty)
expect(deps, pretty, "org.springframework.boot:spring-boot-starter-jdbc", "runtime", "UNKNOWN")

test.write_text(original_test)
build = WORK / "app" / "build.gradle.kts"
build.write_text(build.read_text() + '''
tasks.withType<Test> { systemProperty("dak.enabled", false) }
extensions.configure<io.github.sunjirapath.dak.gradle.DakExtension>("dak") { ignored = listOf("*:*") }
''')
out = gradle("absent-probe", "-PdakGate", succeeds=False)
check(incomplete in out, "absent-probe: did not fail closed", out)
data, deps, pretty = report()
check(data["probeReports"] == 0, "absent-probe: unexpected evidence", pretty)
check(all(d["verdict"] != "UNKNOWN" for d in deps.values()), "absent-probe: full-context guard not isolated", pretty)

print("Gradle IT passed: README setup, multi-project, BOM, constraints, strict repositories, scopes, configuration cache, fail-closed evidence lifecycle")

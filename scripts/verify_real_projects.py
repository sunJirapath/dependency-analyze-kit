"""Opt-in, offline DAK verification against MANIFEST.json projects; standard library only.

Run after installing DAK into DAK_REPO_DIR. No source checkout is built in place.
Build tools must already be installed; wrappers are neither changed nor invoked.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET


REPO = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ET.register_namespace("", NS["m"])
GROUP = "io.github.sunjirapath"
PLUGIN = "dependency-analyze-kit-maven-plugin"
PROBE = "dependency-analyze-kit-spring-probe"
VERSION = ET.parse(REPO / "pom.xml").findtext("m:version", namespaces=NS)
FAKE_SECRET = "dak-planted-fake-secret-01a1ddba"
UNUSED = "org.apache.commons:commons-lang3"
EXPECTED = ("spring-petclinic", "spring-petclinic-rest", "spring-petclinic-microservices",
            "spring-petclinic-boot35", "piggymetrics")


def require(condition, message):
    # Do not use assert: python -O must not disable security/integration checks.
    if not condition:
        raise AssertionError(message)


def gradle_cache_reuse_decision(log_head):
    if "Configuration cache entry reused." in log_head:
        return True, "Configuration cache entry reused."
    reason = next((line.strip() for line in log_head.splitlines()
                   if "configuration cache cannot be reused because" in line.lower()), None)
    if reason is None:
        return False, "no reason line"
    lowered = reason.lower()
    accepted = ("configuration cache cannot be reused because the file system entry" in lowered
                and "has been created" in lowered
                and not any(term in lowered for term in ("dependency-analyze-kit", "dak", "probe", "input changed")))
    return accepted, reason


def gradle_recall_version(cache_root):
    base = Path(cache_root) / "modules-2/files-2.1/org.apache.commons/commons-lang3"
    versions = sorted(version.name for version in base.iterdir() if version.is_dir()
                      and "SNAPSHOT" not in version.name
                      and any(version.glob("*/commons-lang3-" + version.name + ".jar"))
                      and any(version.glob("*/commons-lang3-" + version.name + ".pom"))) if base.is_dir() else []
    require(versions, "Gradle cache has no commons-lang3 version with jar and pom for the recall mutation")
    return versions[-1]


def excluded(path):
    name = path.name.lower()
    return (name in {".git", ".github", ".env", ".secrets", "target", "build", ".gradle"}
            or name.startswith(".env.") or name.endswith(("_ssh.env", ".pem", ".key", ".p12", ".pfx"))
            or name in {"id_rsa", "id_ed25519", "id_ecdsa"})


def source_files(root):
    """Never follow source links or read private/environment files."""
    for directory, dirs, files in os.walk(root, followlinks=False):
        here = Path(directory)
        dirs[:] = sorted(d for d in dirs if not excluded(here / d))
        for name in [*dirs, *files]:
            path = here / name
            if not excluded(path):
                require(not path.is_symlink(), f"Refusing source symlink: {path.name}")
        for name in sorted(files):
            path = here / name
            if not excluded(path):
                yield path


def fingerprint(root):
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files(root)}


def copy_project(source, destination):
    destination.mkdir()
    for path in source_files(source):
        target = destination / path.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)


def copy_cache(source, destination):
    """Copy offline caches so build-tool cache writes use disposable paths."""
    def ignore(directory, names):
        return [n for n in names if (Path(directory) / n).is_symlink() or n.endswith((".lock", ".lck"))]
    shutil.copytree(source, destination, ignore=ignore)


def config_secrets(root):
    values = {FAKE_SECRET}
    for path in source_files(root):
        if not path.name.startswith("application") or path.suffix not in {".properties", ".yaml", ".yml"}:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            if line.lstrip().startswith("#"):
                continue
            key, separator, value = line.partition("=" if path.suffix == ".properties" else ":")
            normalized = key.lower().replace("_", "").replace("-", "").replace(".", "")
            if separator and any(word in normalized for word in ("password", "secret", "token", "apikey")):
                value = value.split(" #", 1)[0].strip().strip("\"'")
                if len(value) >= 4 and "${" not in value and value.lower() not in {"true", "false", "null"}:
                    values.add(value)
            if "://" in line:
                authority = line.split("://", 1)[1].split("/", 1)[0]
                credentials, at, _ = authority.rpartition("@")
                if at and ":" in credentials and "${" not in credentials:
                    values.add(credentials)
    return values


def child(parent, tag, text=None):
    result = ET.SubElement(parent, "{" + NS["m"] + "}" + tag)
    result.text = text
    return result


def ensure(parent, tag):
    result = parent.find("m:" + tag, NS)
    return result if result is not None else child(parent, tag)


def dependency(parent, ga, version=None, scope=None):
    dep = child(parent, "dependency")
    group, artifact = ga.split(":")
    child(dep, "groupId", group)
    child(dep, "artifactId", artifact)
    if version:
        child(dep, "version", version)
    if scope:
        child(dep, "scope", scope)


def ga(element):
    return element.findtext("m:groupId", namespaces=NS) + ":" + element.findtext("m:artifactId", namespaces=NS)


def reactor(root):
    result, visited = [], set()

    def visit(module):
        module = module.resolve()
        require(module.is_relative_to(root.resolve()), "Reactor module escapes temp copy")
        if module in visited:
            return
        visited.add(module)
        tree = ET.parse(module / "pom.xml")
        model = tree.getroot()
        if model.findtext("m:packaging", "jar", NS) != "pom":
            result.append(module)
        for item in model.findall("m:modules/m:module", NS):
            visit(module / item.text)
    visit(root)
    require(result, "No buildable Maven reactor modules found")
    return result


def inject_maven(module):
    path = module / "pom.xml"
    tree = ET.parse(path)
    model = tree.getroot()
    deps = ensure(model, "dependencies")
    if not any(ga(d) == GROUP + ":" + PROBE for d in deps):
        dependency(deps, GROUP + ":" + PROBE, VERSION, "test")
    plugins = ensure(ensure(model, "build"), "plugins")
    require(not any(ga(p) == GROUP + ":" + PLUGIN for p in plugins if p.find("m:groupId", NS) is not None),
            "Project already configures DAK; manual conflict resolution required")
    plugin = child(plugins, "plugin")
    child(plugin, "groupId", GROUP)
    child(plugin, "artifactId", PLUGIN)
    child(plugin, "version", VERSION)
    tree.write(path, encoding="utf-8", xml_declaration=True)


def groovy(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


def gradle_init(local_repo, destination):
    destination.write_text("""
initscript {
    repositories { maven { url = uri(REPOSITORY) } }
    dependencies { classpath 'io.github.sunjirapath:dependency-analyze-kit-gradle-plugin:VERSION' }
}
allprojects { p ->
    p.pluginManager.withPlugin('java') {
        p.apply plugin: io.github.sunjirapath.dak.gradle.DakPlugin
        p.repositories { maven { url = uri(REPOSITORY); content { includeGroup 'DAK_GROUP_PLACEHOLDER' } } }
        p.dependencies.add('testImplementation', 'io.github.sunjirapath:dependency-analyze-kit-spring-probe:VERSION')
        p.extensions.configure(io.github.sunjirapath.dak.gradle.DakExtension) { ext ->
            ext.failOnUnused = p.providers.gradleProperty('dakFailOnUnused').map { it.toBoolean() }.getOrElse(false)
        }
    }
}
""".replace("REPOSITORY", groovy(local_repo.as_uri())).replace("VERSION", VERSION).replace("DAK_GROUP_PLACEHOLDER", GROUP), encoding="utf-8")


class RealProject(unittest.TestCase):
    def __init__(self, entry, tool, missing=None):
        super().__init__("runTest")
        self.entry, self.tool, self.missing = entry, tool, missing

    def id(self):
        return f"RealProject.{self.entry['name']}.{self.tool}"

    def __str__(self):
        return self.id()

    def run_build(self, label, analyze=True, tests=True, fail=False, tasks=None, module=None):
        if self.tool == "maven":
            command = [os.environ.get("DAK_MAVEN", "mvn"), "-o", "-B", "-ntp",
                       f"-Dmaven.repo.local={self.local_repo}", "-Ddak.enabled=true",
                       f"-Ddak.failOnUnused={str(fail).lower()}"]
            if module is not None and module != self.work:
                command += ["-pl", module.relative_to(self.work).as_posix()]
            if tests:
                command.append("test")  # Includes compile/test-compile; never deploys or publishes.
            if analyze:
                command.append(f"{GROUP}:{PLUGIN}:{VERSION}:analyze")
        else:
            command = [os.environ.get("DAK_GRADLE", "gradle"), "--offline", "--no-daemon", "--console=plain",
                       "--configuration-cache", "--project-cache-dir", str(self.scratch / "project-cache"),
                       "-g", str(self.gradle_home), "-I", str(self.init),
                       f"-PdakFailOnUnused={str(fail).lower()}"]
            command += tasks if tasks is not None else (["dependencyAnalyzeKit"] if analyze else ["classes", "test"])
            if not tests:
                command += ["-x", "test", "-x", "dakPrepare"]
            if tests and analyze:
                command += ["--rerun-tasks"]
        environment = dict(os.environ)
        environment.update({"GRADLE_USER_HOME": str(self.gradle_home), "MAVEN_USER_HOME": str(self.scratch / "maven-home"),
                            "TMP": str(self.scratch), "TEMP": str(self.scratch), "TMPDIR": str(self.scratch),
                            "MAVEN_OPTS": f'-Duser.home="{self.scratch / "home"}" -Djava.io.tmpdir="{self.scratch}"',
                            "GRADLE_OPTS": f'-Duser.home="{self.scratch / "home"}" -Djava.io.tmpdir="{self.scratch}"'})
        # Bound both build duration and in-memory log handling; logs remain in the temp tree.
        log = self.scratch / (label + ".log")
        with log.open("wb") as output:
            process = subprocess.Popen(command, cwd=self.work, env=environment, stdout=output, stderr=subprocess.STDOUT)
            try:
                code = process.wait(timeout=int(os.environ.get("DAK_REAL_TIMEOUT", "1800")))
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise AssertionError(f"{label}: build timed out")
        with log.open("rb") as stream:
            stream.seek(max(0, log.stat().st_size - 16000))
            tail = stream.read().decode("utf-8", errors="replace")
            for secret in self.secrets:
                tail = tail.replace(secret, "[REDACTED]")
        return code, tail

    def succeed(self, label, **kwargs):
        code, tail = self.run_build(label, **kwargs)
        require(code == 0, json.dumps({"step": label, "exit": code, "tail": tail}))
        return tail

    def reports(self):
        reports = {}
        for module in self.modules:
            base = module / ("target" if self.tool == "maven" else "build") / "dependency-analyze-kit"
            files = list(base.rglob("report.json"))
            require(len(files) == 1, f"Expected one module report under {base.relative_to(self.work)}; got {len(files)}")
            data = json.loads(files[0].read_text(encoding="utf-8"))
            require(data["schemaVersion"] == 1, "Unexpected report schema")
            require(data["tool"]["version"] == VERSION, "Wrong installed DAK version")
            require(data["probeReports"] == len(data["contexts"]), "Missing context provenance")
            keys = [(d["dependency"], d["scope"]) for d in data["dependencies"]]
            require(len(keys) == len(set(keys)), "Duplicated dependency identity")
            reports[module] = data
        require(reports, "No reports generated")
        return reports

    @staticmethod
    def canonical(reports):
        # Provenance is deliberately per-run: UUIDs, process context IDs and capture timestamps differ.
        result = []
        for module, data in reports.items():
            data = copy.deepcopy(data)
            data["contexts"] = sorted(
                ({"fullAutoConfig": c["fullAutoConfig"], "profiles": c["profiles"]} for c in data["contexts"]),
                key=lambda c: json.dumps(c, sort_keys=True))
            result.append((module.name, data))
        return sorted(result, key=lambda item: item[0])

    def probe_locations_and_secrets(self):
        for module in self.modules:
            base = module / ("target" if self.tool == "maven" else "build") / "dependency-analyze-kit"
            for file in base.rglob("*"):
                require(not file.is_symlink(), "DAK emitted a symlink")
                if not file.is_file():
                    continue
                text = file.read_text(encoding="utf-8")
                require(not any(secret in text for secret in self.secrets), f"Config secret leaked into {file.name}")
                if file.name.startswith("context-") and file.suffix == ".json":
                    probe = json.loads(text)
                    require(Path(probe["projectDirectory"]).resolve() == module.resolve(), "Foreign probe identity")
                    for record in probe["autoConfigs"] + probe["beans"]:
                        for location in record["locations"]:
                            require(Path(location).exists(), "Evidence location does not exist")

    def remove_one(self, module, target):
        """Return the exact original build text; refuse ambiguous declarations."""
        build = module / ("pom.xml" if self.tool == "maven" else "build.gradle")
        original = build.read_bytes()
        if self.tool == "maven":
            tree = ET.parse(build)
            deps = tree.find("m:dependencies", NS)
            matches = [d for d in deps if ga(d) == target] if deps is not None else []
            require(len(matches) == 1, f"Cannot remove exactly one direct dependency: {target} in {module.name}")
            deps.remove(matches[0])
            tree.write(build, encoding="utf-8", xml_declaration=True)
        else:
            # Pinned petclinic uses one direct Groovy dependency per line; refuse other forms.
            group, artifact = target.split(":")
            pattern = re.compile(r"(?m)^\s*(?:implementation|api|runtimeOnly|compileOnly|developmentOnly)\s+"
                                 r"['\"]" + re.escape(group + ":" + artifact) + r"(?::[^'\"]+)?['\"][ \t]*\r?$" )
            text, count = pattern.subn("", original.decode("utf-8"))
            require(count == 1, f"Cannot remove exactly one Gradle declaration: {target}")
            build.write_text(text, encoding="utf-8")
        return build, original

    def precision(self, baseline):
        for module, report in baseline.items():
            for dep in report["dependencies"]:
                if dep["verdict"] != "UNUSED_CANDIDATE":
                    continue
                with self.subTest(precision=dep["dependency"], module=module.name):
                    build, original = self.remove_one(module, dep["dependency"])
                    try:
                        # Clean outputs so old compiled classes cannot hide removal breakage.
                        self.clear_outputs()
                        code, tail = self.run_build("precision", analyze=False)
                        require(code == 0, json.dumps({"falsePositive": dep["dependency"],
                                                     "module": module.name, "tail": tail}))
                    finally:
                        build.write_bytes(original)

    def clear_outputs(self):
        for module in self.modules:
            output = module / ("target" if self.tool == "maven" else "build")
            require(output.resolve().is_relative_to(self.work.resolve()), "Output escapes temp copy")
            if output.exists():
                shutil.rmtree(output)

    def recall_and_fail(self):
        baseline = self.reports()
        module = next((m for m, report in baseline.items() if any(c["fullAutoConfig"] for c in report["contexts"])), None)
        require(module is not None, "Recall requires at least one full-context test in the real project")
        build = module / ("pom.xml" if self.tool == "maven" else "build.gradle")
        original = build.read_bytes()
        try:
            if self.tool == "maven":
                tree = ET.parse(build)
                deps = ensure(tree.getroot(), "dependencies")
                require(not any(ga(d) == UNUSED for d in deps), "Recall dependency already declared; choose another fixture")
                dependency(deps, UNUSED, self.recall_version())
                tree.write(build, encoding="utf-8", xml_declaration=True)
            else:
                require("commons-lang3" not in original.decode("utf-8"), "Recall dependency already declared")
                with build.open("a", encoding="utf-8") as stream:
                    stream.write("\ndependencies { implementation 'org.apache.commons:commons-lang3:"
                                 + self.recall_version() + "' }\n")
            self.succeed("recall")
            reports = self.reports()
            added = next((d for d in reports[module]["dependencies"] if d["dependency"] == UNUSED), None)
            require(added is not None and added["verdict"] == "UNUSED_CANDIDATE", "Recall failed: planted unused dependency was not reported")
            unchanged = copy.deepcopy(reports)
            unchanged[module]["dependencies"] = [d for d in unchanged[module]["dependencies"] if d["dependency"] != UNUSED]
            require(self.canonical(baseline) == self.canonical(unchanged), "Adding unused library changed unrelated verdicts/evidence")
            code, tail = self.run_build("fail-on-unused", fail=True, module=module)
            require(code != 0 and "unused dependency candidate" in tail.lower(),
                    json.dumps({"step": "failOnUnused", "tail": tail}))
        finally:
            build.write_bytes(original)

    def recall_version(self):
        if self.tool == "gradle":
            return gradle_recall_version(self.gradle_home / "caches")
        base = self.local_repo / "org/apache/commons/commons-lang3"
        versions = sorted(p.name for p in base.iterdir() if p.is_dir() and "SNAPSHOT" not in p.name
                          and (p / ("commons-lang3-" + p.name + ".jar")).is_file()
                          and (p / ("commons-lang3-" + p.name + ".pom")).is_file()) if base.is_dir() else []
        require(versions, "Offline repository needs commons-lang3 for the recall mutation")
        return versions[-1]

    def hostile_output_directory_deletion_attack(self):
        # FINDING: dakClean trusts outputDirectory and recursively deletes an unrelated configured directory.
        # Kills an eventual containment guard if removed; all attack targets are within this test's temp tree.
        target = self.scratch / "unrelated-data"
        target.mkdir()
        sentinel = target / "keep.txt"
        sentinel.write_text("precious", encoding="utf-8")
        original = self.init.read_text(encoding="utf-8")
        try:
            with self.init.open("a", encoding="utf-8") as stream:
                stream.write("\nallprojects { p -> p.pluginManager.withPlugin('java') { p.dak.outputDirectory = new File("
                             + groovy(target) + ") } }\n")
            code, tail = self.run_build("hostile-output-clean", tests=False, tasks=["dakClean"])
            require(code == 0 or "unsafe output directory" in tail.lower(),
                    json.dumps({"step": "hostile output setup failed", "tail": tail}))
            require(sentinel.is_file() and sentinel.read_text(encoding="utf-8") == "precious",
                    "FINDING: dakClean deleted unrelated data selected by hostile outputDirectory")
        finally:
            self.init.write_text(original, encoding="utf-8")

    def stale_gradle_probe_attack(self):
        # FINDING: Gradle validates the producer directory but not the probe capture timestamp.
        # Kills accepting old full-context evidence when test/prepare tasks are excluded.
        self.succeed("before-stale-probe")
        require(all(r["analysis"]["status"] == "COMPLETE" for r in self.reports().values()),
                "Stale-probe attack requires an otherwise complete baseline")
        found = False
        for module in self.modules:
            base = module / "build/dependency-analyze-kit"
            for path in base.rglob("context-*.json"):
                data = json.loads(path.read_text(encoding="utf-8"))
                found |= bool(data["fullAutoConfig"])
                data["capturedAt"] = 1
                path.write_text(json.dumps(data), encoding="utf-8")
        require(found, "Stale attack needs actual full-context evidence")
        self.succeed("stale-probe", tests=False)
        for report in self.reports().values():
            require(report["analysis"]["status"] == "INCOMPLETE", "FINDING: stale Gradle probe was accepted as complete")
            require(not any(d["verdict"] == "UNUSED_CANDIDATE" for d in report["dependencies"]),
                    "FINDING: stale Gradle probe authorized unused")

    def corrupt_probe(self, mode):
        # A fresh test run first ensures we attack a report actually produced by this copy.
        self.succeed("before-" + mode)
        for module in self.modules:
            base = module / ("target" if self.tool == "maven" else "build") / "dependency-analyze-kit"
            probes = list(base.rglob("context-*.json"))
            for path in probes:
                if mode == "truncate":
                    path.write_text('{"fullAutoConfig":true,', encoding="utf-8")
                    # Maven selects only files current at invocation start; malformed contents remain untouched.
                    os.utime(path, (time.time() + 3600, time.time() + 3600))
                else:
                    path.unlink()
        tail = self.succeed(mode, tests=False)
        reports = self.reports()
        for report in reports.values():
            require(not any(d["verdict"] == "UNUSED_CANDIDATE" for d in report["dependencies"]),
                    f"{mode}: corrupt/missing evidence produced unused")
            require(report["analysis"]["status"] == "INCOMPLETE", f"{mode}: evidence loss reported complete")
            require(report["analysis"]["reasons"], f"{mode}: missing diagnostic")
        if mode == "truncate":
            require("invalid probe report" in tail.lower(), "Truncated probe was not diagnosed")

    def runTest(self):
        if self.missing:
            self.skipTest(self.missing)
        source = Path(self.entry["path"])
        if not source.is_absolute():
            source = Path(os.environ["REAL_PROJECTS_DIR"]) / source
        if not source.is_dir():
            self.skipTest("Manifest project directory missing: " + self.entry["name"])
        require(source.resolve().is_relative_to(Path(os.environ["REAL_PROJECTS_DIR"]).resolve()),
                "Manifest project escapes REAL_PROJECTS_DIR")
        cache = os.environ.get("DAK_REPO_DIR")
        require(cache and Path(cache).is_dir(), "Set DAK_REPO_DIR to the offline local repository with freshly installed DAK")
        require(re.fullmatch(r"[0-9a-fA-F]{40}", self.entry.get("sha", "")) is not None, "Missing pinned manifest SHA")
        before = fingerprint(source)
        with tempfile.TemporaryDirectory(prefix="dak-real-") as temporary:
            self.scratch = Path(temporary)
            self.work = self.scratch / "project"
            self.local_repo = self.scratch / "repository"
            self.gradle_home = self.scratch / "gradle-home"
            (self.scratch / "home").mkdir()
            copy_project(source, self.work)
            copy_cache(Path(cache), self.local_repo)
            self.modules = reactor(self.work) if self.tool == "maven" else [self.work]
            if self.tool == "maven":
                for module in self.modules:
                    inject_maven(module)
            else:
                gradle_cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
                if (gradle_cache / "caches").is_dir():
                    self.gradle_home.mkdir()
                    copy_cache(gradle_cache / "caches", self.gradle_home / "caches")
                self.init = self.scratch / "dak.init.gradle"
                gradle_init(self.local_repo, self.init)
            for module in self.modules:
                config = module / "src/main/resources/application.properties"
                config.parent.mkdir(parents=True, exist_ok=True)
                with config.open("a", encoding="utf-8") as stream:
                    stream.write("\ndak.security.fake-secret=" + FAKE_SECRET + "\n")
            self.secrets = config_secrets(self.work)
            try:
                if self.entry["bootVersion"].startswith("2."):
                    code, tail = self.run_build("unsupported-boot2", fail=True)
                    require(code != 0, "Boot 2 must fail closed")
                    require(any(term in tail.lower() for term in ("unsupported", "incomplete dependency analysis", "current full-context")),
                            json.dumps({"step": "Boot2 diagnostic", "tail": tail}))
                    for path in self.work.rglob("report.json"):
                        if "dependency-analyze-kit" in path.parts:
                            report = json.loads(path.read_text(encoding="utf-8"))
                            require(not any(d["verdict"] == "UNUSED_CANDIDATE" for d in report["dependencies"]), "Boot 2 yielded unused")
                    return
                self.succeed("baseline-1")
                baseline = self.reports()
                self.probe_locations_and_secrets()
                second_tail = self.succeed("baseline-2")
                if self.tool == "gradle":
                    if "Configuration cache entry reused." not in second_tail:
                        log_head = (self.scratch / "baseline-2.log").read_text(encoding="utf-8", errors="replace")
                        for secret in self.secrets:
                            log_head = log_head.replace(secret, "[REDACTED]")
                        accepted, reason = gradle_cache_reuse_decision(log_head)
                        require(accepted, f"Gradle configuration cache was not reused: {reason}")
                        third_tail = self.succeed("baseline-3")
                        require("Configuration cache entry reused." in third_tail,
                                "Gradle configuration cache was not reused on baseline-3")
                require(self.canonical(baseline) == self.canonical(self.reports()), "Semantic report is not deterministic")
                expected_fail = any(r["analysis"]["status"] != "COMPLETE" or any(
                    d["verdict"] == "UNUSED_CANDIDATE" for d in r["dependencies"]) for r in baseline.values())
                code, tail = self.run_build("baseline-fail-policy", fail=True)
                require((code != 0) == expected_fail, json.dumps({"step": "failOnUnused baseline", "tail": tail}))
                if expected_fail:
                    require("unused dependency candidate" in tail.lower() or "incomplete dependency analysis" in tail.lower(),
                            json.dumps({"step": "failOnUnused unrelated failure", "tail": tail}))
                if self.tool == "gradle":
                    with self.subTest(boundary="stale_gradle_probe_attack"):
                        self.stale_gradle_probe_attack()
                    self.succeed("restore-after-stale-probe")
                self.precision(baseline)
                self.succeed("restore-after-precision")
                self.recall_and_fail()
                self.corrupt_probe("truncate")
                self.corrupt_probe("delete")
                self.probe_locations_and_secrets()
                if self.tool == "gradle":
                    with self.subTest(boundary="hostile_output_directory_deletion_attack"):
                        self.hostile_output_directory_deletion_attack()
            finally:
                require(before == fingerprint(source), "Original source checkout changed")


def suite():
    root = os.environ.get("REAL_PROJECTS_DIR")
    manifest = Path(root) / "MANIFEST.json" if root else None
    entries = {}
    if manifest and manifest.is_file():
        data = json.loads(manifest.read_text(encoding="utf-8"))
        entries = {entry["name"]: entry for entry in data["projects"]}
    tests = unittest.TestSuite()
    for name in EXPECTED:
        entry = entries.get(name, {"name": name})
        missing = ("REAL_PROJECTS_DIR is unset; real-project checks are opt-in" if not root else
                   "MANIFEST.json is missing" if not manifest.is_file() else
                   "Project absent from manifest: " + name if name not in entries else None)
        tools = entry.get("buildTool", "maven").split("+")
        for tool in tools:
            require(tool in {"maven", "gradle"}, "Unsupported manifest build tool")
            tests.addTest(RealProject(entry, tool, missing))
    return tests


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(suite())
    raise SystemExit(0 if result.wasSuccessful() else 1)

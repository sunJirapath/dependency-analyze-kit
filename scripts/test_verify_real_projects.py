"""Harness self-checks; no external builds are launched by these tests."""
import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import verify_real_projects as verifier


class RealProjectHarnessTest(unittest.TestCase):
    def test_gradle_recall_version_selects_highest_complete_cached_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "modules-2/files-2.1/org.apache.commons/commons-lang3"
            for version in ("3.16.0", "3.19.0"):
                artifacts = root / version / "hash"
                artifacts.mkdir(parents=True)
                (artifacts / f"commons-lang3-{version}.jar").touch()
                (artifacts / f"commons-lang3-{version}.pom").touch()
            pom_only = root / "3.20.0/hash"
            pom_only.mkdir(parents=True)
            (pom_only / "commons-lang3-3.20.0.pom").touch()
            snapshot = root / "3.21.0-SNAPSHOT/hash"
            snapshot.mkdir(parents=True)
            (snapshot / "commons-lang3-3.21.0-SNAPSHOT.jar").touch()
            (snapshot / "commons-lang3-3.21.0-SNAPSHOT.pom").touch()

            self.assertEqual("3.19.0", verifier.gradle_recall_version(Path(temporary)))

    def test_gradle_recall_version_fails_clearly_without_complete_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "modules-2/files-2.1/org.apache.commons/commons-lang3"
            artifacts = root / "3.20.0/hash"
            artifacts.mkdir(parents=True)
            (artifacts / "commons-lang3-3.20.0.pom").touch()

            with self.assertRaisesRegex(AssertionError, "Gradle cache has no commons-lang3 version with jar and pom"):
                verifier.gradle_recall_version(Path(temporary))

    def test_gradle_cache_reuse_decision(self):
        reused = "Configuration cache entry reused."
        allowed = "Calculating task graph as configuration cache cannot be reused because the file system entry '../agent-config/access-filter.json' has been created."
        dak = "Calculating task graph as configuration cache cannot be reused because dependency-analyze-kit input changed."
        for log, expected in ((reused, True), (allowed, True), (dak, False), ("Gradle did not explain", False)):
            with self.subTest(log=log):
                accepted, reason = verifier.gradle_cache_reuse_decision(log)
                self.assertEqual(expected, accepted)
                self.assertEqual("no reason line" if log == "Gradle did not explain" else log, reason)

    def test_copy_cache_keeps_maven_build_and_target_paths_but_ignores_locks_and_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, destination = root / "cache", root / "copy"
            pom = source / "org/x/build/y/1/y-1.pom"
            pom.parent.mkdir(parents=True)
            pom.write_text("pom", encoding="utf-8")
            target_file = source / "org/x/target/cache.bin"
            target_file.parent.mkdir(parents=True)
            target_file.write_text("data", encoding="utf-8")
            (source / "entry.lock").write_text("lock", encoding="utf-8")
            linked = source / "linked"
            try:
                linked.symlink_to(target_file)
            except (OSError, NotImplementedError):
                linked = None

            verifier.copy_cache(source, destination)

            self.assertEqual("pom", (destination / pom.relative_to(source)).read_text(encoding="utf-8"))
            self.assertEqual("data", (destination / target_file.relative_to(source)).read_text(encoding="utf-8"))
            self.assertFalse((destination / "entry.lock").exists())
            if linked is not None:
                self.assertFalse((destination / "linked").exists())

    def test_gradle_init_uses_explicit_extension_parameter(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "dak.init.gradle"
            verifier.gradle_init(Path(temporary) / "repository", destination)
            text = destination.read_text(encoding="utf-8")
        self.assertIn("ext.failOnUnused =", text)
        self.assertNotRegex(text, r"(?m)^\s*failOnUnused\s*=")

    def test_gradle_init_restricts_project_repository_to_dak_group(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "dak.init.gradle"
            verifier.gradle_init(Path(temporary) / "repository", destination)
            text = destination.read_text(encoding="utf-8")
        project_repositories = text.split("p.repositories", 1)[1].split("p.dependencies", 1)[0]
        self.assertIn("includeGroup '" + verifier.GROUP + "'", project_repositories)
        self.assertNotIn("includeGroup", text.split("allprojects", 1)[0])

    def test_unset_opt_in_skips_without_starting_builds(self):
        # Kills accidentally invoking projects during normal offline test runs.
        with patch.dict(os.environ, {}, clear=True), patch.object(verifier.subprocess, "Popen") as process:
            result = unittest.TestResult()
            verifier.suite().run(result)
            self.assertTrue(result.skipped)
            self.assertFalse(result.failures or result.errors)
            process.assert_not_called()

    def test_missing_project_skip_does_not_require_repository(self):
        # Kills resolving caches/tools before checking whether a project was provisioned.
        test = verifier.RealProject({"name": "missing", "path": "a-path-that-does-not-exist"}, "maven")
        with patch.dict(os.environ, {"REAL_PROJECTS_DIR": tempfile.gettempdir()}, clear=True):
            result = unittest.TestResult()
            test.run(result)
        self.assertEqual(1, len(result.skipped))

    def test_probe_mutation_removes_exactly_one_maven_dependency(self):
        # Kills removal from dependencyManagement or removal of an unrelated direct dependency.
        with tempfile.TemporaryDirectory() as temporary:
            module = Path(temporary)
            build = module / "pom.xml"
            build.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>
<dependency><groupId>a</groupId><artifactId>one</artifactId></dependency>
<dependency><groupId>a</groupId><artifactId>two</artifactId></dependency></dependencies>
<dependencyManagement><dependencies><dependency><groupId>a</groupId><artifactId>one</artifactId></dependency>
</dependencies></dependencyManagement></project>''', encoding="utf-8")
            test = verifier.RealProject({"name": "fixture"}, "maven")
            path, original = test.remove_one(module, "a:one")
            model = ET.parse(path)
            direct = model.findall("m:dependencies/m:dependency", verifier.NS)
            self.assertEqual(["a:two"], [verifier.ga(d) for d in direct])
            self.assertEqual(1, len(model.findall("m:dependencyManagement/m:dependencies/m:dependency", verifier.NS)))
            path.write_bytes(original)
            self.assertIn(b"a</groupId><artifactId>one", path.read_bytes())

    def test_ambiguous_gradle_mutation_refuses_to_write(self):
        # Kills deleting multiple declarations for one coordinate and mislabelling it as an independent removal.
        with tempfile.TemporaryDirectory() as temporary:
            module = Path(temporary)
            build = module / "build.gradle"
            original = "implementation 'a:one:1'\nruntimeOnly 'a:one:1'\n"
            build.write_text(original, encoding="utf-8")
            test = verifier.RealProject({"name": "fixture"}, "gradle")
            with self.assertRaises(AssertionError):
                test.remove_one(module, "a:one")
            self.assertEqual(original, build.read_text(encoding="utf-8"))

    def test_maven_fail_on_unused_selects_only_the_planted_module(self):
        for relative in (Path("services/owners"), Path(".")):
            with self.subTest(module=relative), tempfile.TemporaryDirectory() as temporary:
                test = verifier.RealProject({"name": "fixture"}, "maven")
                test.scratch = Path(temporary)
                test.work = test.scratch / "project"
                test.local_repo = test.scratch / "repository"
                test.gradle_home = test.scratch / "gradle-home"
                test.secrets = []
                module = test.work / relative
                module.mkdir(parents=True)
                build = module / "pom.xml"
                original = b'<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies/></project>'
                build.write_bytes(original)
                baseline = {module: {"contexts": [{"fullAutoConfig": True, "profiles": []}], "dependencies": []}}
                recalled = copy.deepcopy(baseline)
                recalled[module]["dependencies"].append(
                    {"dependency": verifier.UNUSED, "verdict": "UNUSED_CANDIDATE"})

                with patch.object(test, "reports", side_effect=[baseline, recalled]), \
                        patch.object(test, "recall_version", return_value="3.17.0"), \
                        patch.object(test, "succeed"), patch.object(verifier.subprocess, "Popen") as process:
                    process.return_value.wait.return_value = 1

                    def launch(command, **kwargs):
                        kwargs["stdout"].write(b"unused dependency candidate")
                        return process.return_value

                    process.side_effect = launch
                    test.recall_and_fail()

                process.assert_called_once()
                command = process.call_args.args[0]
                self.assertIn("-Ddak.failOnUnused=true", command)
                if relative == Path("."):
                    self.assertNotIn("-pl", command)
                else:
                    self.assertEqual(relative.as_posix(), command[command.index("-pl") + 1])
                self.assertEqual(original, build.read_bytes())

    def test_reactor_path_traversal_attack_is_rejected(self):
        # Kills resolving a POM module outside the temp project before opening it.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "project"
            root.mkdir()
            (root / "pom.xml").write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
<packaging>pom</packaging><modules><module>../outside</module></modules></project>''', encoding="utf-8")
            with self.assertRaisesRegex(AssertionError, "escapes"):
                verifier.reactor(root)

    def test_determinism_keeps_verdict_changes_visible(self):
        # Kills over-normalizing away the dependency evidence being tested.
        report = {Path("module"): {"contexts": [{"id": "one", "contextKey": "a", "capturedAt": 1,
                                                "fullAutoConfig": True, "profiles": []}],
                                   "dependencies": [{"verdict": "UNKNOWN"}]}}
        another = copy.deepcopy(report)
        another[Path("module")]["contexts"][0].update(id="two", contextKey="b", capturedAt=2)
        self.assertEqual(verifier.RealProject.canonical(report), verifier.RealProject.canonical(another))
        another[Path("module")]["dependencies"][0]["verdict"] = "UNUSED_CANDIDATE"
        self.assertNotEqual(verifier.RealProject.canonical(report), verifier.RealProject.canonical(another))

    def test_require_cannot_be_disabled_by_optimized_python(self):
        # Kills replacing require with an assert statement (also run this file with python -O).
        with self.assertRaises(AssertionError):
            verifier.require(False, "must fail")

    def test_secret_extraction_ignores_placeholders_and_collects_url_credentials(self):
        # Kills dropping configured secret values or comparing unresolved property expressions as secrets.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "application.properties").write_text(
                "service.password=long-secret\nservice.token=${ENV_TOKEN}\nurl=https://user:password@host/path\n", encoding="utf-8")
            values = verifier.config_secrets(root)
            self.assertIn("long-secret", values)
            self.assertIn("user:password", values)
            self.assertNotIn("${ENV_TOKEN}", values)


if __name__ == "__main__":
    unittest.main()

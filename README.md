# dependency-analyze-kit — unused Spring Boot dependencies, without the false positives

[![CI](https://github.com/sunJirapath/dependency-analyze-kit/actions/workflows/build.yml/badge.svg)](https://github.com/sunJirapath/dependency-analyze-kit/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.sunjirapath/dependency-analyze-kit-maven-plugin)](https://central.sonatype.com/artifact/io.github.sunjirapath/dependency-analyze-kit-maven-plugin)
[![License](https://img.shields.io/github/license/sunJirapath/dependency-analyze-kit)](LICENSE)
[![Java 17+ / Maven 3.9+](https://img.shields.io/badge/Java%2017%2B-Maven%203.9%2B-blue)](#install)
[![Spring Boot 3.5.16 / 4.0.8 / 4.1.1](https://img.shields.io/badge/Spring%20Boot-3.5.16%20%2F%204.0.8%20%2F%204.1.1-green)](#install)

Maven and Gradle plugins that find unused dependencies in Spring Boot projects, without the false positives of
`mvn dependency:analyze`.

`dependency:analyze` looks at bytecode only. Spring Boot starters are empty jars, so it reports every starter as
"unused declared" and the starter's transitive dependencies as "used undeclared" ([MDEP-557]).

The plugins join three sources:

1. **Bytecode references** from `maven-dependency-analyzer` plus metadata scanning on Maven, or the class scanner on Gradle.
2. **The resolved dependency graph.** Each starter is judged by the artifacts it brings in *exclusively*.
3. **Runtime evidence from Spring** collected while your tests run: the condition evaluation report (matched and
   unmatched auto-configurations) and the bean graph (which beans your own beans depend on).

Background and design decisions: [docs/research.md](docs/research.md). ตรวจหา dependency ที่ไม่ได้ใช้ใน Spring Boot.

[English](#english) · [ภาษาไทย](#ภาษาไทย)

## English

### Install

Latest version: **0.1.0**, available on Maven Central. Group `io.github.sunjirapath`, artifacts
`dependency-analyze-kit-maven-plugin` and `dependency-analyze-kit-spring-probe`.

**Tested:** Java 17 and 21; Maven 3.9+ (CI also runs Maven 4.0.0-rc-7); Spring Boot 3.5.16 (Maven and Gradle),
4.0.8 (Gradle), and 4.1.1 (Maven). Spring Boot 2.x, 3.0-3.4, other 3.5 patch versions, and other 4.x versions are not claimed.
CI runs on Linux (Java 17 and 21), Windows and macOS. Your tests need at least one `@SpringBootTest`.

Add the probe and the plugin to your Spring Boot project:

```xml
<dependencies>
  <!-- Writes Spring's condition report and bean graph during tests. Test scope only. -->
  <dependency>
    <groupId>io.github.sunjirapath</groupId>
    <artifactId>dependency-analyze-kit-spring-probe</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>io.github.sunjirapath</groupId>
      <artifactId>dependency-analyze-kit-maven-plugin</artifactId>
      <version>0.1.0</version>
      <executions>
        <execution>
          <goals><goal>analyze</goal></goals>   <!-- runs in the verify phase -->
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

### Gradle

CI exercises Gradle **9.8.1** on Linux / Java 21. Compiled against Gradle API **8.11.1**.
The minimum supported Gradle version is not established; compilation against 8.11.1 is not a compatibility test.
Other wrappers and Gradle operating systems remain unverified. Use the CI version for the tested setup.

Apply the plugin from a buildscript dependency, then add the probe to the test classpath. Use the same released version for both artifacts. The plugin runs
`dependencyAnalyzeKit` as part of `check`, enables the probe for Gradle test tasks, and writes
`build/dependency-analyze-kit/report.json`.

```kotlin
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("io.github.sunjirapath:dependency-analyze-kit-gradle-plugin:<version>")
    }
}

apply(plugin = "io.github.sunjirapath.dependency-analyze-kit")

dependencies {
    testImplementation("io.github.sunjirapath:dependency-analyze-kit-spring-probe:<version>")
}

extensions.configure<io.github.sunjirapath.dak.gradle.DakExtension>("dak") {
    setFailOnUnused(true)
    ignored = listOf("io.micrometer:micrometer-registry-*")
    sideEffectStarters = listOf("org.springframework.boot:spring-boot-starter-oauth2-client")
}
```

Run `./gradlew check`. The plugin currently supports JVM projects that apply Gradle's `java` plugin; use
`dependencyAnalyzeKit` directly if you want to generate only the dependency report. It runs every `Test` task
first; each one writes its probe reports to `build/dependency-analyze-kit/<testTask>/`. The `dakPrepare` prerequisite
deletes all previous probe files, including orphan task directories, before tests and analysis. DAK forces `Test`
tasks to execute without reusing up-to-date or build-cache results; analysis also executes every time.
Excluded, disabled or no-source tasks provide no evidence. `dakClean` deletes the whole directory. `platform()` BOM imports and
dependency constraint edges are skipped; a dependency that is also constrained is still reported.
`implementation`/`api` dependencies are reported as `compile` scope, `runtimeOnly` ones as `runtime`.
The task is configuration-cache compatible.

Probe file modification times and `capturedAt` must both be at or after this build's execution start;
run the tests in the same Gradle invocation, even when `dakPrepare` is excluded.

The Gradle `dak.outputDirectory` setting defaults to `build/dependency-analyze-kit` and must be strictly inside
the project's build directory (for example, `build/reports/dak`), after normalization and resolving existing symlinks/junctions; unsafe paths fail before deletion or writing.

**Analysis scope:** Gradle reports the main `runtimeClasspath` module graph. `compileOnly` / `compileOnlyApi`
and local `files(...)` dependencies have no independent verdicts. When these declarations exist, the report
records `INCOMPLETE` with a scope diagnostic, logs a warning, and `failOnUnused=true` fails even with valid
runtime evidence. Review them separately and use advisory mode for such projects. Custom configurations outside
the main runtime graph, including annotation processors and test-only dependencies, are outside this scope.

With `failOnUnused` enabled, both plugins fail on unused candidates, `UNKNOWN` verdicts, or missing current
full-context evidence (even if all dependencies have bytecode evidence or are ignored), and known incomplete
analysis such as Gradle's excluded declarations. Leave it `false` for
advisory reports. Neither a successful gate nor a full context proves that every production path was exercised.

### Quick start

#### 1. Run the build with the probe switched on

```bash
mvn verify -Ddak.enabled=true
```

The probe does nothing unless `dak.enabled=true` is set. Surefire passes `-D` properties on to the forked test
JVM. With the property set, every Spring context the tests start writes
`target/dependency-analyze-kit/context-<pid>-<n>-<uuid>.json`. The `analyze` goal then prints a table and writes
`target/dependency-analyze-kit/report.json`.

Without `-Ddak.enabled=true` the goal still runs, but with bytecode evidence only. Everything else comes out
`UNKNOWN`; nothing is ever called unused.

#### 2. Read the result

This is real output from the [`web-redis-unused`](dependency-analyze-kit-maven-plugin/src/it/web-redis-unused) sample:
a web app that also declares Redis and commons-lang3 but never uses them.

```
[INFO] DEPENDENCY                                               VERDICT           EVIDENCE
[INFO] org.springframework.boot:spring-boot-starter-web         USED_BYTECODE     org.springframework:spring-web: org.springframework.web.bind.annotation.GetMapping (+1 more)
[INFO] org.springframework.boot:spring-boot-starter-data-redis  UNUSED_CANDIDATE  12 auto-configurations matched (RedisAutoConfiguration, RedisCacheConfiguration, ...), but no project class or bean uses them
[INFO] org.apache.commons:commons-lang3                         UNUSED_CANDIDATE  no bytecode reference, bean or auto-configuration
```

`report.json` carries every piece of evidence, not just the one-line summary (shortened here):

```json
{
  "schemaVersion": 1,
  "tool": { "name": "dependency-analyze-kit", "version": "<running version>" },
  "analysis": { "status": "COMPLETE", "reasons": [] },
  "probeReports": 1,
  "dependencies": [
    {
      "dependency": "org.springframework.boot:spring-boot-starter-data-redis",
      "scope": "compile",
      "verdict": "UNUSED_CANDIDATE",
      "summary": "12 auto-configurations matched (RedisAutoConfiguration, RedisCacheConfiguration, ...), but no project class or bean uses them",
      "evidence": [
        { "kind": "AUTOCONFIG_MATCHED", "detail": "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration matched" },
        { "kind": "AUTOCONFIG_UNMATCHED", "detail": "org.springframework.boot.autoconfigure.cache.JCacheCacheConfiguration did not match: OnClassCondition: condition message omitted" }
      ]
    }
  ]
}
```

Evidence `kind` is one of `BYTECODE`, `BEAN`, `AUTOCONFIG_MATCHED`, `AUTOCONFIG_UNMATCHED` or `IGNORED`.

The versioned envelope applies to this checkout; older releases may have unversioned reports. Consumers must
check `schemaVersion` and reject unsupported schemas, `analysis.status=INCOMPLETE`, or unavailable provenance
when gating. `COMPLETE` means no known analysis failure within the documented scope, with current full-context
evidence and no `UNKNOWN` verdicts; it does not mean every production path is covered or every dependency is used.
`analysis.reasons` records missing full contexts, unknown verdicts, excluded Gradle declarations, and Maven's
advisory partial-graph / metadata-scan failures. Analysis errors can abort a build before a report is written;
never use an old report after a failed invocation.

`contexts` (omitted from the shortened example) records each accepted context's `id`, `contextKey`,
`parentContextKey`, `capturedAt` (epoch milliseconds), `fullAutoConfig`, and sorted active `profiles`.
The plugins filter evidence to the current module/invocation before serialization; absolute module and jar paths
are not copied into this provenance. Context keys and capture times change between runs. Dependencies sort by
coordinate/scope, evidence by kind/detail, and contexts by key/id/capture time. The legacy core
`Reports.json(results, count)` overload cannot supply provenance and always marks its report `INCOMPLETE`.

**Condition-message privacy:** mismatch messages can contain credentials or property values supplied by custom
conditions. The probe omits their free-form text by default, retaining the condition class and match boolean.
Use `-Ddak.includeConditionMessages=true` in the **test JVM** only when raw diagnostics are needed; those values
can then reach raw probe files, report evidence/summaries and console logs. Gradle users can forward this with
`tasks.withType<Test> { systemProperty("dak.includeConditionMessages", true) }`. This is omission, not secret
detection: class/bean/context names, active profile names and other build/test logs are not sanitized. Raw probe
files also contain absolute paths. Restrict artifact access and use the matching probe version; old probes and
previously captured files/logs may contain raw messages.

### Verdicts

| Verdict | Meaning | What to do |
|---|---|---|
| `USED_BYTECODE` | Project classes reference the dependency's jar, or a jar only it brings in. | Keep it. |
| `USED_BEAN` | A bean from it (or from a jar only it brings in) is injected into your beans, or is a non-framework startup runner/listener. | Keep it. |
| `USED_AUTOCONFIG` | One of its auto-configurations matched, and it is a side-effect starter: actuator, security, template engines, web-services, cache, validation, AOP/AspectJ, Resilience4j, Spring Cloud Stream binders, Micrometer registries and tracing bridges, OpenTelemetry, OAuth2 resource server, springdoc, H2 console, properties migrator, Jackson XML/Kotlin modules, Jetty HTTP/2, Caffeine and Feign HTTP clients, plus your `sideEffectStarters`. | Keep it. |
| `UNUSED_CANDIDATE` | No usage evidence or retention rule applies, in at least one full (non-slice) test context. This is a candidate, not proof. | Review it before removal, then run the tests. Reflection-only use needs `ignored` (see [Known gaps](#known-gaps)). |
| `IGNORED` | It would be unused or unknown, but it is on the built-in or configured ignore list. | Nothing. |
| `UNKNOWN` | Missing/incomplete runtime evidence, unresolved reached bean locations, dynamic registrations, or test/provided scope prevents an unused verdict. | Retain it; review the summary and probe coverage. |

A matched auto-configuration on its own does **not** count as usage. `@ConditionalOnClass` matches simply because
the jar is on the classpath.

### Examples

Every example below is one of the integration-test projects in
[`dependency-analyze-kit-maven-plugin/src/it`](dependency-analyze-kit-maven-plugin/src/it). Each one is a complete
Spring Boot app you can copy.

**A bean injected by type counts, even when your code never names the library**
([`jdbc-actuator`](dependency-analyze-kit-maven-plugin/src/it/jdbc-actuator)). The only project code is:

```java
@Service
public class ReportService {
    public ReportService(javax.sql.DataSource dataSource) { ... }   // a JDK type, not a Hikari one
}
```

Bytecode analysis sees nothing from `spring-boot-starter-jdbc`. The bean graph shows that the `dataSource` bean
(a `HikariDataSource`, which only that starter brings in) is injected into `reportService`, so the starter is
`USED_BEAN`. `spring-boot-starter-actuator` is never referenced, but its endpoints are the point, so it is
`USED_AUTOCONFIG`.

**A property-gated auto-configuration names the unmet condition**
([`conditional-property`](dependency-analyze-kit-maven-plugin/src/it/conditional-property)):

```
io.github.sunjirapath.it:feature-starter      UNUSED_CANDIDATE  it.feature.FeatureAutoConfiguration did not match: OnPropertyCondition: condition message omitted
```

Either the starter really is unused, or your tests don't set the property your production profile sets. See
[profiles](#run-once-per-profile). Enable raw condition messages in a trusted test JVM to inspect the property diagnostic.

**Slice tests alone are not enough evidence**
([`slice-only`](dependency-analyze-kit-maven-plugin/src/it/slice-only)). With only `@WebMvcTest`, most
auto-configurations never run, so nothing without bytecode evidence is judged:

```
org.springframework.boot:spring-boot-starter-web  USED_BYTECODE  org.springframework:spring-web: org.springframework.web.bind.annotation.GetMapping (+1 more)
org.apache.commons:commons-lang3                  UNKNOWN        only slice-test contexts observed; add a @SpringBootTest
```

**Ignore list and failing the build**
([`fail-on-unused`](dependency-analyze-kit-maven-plugin/src/it/fail-on-unused)):

```
org.springframework.boot:spring-boot-starter  USED_BYTECODE     org.springframework.boot:spring-boot-autoconfigure: org.springframework.boot.autoconfigure.SpringBootApplication
org.apache.commons:commons-lang3              IGNORED           ignored by configuration (org.apache.commons:commons-lang3)
commons-io:commons-io                         UNUSED_CANDIDATE  no bytecode reference, bean or auto-configuration
[ERROR] Failed to execute goal ...:analyze (default) on project fail-on-unused: 1 unused dependency candidate(s); see .../report.json
```

### Recipes

#### Fail CI on unused dependencies

```bash
mvn verify -Ddak.enabled=true -Ddak.failOnUnused=true
```

Or set it permanently in the POM (see [Configuration](#configuration)).

#### Run once per profile

Conditions depend on profiles and properties. Give each profile its own output directory, because the probe and
the goal both read `dak.outputDirectory`:

```bash
mvn verify -Ddak.enabled=true -Dspring.profiles.active=prod -Ddak.outputDirectory=target/dak-prod
```

`-Dspring.profiles.active=prod` activates Spring's `prod` profile in the test JVM. Add `-Pprod` only if you also
need a Maven profile; it does not activate a Spring profile unless you explicitly map it. If your test runner
does not forward Maven user properties, explicitly forward `spring.profiles.active` to the test JVM.

A dependency is only safe to remove if it is a candidate in every profile you ship.

#### Run the analysis without the verify phase

The goal only reads probe files captured **during the same Maven invocation**; it checks both file modification
time and the capture timestamp inside the report, so touching an old file cannot make it current. Run
the tests and the goal together:

```bash
mvn test dependency-analyze-kit:analyze -Ddak.enabled=true
```

The `dependency-analyze-kit:` prefix resolves because the plugin is declared in your POM.

#### Multi-module projects

Add the probe and the plugin to each Spring Boot application module. Plain library modules don't need them.
Sibling modules that the app references are credited like any other dependency
([`multi-module`](dependency-analyze-kit-maven-plugin/src/it/multi-module)).

Probe files carry the producing module's real working-directory path; files from another module or an older
probe without that identity are rejected. Keep the test JVM working directory at the module directory and use
the matching probe version. In a reactor, reports go under
`<outputDirectory>/<groupId>/<artifactId>/report.json`, so even a shared absolute output directory preserves
each module's report. Runtime probe files can share that directory; only current files for the module are read.

### Configuration

| Parameter | Default | |
|---|---|---|
| `failOnUnused` (`dak.failOnUnused`) | `false` | Fail on `UNUSED_CANDIDATE` or incomplete analysis, including `UNKNOWN` and missing current full-context evidence. |
| `ignored` | – | `groupId:artifactId` patterns, `*` wildcard. |
| `sideEffectStarters` | – | Extra starters whose matched auto-configuration counts as usage. |
| `includeTestScope` (`dak.includeTestScope`) | `false` | Also report test-scope dependencies. |
| `outputDirectory` (`dak.outputDirectory`) | `${project.build.directory}/dependency-analyze-kit` | Report base directory (reactors add `<groupId>/<artifactId>/`). `-Ddak.outputDirectory=...` overrides both probe and report directories. |
| `skip` (`dak.skip`) | `false` | |

The probe writes under `${basedir}/target/dependency-analyze-kit` by default. The goal reads from that
same directory even if `<build><directory>` or the plugin's XML `<outputDirectory>` selects a different
destination for `report.json`. To relocate both the runtime evidence and the final report, pass
`-Ddak.outputDirectory=...`; Surefire forwards the property to the test JVM. No lifecycle or test-runner
configuration change is needed for custom Maven build directories.

```xml
<plugin>
  <groupId>io.github.sunjirapath</groupId>
  <artifactId>dependency-analyze-kit-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals><goal>analyze</goal></goals>
    </execution>
  </executions>
  <configuration>
    <failOnUnused>true</failOnUnused>
    <ignored>
      <!-- used only through ServiceLoader in production -->
      <ignored>com.example:payment-sdk-spi</ignored>
      <ignored>io.micrometer:micrometer-registry-*</ignored>
    </ignored>
    <sideEffectStarters>
      <!-- matching its auto-configuration is the whole point -->
      <sideEffectStarter>org.springframework.boot:spring-boot-starter-oauth2-client</sideEffectStarter>
    </sideEffectStarters>
  </configuration>
</plugin>
```

### Known gaps

- **Jar registrations retain dependencies.** A declared dependency's own jar containing a non-empty
  `META-INF/services/*` file is retained as `UNKNOWN` unless stronger usage evidence exists. `META-INF/spring.factories`
  and `META-INF/spring/*.imports` also retain it unless the probe has matched or unmatched auto-config evidence for
  that artifact. Transitive jars do not trigger this rule for their owner. Unreadable or unsafe declared jars retain too.
- **Startup runners/listeners count as usage.** Beans implementing `ApplicationRunner`, `CommandLineRunner` or
  `ApplicationListener` credit their artifacts even without an injection consumer. Beans whose class names start
  with `org.springframework.` and beans implementing only `Lifecycle`/`SmartLifecycle` do not trigger this rule.
- **Scope and location limits retain dependencies.** Test/provided declarations never become `UNUSED_CANDIDATE`;
  runtime declarations remain reportable. An unresolved location on a reached dependency bean, project-module root
  or factory-product root makes otherwise unused dependencies `UNKNOWN`. Unreached probe-only beans do not.
- **No class-loading or JFR tracing.** Reflection-only or template-only usage (e.g. a Thymeleaf view) still needs
  `ignored`; `UNUSED_CANDIDATE` cannot prove such usage absent. Registrations do not prove that a provider ran. The
  built-in ignore list covers JDBC drivers, logging backends and bridges, annotation processors, devtools,
  docker-compose, ehcache (JCache), commons-pool2 and feign-micrometer. Feign's per-client contexts are not probed either.
- **Startup migrations need manual review.** `flyway-core`, `liquibase-core`, `spring-boot-starter-flyway` and
  `spring-boot-starter-liquibase` are conservatively ignored when no stronger usage evidence exists. They can
  perform migrations without an application bean consumer. This suppression does not prove a migration ran;
  inspect migration resources and test the migrated schema before removing them.
- **Evidence covers only what your tests exercised.** Conditions depend on profiles and properties. Run the build
  once per profile; merging results across runs is not supported. Lazy and prototype beans that no test created
  have no dependency edges.
- **Shared artifacts count for nobody.** If two declared starters both bring `spring-boot`, a reference to
  `SpringApplication` credits neither. One exception: when every other claimant brings in a dependency whole, as
  with an internal library that depends on the same starter you declare, that dependency owns the shared jars. So
  an explicit `spring-boot-starter` is credited, and so is a starter that one of your own libraries also pulls in.
  A test-scope dependency never makes a jar shared for a main-scope one, because it can't replace it. So
  `spring-boot-starter-test` doesn't hide `spring-boot-starter`. And a starter owns the Boot module it is named
  after: `spring-boot-starter-X` owns `spring-boot-X` even when a library also pulls that module in.
- **Dependencies are identified by `groupId:artifactId`.** A dependency declared twice with different classifiers
  (e.g. a module and its `test-jar`) is analyzed as one.
- **Class metadata scanning:** missing output directories are optional; malformed metadata is an analysis error.
  Multi-release jar ownership uses the build JVM's version, which should match the test JVM for representative evidence.
- **Maven 4** passes in CI on 4.0.0-rc-7. That job doesn't block the build until Maven 4 reaches GA.
- **Not supported:** auto-removal of dependencies, fat-jar (`jar:nested:`) class locations.
  The probe is meant for the test classpath. There's no `mvn dependency:analyze -Danalyzer=…` adapter yet.
  Its result format can't carry verdicts or evidence; it can be added on request.

### Build and contribute

```bash
mvn verify
```

This runs the unit tests and the maven-invoker integration tests in `dependency-analyze-kit-maven-plugin/src/it`.
The Maven integration tests use Boot 3.5.16, plus one on Boot 4.1.1:

| IT | Checks |
|---|---|
| `web-redis-unused`, `web-redis-unused-boot4` | Unused redis starter and commons-lang3 are candidates; the web starter is used. |
| `jpa-h2` | Failsafe-only context: data-jpa is used (the JPA auto-config appears in its evidence); h2 is ignored. |
| `jdbc-actuator` | No-fork Surefire: `USED_BEAN` via an injected `DataSource`; actuator is `USED_AUTOCONFIG`. |
| `conditional-property` | Custom `argLine` and explicit property forwarding with user-property promotion disabled; sanitized `OnPropertyCondition` evidence and active-profile provenance. |
| `slice-only` | With only `@WebMvcTest` contexts, the result is `UNKNOWN` and the gate fails. |
| `unconditional-side-effect` | Unconditional auto-configurations count for configured side-effect starters. |
| `nested-factory` | Factory products registered before their factories still contribute their injected dependencies. |
| `parent-child` / `parent-child-shadow` | Child injection reaches parent beans, while local beans shadow same-named parent beans. |
| `custom-build-directory` / `custom-output-directory` / `shared-output-directory` | Runtime evidence survives custom report/build locations and a shared system-property override; unused dependencies fail the build. |
| `no-probe` | Stale reports are rejected; missing evidence is `UNKNOWN` and the gate fails. |
| `missing-probe` | Absent evidence fails the gate even when all dependencies are ignored. |
| `shared-reactor-output` | Two applications share an absolute probe directory; the skipped module rejects the other's evidence and reports stay separate. |
| `flyway-migration` / `liquibase-migration` | Startup migrations create a schema without application bean consumers; the migration dependency is retained. |
| `fail-on-unused` | `failOnUnused` and `ignored`. |
| `multi-module` | A reactor sibling dependency. |

The first run fills an isolated repository under `target/local-repo`, which can take several minutes.
CI runs on Linux (Java 17 and 21), Windows and macOS, plus Maven 4 as a non-blocking job.

The Gradle plugin's integration test runs the full multi-project fixture ([`multi-project`](dependency-analyze-kit-gradle-plugin/src/it/multi-project))
on Boot 3.5.16 and 4.0.8. It checks `report.json`, the README extension configuration, constraint exclusion, excluded-scope diagnostics,
strict settings repositories, configuration-cache reuse and current probe evidence, including Boot 3 `@ConditionalOnClass` attribution.
It needs Python 3 and Gradle 9.8.1 on the path (CI runs both Boot versions on Java 17 and 21):

```bash
mvn install -DskipTests -Dinvoker.skip=true && python3 dependency-analyze-kit-gradle-plugin/src/it/verify.py gradle 3.5.16 && python3 dependency-analyze-kit-gradle-plugin/src/it/verify.py gradle 4.0.8
```

The opt-in [real-project verifier](scripts/verify_real_projects.py) reads `MANIFEST.json` under
`REAL_PROJECTS_DIR`. With that variable unset, or a project missing, it reports explicit unittest skips.
It covers both petclinic builds, petclinic REST, the microservices reactor, the Boot 3.5 pin, and the
manifest's Boot 2 control. It copies each checkout and dependency cache into a temporary directory.

For example, after installing the current snapshot into an offline Maven repository:

```powershell
$env:REAL_PROJECTS_DIR = 'E:/rv/_real'
$env:DAK_REPO_DIR = 'path/to/prepared/local-repository'
python scripts/verify_real_projects.py
```

Requires Python 3.9+, installed Maven/Gradle (override with `DAK_MAVEN` / `DAK_GRADLE`), and the
project dependencies and plugins already cached. Gradle copies the caches under `GRADLE_USER_HOME`
(default `~/.gradle`). Builds use offline mode and isolated cache copies; wrappers are not invoked.
The source checkouts must already match the manifest pins; the verifier does not run Git to attest them.
External services or container runtimes required by a project's tests still need to be available.
`DAK_REAL_TIMEOUT` sets each build's timeout in seconds (default 1800).
Cache isolation and source fingerprints do not sandbox arbitrary build/test code; use an external sandbox
for filesystem/network enforcement. Config-secret matching covers literal sensitive keys and URL credentials,
not full YAML semantics, unresolved environment expressions, or secrets shorter than four characters.

Checks include report determinism (excluding per-run context IDs/keys/timestamps), evidence paths,
secret exclusion, independent removal/rebuild of **every** unused candidate, an added unused dependency,
the documented failure policy (unused **or incomplete** analysis), and truncated/deleted runtime evidence.
Precision failures print the dependency, module and escaped build-output tail. Missing offline artifacts or
external services are failures, not successful validations. Real-project results were observed on one Windows host: all 5 Maven and 2 Gradle projects passed, and the Boot 2.0.3 control failed closed as intended; Gradle took about 38 minutes.

Security regression tests with `FINDING` comments are deliberately active and failing pending design fixes.
Symlink attack tests were skipped on Windows (2 skipped); they still need a host permitted to create symlinks, such as Linux CI. The verifier passed on one Windows host; the 7 security tests remain deliberately active and failing pending design fixes.
Harness-only checks can be run separately with `python -m unittest discover -s scripts -p 'test_*.py'`;
they launch no external builds. The real Gradle cases also check configuration-cache reuse, stale probe
rejection, and whether a hostile output directory lets `dakClean` delete unrelated data inside the temp tree.
Bounded readers reject class/probe files over 16 MiB, jars over 256 MiB, manifests over 1 MiB,
more than 100,000 scan paths/jar entries, and JSON nesting deeper than 64. Unsupported/oversized inputs
must fail clearly or make analysis incomplete; these limits have not been calibrated on the real projects.

To try an unreleased snapshot of `main` in your own project, install it locally with
`mvn install -DskipTests -Dinvoker.skip=true` and use version `0.2.0-SNAPSHOT`.

#### Releasing

Releases go to Maven Central through the Central Portal with
[`.github/workflows/release.yml`](.github/workflows/release.yml).

One-time setup (repository secrets):

| Secret | Value |
|---|---|
| `CENTRAL_USERNAME`, `CENTRAL_TOKEN` | A user token from https://central.sonatype.com/account (the namespace `io.github.sunjirapath` must be verified). |
| `GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys <KEY_ID>` of a key whose public part is on `keyserver.ubuntu.com`. |
| `GPG_PASSPHRASE` | That key's passphrase. |

To release, push a tag such as `v0.1.0`. The workflow sets that version, runs the full build, signs the
artifacts and uploads them. The deployment then waits at https://central.sonatype.com/publishing until you
press **Publish**. A published version can never be changed or deleted.

Every push also runs a `release-dry-run` CI job that builds the sources and javadoc jars without signing.

### License

This project is licensed under the [Apache License 2.0](LICENSE).

## ภาษาไทย

Maven และ Gradle plugins สำหรับหา dependency ที่ไม่ได้ใช้ในโปรเจกต์ Spring Boot โดยไม่เจอผลผิดแบบ `mvn dependency:analyze`
ซึ่งดูแค่ bytecode จึงรายงาน starter ทุกตัวว่า "ไม่ได้ใช้" (starter เป็น jar เปล่า)
ปลั๊กอินนี้รวมหลักฐาน 3 แหล่ง: bytecode, dependency graph (ตัดสิน starter จาก artifact ที่มันดึงเข้ามาแต่เพียงผู้เดียว)
และหลักฐานจาก Spring ตอนรันเทสต์ (condition evaluation report และ bean graph)

### ติดตั้ง

เวอร์ชันล่าสุด **0.1.0** บน Maven Central: `io.github.sunjirapath:dependency-analyze-kit-maven-plugin:0.1.0`
และ `io.github.sunjirapath:dependency-analyze-kit-spring-probe:0.1.0` (ใส่เป็น `test` scope) ดูตัวอย่าง XML ที่ [Install](#install)

**เวอร์ชันที่ทดสอบ:** Java 17 และ 21, Maven 3.9 ขึ้นไป (ทดสอบ Maven 4.0.0-rc-7 ใน CI ด้วย), Spring Boot 3.5.16 (Maven และ Gradle), 4.0.8 (Gradle) และ 4.1.1 (Maven) ไม่ได้อ้างว่ารองรับ Spring Boot 2.x, 3.0-3.4, patch version อื่นของ 3.5 หรือ version อื่นของ 4.x
ต้องมี `@SpringBootTest` อย่างน้อยหนึ่งตัว

### เริ่มต้นใช้งาน

```bash
mvn verify -Ddak.enabled=true
```

probe จะทำงานเฉพาะเมื่อใส่ `-Ddak.enabled=true` จากนั้น goal `analyze` จะพิมพ์ตารางผลลัพธ์และเขียน
`target/dependency-analyze-kit/report.json` ถ้าต้องการให้ CI fail เมื่อเจอ dependency ที่ไม่ได้ใช้ ให้เพิ่ม `-Ddak.failOnUnused=true`

เมื่อเปิด `failOnUnused` จะ fail ด้วยหากเป็น `UNKNOWN` หรือไม่มีหลักฐาน full context ของการรันปัจจุบัน

### ผลลัพธ์

| ผล | ความหมาย | ควรทำอะไร |
|---|---|---|
| `USED_BYTECODE` | โค้ดในโปรเจกต์อ้างถึง jar ของมัน หรือ jar ที่มันดึงเข้ามาแต่เพียงผู้เดียว | เก็บไว้ |
| `USED_BEAN` | bean จากมันถูก inject เข้า bean ของโปรเจกต์ หรือเป็น startup runner/listener ที่ไม่ใช่คลาสภายใน Spring | เก็บไว้ |
| `USED_AUTOCONFIG` | auto-config ของมัน match และเป็น starter ที่ทำงานด้วยตัวเอง (actuator, security, template engine, cache, validation, AOP, Resilience4j, Micrometer registry ฯลฯ) | เก็บไว้ |
| `UNUSED_CANDIDATE` | ไม่พบหลักฐานการใช้หรือกฎให้เก็บไว้ ใน test context เต็มอย่างน้อยหนึ่งตัว ยังไม่ใช่ข้อพิสูจน์ว่าไม่ใช้ | ตรวจสอบก่อนลบแล้วรันเทสต์ การใช้ผ่าน reflection อย่างเดียวต้องใส่ใน `ignored` |
| `IGNORED` | อยู่ใน ignore list (เช่น JDBC driver, logging backend) | ไม่ต้องทำอะไร |
| `UNKNOWN` | หลักฐานไม่ครบ, location ของ bean ที่เข้าถึงแมปไม่ได้, มี dynamic registration หรือเป็น scope test/provided | เก็บไว้และตรวจ summary กับความครอบคลุมของ probe |

auto-config ที่ match อย่างเดียว **ไม่นับ** ว่าใช้ เพราะ `@ConditionalOnClass` จะ match ทันทีที่ jar อยู่บน classpath

### ข้อจำกัดสำคัญ

- jar ของ dependency ที่ประกาศเองซึ่งมี `META-INF/services/*` ที่ไม่ว่างจะถูกเก็บไว้ เช่นเดียวกับ `spring.factories`/`spring/*.imports`
  ที่ยังไม่มีหลักฐาน auto-config matched/unmatched; ไม่ใช้กฎนี้กับ jar ทางอ้อม และ jar ที่อ่านไม่ได้จะถูกเก็บไว้
- startup runner/listener ที่ไม่ใช่คลาสภายใน Spring นับว่าใช้ แม้ไม่มี bean อื่น inject; ไม่รวม Lifecycle/SmartLifecycle อย่างเดียว
- scope test/provided ไม่เป็น `UNUSED_CANDIDATE` แต่ runtime ยังรายงานได้; location ที่แมปไม่ได้ของ bean ที่เข้าถึงจากโปรเจกต์ทำให้เก็บไว้
  ส่วน bean ที่ไม่ถูกเข้าถึงไม่กระทบ dependency อื่น
- reflection หรือ template อย่างเดียวยังต้องใส่ `ignored`; `UNUSED_CANDIDATE` เป็นเพียงตัวเลือกให้ตรวจ ไม่ใช่ข้อพิสูจน์ว่าไม่ใช้
- หลักฐานครอบคลุมเฉพาะสิ่งที่เทสต์รันถึง ผลขึ้นกับ profile และ property ให้รันแยกทีละ profile
- artifact ที่ starter หลายตัวใช้ร่วมกันจะไม่นับให้ใครเลย ยกเว้นตัวที่ตัวอื่นทุกตัวดึงเข้ามาทั้งก้อน รายละเอียดดู [Known gaps](#known-gaps)

### สัญญาอนุญาต

โปรเจกต์นี้อยู่ภายใต้ [Apache License 2.0](LICENSE)

[MDEP-557]: https://issues.apache.org/jira/browse/MDEP-557

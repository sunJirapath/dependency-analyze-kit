# dependency-analyze-kit

[![build](https://github.com/sunJirapath/dependency-analyze-kit/actions/workflows/build.yml/badge.svg)](https://github.com/sunJirapath/dependency-analyze-kit/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.sunjirapath/dependency-analyze-kit-maven-plugin)](https://central.sonatype.com/artifact/io.github.sunjirapath/dependency-analyze-kit-maven-plugin)

A Maven plugin that finds unused dependencies in Spring Boot projects, without the false positives of
`mvn dependency:analyze`.

`dependency:analyze` looks at bytecode only. Spring Boot starters are empty jars, so it reports every starter as
"unused declared" and the starter's transitive dependencies as "used undeclared" ([MDEP-557]).

This plugin joins three sources:

1. **Bytecode references** from the default `maven-dependency-analyzer`.
2. **The resolved dependency graph.** Each starter is judged by the artifacts it brings in *exclusively*.
3. **Runtime evidence from Spring** collected while your tests run: the condition evaluation report (matched and
   unmatched auto-configurations) and the bean graph (which beans your own beans depend on).

Background and design decisions: [docs/research.md](docs/research.md).

## Quick start

Requirements: Java 17+, Maven 3.9+, Spring Boot 3.x or 4.x, and at least one `@SpringBootTest`.

Both artifacts are on Maven Central under `io.github.sunjirapath`.

### 1. Add the probe and the plugin to your Spring Boot project

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

### 2. Run the build with the probe switched on

```bash
mvn verify -Ddak.enabled=true
```

The probe does nothing unless `dak.enabled=true` is set. Surefire passes `-D` properties on to the forked test
JVM. With the property set, every Spring context the tests start writes
`target/dependency-analyze-kit/context-<pid>-<n>.json`. The `analyze` goal then prints a table and writes
`target/dependency-analyze-kit/report.json`.

Without `-Ddak.enabled=true` the goal still runs, but with bytecode evidence only. Everything else comes out
`UNKNOWN`; nothing is ever called unused.

### 3. Read the result

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
  "probeReports": 1,
  "dependencies": [
    {
      "dependency": "org.springframework.boot:spring-boot-starter-data-redis",
      "scope": "compile",
      "verdict": "UNUSED_CANDIDATE",
      "summary": "12 auto-configurations matched (RedisAutoConfiguration, RedisCacheConfiguration, ...), but no project class or bean uses them",
      "evidence": [
        { "kind": "AUTOCONFIG_MATCHED", "detail": "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration matched" },
        { "kind": "AUTOCONFIG_UNMATCHED", "detail": "org.springframework.boot.autoconfigure.cache.JCacheCacheConfiguration did not match: OnClassCondition: @ConditionalOnClass did not find required class 'javax.cache.Caching'" }
      ]
    }
  ]
}
```

Evidence `kind` is one of `BYTECODE`, `BEAN`, `AUTOCONFIG_MATCHED`, `AUTOCONFIG_UNMATCHED` or `IGNORED`.

## Verdicts

| Verdict | Meaning | What to do |
|---|---|---|
| `USED_BYTECODE` | Project classes reference the dependency's jar, or a jar only it brings in. | Keep it. |
| `USED_BEAN` | A bean from it (or from a jar only it brings in) is injected, directly or transitively, into one of your beans. | Keep it. |
| `USED_AUTOCONFIG` | One of its auto-configurations matched, and it is a side-effect starter: actuator, security, template engines, web-services, plus your `sideEffectStarters`. | Keep it. |
| `UNUSED_CANDIDATE` | None of the above, in at least one full (non-slice) test context. | Remove it and run the tests. If something breaks at runtime, add it to `ignored` (see [Known gaps](#known-gaps)). |
| `IGNORED` | It would be unused or unknown, but it is on the built-in or configured ignore list. | Nothing. |
| `UNKNOWN` | No probe report, or only slice-test contexts were observed. Nothing is ever reported as unused without runtime evidence. | Run with `-Ddak.enabled=true` and add a `@SpringBootTest`. |

A matched auto-configuration on its own does **not** count as usage. `@ConditionalOnClass` matches simply because
the jar is on the classpath.

## Examples

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
io.github.sunjirapath.it:feature-starter      UNUSED_CANDIDATE  it.feature.FeatureAutoConfiguration did not match: OnPropertyCondition: @ConditionalOnProperty (feature.enabled=true) did not find property 'feature.enabled'
```

Either the starter really is unused, or your tests don't set the property your production profile sets. See
[profiles](#run-once-per-profile).

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

## Recipes

### Fail CI on unused dependencies

```bash
mvn verify -Ddak.enabled=true -Ddak.failOnUnused=true
```

Or set it permanently in the POM (see [Configuration](#configuration)).

### Run once per profile

Conditions depend on profiles and properties. Give each profile its own output directory, because the probe and
the goal both read `dak.outputDirectory`:

```bash
mvn verify -Ddak.enabled=true -Pprod -Ddak.outputDirectory=target/dak-prod
```

A dependency is only safe to remove if it is a candidate in every profile you ship.

### Run the analysis without the verify phase

The goal only reads probe files written **during the same Maven invocation**; older ones are treated as stale. Run
the tests and the goal together:

```bash
mvn test dependency-analyze-kit:analyze -Ddak.enabled=true
```

The `dependency-analyze-kit:` prefix resolves because the plugin is declared in your POM.

### Multi-module projects

Add the probe and the plugin to each Spring Boot application module. Plain library modules don't need them.
Sibling modules that the app references are credited like any other dependency
([`multi-module`](dependency-analyze-kit-maven-plugin/src/it/multi-module)).

## Configuration

| Parameter | Default | |
|---|---|---|
| `failOnUnused` (`dak.failOnUnused`) | `false` | Fail the build when any `UNUSED_CANDIDATE` is found. |
| `ignored` | – | `groupId:artifactId` patterns, `*` wildcard. |
| `sideEffectStarters` | – | Extra starters whose matched auto-configuration counts as usage. |
| `includeTestScope` (`dak.includeTestScope`) | `false` | Also report test-scope dependencies. |
| `outputDirectory` (`dak.outputDirectory`) | `target/dependency-analyze-kit` | Used by both the probe and the goal. |
| `skip` (`dak.skip`) | `false` | |

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

## Known gaps

- **No class-loading or JFR tracing.** Usage through `ServiceLoader`/SPI, JDBC drivers, logging backends, reflection,
  or templates only (e.g. a Thymeleaf view) is invisible, so these can show up as false `UNUSED_CANDIDATE`s. The
  built-in ignore list covers JDBC drivers, logging backends and bridges, annotation processors, devtools and
  docker-compose.
- **Evidence covers only what your tests exercised.** Conditions depend on profiles and properties. Run the build
  once per profile; merging results across runs is not supported. Lazy and prototype beans that no test created
  have no dependency edges.
- **Shared artifacts count for nobody.** If two declared starters both bring `spring-boot`, a reference to
  `SpringApplication` credits neither. An explicitly declared `spring-boot-starter` next to another starter is
  therefore often a candidate. That is accurate for compilation, but removing it can change logging. Use
  `ignored` if you want to keep it.
  A test-scope dependency never makes a jar shared for a main-scope one, because it can't replace it. So
  `spring-boot-starter-test` doesn't hide `spring-boot-starter`.
- **Dependencies are identified by `groupId:artifactId`.** A dependency declared twice with different classifiers
  (e.g. a module and its `test-jar`) is analyzed as one.
- **Maven 4** passes in CI on 4.0.0-rc-7. That job doesn't block the build until Maven 4 reaches GA.
- **Not supported:** Gradle, auto-removal of dependencies, fat-jar (`jar:nested:`) class locations.
  The probe is meant for the test classpath. There's no `mvn dependency:analyze -Danalyzer=…` adapter yet.
  Its result format can't carry verdicts or evidence; it can be added on request.

## Build

```bash
mvn verify
```

To try an unreleased snapshot of `main` in your own project, install it locally with
`mvn install -DskipTests -Dinvoker.skip=true` and use version `0.2.0-SNAPSHOT`.

This runs the unit tests and the maven-invoker integration tests in `dependency-analyze-kit-maven-plugin/src/it`.
The integration tests use Boot 3.5.16, plus one on Boot 4.1.1:

| IT | Checks |
|---|---|
| `web-redis-unused`, `web-redis-unused-boot4` | Unused redis starter and commons-lang3 are candidates; the web starter is used. |
| `jpa-h2` | data-jpa is used (the JPA auto-config appears in its evidence); h2 is ignored. |
| `jdbc-actuator` | `USED_BEAN` via an injected `DataSource`; actuator is `USED_AUTOCONFIG`. |
| `conditional-property` | The evidence names the unmet `@ConditionalOnProperty`. |
| `slice-only` | With only `@WebMvcTest` contexts, the result is `UNKNOWN`, never unused. |
| `no-probe` | Without probe reports, everything without bytecode evidence is `UNKNOWN`. |
| `fail-on-unused` | `failOnUnused` and `ignored`. |
| `multi-module` | A reactor sibling dependency. |

The first run fills an isolated repository under `target/local-repo`, which can take several minutes.
CI runs on Linux (Java 17 and 21), Windows and macOS, plus Maven 4 as a non-blocking job.

## Releasing

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

License: [Apache-2.0](LICENSE).

[MDEP-557]: https://issues.apache.org/jira/browse/MDEP-557

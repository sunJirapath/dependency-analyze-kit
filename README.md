# dependency-analyze-kit

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

## Usage

```xml
<dependency>
  <groupId>io.github.sunjirapath</groupId>
  <artifactId>dependency-analyze-kit-spring-probe</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
...
<plugin>
  <groupId>io.github.sunjirapath</groupId>
  <artifactId>dependency-analyze-kit-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>analyze</goal></goals>   <!-- bound to verify -->
    </execution>
  </executions>
</plugin>
```

```bash
mvn verify -Ddak.enabled=true
```

The probe does nothing unless `dak.enabled=true` is set. With it set, every Spring context refreshed during the
tests writes `target/dependency-analyze-kit/context-<pid>-<n>.json`. The `analyze` goal prints a table and writes
`target/dependency-analyze-kit/report.json`:

```
DEPENDENCY                                               VERDICT           EVIDENCE
org.springframework.boot:spring-boot-starter-web         USED_BYTECODE     org.springframework:spring-web: org.springframework.web.bind.annotation.GetMapping (+1 more)
org.springframework.boot:spring-boot-starter-data-redis  UNUSED_CANDIDATE  ...RedisCacheConfiguration matched, but no project class or bean uses it
org.apache.commons:commons-lang3                         UNUSED_CANDIDATE  no bytecode reference, bean or auto-configuration
```

## Verdicts

| Verdict | Meaning |
|---|---|
| `USED_BYTECODE` | Project classes reference the dependency's jar, or a jar only it brings in. |
| `USED_BEAN` | A bean from it (or from a jar only it brings in) is injected, directly or transitively, into one of your beans. |
| `USED_AUTOCONFIG` | One of its auto-configurations matched, and it is a side-effect starter: actuator, security, template engines, web-services, plus your `sideEffectStarters`. |
| `UNUSED_CANDIDATE` | None of the above, in at least one full (non-slice) test context. |
| `IGNORED` | It would be unused or unknown, but it is on the built-in or configured ignore list. |
| `UNKNOWN` | No probe report, or only slice-test contexts were observed. Nothing is ever reported as unused without runtime evidence. |

A matched auto-configuration on its own does **not** count as usage. `@ConditionalOnClass` matches simply because
the jar is on the classpath.

## Configuration

| Parameter | Default | |
|---|---|---|
| `failOnUnused` (`dak.failOnUnused`) | `false` | Fail the build when any `UNUSED_CANDIDATE` is found. |
| `ignored` | – | `groupId:artifactId` patterns, `*` wildcard. |
| `sideEffectStarters` | – | Extra starters whose matched auto-configuration counts as usage. |
| `includeTestScope` (`dak.includeTestScope`) | `false` | Also report test-scope dependencies. |
| `outputDirectory` (`dak.outputDirectory`) | `target/dependency-analyze-kit` | Used by both the probe and the goal. |
| `skip` (`dak.skip`) | `false` | |

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
- **Not supported:** Gradle, auto-removal of dependencies, fat-jar (`jar:nested:`) class locations.
  The probe is meant for the test classpath.

## Build

```bash
mvn verify
```

This runs the unit tests and the maven-invoker integration tests in `dependency-analyze-kit-maven-plugin/src/it`
(Boot 3.5.16 and one on Boot 4.1.1). The first run fills an isolated repository under `target/local-repo`, which
can take several minutes.

License: Apache-2.0.

[MDEP-557]: https://issues.apache.org/jira/browse/MDEP-557

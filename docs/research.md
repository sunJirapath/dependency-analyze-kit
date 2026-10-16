# Phase 0 research — dependency-analyze-kit

Date: 2026-10-16. Sources were checked against code (cloned repos, `javap` on local jars, Maven Central metadata). Anything not checked that way is marked **UNVERIFIED**.

## TL;DR

1. **Build it; don't contribute.** No existing tool joins runtime condition evidence with bytecode analysis and the dependency graph. Closest prior art is `boot-usage` (a runtime-only Actuator endpoint). It has the false-positive problem described in #3.
2. **Wrap the default analyzer.** `ProjectDependencyAnalyzer` can be injected into our own Mojo. Its result object can be rebuilt. No bytecode scanning to rewrite.
3. **Design flaw found: "auto-config matched" is not evidence of use.** `@ConditionalOnClass` matches *because the jar is on the classpath*. An unused `spring-boot-starter-data-redis` still matches `RedisAutoConfiguration` and still creates a `RedisTemplate` bean. Under the current rules, IT #1 (redis must be `UNUSED_CANDIDATE`) can't pass. Proposed fix: count *consumption*, not *presence* (§5.1).
4. **Spring Boot 4.x is current (4.1.1); 3.5.x is the last 3.x line.** The APIs we need are the same in both. Boot 4 split auto-configuration into per-technology jars, which makes attribution *easier*. Boot 3 keeps every auto-config in one shared jar (`spring-boot-autoconfigure`), so attribution there has to go through `@ConditionalOnClass` targets (§5.2).

---

## 1. Existing tools

| Tool | Spring-aware? | Runtime evidence? | Status | Verdict |
|---|---|---|---|---|
| `io.github.tourem:deploy-manifest-plugin` 3.0.0 | Name heuristic only | No | Last release 2025-11-24, 4★, no LICENSE file (POM says Apache-2.0) | Build new |
| DepClean (`se.kth.castor:depclean-maven-plugin` 2.2.0) | Legacy Spring XML class refs only | No | Active (commits Oct 2026), MIT | Build new (contribution optional, later) |
| autonomousapps DAGP v3.19.2 | Only treats Boot apps as "application" projects | No | Active, Apache-2.0, Gradle/Kotlin | Build new, borrow concepts |
| `dhruv-15-03/boot-usage` v1.0.3 | Yes | Yes — `ConditionEvaluationReport` | Commit 2026-07-27, JitPack only, Apache-2.0 | Reference only |

**deploy-manifest-plugin.** `AnalyzeDependenciesMojo.detectFalsePositives()` / `isSpringBootStarter()` checks `groupId == org.springframework.boot` and `artifactId.startsWith("spring-boot-starter-")`; a ~40-entry list next to it is redundant. A match is only *flagged*: `suspectedFalsePositive=true`, confidence 0.5. It uses maven-dependency-analyzer 1.16.0 underneath. There's nothing to extend.
<https://github.com/tourem/deploy-manifest-plugin>

**DepClean.** Has its own ASM engine. Does not reuse maven-dependency-analyzer. Scans XML resources for class names. Its "dynamic" sibling, `castor-software/jdbl`, debloats bytecode with coverage data. It doesn't classify dependencies, and the last push was 2023. DepClean could take a runtime-evidence input through its `ignoreDependencies` knob, but its model has no idea of starters or exclusive closures. Revisit after the MVP proves the idea.
<https://github.com/castor-software/depclean>

**DAGP.** Gradle only. Two concepts worth borrowing:
- **Bundles**: if any member of a declared group is used, the whole group counts as used. That is our starter rule, just declared by hand instead of derived from the graph.
- **Per-dependency "capabilities"**: classes, constants, annotation processor, ServiceLoader, native. This maps onto our `Evidence` list.

<https://github.com/autonomousapps/dependency-analysis-gradle-plugin>

**boot-usage** (`StarterUsageAnalyzer.java`). Hardcoded starter → auto-config FQCN map. Example: `spring-boot-starter-data-redis` → `org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration`. It returns `USED` as soon as any mapped auto-config matched. That gives two bugs we must avoid:
- (a) An unused redis starter reports USED, because the auto-config matches on class presence alone.
- (b) The 3.x FQCNs don't exist in Boot 4.

It has no bytecode or graph join and is not a Maven plugin.
<https://github.com/dhruv-15-03/boot-usage>

**References confirmed:**
- [MDEP-557](https://issues.apache.org/jira/browse/MDEP-557) — open, unassigned: support Boot-style intentional transitive deps.
- [MDEP-665](https://issues.apache.org/jira/browse/MDEP-665) — closed as a duplicate of 557. It proposed treating starters as "delegates".
- [spring-boot#8341](https://github.com/spring-projects/spring-boot/issues/8341) — closed as invalid on 2017-02-20. The issue was sent to Maven's side.
- [Frankel, 2025-03-09](https://blog.frankel.ch/maven-dependency-analyze/) — proposes a Spring-aware analyzer that wraps the default one. Nothing was built.

## 2. maven-dependency-analyzer API (latest 1.17.2; plugin latest 3.11.0)

Source: `apache/maven-dependency-analyzer` tag `maven-dependency-analyzer-1.17.2`, plus `javap` on the local 1.17.0 jar.

```java
public interface ProjectDependencyAnalyzer {                       // no ROLE constant; role = interface FQN
  default ProjectDependencyAnalysis analyze(MavenProject p) throws ProjectDependencyAnalyzerException;
  ProjectDependencyAnalysis analyze(MavenProject p, Collection<String> excludedClasses) throws ...;
}
```

- **Default implementation.** `DefaultProjectDependencyAnalyzer` is `@Named @Singleton` (JSR-330 / Sisu, hint `"default"`). Its collaborators are private `@Inject` fields, so `new` doesn't work. Get it through `@Inject ProjectDependencyAnalyzer analyzer;` in our Mojo. ✅ That is the wrap.
- **Result object.** `ProjectDependencyAnalysis` is immutable: final fields, defensive copies. To move an artifact out of "unused", rebuild it from the getters with the constructor
  `(Map<Artifact,Set<DependencyUsage>> usedDeclared, Map<…> usedUndeclared, Set<Artifact> unusedDeclared, Set<Artifact> testArtifactsWithNonTestScope)`.
  - Getters include `getUsedDeclaredArtifactsWithUsages()` and `getUsedUndeclaredArtifactsWithClasses()`.
  - Helpers `ignoreNonCompile()` and `forceDeclaredDependenciesUsage(String[])` also exist.
- **Class → artifact mapping.** It exists only as `protected buildArtifactClassMap(...)`, which isn't usable from outside the class. We don't need it. Bytecode evidence comes from `usedDeclared` + `usedUndeclared`: a used-undeclared artifact is a *transitive* that project code references, and the starter rule credits it to its owning declared dependency.
- **maven-dependency-plugin.** `analyze` has `@Parameter(property="analyzer", defaultValue="default")`. The plugin looks the analyzer up with `plexusContainer.lookup(ProjectDependencyAnalyzer.class, analyzer)`. So a third-party `@Named("kit")` jar added as a plugin `<dependency>` does work. The plugin then applies `usedDependencies`, `ignoredUnusedDeclaredDependencies`, `ignoredNonTestScopedDependencies` and `ignoredDependencies` afterwards.
  - **Recommendation: skip the adapter in the MVP.** The `ProjectDependencyAnalysis` shape can't carry verdicts or evidence. Add the adapter if users ask to keep `mvn dependency:analyze`. It's about 30 lines on top of the classifier.
- **Dependency graph.** `Artifact.getDependencyTrail()` records only the one winning path after conflict mediation. That makes it the wrong tool for "exclusive" closures (a shared artifact shows up under only one parent). Use the Resolver, which is already in Maven core with no new dependency. For each declared dependency D, run `RepositorySystem.collectDependencies` with root D, the project's managed deps and D's exclusions. Then:
  `exclusive(D) = closure(D) − ⋃ closure(other declared)`
  The comparison uses `groupId:artifactId`, ignoring version. The cost is N collect calls, and resolver caching makes them cheap.

## 3. Spring Boot APIs (3.5.x vs 4.1.1)

Source: `javap` on local Boot 4.1.1 jars; Boot 3.5.x from `spring-projects/spring-boot` branch `3.5.x`.

| Need | API | 3.5 = 4.1? |
|---|---|---|
| Condition report | `org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport`<br>`static get(ConfigurableListableBeanFactory)` (creates one if missing)<br>`static find(BeanFactory)` (returns null) | ✅ |
| Outcomes | `Map<String, ConditionAndOutcomes> getConditionAndOutcomesBySource()`<br>• keys are an FQCN (config class) or `"FQCN#beanMethod"`<br>• `ConditionAndOutcomes.isFullMatch()`<br>• each `ConditionAndOutcome` has `getCondition()` and `getOutcome()` → `isMatch()`, `getMessage()` | ✅ (`getMessage()` exists via `ConditionOutcome`; **UNVERIFIED** in javap output) |
| Exclusions etc. | `getExclusions()`, `getUnconditionalClasses()`, `getParent()` | ✅ |
| When complete | All conditions are evaluated in `ConfigurationClassPostProcessor` (the BFPP phase), before `ContextRefreshedEvent`. Safe to read on refresh. | ✅ |
| Model to copy | `org.springframework.boot.autoconfigure.logging.ConditionEvaluationReportLoggingListener`:<br>• an `ApplicationContextInitializer` registered in `spring.factories`<br>• adds a listener for `ContextRefreshedEvent` that checks `event.getApplicationContext() == this.context`, which filters out parent/child contexts | ✅ |
| Matched / unmatched split | Copy the Actuator `ConditionsReportEndpoint`: `isFullMatch()` → positive; otherwise negative, as {matched, notMatched}. It shortens the keys with `ClassUtils.getShortName`; **we keep the FQCN**. | ✅ |
| `AutoConfiguration.imports` | `org.springframework.boot.context.annotation.ImportCandidates.load(Class<?>, ClassLoader)`. It returns class names only, without the URL they came from. For per-jar origin, call `cl.getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")` and parse each URL, skipping `#` comments. `spring.factories` `EnableAutoConfiguration` hasn't been read since 3.0. | ✅ |
| Class → jar | Use `cl.getResource(fqcn.replace('.', '/') + ".class")` and parse the `jar:file:…!/` URL. It is more robust than `getProtectionDomain().getCodeSource()`, which is null for JDK classes. Under Surefire, locations are plain `~/.m2` jar paths or `target/(test-)classes`. | ✅ |
| Bean → class without instantiation | Use `beanFactory.getType(name, false)`. It handles `@Bean` factory methods and `FactoryBean` object types. `BeanDefinition.getBeanClassName()` is null for `@Bean` methods. Strip proxies with `ClassUtils.getUserClass`. | ✅ |
| Who consumes a bean | `ConfigurableBeanFactory.getDependenciesForBean(name)`. It holds injection edges and `@DependsOn` edges and is filled in by the time `ContextRefreshedEvent` fires (singletons only). | ✅ (Spring Framework API) |

**Registering the probe.** Use `META-INF/spring.factories` with key `org.springframework.context.ApplicationContextInitializer`. It still works in 4.1.1; Boot's own autoconfigure jar uses it. `SpringBootContextLoader` goes through `SpringApplication`, so the probe applies to `@SpringBootTest` and to slice tests (**UNVERIFIED** by running; IT #1 will prove it).
- Plain `@ContextConfiguration` tests are not covered. They have no auto-configuration anyway, so they don't matter.
- Fallback if needed: `ContextCustomizerFactory` under the `org.springframework.test.context.ContextCustomizerFactory` key. It runs for every Spring test, but becomes part of the context-cache key, so it needs a constant `equals`/`hashCode`.

**Boot 4 modularization** (verified on 4.1.1).
- Technology auto-configs moved out of `spring-boot-autoconfigure` into separate jars: `spring-boot-webmvc`, `spring-boot-data-jpa`, `spring-boot-jdbc`, `spring-boot-hibernate`, `spring-boot-jackson`, … Each jar has its own `.imports` file.
- Packages changed too, e.g. `org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration`.
- `spring-boot-starter-*` are POM aggregators of these modules.
- Consequence: never key anything on auto-config FQCNs or package prefixes. Always attribute by the jar the class came from.

## 4. Verifying the "Known context" claims

| Claim | Result |
|---|---|
| `maven-dependency-plugin` takes a custom analyzer via `analyzer` (role-hint) | ✅ It is a container lookup by hint, so the analyzer jar must be a plugin `<dependency>`. |
| Starters show as "unused declared"; their transitives as "used undeclared" | ✅ MDEP-557 / MDEP-665 / spring-boot#8341, as linked above. |
| Frankel proposed a Spring-aware analyzer, never built | ✅ |
| deploy-manifest-plugin filters starters heuristically, no condition evaluation | ✅ It only flags them; it doesn't filter them out. |

## 5. Proposed design changes

### 5.1 Classification: count consumption, not presence (the important one)

*Problem.* Both evidence types in the current design fire for any starter that is merely on the classpath:
- `RedisAutoConfiguration` matches on `@ConditionalOnClass(RedisOperations.class)`.
- Its `@ConditionalOnMissingBean` methods then create `redisTemplate` and `redisConnectionFactory`, whose classes come from `spring-data-redis` and `lettuce-core`.

So `USED_AUTOCONFIG` and `USED_BEAN` would both fire for the unused starter in IT #1. boot-usage ships exactly this bug.

*Proposal.*
- `USED_BYTECODE`: project classes reference the dependency's own jar *or* its exclusive closure. That is the default analyzer's `usedDeclared` + `usedUndeclared`, grouped back to their owning declared dependency.
- `USED_BEAN`: a bean whose class or `getType()` comes from the exclusive closure is **reachable from a project bean** in the `getDependenciesForBean` graph.
  - A *project bean* is one whose type comes from `target/classes` or `target/test-classes`.
  - For JPA repositories, `getType()` returns the user's interface, so they count as roots.
  - Reachability includes `@DependsOn` edges, which is how Flyway/Liquibase get credited.
- `USED_AUTOCONFIG`: a matched auto-config attributed to the dependency, **and** the dependency is on a built-in *side-effect* list where a match really is the usage. Examples: `spring-boot-starter-actuator`, `-security`, `-thymeleaf`/`-freemarker`/`-mustache`, `-devtools`, `-web-services`. The user can extend the list. Everywhere else, matched auto-configs are recorded as evidence text but don't by themselves make a dependency used.
- `UNUSED_CANDIDATE`: none of the above, and at least one probe report exists. The evidence line names what was found, e.g.:
  - `RedisAutoConfiguration matched; its 3 beans have no project consumers`
  - `FeatureAutoConfiguration did not match: @ConditionalOnProperty (feature.enabled) did not find property 'feature.enabled'` (IT #3)
- `UNKNOWN`: no probe report.
- Precedence: `USED_BYTECODE > USED_BEAN > USED_AUTOCONFIG > UNUSED_CANDIDATE`. The report keeps *all* evidence, not just the winning type.

*Consequence for IT #2.* A JPA app always references `@Entity` and `JpaRepository`, so data-jpa comes out `USED_BYTECODE` through its exclusive closure (spring-data-jpa, jakarta.persistence). I propose the IT asserts "not `UNUSED_CANDIDATE`, and the evidence includes the matched JPA auto-config", not a literal `USED_AUTOCONFIG`. `dependency:analyze` still flags the starter, which is the point of the IT.

### 5.2 Attributing an auto-config to a jar

In Boot 3.x every built-in auto-config lives in `spring-boot-autoconfigure`. That jar is shared by every starter, so by the starter rule it counts for none of them. The probe therefore records, for each auto-config (matched or not):
- the jar of the auto-config class (this alone is enough on Boot 4 and for third-party starters), and
- the jars of the classes named in its `@ConditionalOnClass`, read with `MergedAnnotations` and resolved through the class loader.

The classifier attributes the auto-config to every declared dependency whose own jar or exclusive closure contains any of those jars. This works the same way on 3.x and 4.x.

### 5.3 Smaller changes

- **Probe file name:** `context-<pid>-<n>.json`. Surefire may fork several JVMs, and a per-JVM counter alone would overwrite files.
- **Stale reports:** the Mojo ignores probe files older than the Maven session start time. No deleting, so no fight with parallel forks.
- **Evidence from several contexts is unioned.** The test-context cache gives one report per distinct context. A slice-test context (`@WebMvcTest`) sees only part of the auto-configs.
  - `UNUSED_CANDIDATE` requires at least one *full* auto-config context. Otherwise the verdict is `UNKNOWN` with "only slice-test contexts observed".
  - The probe records whether `@EnableAutoConfiguration` imported the full candidate list. Exact detection is to be settled in Phase 1.
- **Profiles:** the probe records `environment.getActiveProfiles()` in each file, so the report can say which profiles the evidence covers.
- **`-Ddak.enabled=true` reaching the forked test JVM:** Surefire copies Maven user properties into the fork's system properties (**UNVERIFIED** on this version; IT #1 proves it).
- **Probe output directory:** `${dak.outputDirectory}`, default `target/dependency-analyze-kit`, relative to `user.dir`. Surefire's default working directory is `${basedir}`.
- **Probe JSON writing:** hand-written, about 40 lines. The probe lands on the user's test classpath, so it must not pull in Jackson or anything else that could clash.
- **Shared transitives:** shared artifacts are neutral, but a declared dependency's *own* jar always counts for it. Example: `commons-lang3` declared directly and also pulled in transitively by something else is still judged on its own references.
- **Built-in ignore list** (`UNUSED_CANDIDATE` → `IGNORED`, with a reason):
  - JDBC drivers (`h2`, `postgresql`, `mysql-connector-j`, `mariadb-java-client`, `ojdbc*`, `mssql-jdbc`)
  - logging backends/bridges (`logback-classic`, `log4j-slf4j2-impl`, `jul-to-slf4j`, …)
  - `spring-boot-configuration-processor`, `lombok`, `spring-boot-devtools`, `spring-boot-starter-test`

  `IGNORED` is one extra verdict, and it keeps ignored dependencies visible instead of silently dropping them.

## 6. Module layout

```
dependency-analyze-kit/                     parent POM (io.github.sunjirapath, Apache-2.0, Java 17)
├── dependency-analyze-kit-core/            model (ArtifactKey, Evidence, Verdict), Classifier, ConsoleTable/JsonReport writers
│                                           dependency: jackson-jr-objects (read probe JSON + write report.json; ~100 KB, no transitive deps beyond jackson-core)
├── dependency-analyze-kit-spring-probe/    ApplicationContextInitializer + listener, hand-written JSON
│                                           dependency: spring-boot (provided) — compiled against 3.5.x, binary-compatible with 4.x (same APIs)
└── dependency-analyze-kit-maven-plugin/    AnalyzeMojo (@Mojo analyze, defaultPhase VERIFY, requiresDependencyResolution TEST)
                                            deps: maven-plugin-api/annotations/core (provided), maven-dependency-analyzer 1.17.2, core
                                            src/it/{web-redis-unused, jpa-h2, conditional-property, no-probe}
                                            src/it/setup-feature-starter/  (tiny third-party starter with @ConditionalOnProperty auto-config, for IT #3)
```

The probe does **not** depend on core. Only core and maven-plugin share the model; the probe is a plain JSON producer.

What the Mojo does, in order:
1. Inject the default analyzer and call it.
2. Collect closures per declared dependency with the Resolver and compute the exclusive sets.
3. Map jar paths to GAV through `project.getArtifacts()` → `getFile()`.
4. Load the probe JSON files.
5. Classify, write the reports, then fail if `failOnUnused` is set.

## 7. Open questions (need your call)

1. **Accept §5.1 (consumption-based `USED_BEAN`, side-effect list for `USED_AUTOCONFIG`)?** The literal design can't pass IT #1.
2. **IT #2 assertion:** "not `UNUSED_CANDIDATE`, evidence names the JPA auto-config" instead of literal `USED_AUTOCONFIG`. OK?
3. **Boot version for ITs:** all 4 on 3.5.x as specified? Or also run IT #1 on 4.1.x, since 4.x is the current line? It's one extra IT; the probe compiles against 3.5 either way.
4. **Add the `IGNORED` verdict** for the built-in and user ignore lists? The alternative is silently dropping ignored dependencies.
5. **`jackson-jr-objects` in core:** justification is that it reads probe JSON and writes `report.json` without hand-rolling a parser. OK, or hand-roll?

## Known gaps (to repeat in README)

- **No JFR or class-loading tracing.** SPI / `ServiceLoader`, JDBC drivers, logging backends, reflection-only use and template-only use (Thymeleaf views) can show up as false `UNUSED_CANDIDATE`s. The built-in ignore list and the side-effect list cover the common cases.
- **Evidence covers only what the tests exercised:** contexts, profiles and properties. Lazy beans aren't in the dependency graph until they are created. Run once per profile; merging profiles is a non-goal.

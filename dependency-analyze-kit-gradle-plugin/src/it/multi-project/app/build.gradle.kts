// dakVersion is passed by ../verify.py: the plugin and probe built from this checkout, installed in mavenLocal.
buildscript {
    repositories { mavenLocal() }
    dependencies {
        classpath("io.github.sunjirapath:dependency-analyze-kit-gradle-plugin:${property("dakVersion")}")
    }
}

plugins {
    java
}

apply(plugin = "io.github.sunjirapath.dependency-analyze-kit")

extensions.configure<io.github.sunjirapath.dak.gradle.DakExtension>("dak") {
    setFailOnUnused(true)
    ignored = listOf("io.micrometer:micrometer-registry-*")
    sideEffectStarters = listOf("org.springframework.boot:spring-boot-starter-oauth2-client")
}

// Ordinary report checks are advisory; gate scenarios suppress only the known unrelated candidates.
extensions.configure<io.github.sunjirapath.dak.gradle.DakExtension>("dak") {
    setFailOnUnused(providers.gradleProperty("dakGate").isPresent)
    if (providers.gradleProperty("dakGate").isPresent) {
        ignored = ignored + listOf("org.apache.commons:commons-lang3", "org.springframework.boot:spring-boot-starter-json")
    }
}

group = "it"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.8"))
    implementation(project(":lib"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.apache.commons:commons-lang3")
    runtimeOnly("org.springframework.boot:spring-boot-starter-json")
    runtimeOnly("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.h2database:h2")
    constraints {
        runtimeOnly("com.zaxxer:HikariCP:5.1.0")
        implementation("org.apache.commons:commons-lang3:3.17.0")
    }
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.github.sunjirapath:dependency-analyze-kit-spring-probe:${property("dakVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<Test>("test") {
    enabled = !providers.gradleProperty("disableTest").isPresent
    val skip = providers.gradleProperty("skipTest").isPresent
    onlyIf { !skip }
    if (providers.gradleProperty("noSourceTest").isPresent) {
        testClassesDirs = files()
    }
}

if (providers.gradleProperty("extraTest").isPresent) {
    tasks.register<Test>("removedTask") {
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
    }
}

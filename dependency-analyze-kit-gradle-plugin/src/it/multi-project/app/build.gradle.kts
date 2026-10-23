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

group = "it"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.8"))
    implementation(project(":lib"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.apache.commons:commons-lang3")
    runtimeOnly("org.springframework.boot:spring-boot-starter-json")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.github.sunjirapath:dependency-analyze-kit-spring-probe:${property("dakVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

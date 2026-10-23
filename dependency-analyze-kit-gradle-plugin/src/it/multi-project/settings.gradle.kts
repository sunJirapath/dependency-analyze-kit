rootProject.name = "multi-project"
include("lib", "app")

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.detekt) apply false
}

// The library's public API is recorded in passport-reader/api/ and checked on
// every build; see tools/checks.sh. The sample app is an application, with no
// API anyone compiles against.
apiValidation {
    ignoredProjects += "sample-app"
}

// Static analysis over both modules, on detekt's default rules. Issues that
// predate it are recorded per module in detekt-baseline.xml, so the gate fails
// on new ones only; see docs/design-notes.md.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel = true
        baseline = file("detekt-baseline.xml")
        source.setFrom("src/main/java", "src/test/java")
    }
}

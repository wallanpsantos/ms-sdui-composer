plugins {
    id("sdui.kotlin-library")
}

dependencies {
    api(libs.jackson.annotations)
    api(libs.jackson.databind)

    testImplementation(libs.jackson.module.kotlin)
}

tasks.withType<Test>().configureEach {
    val examples = rootProject.layout.projectDirectory
        .dir("docs/examples/screens")
        .asFile
        .absolutePath
    systemProperty("sdui.examplesDir", examples)
}

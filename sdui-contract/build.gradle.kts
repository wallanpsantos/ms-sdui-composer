plugins {
    id("sdui.kotlin-library")
}

dependencies {
    api(libs.jackson.annotations)
    api(libs.jackson.databind)

    testImplementation(libs.jackson.module.kotlin)
}

tasks.withType<Test>().configureEach {
    val artifact = rootProject.layout.projectDirectory
        .file("docs/artifacts/contrato-sdui-home-definitivo.json")
        .asFile
        .absolutePath
    systemProperty("sdui.canonicalArtifact", artifact)
}

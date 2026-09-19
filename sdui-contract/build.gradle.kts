plugins {
    id("sdui.kotlin-library")
}

dependencies {
    api(libs.jackson.annotations)
    api(libs.jackson.databind)

    testImplementation(libs.jackson.module.kotlin)
}

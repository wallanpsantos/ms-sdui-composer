plugins {
    id("sdui.spring-library")
}

dependencies {
    api(project(":sdui-core"))
    implementation(project(":sdui-contract"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test)
}

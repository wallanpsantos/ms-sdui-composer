plugins {
    id("sdui.spring-app")
}

dependencies {
    implementation(project(":sdui-app"))
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.starter.test)
}

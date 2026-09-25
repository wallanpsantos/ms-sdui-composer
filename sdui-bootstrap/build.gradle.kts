plugins {
    id("sdui.spring-app")
}

dependencies {
    implementation(project(":sdui-app"))
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.starter.test)
}

// Nome fixo do jar executável: o Dockerfile (COPY .../sdui-bootstrap.jar) e o release.yml
// dependem dele, e assim não quebram se o projeto passar a declarar `version`.
tasks.bootJar {
    archiveFileName.set("sdui-bootstrap.jar")
}

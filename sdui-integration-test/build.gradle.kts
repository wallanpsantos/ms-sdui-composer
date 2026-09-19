plugins {
    id("sdui.spring-library")
}

dependencies {
    testImplementation(project(":sdui-bootstrap"))
    testImplementation(project(":sdui-app"))
    testImplementation(project(":sdui-core"))
    testImplementation(project(":sdui-contract"))

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit)
}

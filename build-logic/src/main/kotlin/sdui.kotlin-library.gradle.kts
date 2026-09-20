plugins {
    id("sdui.kotlin-base")
}

val verifyPureClasspath = tasks.register<VerifyDependencies>("verifyPureClasspath") {
    forbiddenGroupPrefixes.set(listOf("org.springframework", "org.mongodb", "io.lettuce", "jakarta.servlet"))
    rootComponent.set(configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent })
}

tasks.named("check") { dependsOn(verifyPureClasspath) }

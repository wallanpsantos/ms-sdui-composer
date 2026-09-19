import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.`java-library`
import org.gradle.kotlin.dsl.kotlin
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform(lib("spring-boot-bom")))
    testImplementation(platform(lib("spring-boot-bom")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj-core"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val verifyForbiddenDependencies = tasks.register<VerifyDependencies>("verifyForbiddenDependencies") {
    forbiddenGroupPrefixes.set(
        listOf(
            "io.grpc", "com.google.protobuf", "org.springframework.grpc",
            "com.graphql-java", "org.springframework.graphql",
            "org.mapstruct", "org.apache.kafka",
        ),
    )
    rootComponent.set(configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent })
}

tasks.named("check") { dependsOn(verifyForbiddenDependencies) }

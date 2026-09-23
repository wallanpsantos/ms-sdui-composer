plugins {
    id("sdui.spring-library")
}

dependencies {
    api(project(":sdui-core"))
    implementation(project(":sdui-contract"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.boot.starter.data.redis)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test)
}

val examplesDir: String = rootProject.layout.projectDirectory.dir("docs/examples/screens").asFile.absolutePath

tasks.withType<Test>().configureEach {
    // Os exemplos de docs/examples/screens sao fonte de verdade da documentacao; os testes
    // comparam as respostas do servico com eles.
    systemProperty("sdui.examplesDir", examplesDir)
}

val java25 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

// Medicao in-process dos achados de performance. Sob demanda; nao faz parte de `check`.
tasks.register<JavaExec>("perfHarness") {
    group = "verification"
    description = "Executa o harness de medicao in-process (docs/performance/medicoes-2026-09-23.md)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("br.com.empresa.sdui.perf.PerfHarness")
    javaLauncher.set(java25)
    jvmArgs("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")
    args((providers.gradleProperty("scenarios").orNull ?: "").split(",").filter { it.isNotBlank() })
}

// Carga HTTP contra uma instancia ja no ar. Sob demanda; nao faz parte de `check`.
tasks.register<JavaExec>("loadTest") {
    group = "verification"
    description = "Executa o cenario de carga HTTP load/compose-hit-p99.yaml contra -PbaseUrl."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("br.com.empresa.sdui.load.HttpLoadGenerator")
    javaLauncher.set(java25)
    args(providers.gradleProperty("baseUrl").orElse("http://localhost:8080").get())
}

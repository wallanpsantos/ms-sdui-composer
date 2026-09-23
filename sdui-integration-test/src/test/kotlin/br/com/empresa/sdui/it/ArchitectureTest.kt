package br.com.empresa.sdui.it

import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication

class ArchitectureTest {

    companion object {
        /** Importado uma vez: o JUnit cria uma instancia por metodo, e o import varre o classpath. */
        private val importedClasses: JavaClasses = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("br.com.empresa.sdui")

        /** O que o bytecode Kotlin referencia por conta propria: JDK, stdlib e as anotacoes de nulidade. */
        private val JDK_E_STDLIB = arrayOf("java..", "kotlin..", "org.jetbrains.annotations..")

        private const val HYDRATOR = "br.com.empresa.sdui.orchestrator.hydration.SectionHydrator"
    }

    @Test
    fun `core depende apenas de JDK e stdlib Kotlin`() {
        classes().that().resideInAPackage("..sdui.core..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*JDK_E_STDLIB, "..sdui.core..")
            .check(importedClasses)
    }

    @Test
    fun `orchestrator depende apenas de JDK, stdlib Kotlin e core`() {
        classes().that().resideInAPackage("..sdui.orchestrator..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(*JDK_E_STDLIB, "..sdui.core..", "..sdui.orchestrator..")
            .check(importedClasses)
    }

    @Test
    fun `contract nao depende de core, frameworks nem de outras camadas`() {
        noClasses().that().resideInAPackage("..sdui.contract..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..sdui.core..", "org.springframework..", "jakarta.servlet..",
                "..sdui.adapters..", "..sdui.api..", "..sdui.orchestrator..",
            )
            .check(importedClasses)
    }

    @Test
    fun `api nao depende de adapters`() {
        noClasses().that().resideInAPackage("..sdui.api..")
            .should().dependOnClassesThat().resideInAPackage("..sdui.adapters..")
            .check(importedClasses)
    }

    /**
     * A borda HTTP conversa com o orchestrator so pelas portas: casos de uso, comandos, resultados
     * e excecoes de `port.inbound`, e metricas e excecoes de store de `port.outbound`. Nunca com a
     * implementacao dos servicos, nem com um store direto — isso pularia o caso de uso e a regra
     * de papel que ele aplica.
     */
    @Test
    fun `api acessa o orchestrator somente pelas portas e nunca por um store`() {
        noClasses().that().resideInAPackage("..sdui.api..")
            .should().dependOnClassesThat(
                resideInAPackage("..sdui.orchestrator..").and(not(resideInAPackage("..sdui.orchestrator.port.."))),
            )
            .orShould().dependOnClassesThat(
                resideInAPackage("..sdui.orchestrator.port.outbound..").and(simpleNameEndingWith("Store")),
            )
            .check(importedClasses)
    }

    @Test
    fun `adapters nao dependem de api nem do contrato`() {
        noClasses().that().resideInAPackage("..sdui.adapters..")
            .should().dependOnClassesThat().resideInAnyPackage("..sdui.api..", "..sdui.contract..")
            .check(importedClasses)
    }

    @Test
    fun `pacotes de producao sao livres de ciclos`() {
        slices().matching("br.com.empresa.sdui.(**)")
            .should().beFreeOfCycles()
            .check(importedClasses)
    }

    @Test
    fun `adapters de mongo e redis nao dependem um do outro`() {
        noClasses().that().resideInAPackage("..sdui.adapters.mongo..")
            .should().dependOnClassesThat().resideInAPackage("..sdui.adapters.redis..")
            .check(importedClasses)
        noClasses().that().resideInAPackage("..sdui.adapters.redis..")
            .should().dependOnClassesThat().resideInAPackage("..sdui.adapters.mongo..")
            .check(importedClasses)
    }

    @Test
    fun `SectionHydrator reside em hydration e suas implementacoes em hydration ou adapters`() {
        classes().that().haveSimpleName("SectionHydrator")
            .should().haveFullyQualifiedName(HYDRATOR)
            .check(importedClasses)
        classes().that().implement(HYDRATOR)
            .should().resideInAnyPackage("..sdui.orchestrator.hydration..", "..sdui.adapters..")
            .check(importedClasses)
    }

    @Test
    fun `controllers somente em api`() {
        classes().that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .or().areAnnotatedWith("org.springframework.stereotype.Controller")
            .or().areAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
            .or().areAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice")
            .or().haveSimpleNameEndingWith("Controller")
            .should().resideInAPackage("..sdui.api..")
            .check(importedClasses)
    }

    @Test
    fun `somente bootstrap contem classes anotadas com SpringBootApplication`() {
        classes().that().areAnnotatedWith(SpringBootApplication::class.java)
            .should().resideInAPackage("..sdui.bootstrap..")
            .check(importedClasses)
    }

    /** Cobre `@Transactional` (Spring e Jakarta) e `TransactionTemplate`: a transacao e a porta `TransactionalUnitOfWork`. */
    @Test
    fun `nenhuma classe de producao usa transacao do Spring ou do Jakarta`() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.transaction..", "jakarta.transaction..")
            .check(importedClasses)
    }

    /** `suspend fun` compila com `kotlin.coroutines.Continuation`, da stdlib, sem tocar em `kotlinx.coroutines`. */
    @Test
    fun `nenhuma classe de producao usa coroutines nem suspend fun`() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("kotlinx.coroutines..", "kotlin.coroutines..")
            .check(importedClasses)
    }

    @Test
    fun `nenhuma classe de producao depende de bibliotecas reativas`() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                "reactor.core..",
                "org.springframework.web.reactive..",
                "io.reactivex..",
                "rx..",
            )
            .check(importedClasses)
    }

    @Test
    fun `drivers de persistencia ficam confinados aos adapters`() {
        noClasses().that().resideOutsideOfPackages("..sdui.adapters..")
            .should().dependOnClassesThat().resideInAnyPackage("com.mongodb..", "org.bson..", "io.lettuce..", "org.springframework.data..")
            .check(importedClasses)
    }
}

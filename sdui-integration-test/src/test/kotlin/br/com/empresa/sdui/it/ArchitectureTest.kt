package br.com.empresa.sdui.it

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication

class ArchitectureTest {

    private val importedClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("br.com.empresa.sdui")

    @Test
    fun `core nao depende de frameworks, jackson nem de outras camadas`() {
        noClasses().that().resideInAPackage("..sdui.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "org.mongodb..", "com.mongodb..", "io.lettuce..",
                "jakarta.servlet..", "tools.jackson..", "com.fasterxml.jackson..",
                "..sdui.contract..", "..sdui.orchestrator..", "..sdui.adapters..", "..sdui.api..",
            )
            .check(importedClasses)
    }

    @Test
    fun `orchestrator nao depende de spring, jackson, contrato nem bordas`() {
        noClasses().that().resideInAPackage("..sdui.orchestrator..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.servlet..", "tools.jackson..", "com.fasterxml.jackson..",
                "..sdui.contract..", "..sdui.adapters..", "..sdui.api..",
            )
            .check(importedClasses)
    }

    @Test
    fun `api nao depende de adapters`() {
        noClasses().that().resideInAPackage("..sdui.api..")
            .should().dependOnClassesThat().resideInAPackage("..sdui.adapters..")
            .check(importedClasses)
    }

    @Test
    fun `adapters nao dependem de api nem do contrato`() {
        noClasses().that().resideInAPackage("..sdui.adapters..")
            .should().dependOnClassesThat().resideInAnyPackage("..sdui.api..", "..sdui.contract..")
            .check(importedClasses)
    }

    @Test
    fun `Transactional so existe em MongoTransactionalUnitOfWork`() {
        noMethods().that().areAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .should().beDeclaredInClassesThat().resideOutsideOfPackage("..adapters.mongo.tx..")
            .check(importedClasses)
    }

    @Test
    fun `core e orchestrator nao referenciam ComposeTraceContext nem spring transaction`() {
        noClasses().that().resideInAnyPackage("..sdui.core..", "..sdui.orchestrator..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..sdui.api.trace..",
                "org.springframework.transaction..",
            )
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
    fun `somente bootstrap contem classes anotadas com SpringBootApplication`() {
        classes().that().areAnnotatedWith(SpringBootApplication::class.java)
            .should().resideInAPackage("..sdui.bootstrap..")
            .check(importedClasses)
    }

    @Test
    fun `nenhuma classe de producao depende de coroutines`() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("kotlinx.coroutines..")
            .check(importedClasses)
    }

    @Test
    fun `compose nao declara Transactional`() {
        noMethods().that().areDeclaredInClassesThat().haveSimpleName("ComposeScreenService")
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .check(importedClasses)
        noClasses().that().haveSimpleName("ComposeScreenService")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.transaction..")
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
}

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Task de build que falha quando uma dependencia de grupo proibido aparece no runtimeClasspath.
 *
 * Serve a duas regras, com listas diferentes: em todos os modulos barra bibliotecas que exigiriam
 * ADR para entrar — gRPC, Protobuf, GraphQL, MapStruct, Kafka — e, nos modulos de biblioteca
 * pura (sdui-core e sdui-contract), barra tambem Spring, driver de MongoDB, Lettuce e a API de
 * servlet.
 *
 * Varre o grafo resolvido inteiro, e nao so as dependencias declaradas, porque a violacao
 * normalmente chega por transitividade. Plataformas (BOM) sao ignoradas: aparecem no grafo mas
 * nao levam codigo ao classpath.
 *
 * Complementa o ArchUnit: la se verifica o que o codigo importa, aqui o que ele teria como
 * importar.
 */
abstract class VerifyDependencies : DefaultTask() {

    @get:Input
    abstract val forbiddenGroupPrefixes: ListProperty<String>

    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @TaskAction
    fun verify() {
        val forbidden = forbiddenGroupPrefixes.get()
        val seen = mutableSetOf<ComponentIdentifier>()
        val violations = sortedSetOf<String>()

        fun ResolvedComponentResult.isPlatform(): Boolean = variants.any { variant ->
            val key = variant.attributes.keySet().firstOrNull { it.name == "org.gradle.category" }
            val category = key?.let { variant.attributes.getAttribute(it)?.toString() }
            category == "platform" || category == "enforced-platform"
        }

        fun visit(component: ResolvedComponentResult) {
            if (!seen.add(component.id)) return
            val id = component.id
            if (id is ModuleComponentIdentifier && !component.isPlatform() &&
                forbidden.any { id.group.startsWith(it) }
            ) {
                violations += id.displayName
            }
            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { visit(it.selected) }
        }

        visit(rootComponent.get())
        if (violations.isNotEmpty()) {
            throw GradleException("Dependências proibidas no runtimeClasspath: $violations")
        }
    }
}

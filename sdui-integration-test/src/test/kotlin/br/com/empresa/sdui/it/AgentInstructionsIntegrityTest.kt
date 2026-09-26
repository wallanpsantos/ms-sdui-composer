package br.com.empresa.sdui.it

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Guarda contra deriva das instruções dos agentes (`AGENTS.md`, `.agents/` e adaptadores em `.claude/`).
 *
 * Os papéis tratam o `AGENTS.md` como regra inegociável; uma instrução que aponta caminho ou arquivo inexistente manda o
 * agente procurar o que não existe ou "corrigir" a coisa errada. Este teste falha quando isso acontece, quando uma
 * skill perde o frontmatter exigido pelo formato Agent Skills ou quando um adaptador do Claude Code deixa de apontar
 * para o arquivo canônico. Só lê arquivos; não depende de nenhum módulo de produção.
 */
class AgentInstructionsIntegrityTest {

    @Test
    fun `caminhos citados nas instrucoes existem`() {
        val quebrados = instrucoes().flatMap { arquivo ->
            val texto = semBlocosDeCodigo(ler(arquivo))
            val porCrase = TOKEN.findAll(texto)
                .map { removerSufixoDeLinha(it.groupValues[1]) }
                .filter { ehCaminhoDoRepositorio(it) && it !in INTENCIONALMENTE_AUSENTES }
                .filterNot { Files.exists(raiz.resolve(it)) }
                .map { "${relativo(arquivo)} -> $it" }
            val porLink = if (relativo(arquivo).startsWith(".agents/")) {
                LINK.findAll(texto)
                    .map { it.groupValues[1] }
                    .filterNot { it.startsWith("http") || it.startsWith("#") || it.startsWith("mailto:") }
                    .filterNot { Files.exists(arquivo.parent.resolve(it.substringBefore('#')).normalize()) }
                    .map { "${relativo(arquivo)} -> $it" }
            } else {
                emptySequence()
            }
            (porCrase + porLink).toList()
        }

        assertThat(quebrados)
            .`as`("Instruções dos agentes não podem apontar caminhos inexistentes (arquivo -> citação)")
            .isEmpty()
    }

    @Test
    fun `arquivos kotlin citados existem em src`() {
        val nomesEmSrc = fontesKotlin()
        val ausentes = instrucoes().flatMap { arquivo ->
            TOKEN.findAll(semBlocosDeCodigo(ler(arquivo)))
                .map { removerSufixoDeLinha(it.groupValues[1]) }
                .filter { ARQUIVO_KOTLIN.matches(it) && it !in nomesEmSrc }
                .map { "${relativo(arquivo)} -> $it" }
                .toList()
        }

        assertThat(ausentes)
            .`as`("Todo arquivo .kt citado nas instruções deve existir em algum src/ (nunca em bin/ ou build/)")
            .isEmpty()
    }

    @Test
    fun `skills tem frontmatter valido`() {
        val skills = subdiretorios(raiz.resolve(".agents/skills"))
        assertThat(skills).`as`("O diretório .agents/skills deve existir e ter skills").isNotEmpty()

        val problemas = skills.flatMap { dir ->
            val nome = dir.fileName.toString()
            if (nome == MARCADOR_SEM_SKILL) {
                listOfNotNull("$nome: marcador sem README.md".takeUnless { Files.isRegularFile(dir.resolve("README.md")) })
            } else {
                problemasDeFrontmatter(dir.resolve("SKILL.md"), nome)
            }
        }

        assertThat(problemas)
            .`as`("Cada skill em .agents/skills precisa de SKILL.md com name igual ao diretório e description")
            .isEmpty()
    }

    @Test
    fun `adaptadores apontam para o canonico`() {
        val agentes = raiz.resolve(".claude/agents")
        val skills = raiz.resolve(".claude/skills")
        assumeTrue(Files.isDirectory(agentes) || Files.isDirectory(skills), "Sem adaptadores do Claude Code")

        val problemasDeAgentes = arquivosMarkdown(agentes, recursivo = false).flatMap { adaptador ->
            val nome = adaptador.fileName.toString().removeSuffix(".md")
            problemasDeFrontmatter(adaptador, nome) + problemasDeCanonico(adaptador, ".agents/agents/$nome.md")
        }
        val problemasDeSkills = subdiretorios(skills).flatMap { dir ->
            val nome = dir.fileName.toString()
            val adaptador = dir.resolve("SKILL.md")
            problemasDeFrontmatter(adaptador, nome) + problemasDeCanonico(adaptador, ".agents/skills/$nome/SKILL.md")
        }

        assertThat(problemasDeAgentes + problemasDeSkills)
            .`as`("Adaptadores em .claude/ devem ter name igual ao arquivo e citar um canônico existente em .agents/")
            .isEmpty()
    }

    private fun problemasDeFrontmatter(arquivo: Path, nomeEsperado: String): List<String> {
        if (!Files.isRegularFile(arquivo)) return listOf("${relativo(arquivo)}: arquivo ausente")
        val frontmatter = FRONTMATTER.find(ler(arquivo))?.groupValues?.get(1)
            ?: return listOf("${relativo(arquivo)}: sem frontmatter")
        val campos = frontmatter.lines()
            .filter { ':' in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        return listOfNotNull(
            "${relativo(arquivo)}: name diferente de $nomeEsperado".takeUnless { campos["name"] == nomeEsperado },
            "${relativo(arquivo)}: description vazia".takeIf { campos["description"].isNullOrBlank() },
        )
    }

    private fun problemasDeCanonico(adaptador: Path, canonico: String): List<String> = listOfNotNull(
        "${relativo(adaptador)}: não cita $canonico".takeUnless {
            Files.isRegularFile(adaptador) && canonico in ler(
                adaptador
            )
        },
        "${relativo(adaptador)}: $canonico não existe".takeUnless { Files.isRegularFile(raiz.resolve(canonico)) },
    )

    private fun instrucoes(): List<Path> =
        listOf(raiz.resolve("AGENTS.md")) +
                arquivosMarkdown(raiz.resolve(".agents"), recursivo = true) +
                arquivosMarkdown(raiz.resolve(".claude/agents"), recursivo = false) +
                arquivosMarkdown(raiz.resolve(".claude/skills"), recursivo = true)

    private fun arquivosMarkdown(dir: Path, recursivo: Boolean): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        val caminhos = if (recursivo) Files.walk(dir) else Files.list(dir)
        return caminhos.use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                .sorted()
                .toList()
        }
    }

    private fun subdiretorios(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream -> stream.asSequence().filter { Files.isDirectory(it) }.sorted().toList() }
    }

    private fun fontesKotlin(): Set<String> {
        val raizesDeFonte = Files.list(raiz).use { stream ->
            stream.asSequence()
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("sdui-") }
                .map { it.resolve("src") }
                .toList()
        } + raiz.resolve("build-logic/src")
        return raizesDeFonte.filter { Files.isDirectory(it) }.flatMap { src ->
            Files.walk(src).use { stream ->
                stream.asSequence()
                    .filter { Files.isRegularFile(it) }
                    .map { it.fileName.toString() }
                    .toList()
            }
        }.toSet()
    }

    private fun ehCaminhoDoRepositorio(token: String): Boolean =
        PREFIXO_DO_REPOSITORIO.containsMatchIn(token) && token.none { it in CARACTERES_DE_MODELO } && "XXX" !in token

    private fun removerSufixoDeLinha(token: String): String = SUFIXO_DE_LINHA.replace(token, "")

    private fun semBlocosDeCodigo(texto: String): String = BLOCO_DE_CODIGO.replace(texto, "")

    private fun ler(arquivo: Path): String = Files.readString(arquivo).replace("\r\n", "\n")

    private fun relativo(arquivo: Path): String = raiz.relativize(arquivo).toString().replace('\\', '/')

    private companion object {
        /** Citações que dizem de propósito que o caminho não existe. */
        val INTENCIONALMENTE_AUSENTES = setOf("docs/adr/")

        /** A skill canônica `sdui-backend` não existe; o diretório guarda só um marcador (AGENTS.md §11). */
        const val MARCADOR_SEM_SKILL = "sdui-backend"

        val TOKEN = Regex("`([^`\n]+)`")
        val LINK = Regex("""]\(([^)\s]+)\)""")
        val FRONTMATTER = Regex("""\A---\n(.*?)\n---\n""", RegexOption.DOT_MATCHES_ALL)
        val BLOCO_DE_CODIGO = Regex("""^```.*?^```[ \t]*$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        val SUFIXO_DE_LINHA = Regex(""":\d+(-\d+)?(, ?\d+(-\d+)?)*$""")
        val ARQUIVO_KOTLIN = Regex("""^[A-Z][A-Za-z0-9]*\.kt$""")
        val PREFIXO_DO_REPOSITORIO =
            Regex("""^(docs|\.agents|\.claude|\.github|build-logic|gradle|sdui-(core|contract|app|bootstrap|integration-test))/""")
        val CARACTERES_DE_MODELO = setOf('*', '<', '>', '{', '}', '$', ' ')

        val raiz: Path = localizarRaiz()

        private fun localizarRaiz(): Path {
            var atual: Path? = Path.of("").toAbsolutePath().normalize()
            while (atual != null) {
                if (Files.isRegularFile(atual.resolve("AGENTS.md")) && Files.isRegularFile(atual.resolve("settings.gradle.kts"))) {
                    return atual
                }
                atual = atual.parent
            }
            error(
                "Raiz do repositório (AGENTS.md e settings.gradle.kts) não encontrada a partir de ${
                    Path.of("").toAbsolutePath()
                }"
            )
        }
    }
}

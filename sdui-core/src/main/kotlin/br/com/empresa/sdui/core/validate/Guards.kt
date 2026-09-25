package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.Action
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.validate.ActionGuard.CTA_ACTIONS
import br.com.empresa.sdui.core.validate.PiiGuard.CPF
import br.com.empresa.sdui.core.validate.PiiGuard.LOWER_PII_KEYS
import br.com.empresa.sdui.core.validate.PiiGuard.PAN
import br.com.empresa.sdui.core.validate.VisualGuard.LOWER_VISUAL_KEYS

/**
 * Barreira de proteção arquitetural anti-CSS para propriedades de componentes Server-Driven UI.
 *
 * ### 1. O que faz
 * Inspeciona a árvore de propriedades (`props`) de seções visuais e rejeita qualquer declaração de
 * atributos de estilo visual ou apresentação gráfica (ADR-010).
 *
 * ### 2. Para que serve
 * Assegura o princípio arquitetural inegociável de separação de responsabilidades do SDUI: o backend
 * Server-Driven UI é um controlador de apresentação e aplicação (BFF de UI, Fowler), responsável
 * unicamente por determinar a semântica, o conteúdo de negócio e a hierarquia da tela. Decisões de
 * estilo visual (cores, espaçamentos, margens, raios de borda, sombras, tipografia, dimensões em pixels/pontos)
 * são prerrogativas exclusivas dos Design Systems nativos dos clientes móveis (iOS e Android). Essa
 * barreira previne a criação acidental de uma DSL de layout frágil e inconsistente entre plataformas.
 *
 * ### 3. Como funciona
 * Realiza uma varredura recursiva em profundidade através de [PropWalk.walkKeys]. Compara cada chave
 * encontrada (convertida para minúsculas) contra o conjunto estático pré-calculado [LOWER_VISUAL_KEYS]
 * derivado de [MvpCatalog.VISUAL_KEYS]. Essa pré-computação estática elimina alocações redundantes
 * de coleções e transformações de string durante a validação no hot path.
 */
object VisualGuard {
    /**
     * Conjunto estático pré-calculado de chaves visuais proibidas em caixa baixa.
     */
    private val LOWER_VISUAL_KEYS: Set<String> = MvpCatalog.VISUAL_KEYS.map { it.lowercase() }.toSet()

    /**
     * Identifica violações de atributos visuais em uma estrutura de propriedades.
     *
     * ### 1. O que faz
     * Percorre exaustivamente o grafo de propriedades informado e compila uma lista com todas as
     * ocorrências de chaves visuais proibidas detectadas.
     *
     * ### 2. Para que serve
     * Fornece feedback detalhado aos validadores de especificação (`SpecValidator`), identificando o
     * caminho exato (ex: `header.background` ou `items[0].padding`) de qualquer estilização CSS indevida.
     *
     * ### 3. Como funciona
     * Dispara [PropWalk.walkKeys] sobre o objeto [root]. Para cada chave cuja versão em minúsculas coincidir
     * com algum elemento de [LOWER_VISUAL_KEYS], adiciona à lista de retorno a mensagem contextualizada
     * formatada como `"$path: campo proibido '$key'"`.
     *
     * @param root Objeto raiz ou mapa de propriedades da seção a ser inspecionado.
     * @return Lista contendo as descrições pontuais de cada violação visual encontrada (vazia se estiver em conformidade).
     */
    fun violations(root: Any?): List<String> {
        val found = mutableListOf<String>()
        PropWalk.walkKeys(root) { path, key ->
            if (key.lowercase() in LOWER_VISUAL_KEYS) {
                found += "$path: campo proibido '$key'"
            }
        }
        return found
    }
}

/**
 * Guarda de conformidade e proteção de dados sensíveis e regulados (PII / PCI-DSS).
 *
 * ### 1. O que faz
 * Analisa as propriedades dos componentes visuais bloqueando o tráfego de Informações Pessoalmente
 * Identificáveis (PII) e dados bancários protegidos por regulamentação (ADR-015).
 *
 * ### 2. Para que serve
 * Protege a privacidade dos usuários e cumpre diretrizes rígidas de segurança bancária e proteção
 * de dados (LGPD, GDPR, PCI-DSS). Evita que informações financeiras críticas, senhas, tokens de
 * sessão ou números de documentos transitem desnecessariamente em envelopes de UI, fiquem armazenados
 * em caches públicos/compartilhados (Redis) ou vazem em logs de auditoria e monitoramento de tráfego.
 *
 * ### 3. Como funciona
 * Opera em duas frentes de defesa simultâneas e complementares:
 * 1. **Varredura Estrutural de Chaves:** Inspeciona todas as chaves de mapas via [PropWalk.walkKeys]
 *    comparando-as com o conjunto estático em caixa baixa [LOWER_PII_KEYS] derivado de [MvpCatalog.PII_KEYS]
 *    (bloqueando nomes como `cpf`, `token`, `password`, `accountNumber`, `pan`, `cvv`);
 * 2. **Varredura Semântica de Conteúdo Textual:** Examina os valores das strings via [PropWalk.walkStrings]
 *    contra expressões regulares compiladas ([CPF] e [PAN]), interceptando números de CPF ou dados de cartão
 *    mesmo quando embutidos sob chaves de nomes neutros ou genéricos.
 */
object PiiGuard {
    /**
     * Conjunto estático de chaves reguladas e sensíveis em caixa baixa.
     */
    private val LOWER_PII_KEYS: Set<String> = MvpCatalog.PII_KEYS.map { it.lowercase() }.toSet()

    /**
     * Expressão regular para detecção de números de Cadastro de Pessoas Físicas (CPF).
     * Reconhece tanto o formato padronizado com pontuação (`000.000.000-00`) quanto 11 dígitos contíguos.
     */
    private val CPF = Regex("""\b\d{3}\.\d{3}\.\d{3}-\d{2}\b|\b\d{11}\b""")

    /**
     * Expressão regular para detecção de Número Primário de Conta de Cartão (PAN).
     * Reconhece padrões de 16 dígitos com ou sem agrupamentos por espaços ou hifens.
     */
    private val PAN = Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b|\b\d{16}\b""")

    /**
     * Detecta violações de dados pessoais sensíveis e informações bancárias reguladas.
     *
     * ### 1. O que faz
     * Realiza a auditoria de segurança e privacidade sobre a árvore de dados informada, retornando a
     * relação de violações detectadas tanto por nome de chave quanto por padrão de conteúdo.
     *
     * ### 2. Para que serve
     * Alimenta a validação de especificações (`SpecValidator`) e de esqueletos de tela (`SkeletonValidator`),
     * impedindo que rascunhos de telas com vazamento de dados prossigam para homologação ou produção.
     *
     * ### 3. Como funciona
     * Acumula violações através de duas passagens:
     * - Varre chaves com [PropWalk.walkKeys] sinalizando correspondências em [LOWER_PII_KEYS];
     * - Varre valores textuais com [PropWalk.walkStrings] executando testes de casamento via [Regex.containsMatchIn]
     *   para os padrões de [CPF] e [PAN].
     *
     * @param root Objeto raiz, mapa de propriedades ou nó a ser inspecionado.
     * @return Lista com os apontamentos de inconformidade de PII/PCI (vazia se a estrutura for segura).
     */
    fun violations(root: Any?): List<String> {
        val found = mutableListOf<String>()
        PropWalk.walkKeys(root) { path, key ->
            if (key.lowercase() in LOWER_PII_KEYS) {
                found += "$path: chave regulada '$key'"
            }
        }
        PropWalk.walkStrings(root) { text ->
            if (CPF.containsMatchIn(text)) found += "cpf"
            if (PAN.containsMatchIn(text)) found += "pan"
        }
        return found
    }
}

/**
 * Validador de integridade e conformidade de ações e gatilhos interativos.
 *
 * ### 1. O que faz
 * Valida as declarações de ações ([Action]) contidas em uma seção visual e audita a integridade
 * referencial entre as propriedades da seção (`props`) e a lista de ações declaradas.
 *
 * ### 2. Para que serve
 * Garante a estabilidade da camada de interação do usuário nos clientes móveis nativos:
 * - Restringe as ações aos tipos catalogados permitidos ([MvpCatalog.ALLOWED_ACTIONS]), prevenindo
 *   que o dispatcher nativo receba comandos desconhecidos;
 * - Exige que ações de navegação (`navigate`) apontem estritamente para rotas internas seguras (`app://`),
 *   impedindo o uso de URLs externas arbitrárias que deveriam ser tratadas por navegadores ou fluxos específicos;
 * - Garante que todo Call-to-Action interativo (CTA) possua um rótulo legível ([Action.label]);
 * - Assegura integridade referencial estrita: detecta propriedades que referenciam um identificador de ação
 *   inexistente na seção, o que resultaria em botões inoperantes (toques mortos) no aplicativo nativo.
 *
 * ### 3. Como funciona
 * Mapeia os identificadores declarados em [Action.id] para um conjunto estático de consulta $O(1)$.
 * Valida individualmente cada ação contra as regras de tipo, rota e rotulagem. Em seguida, utiliza
 * [PropWalk.collectActionIds] para extrair os identificadores referenciados nas `props` e checa se todos
 * pertencem ao conjunto de ações válidas declaradas na seção.
 */
object ActionGuard {
    /**
     * Conjunto de tipos de ação que constituem chamadas para ação explícitas (CTAs) e exigem rótulo.
     */
    private val CTA_ACTIONS: Set<String> = setOf("navigate", "open_bottom_sheet")

    /**
     * Valida as regras estruturais das ações e a integridade de suas referências nas propriedades.
     *
     * ### 1. O que faz
     * Examina a conformidade individual de cada ação declarada e certifica que todas as referências
     * feitas nas propriedades da seção apontam para ações existentes.
     *
     * ### 2. Para que serve
     * Rejeita especificações inválidas na governança, protegendo a experiência do usuário móvel contra
     * links quebrados, ações não cadastradas ou botões sem texto.
     *
     * ### 3. Como funciona
     * Constrói o conjunto `ids` com os identificadores presentes em [actions]. Itera sobre cada ação
     * conferindo:
     * 1. Se o tipo pertence a [MvpCatalog.ALLOWED_ACTIONS];
     * 2. Se o tipo for `"navigate"`, se a rota no payload inicia com `"app://"`;
     * 3. Se o tipo estiver em [CTA_ACTIONS], se o campo [Action.label] foi preenchido.
     * Por fim, recupera todos os IDs de ação vinculados em [props] via [PropWalk.collectActionIds] e
     * reporta erro para qualquer ID ausente em `ids`.
     *
     * @param sectionId Identificador da seção visual sob validação.
     * @param actions Coleção de ações declaradas na seção.
     * @param props Mapa de propriedades da seção visual.
     * @return Lista contendo as mensagens de erro de validação (vazia se a seção for válida).
     */
    fun validate(
        sectionId: String,
        actions: List<Action>,
        props: Map<String, Any?>
    ): List<String> {
        val errors = mutableListOf<String>()
        val ids = actions.map { it.id }.toSet()
        for (action in actions) {
            if (action.type !in MvpCatalog.ALLOWED_ACTIONS) {
                errors += "section $sectionId action ${action.id} type ${action.type} fora do catalogo"
            }
            if (action.type == "navigate") {
                val route = action.payload?.route
                if (route.isNullOrBlank() || !route.startsWith("app://")) {
                    errors += "section $sectionId action ${action.id} navigate exige rota app://"
                }
            }
            if (action.type in CTA_ACTIONS && action.label.isNullOrBlank()) {
                errors += "section $sectionId action ${action.id} CTA exige label"
            }
        }
        for (actionId in PropWalk.collectActionIds(props)) {
            if (actionId !in ids) {
                errors += "section $sectionId referencia actionId $actionId inexistente"
            }
        }
        return errors
    }
}

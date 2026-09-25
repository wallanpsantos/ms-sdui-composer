package br.com.empresa.sdui.orchestrator.port.inbound

/**
 * Exceção lançada quando uma operação administrativa é rejeitada por falta de privilégios
 * do ator ou por violação das regras de segregação estrita de funções (*maker-checker*).
 *
 * ### 1. O que faz
 * Representa a recusa explícita de uma ação de governança administrativa por inadequação de papel
 * ou tentativa de auto-aprovação de artefatos.
 *
 * ### 2. Para que serve
 * É mapeada pela borda HTTP administrativa diretamente para o status `HTTP 403 Forbidden`. Garante
 * conformidade com as diretrizes de governança onde quem propõe um rascunho (*maker*) é terminantemente
 * impedido de aprovar o próprio pedido de publicação em canais produtivos (`canary` ou `stable`),
 * além de proteger a leitura da trilha de auditoria contra atores não autorizados.
 *
 * ### 3. Como funciona
 * É instanciada e lançada pelos casos de uso administrativos (`PublishUseCase`, `AuditQueryUseCase`)
 * ao confrontar o `Actor` solicitante com o papel exigido para a operação ou com o criador do pedido
 * sob julgamento.
 */
class AdminDenied(message: String) : RuntimeException(message)

/**
 * Exceção de conflito de concorrência otimista (CAS — *Compare-And-Swap*) no plano administrativo.
 *
 * ### 1. O que faz
 * Sinaliza que o estado do recurso persistido (spec, skeleton, pedido de publicação ou ponteiro)
 * foi alterado concorrentemente entre a leitura inicial e a tentativa de gravação da mutação.
 *
 * ### 2. Para que serve
 * É traduzida pela borda HTTP administrativa para o status `HTTP 409 Conflict`. Informa ao operador
 * ou processo cliente que sua intenção foi baseada em uma versão defasada do artefato, impedindo
 * que duas publicações, edições ou decisões simultâneas sobrescrevam silenciosamente o trabalho alheio.
 *
 * ### 3. Como funciona
 * Lançada pelos casos de uso quando o store subjacente reporta falha em uma operação atômica de
 * comparação e troca (`compareAndSet` ou `compareAndSetStatus`). O cliente deve reler o recurso
 * atualizado e submeter uma nova intenção deliberada.
 */
class AdminConflict(message: String) : RuntimeException(message)

/**
 * Exceção que sinaliza que uma requisição com a mesma chave de idempotência já está em execução.
 *
 * ### 1. O que faz
 * Indica que o identificador fornecido no header `Idempotency-Key` está atualmente retido por uma
 * operação em voo (*in flight*) que ainda não concluiu seu ciclo de execução.
 *
 * ### 2. Para que serve
 * É mapeada na borda HTTP para o status `HTTP 409 Conflict`. Diferencia-se de [AdminConflict] porque
 * não indica que outro operador alterou o artefato, mas sim que a própria chamada do mesmo cliente
 * ainda está sendo processada no servidor, orientando o chamador a aguardar o término em vez de
 * insistir com novos retries que só aumentariam a contenção.
 *
 * ### 3. Como funciona
 * Disparada durante o protocolo de reserva antecipada via `IdempotencyStore.reserve` quando a chave
 * existe na tabela mas ainda possui seu ponteiro de resultado nulo e o tempo limite de execução
 * não foi atingido.
 */
class AdminInFlight(key: String) : RuntimeException("Idempotency-Key $key em voo")

/**
 * Exceção de divergência semântica ou de parâmetros no reenvio de chave de idempotência.
 *
 * ### 1. O que faz
 * Sinaliza que a chave informada no header `Idempotency-Key` já foi utilizada anteriormente com
 * sucesso ou falha definitiva, porém para uma operação distinta ou com parâmetros divergentes.
 *
 * ### 2. Para que serve
 * É traduzida na borda HTTP para o status `HTTP 422 Unprocessable Content`. Garante a integridade
 * transacional da governança, prevenindo que um replay acidental ou malicioso reutilize um token
 * de publicação para aprovar um rascunho diferente do originalmente submetido.
 *
 * ### 3. Como funciona
 * Ao interceptar uma requisição com chave já existente no `IdempotencyStore`, o caso de uso compara
 * o hash resumo (*fingerprint*) do comando atual com o armazenado no registro prévio. Em caso de
 * incompatibilidade, interrompe o fluxo imediatamente sem causar efeito colateral.
 */
class AdminIdempotencyMismatch(key: String) :
    RuntimeException("Idempotency-Key $key ja usada com outra operacao ou outros parametros")

/**
 * Exceção de saturação de capacidade temporária do plano administrativo.
 *
 * ### 1. O que faz
 * Comunica que o subsistema de governança administrativa não dispõe de capacidade segura em memória
 * ou infraestrutura para admitir novas transações no momento.
 *
 * ### 2. Para que serve
 * É convertida pela camada HTTP no status `HTTP 503 Service Unavailable`, acompanhada do header
 * `Retry-After`. Protege a aplicação contra exaustão de memória ou perda de integridade sob alta
 * concorrência de mutações administrativas.
 *
 * ### 3. Como funciona
 * Lançada primariamente quando a tabela de reserva de idempotência atinge o teto máximo de registros
 * ativos e nenhuma entrada expirada pode ser podada com segurança, recusando a admissão de novos
 * comandos até que haja liberação de capacidade.
 */
class AdminUnavailable(message: String) : RuntimeException(message)

/**
 * Exceção agregadora de inconsistências e violações estruturais em rascunhos administrativos.
 *
 * ### 1. O que faz
 * Reúne todas as violações estruturais, sintáticas e contratuais detectadas pelas rotinas de validação
 * de specs, skeletons e catálogos em um único payload de erro.
 *
 * ### 2. Para que serve
 * É traduzida na borda HTTP para o status `HTTP 400 Bad Request`. Permite que o operador receba uma
 * lista completa de correções pendentes no payload submetido, evitando o ciclo ineficiente de
 * correção de um erro por vez.
 *
 * ### 3. Como funciona
 * É instanciada recebendo uma lista de mensagens de erro acumuladas durante as fases de validação de
 * domínio (`SpecValidator`, `SkeletonValidator`) e formata sua mensagem concatenando-as por ponto e vírgula.
 */
class AdminValidation(
    /**
     * Lista com todas as mensagens de erro de validação detectadas no artefato.
     *
     * ### 1. O que faz
     * Armazena os apontamentos detalhados de cada violação de regra estrutural ou de negócio.
     *
     * ### 2. Para que serve
     * Fornece o conteúdo pedagógico necessário para compor o corpo da resposta de erro `HTTP 400`.
     *
     * ### 3. Como funciona
     * Preenchida pelo orquestrador a partir dos resultados retornados pelos validadores puros do core.
     */
    val errors: List<String>,
) : RuntimeException(errors.joinToString("; "))

/**
 * Exceção indicativa de recurso de governança não encontrado nos repositórios.
 *
 * ### 1. O que faz
 * Sinaliza que a entidade solicitada (spec, skeleton, pedido de publicação, ponteiro ou surface)
 * não existe no armazenamento correspondente.
 *
 * ### 2. Para que serve
 * É mapeada na borda HTTP para o status `HTTP 404 Not Found`, informando de maneira inequívoca
 * a inexistência do artefato referenciado.
 *
 * ### 3. Como funciona
 * Lançada pelos casos de uso administrativos quando uma consulta necessária para a continuidade
 * de uma ação de governança (ex.: localizar o spec referenciado em um comando de publicação)
 * retorna nula.
 */
class AdminNotFound(message: String) : RuntimeException(message)

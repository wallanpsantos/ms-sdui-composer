package br.com.empresa.sdui.orchestrator.port.inbound

/*
 * Os desfechos recusados dos casos de uso administrativos. Fazem parte do contrato da porta de
 * entrada, e nao da implementacao: a borda HTTP traduz cada um a um status sem conhecer os
 * servicos que os lancam.
 */

/** Papel insuficiente ou regra de segregacao violada. Vira 403. */
class AdminDenied(message: String) : RuntimeException(message)

/** Estado mudou sob os pes da operacao, como dois approves simultaneos. Vira 409. */
class AdminConflict(message: String) : RuntimeException(message)

/**
 * A chave de idempotencia esta reservada por uma chamada que ainda nao terminou. Vira 409.
 *
 * Separada de [AdminConflict] de proposito: as duas viram 409, mas dizem coisas diferentes ao
 * operador. Conflito significa que outro ator mudou o pedido; esta significa que a propria
 * requisicao dele ainda esta correndo e reenviar agora nao ajuda.
 */
class AdminInFlight(key: String) : RuntimeException("Idempotency-Key $key em voo")

/**
 * A chave de idempotencia ja foi usada para outra operacao ou outros parametros. Vira 422.
 *
 * Replay so faz sentido para a mesma operacao sobre o mesmo alvo; devolver o resultado antigo
 * para um pedido diferente afirmaria um efeito que nao aconteceu.
 */
class AdminIdempotencyMismatch(key: String) :
    RuntimeException("Idempotency-Key $key ja usada com outra operacao ou outros parametros")

/**
 * O plano administrativo recusou a operacao por falta de capacidade segura. Vira 503 com
 * Retry-After. Hoje: o registro de idempotencia em memoria esta no teto so com registros vivos.
 */
class AdminUnavailable(message: String) : RuntimeException(message)

/** Conteudo recusado pelos validadores. Carrega todos os erros de uma vez. Vira 400. */
class AdminValidation(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

/** Spec, skeleton, pedido, pointer ou surface inexistente. Vira 404. */
class AdminNotFound(message: String) : RuntimeException(message)

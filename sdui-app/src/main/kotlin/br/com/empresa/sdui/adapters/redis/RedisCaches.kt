package br.com.empresa.sdui.adapters.redis

import br.com.empresa.sdui.adapters.json.DomainJson
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.StoredScreen
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Codificação e decodificação versionada de valores armazenados no Redis (`P08`, `ADR-021`).
 *
 * ### 1. O que faz
 * Serializa e desserializa entidades de domínio em envelopes JSON versionados para persistência no Redis.
 *
 * ### 2. Para que serve
 * Padronizar a representação binária dos caches distribuídos, permitindo evolução segura de schema
 * sem que versões incompatíveis gerem erros ou corrupção de dados.
 *
 * ### 3. Como funciona
 * Encapsula árvores e specs em [TreeEntry] e [SpecEntry] contendo o número da versão [FORMAT_VERSION].
 * Na leitura, se a versão do envelope divergir da versão corrente, o registro é ignorado e tratado como
 * miss de cache. Nenhum dado sensível ou de usuário transita por aqui (`ADR-008`).
 */
object RedisCacheCodec {
    /** Versão atual do formato de envelope dos dados gravados no Redis. */
    const val FORMAT_VERSION: Int = 1

    /**
     * Envelope versionado para armazenamento de árvores de UI.
     *
     * ### 1. O que faz
     * Empacota a tela [screen] com o número de versão [v] do formato.
     *
     * ### 2. Para que serve
     * Permitir detecção de compatibilidade de formato na recuperação do cache.
     *
     * ### 3. Como funciona
     * Data class imutável serializada diretamente pelo Jackson.
     */
    data class TreeEntry(val v: Int, val screen: ComposedScreen)

    /**
     * Envelope versionado para armazenamento de especificações de tela.
     *
     * ### 1. O que faz
     * Empacota a especificação [spec] com o número de versão [v] do formato.
     *
     * ### 2. Para que serve
     * Assegurar que specs em cache respeitem a versão contratada.
     *
     * ### 3. Como funciona
     * Data class imutável serializada diretamente pelo Jackson.
     */
    data class SpecEntry(val v: Int, val spec: Spec)

    /**
     * Serializa uma árvore de tela para array de bytes versionado.
     *
     * ### 1. O que faz
     * Converte [screen] para um envelope [TreeEntry] serializado em JSON UTF-8.
     *
     * ### 2. Para que serve
     * Gerar a carga binária pronta para gravação em chaves de valor do Redis.
     *
     * ### 3. Como funciona
     * Cria [TreeEntry] com [FORMAT_VERSION] e aciona [DomainJson.writeBytes].
     */
    fun encodeScreen(screen: ComposedScreen): ByteArray = DomainJson.writeBytes(TreeEntry(FORMAT_VERSION, screen))

    /**
     * Desserializa uma árvore de tela a partir de array de bytes versionado.
     *
     * ### 1. O que faz
     * Converte bytes do Redis de volta para [ComposedScreen].
     *
     * ### 2. Para que serve
     * Recuperar a tela cacheada durante acertos de cache no pipeline de composição.
     *
     * ### 3. Como funciona
     * Lê [TreeEntry] via [DomainJson.read] e retorna a tela se [TreeEntry.v] for igual a [FORMAT_VERSION].
     */
    fun decodeScreen(bytes: ByteArray): ComposedScreen? =
        DomainJson.read(bytes, TreeEntry::class.java).takeIf { it.v == FORMAT_VERSION }?.screen

    /**
     * Serializa uma especificação para array de bytes versionado.
     *
     * ### 1. O que faz
     * Converte [spec] para um envelope [SpecEntry] serializado em JSON UTF-8.
     *
     * ### 2. Para que serve
     * Gerar a representação binária da spec para o cache distribuído de specs.
     *
     * ### 3. Como funciona
     * Cria [SpecEntry] com [FORMAT_VERSION] e aciona [DomainJson.writeBytes].
     */
    fun encodeSpec(spec: Spec): ByteArray = DomainJson.writeBytes(SpecEntry(FORMAT_VERSION, spec))

    /**
     * Desserializa uma especificação a partir de array de bytes versionado.
     *
     * ### 1. O que faz
     * Converte bytes do Redis de volta para [Spec].
     *
     * ### 2. Para que serve
     * Recuperar a especificação imutável sem onerar o banco de dados principal.
     *
     * ### 3. Como funciona
     * Lê [SpecEntry] via [DomainJson.read] e retorna a spec se [SpecEntry.v] for igual a [FORMAT_VERSION].
     */
    fun decodeSpec(bytes: ByteArray): Spec? =
        DomainJson.read(bytes, SpecEntry::class.java).takeIf { it.v == FORMAT_VERSION }?.spec
}

/**
 * Cronômetro e instrumentador de operações de cache no Redis.
 *
 * ### 1. O que faz
 * Mede latências e contabiliza desfechos e descartes em operações sobre o Redis.
 *
 * ### 2. Para que serve
 * Fornecer observabilidade detalhada com baixo overhead e controle estrito de cardinalidade.
 *
 * ### 3. Como funciona
 * Mede o tempo em nanossegundos via `System.nanoTime()` e despacha para [metrics] com tags finitas.
 */
internal class CacheOperationTimer(private val metrics: MetricsRecorder) {
    /**
     * Executa um bloco de código mensurando a duração e o resultado da operação.
     *
     * ### 1. O que faz
     * Envolve a execução de [block] medindo tempo e registrando a métrica [MetricNames.CACHE_OPERATION].
     *
     * ### 2. Para que serve
     * Registrar duração de `get`, `put` e `invalidate` com tag de desfecho `ok` ou `error`.
     *
     * ### 3. Como funciona
     * Executa o bloco sob `try-finally`, definindo `outcome = "ok"` em caso de sucesso e emitindo métrica.
     */
    fun <T> time(cache: String, operation: String, block: () -> T): T {
        val started = System.nanoTime()
        var outcome = "error"
        try {
            val value = block()
            outcome = "ok"
            return value
        } finally {
            metrics.recordNanos(
                MetricNames.CACHE_OPERATION,
                System.nanoTime() - started,
                mapOf("cache" to cache, "operation" to operation, "outcome" to outcome),
            )
        }
    }

    /**
     * Registra o descarte de uma tentativa de gravação no cache.
     *
     * ### 1. O que faz
     * Incrementa o contador [MetricNames.CACHE_WRITE_SKIPPED] para o cache especificado.
     *
     * ### 2. Para que serve
     * Alertar sobre payloads que excederam o teto de bytes ou entradas rejeitadas por limite de capacidade.
     *
     * ### 3. Como funciona
     * Aciona `metrics.increment` passando a tag identificadora do cache.
     */
    fun skipped(cache: String) {
        metrics.increment(MetricNames.CACHE_WRITE_SKIPPED, mapOf("cache" to cache))
    }
}

/**
 * Cache distribuído de árvores hidratadas no Redis (`ADR-021`, `ADR-022`).
 *
 * ### 1. O que faz
 * Implementa a interface [HydratedScreenCache] armazenando telas prontas com controle de índice por escopo.
 *
 * ### 2. Para que serve
 * Responder rapidamente requisições repetidas sem refazer a composição, garantindo isolamento entre plataformas.
 *
 * ### 3. Como funciona
 * Utiliza chaves livres de dados de usuário (`!RedisKeys.containsUserId(treeKey)`). Para cada escopo,
 * mantém um ZSET onde o score é o timestamp de expiração. O script Lua [PUT_SCRIPT] descarta membros
 * vencidos e impõe teto de capacidade [maxEntries]. A invalidação via [INVALIDATE_SCRIPT] expurga atomicamente
 * todas as chaves referenciadas pelo índice sem sobrecarregar a memória da aplicação.
 */
class RedisHydratedScreenCache(
    private val redis: RedisTemplate<String, ByteArray>,
    private val maxEntryBytes: Int,
    metrics: MetricsRecorder,
    private val maxEntries: Int = 10_000,
) : HydratedScreenCache {
    private val timer = CacheOperationTimer(metrics)

    init {
        require(maxEntries > 0)
    }

    /**
     * Recupera uma árvore de tela pré-composta do Redis.
     *
     * ### 1. O que faz
     * Busca o array de bytes pela chave [treeKey] e decodifica a tela correspondente.
     *
     * ### 2. Para que serve
     * Atender requisições com cache hit imediato no hot path.
     *
     * ### 3. Como funciona
     * Valida que a chave não contém identificador de usuário, busca via `opsForValue().get` e decodifica.
     */
    override fun get(treeKey: String): ComposedScreen? {
        check(!RedisKeys.containsUserId(treeKey))
        val bytes = timer.time(CACHE, "get") { redis.opsForValue().get(treeKey) } ?: return null
        return decodeOrNull(bytes)
    }

    /**
     * Armazena uma árvore pré-composta no Redis associando índice e TTL.
     *
     * ### 1. O que faz
     * Grava a [screen] no Redis associando-a ao índice ZSET do escopo correspondente.
     *
     * ### 2. Para que serve
     * Disponibilizar a resposta fresca para próximas requisições com os mesmos parâmetros.
     *
     * ### 3. Como funciona
     * Valida a ausência de identificador de usuário na chave e o teto [maxEntryBytes]. Executa [PUT_SCRIPT]
     * no Redis, que remove membros vencidos do ZSET, confere o teto [maxEntries], grava a chave e atualiza o índice.
     */
    override fun put(treeKey: String, screen: ComposedScreen, ttl: Duration) {
        check(!RedisKeys.containsUserId(treeKey))
        require(ttl.toMillis() > 0)
        val bytes = RedisCacheCodec.encodeScreen(screen)
        if (bytes.size > maxEntryBytes) {
            timer.skipped(CACHE)
            return
        }
        val admitted = timer.time(CACHE, "put") {
            redis.execute(
                PUT_SCRIPT,
                listOf(treeKey, indexKey(screen.surface, screen.platform, screen.channel)),
                bytes,
                ttl.toMillis().toString().toByteArray(),
                maxEntries.toString().toByteArray(),
            )
        }
        if (admitted != 1L) timer.skipped(CACHE)
    }

    /**
     * Invalida todas as árvores em cache para a combinação de superfície, plataforma e canal.
     *
     * ### 1. O que faz
     * Remove todas as chaves de tela associadas ao escopo e limpa o índice correspondente no Redis.
     *
     * ### 2. Para que serve
     * Evitar a entrega de telas desatualizadas após publicação de nova spec ou rollback.
     *
     * ### 3. Como funciona
     * Executa o script Lua [INVALIDATE_SCRIPT] que recupera todas as chaves do ZSET e as remove atomicamente.
     */
    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel) {
        timer.time(CACHE, "invalidate") {
            redis.execute(INVALIDATE_SCRIPT, listOf(indexKey(surface, platform, channel)))
        }
    }

    private fun indexKey(surface: String, platform: ClientPlatform, channel: Channel): String =
        "sdui:treeidx:v2:$surface:${platform.wire()}:${channel.wire()}"

    private companion object {
        const val CACHE: String = "tree"

        /** ARGV: payload, ttl em ms, teto. TIME evita divergencia de relogio entre instancias. */
        val PUT_SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local ttl = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            if not redis.call('ZSCORE', KEYS[2], KEYS[1]) and redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('ZADD', KEYS[2], now + ttl, KEYS[1])
            local last = redis.call('ZREVRANGE', KEYS[2], 0, 0, 'WITHSCORES')
            redis.call('PEXPIRE', KEYS[2], math.ceil(tonumber(last[2]) - now + ttl))
            return 1
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        /** O indice tem teto: nenhuma lista de chaves e transferida para o heap da aplicacao. */
        val INVALIDATE_SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            """
            local keys = redis.call('ZRANGE', KEYS[1], 0, -1)
            for _, key in ipairs(keys) do redis.call('DEL', key) end
            redis.call('DEL', KEYS[1])
            return #keys
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}

/**
 * Cache distribuído de especificações de telas no Redis (`ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [SpecCache] armazenando especificações publicadas imutáveis.
 *
 * ### 2. Para que serve
 * Reduzir a carga de leitura no MongoDB durante a resolução de targeting e composição.
 *
 * ### 3. Como funciona
 * Chaveia por identificador de revisão e plataforma via [RedisKeys.spec]. Grava o payload com TTL fixo
 * caso não ultrapasse [maxEntryBytes]. Como specs publicadas são imutáveis, entradas antigas apenas expiram.
 */
class RedisSpecCache(
    private val redis: RedisTemplate<String, ByteArray>,
    private val ttl: Duration,
    private val maxEntryBytes: Int,
    metrics: MetricsRecorder,
) : SpecCache {
    private val timer = CacheOperationTimer(metrics)

    /**
     * Busca uma especificação publicada no cache do Redis.
     *
     * ### 1. O que faz
     * Recupera e decodifica a spec a partir da chave de revisão e plataforma.
     *
     * ### 2. Para que serve
     * Atender leituras de specs no hot path de composição sem bater no MongoDB.
     *
     * ### 3. Como funciona
     * Consulta `opsForValue().get` chaveado por [RedisKeys.spec] e decodifica via [RedisCacheCodec.decodeSpec].
     */
    override fun get(specRevisionId: String, platform: ClientPlatform): Spec? {
        val bytes = timer.time(CACHE, "get") { redis.opsForValue().get(RedisKeys.spec(specRevisionId, platform)) }
            ?: return null
        return runCatching { RedisCacheCodec.decodeSpec(bytes) }.getOrNull()
    }

    /**
     * Armazena uma especificação publicada no Redis com TTL fixo.
     *
     * ### 1. O que faz
     * Salva o payload binário da spec no Redis se respeitar o teto de tamanho.
     *
     * ### 2. Para que serve
     * Aquecer o cache de specs para consultas subsequentes.
     *
     * ### 3. Como funciona
     * Codifica a spec em bytes; se exceder [maxEntryBytes], descarta e emite métrica; senão, grava com TTL.
     */
    override fun put(spec: Spec) {
        val bytes = RedisCacheCodec.encodeSpec(spec)
        if (bytes.size > maxEntryBytes) {
            timer.skipped(CACHE)
            return
        }
        timer.time(CACHE, "put") {
            redis.opsForValue().set(RedisKeys.spec(spec.specRevisionId, spec.platform), bytes, ttl)
        }
    }

    /**
     * Remove explicitamente uma especificação do cache do Redis.
     *
     * ### 1. O que faz
     * Exclui a chave correspondente à revisão e plataforma informadas.
     *
     * ### 2. Para que serve
     * Limpar entradas de specs pontualmente quando solicitado pela governança.
     *
     * ### 3. Como funciona
     * Executa `redis.delete` sobre a chave gerada por [RedisKeys.spec].
     */
    override fun invalidate(specRevisionId: String, platform: ClientPlatform) {
        timer.time(CACHE, "invalidate") { redis.delete(RedisKeys.spec(specRevisionId, platform)) }
    }

    private companion object {
        const val CACHE: String = "spec"
    }
}

/**
 * Armazenamento de contingência (Last Good) no Redis (`P10`, `ADR-007`, `ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [LastGoodScreenStore] persistindo a última árvore válida conhecida versionada pelo ponteiro.
 *
 * ### 2. Para que serve
 * Fornecer o degrau final de degradação graciosa (200 OK com `fallback: true`) quando o orquestrador não consegue
 * compor uma nova árvore e os caches primários falham, prevenindo retorno de HTTP 503 desnecessário.
 *
 * ### 3. Como funciona
 * Grava um Hash Redis por escopo contendo campos de versão (`v`), payload (`p`) e instante (`t`).
 * Scripts Lua garantem atomicidade estrita: escritas de versões inferiores à gravada são rejeitadas
 * ([PUT_SCRIPT]), e invalidações instalam lápides (tombstones) na versão mais nova ([TOMBSTONE_SCRIPT]),
 * impedindo que composições atrasadas ou relays reapliquem árvores antigas que já saíram de vigência.
 */
class RedisLastGoodScreenStore(
    private val redis: RedisTemplate<String, ByteArray>,
    private val clock: Clock,
    private val ttl: Duration,
    private val maxEntryBytes: Int,
    metrics: MetricsRecorder,
) : LastGoodScreenStore {
    private val timer = CacheOperationTimer(metrics)

    /**
     * Recupera a última árvore de tela de contingência para a superfície, plataforma e canal.
     *
     * ### 1. O que faz
     * Lê os campos do Hash Redis e reconstrói o objeto [StoredScreen].
     *
     * ### 2. Para que serve
     * Atender à contingência de fallback quando o hot path falha ou estoura timeout.
     *
     * ### 3. Como funciona
     * Busca os campos `p` (payload) e `t` (storedAt) no Hash da chave [RedisKeys.lastGood],
     * decodificando a tela e construindo [StoredScreen] com o timestamp de gravação.
     */
    override fun get(surface: String, platform: ClientPlatform, channel: Channel): StoredScreen? {
        val key = RedisKeys.lastGood(surface, platform, channel)
        val fields = timer.time(CACHE, "get") {
            redis.opsForHash<String, ByteArray>().multiGet(key, listOf(FIELD_PAYLOAD, FIELD_STORED_AT))
        }
        val payload = fields.getOrNull(0) ?: return null
        val storedAt = fields.getOrNull(1)?.let { String(it, Charsets.UTF_8).toLongOrNull() } ?: return null
        val screen = decodeOrNull(payload) ?: return null
        return StoredScreen(screen, Instant.ofEpochMilli(storedAt))
    }

    /**
     * Grava uma árvore de contingência no Redis se a versão do ponteiro for maior ou igual à atual.
     *
     * ### 1. O que faz
     * Salva a [screen] no Hash de contingência utilizando script Lua de controle de versão.
     *
     * ### 2. Para que serve
     * Manter atualizada a resposta de emergência com a última versão válida aprovada.
     *
     * ### 3. Como funciona
     * Codifica a tela e aciona [PUT_SCRIPT], que confere se a versão gravada no Hash não é superior
     * à versão da tela que está sendo persistida, garantindo monotonicidade estrita.
     */
    override fun put(screen: ComposedScreen) {
        val bytes = RedisCacheCodec.encodeScreen(screen)
        if (bytes.size > maxEntryBytes) {
            timer.skipped(CACHE)
            return
        }
        val key = RedisKeys.lastGood(screen.surface, screen.platform, screen.channel)
        timer.time(CACHE, "put") {
            redis.execute(
                PUT_SCRIPT,
                listOf(key),
                screen.pointerVersion.toString().toByteArray(),
                bytes,
                clock.instant().toEpochMilli().toString().toByteArray(),
                ttl.toMillis().toString().toByteArray(),
            )
        }
    }

    /**
     * Invalida a árvore de contingência gravando uma lápide na versão do ponteiro fornecida.
     *
     * ### 1. O que faz
     * Remove o payload atual e marca a versão com uma lápide atômica via script Lua.
     *
     * ### 2. Para que serve
     * Assegurar que uma versão retirada de circulação por rollback ou atualização não seja servida como fallback.
     *
     * ### 3. Como funciona
     * Executa [TOMBSTONE_SCRIPT] que apaga o payload e grava a nova versão no campo `v`, impedindo
     * gravações atrasadas de versões anteriores à informada por [pointerVersion].
     */
    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel, pointerVersion: Long) {
        val key = RedisKeys.lastGood(surface, platform, channel)
        timer.time(CACHE, "invalidate") {
            redis.execute(
                TOMBSTONE_SCRIPT,
                listOf(key),
                pointerVersion.toString().toByteArray(),
                ttl.toMillis().toString().toByteArray(),
            )
        }
    }

    private companion object {
        const val CACHE: String = "last_good"
        const val FIELD_PAYLOAD: String = "p"
        const val FIELD_STORED_AT: String = "t"

        /** Grava se a versao guardada nao for maior. ARGV: versao, arvore, instante, ttl em ms. */
        val PUT_SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            """
            local current = redis.call('HGET', KEYS[1], 'v')
            if current and tonumber(current) > tonumber(ARGV[1]) then return 0 end
            redis.call('HSET', KEYS[1], 'v', ARGV[1], 'p', ARGV[2], 't', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        /** Lapide na versao nova, salvo se ja houver arvore dessa versao ou maior. ARGV: versao, ttl. */
        val TOMBSTONE_SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            """
            local current = redis.call('HGET', KEYS[1], 'v')
            if current and tonumber(current) >= tonumber(ARGV[1]) then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'v', ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}

/**
 * Decodifica um array de bytes em [ComposedScreen] ou retorna nulo em caso de erro.
 *
 * ### 1. O que faz
 * Tenta desserializar o array de bytes utilizando [RedisCacheCodec.decodeScreen].
 *
 * ### 2. Para que serve
 * Tratar graciosamente entradas corrompidas ou em versões legadas como ausência de cache.
 *
 * ### 3. Como funciona
 * Executa a decodificação envolvida por `runCatching { }` e invoca `getOrNull()`.
 */
private fun decodeOrNull(bytes: ByteArray): ComposedScreen? =
    runCatching { RedisCacheCodec.decodeScreen(bytes) }.getOrNull()


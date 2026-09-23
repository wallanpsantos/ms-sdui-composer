package br.com.empresa.sdui.perf

import br.com.empresa.sdui.adapters.memory.InMemoryAuditLogStore
import br.com.empresa.sdui.adapters.memory.InMemoryCatalogStore
import br.com.empresa.sdui.adapters.memory.InMemoryComposeSingleflight
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.adapters.memory.InMemoryIdempotencyStore
import br.com.empresa.sdui.adapters.memory.InMemoryLastGoodScreenStore
import br.com.empresa.sdui.adapters.memory.InMemoryPointerStore
import br.com.empresa.sdui.adapters.memory.InMemorySkeletonStore
import br.com.empresa.sdui.adapters.memory.InMemorySpecCache
import br.com.empresa.sdui.adapters.memory.InMemorySpecStore
import br.com.empresa.sdui.adapters.observability.MicrometerMetricsRecorder
import br.com.empresa.sdui.adapters.observability.NoOpMetricsRecorder
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.api.mapping.ScreenResponseMapper
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.limit.Bulkhead
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.orchestrator.admin.DraftService
import br.com.empresa.sdui.orchestrator.compose.ComposeBudgets
import br.com.empresa.sdui.orchestrator.compose.ComposeScreenService
import br.com.empresa.sdui.orchestrator.compose.DefaultCanaryPolicy
import br.com.empresa.sdui.orchestrator.hydration.HydrationContext
import br.com.empresa.sdui.orchestrator.hydration.HydrationCoordinator
import br.com.empresa.sdui.orchestrator.hydration.HydrationResult
import br.com.empresa.sdui.orchestrator.hydration.SectionHydrator
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeRequest
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult
import br.com.empresa.sdui.orchestrator.port.inbound.DraftCatalogCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftSpecCommand
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyReservation
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.PageRequest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/*
 * Harness de medicao in-process dos achados de docs/analise-performance-2026-09-23.md.
 *
 * Nao e teste: e um `main`, executado sob demanda com `gradlew :sdui-app:perfHarness` (ou com o
 * classpath de teste direto no `java`). Cada cenario corresponde a um achado; os resultados de
 * antes e depois estao em docs/performance/medicoes-2026-09-23.md. Tempo em ns/op (mediana de 5
 * rodadas depois de aquecimento) e bytes alocados pela thread chamadora via ThreadMXBean.
 *
 * Nao substitui carga HTTP em ambiente dedicado: nao mede rede, servlet, GC sob concorrencia real
 * nem o SLO de P99. Serve para comparar o mesmo mecanismo antes e depois de uma mudanca.
 */

private val SEED: String = checkNotNull(PerfHarness::class.java.getResourceAsStream("/seed/contrato-sdui-home-definitivo.json"))
    .use { it.readBytes().decodeToString() }
private val MAPPER: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
private val TMX = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

object PerfHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        val all = linkedMapOf(
            "m1" to ::m1Cardinality, "m2" to ::m2Idempotency, "m3" to ::m3Selection, "m4" to ::m4Singleflight,
            "m5" to ::m5FanOut, "m6" to ::m6Prune, "m7" to ::m7Listings, "m8" to ::m8Mapping, "audit" to ::mAudit,
        )
        println(
            "JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")} | " +
                "CPUs=${Runtime.getRuntime().availableProcessors()} | maxHeap=${Runtime.getRuntime().maxMemory() / MIB}MiB",
        )
        val selected = if (args.isEmpty()) all.keys else args.toList()
        for (key in selected) all.getValue(key)()
    }
}

private const val MIB: Long = 1024 * 1024

private class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

/** Conta hidratacoes; com atraso, simula uma fonte com espera (performsIo = true). */
private class CountingHydrator(
    private val delayMs: Long = 0,
    private val started: CountDownLatch? = null,
) : SectionHydrator {
    val calls = AtomicInteger()
    override fun supports(type: String, typeVersion: Int) = true
    override fun hydrate(context: HydrationContext, section: Section): HydrationResult {
        calls.incrementAndGet()
        started?.countDown()
        if (delayMs > 0) Thread.sleep(delayMs)
        return HydrationResult.Ok(section.props)
    }

    override val performsIo: Boolean get() = delayMs > 0
}

private class CountingExecutor(private val delegate: Executor) : Executor {
    val tasks = AtomicLong()
    override fun execute(command: Runnable) {
        tasks.incrementAndGet()
        delegate.execute(command)
    }
}

private class Harness(
    metrics: MetricsRecorder = NoOpMetricsRecorder,
    treeCache: HydratedScreenCache = InMemoryHydratedScreenCache(),
    hydrators: List<SectionHydrator> = emptyList(),
    rateCapacity: Long = 1_000_000_000,
    val executor: CountingExecutor = CountingExecutor(HydrationCoordinator.virtualThreadExecutor()),
    hydrationTimeoutMs: Long = 80,
) {
    val clock = MutableClock(Instant.parse("2026-09-23T12:00:00Z"))
    val specStore = InMemorySpecStore()
    val skeletonStore = InMemorySkeletonStore()
    val catalogStore = InMemoryCatalogStore()
    val pointerStore = InMemoryPointerStore()
    val service = ComposeScreenService(
        specStore = specStore,
        skeletonStore = skeletonStore,
        pointerStore = pointerStore,
        specCache = InMemorySpecCache(),
        treeCache = treeCache,
        lastGood = InMemoryLastGoodScreenStore(clock),
        singleflight = InMemoryComposeSingleflight(),
        hydrator = HydrationCoordinator(
            hydrators = hydrators,
            fanOut = Semaphore(8),
            timeout = Duration.ofMillis(hydrationTimeoutMs),
            metrics = metrics,
            executor = executor,
        ),
        matrix = CapabilityMatrix(),
        canaryPolicy = DefaultCanaryPolicy,
        rateLimiter = TokenBucketRateLimiter(capacity = rateCapacity, refillPerSecond = rateCapacity, maxKeys = 1_000_000),
        readBulkhead = Bulkhead(1024),
        metrics = metrics,
        clock = clock,
        budgets = ComposeBudgets(request = Duration.ofSeconds(30), singleflightWait = Duration.ofSeconds(10)),
    )

    init {
        HomeSeed(catalogStore, skeletonStore, specStore, pointerStore, MAPPER).seedFromCanonicalFixture(SEED)
    }

    fun compose(
        version: String = "8.14.2",
        build: String = "81420",
        schema: String = "3",
        ifNoneMatch: String? = null,
    ): ComposeResult = service.compose(
        ComposeRequest(
            headers = NegotiateHeaders(schema, "ios", version, build, "pt-BR", "1", "18.1", null),
            ifNoneMatch = ifNoneMatch,
            identity = "ios:$build",
        ),
    )
}

private data class Stat(val nsPerOp: Double, val bytesPerOp: Double)

private fun measure(iterations: Int, runs: Int = 5, warmup: Int = iterations, op: () -> Unit): List<Stat> {
    repeat(warmup) { op() }
    val tid = Thread.currentThread().threadId()
    return (1..runs).map {
        val a0 = TMX.getThreadAllocatedBytes(tid)
        val t0 = System.nanoTime()
        repeat(iterations) { op() }
        val t1 = System.nanoTime()
        val a1 = TMX.getThreadAllocatedBytes(tid)
        Stat((t1 - t0).toDouble() / iterations, (a1 - a0).toDouble() / iterations)
    }
}

private fun fmt(stats: List<Stat>): String {
    val ns = stats.map { it.nsPerOp }.sorted()
    val by = stats.map { it.bytesPerOp }.sorted()
    return "ns/op mediana=%.0f (min=%.0f max=%.0f) | B/op mediana=%.0f".format(
        ns[ns.size / 2], ns.first(), ns.last(), by[by.size / 2],
    )
}

private fun m1Cardinality() {
    println("## M1 cardinalidade de metricas")
    val registry = SimpleMeterRegistry()
    val h = Harness(metrics = MicrometerMetricsRecorder(registry))
    h.compose()
    val base = registry.meters.size
    repeat(1000) { i -> h.compose(version = "8.${10 + i % 10}.${i / 10}") }
    println("hit com 1000 Client-Version distintas: meters $base -> ${registry.meters.size}")
    val beforeSchema = registry.meters.size
    repeat(100) { i -> h.compose(schema = "${4 + i}") }
    println("100 UI-Schema-Version distintos (sem candidato): meters $beforeSchema -> ${registry.meters.size}")
    val rlRegistry = SimpleMeterRegistry()
    val rl = Harness(metrics = MicrometerMetricsRecorder(rlRegistry), rateCapacity = 0)
    repeat(1000) { i -> rl.compose(version = "8.${10 + i % 10}.${i / 10}") }
    println(
        "rate limited com 1000 Client-Version distintas: meters=${rlRegistry.meters.size} " +
            "(${rlRegistry.meters.count { it.id.name == "compose.rate_limited" }} de compose.rate_limited)",
    )
    val drafts = DraftService(h.specStore, h.skeletonStore, h.catalogStore, CapabilityMatrix())
    var inactiveAccepted = 0
    repeat(100) { i ->
        runCatching {
            drafts.upsertComponent(
                DraftCatalogCommand(
                    Actor("m", ActorRole.MAKER),
                    ComponentType("tipo_arbitrario_$i", 1, "DEPRECATED", "3", emptyList()),
                ),
            )
        }.onSuccess { inactiveAccepted++ }
    }
    println("upsert de 100 componentes inativos de nome arbitrario aceitos: $inactiveAccepted (tag admin.catalog.upsert{type})")
    val current = checkNotNull(h.specStore.findByRevisionId("rev_01K8HOMEMAIN"))
    var surfacesAccepted = 0
    repeat(100) { i ->
        runCatching {
            drafts.createSpecDraft(
                DraftSpecCommand(
                    Actor("m", ActorRole.MAKER),
                    current.copy(
                        specId = "s_$i",
                        revision = 1,
                        specRevisionId = "r_$i",
                        status = SpecStatus.DRAFT,
                        surface = "surface_$i",
                    ),
                ),
            )
        }.onSuccess { surfacesAccepted++ }
    }
    println("rascunhos com 100 surfaces arbitrarias aceitos: $surfacesAccepted (tag admin.spec.draft{surface})")
}

private fun m2Idempotency() {
    println("## M2 idempotencia sob saturacao")
    val clock = MutableClock(Instant.parse("2026-09-23T12:00:00Z"))
    val store = InMemoryIdempotencyStore(clock, ttl = Duration.ofHours(1), maxEntries = 4)
    val first = store.reserve("em-voo", "publish.open", "fp")
    repeat(3) {
        val reserved = store.reserve("fechada-$it", "publish.open", "fp-$it") as IdempotencyReservation.Reserved
        store.complete(IdempotencyRecord("fechada-$it", "publish.open", "pr_$it", "fp-$it"), reserved.token)
    }
    val newcomer = store.reserve("nova", "publish.open", "fp")
    val preserved = store.find("em-voo") != null
    val retake = store.reserve("em-voo", "publish.open", "fp")
    val lostResults = (0 until 3).count { store.find("fechada-$it") == null }
    println(
        "reserva inicial=${first.javaClass.simpleName}, nova admissao no teto=${newcomer.javaClass.simpleName}, " +
            "reserva em voo preservada=$preserved, retry da chave=${retake.javaClass.simpleName}, " +
            "resultados dentro do TTL expulsos=$lostResults/3",
    )
    clock.now = clock.now.plus(Duration.ofHours(2))
    val afterExpiry = store.reserve("nova", "publish.open", "fp")
    println("depois do TTL e do prazo de reserva: nova admissao=${afterExpiry.javaClass.simpleName} (Reserved esperado)")
    check(afterExpiry is IdempotencyReservation.Reserved)
}

private fun addRevisions(h: Harness, n: Int, publishedRatio: Double) {
    val current = checkNotNull(h.specStore.findByRevisionId("rev_01K8HOMEMAIN"))
    val publishedEvery = if (publishedRatio <= 0) Int.MAX_VALUE else (1.0 / publishedRatio).toInt().coerceAtLeast(1)
    repeat(n) { i ->
        h.specStore.save(
            current.copy(
                specId = "spec_extra_$i",
                revision = 1,
                specRevisionId = "rev_extra_$i",
                status = if (i % publishedEvery == 0) SpecStatus.PUBLISHED else SpecStatus.DRAFT,
                targeting = current.targeting.copy(appVersion = VersionRange(SemVer(1, 0, 0), SemVer(1, 0, 1))),
            ),
        )
    }
}

private fun m3Selection() {
    println("## M3 custo de selecao em hit e 304 x revisoes residentes")
    for (n in listOf(0, 1_000, 10_000)) {
        for (ratio in listOf(0.1, 1.0)) {
            if (n == 0 && ratio < 1.0) continue
            val h = Harness()
            addRevisions(h, n, ratio)
            val first = h.compose() as ComposeResult.Success
            val etag = first.screen.etag
            val iters = if (n >= 10_000) 2_000 else 20_000
            val hit = measure(iters) { check(h.compose() is ComposeResult.Success) }
            val nm = measure(iters) { check(h.compose(ifNoneMatch = etag) is ComposeResult.NotModified) }
            println("revisoes extras=$n publicadas=${(ratio * 100).toInt()}%: hit ${fmt(hit)}")
            println("revisoes extras=$n publicadas=${(ratio * 100).toInt()}%: 304 ${fmt(nm)}")
        }
    }
}

/** Cache que pausa a thread marcada logo depois de ela ler um miss. */
private class GatedCache(private val delegate: InMemoryHydratedScreenCache) : HydratedScreenCache by delegate {
    @Volatile
    var gated: Thread? = null
    val missObserved = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun get(treeKey: String): ComposedScreen? {
        val value = delegate.get(treeKey)
        if (value == null && Thread.currentThread() === gated && missObserved.count > 0) {
            missObserved.countDown()
            release.await()
        }
        return value
    }
}

private fun m4Singleflight() {
    println("## M4 singleflight: recomposicao apos lider concluido e campos do requisitante no waiter")
    val hydrator = CountingHydrator()
    val cache = GatedCache(InMemoryHydratedScreenCache())
    val h = Harness(treeCache = cache, hydrators = listOf(hydrator))
    val a = Thread.ofVirtual().unstarted { h.compose() }
    cache.gated = a
    a.start()
    cache.missObserved.await()
    h.compose()
    val afterB = hydrator.calls.get()
    cache.release.countDown()
    a.join()
    println("interleaving A(miss, pausa) -> B compoe e grava -> A retoma: composicoes=${hydrator.calls.get() / 8} (B=${afterB / 8})")

    val burstHydrator = CountingHydrator(delayMs = 2)
    val burstCache = InMemoryHydratedScreenCache()
    val burst = Harness(treeCache = burstCache, hydrators = listOf(burstHydrator))
    val pool = Executors.newVirtualThreadPerTaskExecutor()
    val perRound = mutableListOf<Int>()
    repeat(50) {
        burstCache.clear()
        val before = burstHydrator.calls.get()
        val start = CountDownLatch(1)
        val tasks = (1..64).map { pool.submit { start.await(); burst.compose() } }
        start.countDown()
        tasks.forEach { it.get() }
        perRound += (burstHydrator.calls.get() - before) / 8
    }
    pool.close()
    println("rajada 64 concorrentes x 50 rodadas: composicoes/rodada media=%.2f max=%d".format(perRound.average(), perRound.max()))

    val started = CountDownLatch(1)
    val slow = Harness(hydrators = listOf(CountingHydrator(delayMs = 100, started = started)), hydrationTimeoutMs = 5_000)
    val vpool = Executors.newVirtualThreadPerTaskExecutor()
    val leader = vpool.submit<ComposeResult> { slow.compose(build = "81420") }
    started.await()
    val waiter = vpool.submit<ComposeResult> { slow.compose(build = "99999", version = "8.15.0") }
    val w = waiter.get() as ComposeResult.Success
    leader.get()
    vpool.close()
    println(
        "waiter com build 99999 / versao 8.15.0 recebeu client.build=${w.screen.client.build} " +
            "appVersion=${w.screen.client.appVersion} (fromCache=${w.fromCache})",
    )
}

private class NoStoreCache : HydratedScreenCache {
    override fun get(treeKey: String): ComposedScreen? = null
    override fun put(treeKey: String, screen: ComposedScreen, ttl: Duration) = Unit
    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel) = Unit
}

private fun m5FanOut() {
    println("## M5 hidratacao pass-through (miss completo)")
    val h = Harness(treeCache = NoStoreCache())
    val iters = 20_000
    val before = h.executor.tasks.get()
    val stats = measure(iters) { check(h.compose() is ComposeResult.Success) }
    val tasks = h.executor.tasks.get() - before
    println("miss ${fmt(stats)} | tarefas virtuais por composicao=%.1f".format(tasks.toDouble() / (iters * 6)))
    println("obs: B/op mede so a thread chamadora; alocacao das virtual threads nao entra")
}

private fun m6Prune() {
    println("## M6 poda do cache de arvore no teto de 10.000")
    val sample = (Harness().compose() as ComposeResult.Success).screen
    val cache = InMemoryHydratedScreenCache(10_000)
    val writers = 8
    val durationMs = 2_000L
    val maxResident = AtomicInteger()
    val maxOccupied = AtomicInteger()
    val puts = AtomicLong()
    val slowPuts = AtomicLong()
    val maxPutNs = AtomicLong()
    val latencies = LongArray(writers * 400_000)
    val idx = AtomicInteger()
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(writers)
    val deadline = AtomicLong()
    val futures = (0 until writers).map { w ->
        pool.submit {
            start.await()
            var i = 0L
            while (System.nanoTime() < deadline.get()) {
                val t0 = System.nanoTime()
                cache.put("sdui:tree:home:ios:3:rev_$w-$i:caps:stable", sample, Duration.ofSeconds(60))
                val dt = System.nanoTime() - t0
                val slot = idx.getAndIncrement()
                if (slot < latencies.size) latencies[slot] = dt
                if (dt > 1_000_000) slowPuts.incrementAndGet()
                maxPutNs.accumulateAndGet(dt) { x, y -> maxOf(x, y) }
                puts.incrementAndGet()
                maxResident.accumulateAndGet(cache.residentEntries()) { x, y -> maxOf(x, y) }
                maxOccupied.accumulateAndGet(cache.occupiedSlots()) { x, y -> maxOf(x, y) }
                i++
            }
        }
    }
    deadline.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs))
    start.countDown()
    futures.forEach { it.get() }
    pool.shutdown()
    val n = minOf(idx.get(), latencies.size)
    val sorted = latencies.copyOf(n).also { it.sort() }
    println(
        "8 escritores por 2s: puts=${puts.get()} p50=%dns p99=%dns p999=%dns max=%.2fms puts>1ms=${slowPuts.get()} residente max (size, estimativa)=${maxResident.get()} vagas max=${maxOccupied.get()} escritas descartadas=${cache.skippedWrites()}".format(
            sorted[n / 2], sorted[(n * 0.99).toInt()], sorted[(n * 0.999).toInt()], maxPutNs.get() / 1e6,
        ),
    )
    val hot = InMemoryHydratedScreenCache(10_000)
    repeat(5_000) { hot.put("sdui:tree:home:ios:3:hot_$it:c:stable", sample, Duration.ofSeconds(120)) }
    repeat(6_000) { hot.put("sdui:tree:home:ios:3:cold_$it:c:stable", sample, Duration.ofSeconds(60)) }
    val hits = (0 until 5_000).count { hot.get("sdui:tree:home:ios:3:hot_$it:c:stable") != null }
    println("conjunto quente de 5.000 (TTL maior) apos 6.000 frias: hit ratio=%.1f%% residente=${hot.residentEntries()}".format(hits / 50.0))
}

private fun m7Listings() {
    println("## M7 listagens administrativas x historico")
    for (n in listOf(1_000, 10_000)) {
        System.gc()
        Thread.sleep(200)
        val h = Harness()
        addRevisions(h, n, 0.1)
        var bytes = 0
        val stats = measure(if (n >= 10_000) 20 else 50, runs = 3) {
            bytes = MAPPER.writeValueAsBytes(h.specStore.list(null, null, PageRequest.FIRST)).size
        }
        println("revisoes=$n: GET /specs primeira pagina (limit=${PageRequest.DEFAULT_LIMIT}) ${fmt(stats)} corpo=${bytes / 1024}KiB")
    }
}

private fun m8Mapping() {
    println("## M8 mapping vs serializacao no hit")
    val h = Harness()
    val screen = (h.compose() as ComposeResult.Success).screen
    val mapper = ScreenResponseMapper(MAPPER)
    val body = mapper.toResponse(screen)
    val map = measure(50_000) { mapper.toResponse(screen) }
    val ser = measure(50_000) { MAPPER.writeValueAsBytes(body) }
    val hit = measure(50_000) { h.compose() }
    println("compose hit (servico) ${fmt(hit)}")
    println("mapper.toResponse ${fmt(map)}")
    println("writeValueAsBytes ${fmt(ser)} payload=${MAPPER.writeValueAsBytes(body).size}B")
}

private fun mAudit() {
    println("## Menor: append de auditoria no teto de 2.000")
    val store = InMemoryAuditLogStore(2_000)
    val event = AuditEvent(
        "e", Instant.EPOCH, "a", ActorRole.CHECKER, "publish.approve", "home",
        ClientPlatform.IOS, Channel.STABLE, null, null, null, null,
    )
    repeat(2_000) { store.append(event) }
    println("append no teto ${fmt(measure(200_000) { store.append(event) })}")
}

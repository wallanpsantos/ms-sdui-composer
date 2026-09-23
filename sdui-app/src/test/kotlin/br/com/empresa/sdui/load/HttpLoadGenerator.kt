package br.com.empresa.sdui.load

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Gerador de carga HTTP do cenario `load/compose-hit-p99.yaml` (achado 8 de 2026-09-23).
 *
 * Carga fechada: [CONCURRENCY] workers em virtual threads, cada um envia a proxima requisicao ao
 * receber a anterior, por [WARMUP] de aquecimento (descartado) e [DURATION] de medicao. Mede a
 * latencia vista pelo cliente HTTP (sem rede movel nem renderizacao) e le do
 * `/actuator/prometheus` os contadores `compose_hit_total` e `compose_miss_total` antes e depois,
 * para o hit ratio do lado do servidor.
 *
 * Nao e teste: roda sob demanda (`gradlew :sdui-app:loadTest -PbaseUrl=...`) contra uma instancia
 * ja no ar. Os numeros so valem para o ambiente em que foram medidos; o registro fica em
 * docs/performance/medicoes-2026-09-23.md.
 */
object HttpLoadGenerator {
    private const val CONCURRENCY_DEFAULT: Int = 32
    private const val WARMUP_DEFAULT: Long = 10
    private const val DURATION_DEFAULT: Long = 60
    private const val NANOS_PER_MILLI: Double = 1_000_000.0

    private val HEADERS: Map<String, String> = linkedMapOf(
        "UI-Schema-Version" to "3",
        "Client-Platform" to "ios",
        "Client-Version" to "8.14.2",
        "Client-Build" to "81420",
        "Accept-Language" to "pt-BR",
        "API-Version" to "1",
        "OS-Version" to "18.1",
    )

    private val CONCURRENCY: Int = System.getProperty("load.concurrency")?.toIntOrNull() ?: CONCURRENCY_DEFAULT
    private val WARMUP: Duration = Duration.ofSeconds(System.getProperty("load.warmupSeconds")?.toLongOrNull() ?: WARMUP_DEFAULT)
    private val DURATION: Duration = Duration.ofSeconds(System.getProperty("load.durationSeconds")?.toLongOrNull() ?: DURATION_DEFAULT)

    @JvmStatic
    fun main(args: Array<String>) {
        val baseUrl = (args.firstOrNull() ?: System.getProperty("load.baseUrl") ?: "http://localhost:8080").trimEnd('/')
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/surfaces/home"))
            .timeout(Duration.ofSeconds(5))
            .apply { HEADERS.forEach { (name, value) -> header(name, value) } }
            .GET()
            .build()

        println("aquecimento: ${WARMUP.seconds}s, concorrencia=$CONCURRENCY")
        run(client, request, WARMUP, record = null)
        val before = scrape(client, baseUrl)
        val sample = Sample()
        println("medicao: ${DURATION.seconds}s")
        run(client, request, DURATION, record = sample)
        val after = scrape(client, baseUrl)

        val hits = (after["compose_hit_total"] ?: 0.0) - (before["compose_hit_total"] ?: 0.0)
        val misses = (after["compose_miss_total"] ?: 0.0) - (before["compose_miss_total"] ?: 0.0)
        val latencies = sample.sortedMillis()
        fun pct(p: Double) = if (latencies.isEmpty()) 0.0 else latencies[((latencies.size - 1) * p).toInt()]
        println("requisicoes=${latencies.size} throughput=%.0f req/s".format(latencies.size / DURATION.seconds.toDouble()))
        println("status=${sample.statuses}")
        println("latencia cliente ms: p50=%.2f p95=%.2f p99=%.2f max=%.2f".format(pct(0.50), pct(0.95), pct(0.99), latencies.lastOrNull() ?: 0.0))
        if (hits + misses > 0) println("hit ratio servidor=%.3f (hits=%.0f misses=%.0f)".format(hits / (hits + misses), hits, misses))
    }

    private class Sample {
        val nanos = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val statuses = ConcurrentHashMap<Int, LongAdder>()

        fun sortedMillis(): List<Double> = nanos.map { it / NANOS_PER_MILLI }.sorted()
    }

    private fun run(client: HttpClient, request: HttpRequest, duration: Duration, record: Sample?) {
        val deadline = System.nanoTime() + duration.toNanos()
        val errors = AtomicLong()
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            repeat(CONCURRENCY) {
                pool.submit {
                    while (System.nanoTime() < deadline) {
                        val started = System.nanoTime()
                        try {
                            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                            if (record != null) {
                                record.nanos += System.nanoTime() - started
                                record.statuses.computeIfAbsent(response.statusCode()) { LongAdder() }.increment()
                            }
                        } catch (_: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }
            }
        }
        if (errors.get() > 0) println("erros de transporte: ${errors.get()}")
    }

    /** Contadores `nome{...} valor` do Prometheus, somados por nome. */
    private fun scrape(client: HttpClient, baseUrl: String): Map<String, Double> {
        val body = client.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/actuator/prometheus")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        return body.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val name = line.substringBefore('{').substringBefore(' ')
                val value = line.substringAfterLast(' ').toDoubleOrNull() ?: return@mapNotNull null
                name to value
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.sum() }
    }
}

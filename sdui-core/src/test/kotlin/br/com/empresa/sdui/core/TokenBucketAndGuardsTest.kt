package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.limit.RateLimitKey
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.validate.CatalogValidator
import br.com.empresa.sdui.core.validate.PiiGuard
import br.com.empresa.sdui.core.validate.VisualGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TokenBucketAndGuardsTest {
    @Test
    fun `token bucket limita por identidade e plataforma sem olhar payload`() {
        val limiter = TokenBucketRateLimiter(capacity = 2, refillPerSecond = 0)
        val key = RateLimitKey("81420", ClientPlatform.IOS)
        assertThat(limiter.tryConsume(key)).isTrue()
        assertThat(limiter.tryConsume(key)).isTrue()
        assertThat(limiter.tryConsume(key)).isFalse()
        assertThat(limiter.tryConsume(RateLimitKey("81420", ClientPlatform.ANDROID))).isTrue()
    }

    @Test
    fun `identidades novas nao acumulam buckets indefinidamente`() {
        var now = 0L
        val limiter = TokenBucketRateLimiter(
            capacity = 1,
            refillPerSecond = 1,
            maxKeys = 4,
            idleEvictionMs = 1_000,
            clockMs = { now },
        )
        repeat(4) { limiter.tryConsume(RateLimitKey("build-$it", ClientPlatform.IOS)) }
        assertThat(limiter.residentKeys()).isEqualTo(4)

        now = 5_000
        limiter.tryConsume(RateLimitKey("build-novo", ClientPlatform.IOS))
        assertThat(limiter.residentKeys()).isEqualTo(1)
    }

    @Test
    fun `poda nao devolve credito a quem esta consumindo`() {
        var now = 0L
        val limiter = TokenBucketRateLimiter(
            capacity = 2,
            refillPerSecond = 0,
            maxKeys = 1,
            idleEvictionMs = 10_000,
            clockMs = { now },
        )
        val key = RateLimitKey("81420", ClientPlatform.IOS)
        assertThat(limiter.tryConsume(key)).isTrue()
        assertThat(limiter.tryConsume(key)).isTrue()
        now = 1_000
        // o mapa esta no teto, entao a poda roda a cada chamada: o bucket vazio e nao ocioso fica.
        assertThat(limiter.tryConsume(key)).isFalse()
    }

    @Test
    fun `guards recusam visual PII e catalogo generico`() {
        assertThat(VisualGuard.violations(mapOf("color" to "#fff"))).isNotEmpty()
        assertThat(PiiGuard.violations(mapOf("cpf" to "123.456.789-00"))).isNotEmpty()
        val bad = Catalog(listOf(ComponentType("row", 1, "ACTIVE", "3", emptyList())))
        assertThat(CatalogValidator.validate(bad)).isNotEmpty()
        val good = Catalog(
            MvpCatalog.TYPES.map { ComponentType(it.type, it.typeVersion, "ACTIVE", "3", emptyList()) },
        )
        assertThat(CatalogValidator.validate(good)).isEmpty()
    }
}

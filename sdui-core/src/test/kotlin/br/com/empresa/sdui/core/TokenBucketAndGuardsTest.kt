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

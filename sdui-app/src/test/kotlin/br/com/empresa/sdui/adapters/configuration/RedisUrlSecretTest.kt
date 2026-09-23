package br.com.empresa.sdui.adapters.configuration

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A falha de subida por URL malformada vai para o log: a mensagem nao pode repetir a senha. */
class RedisUrlSecretTest {

    @Test
    fun `url malformada falha a subida sem ecoar a senha`() {
        val properties = SduiProperties(
            persistence = PersistenceProperties(
                cache = CacheMode.REDIS,
                redis = SduiRedisProperties(url = "redis://sdui:s3gr3do com espaco@cache:6379/0"),
            ),
        )

        assertThatThrownBy { RedisCacheConfiguration().sduiRedisConnectionFactory(properties) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sdui.persistence.redis.url")
            .hasMessageNotContaining("s3gr3do")
            .hasNoCause()
    }
}

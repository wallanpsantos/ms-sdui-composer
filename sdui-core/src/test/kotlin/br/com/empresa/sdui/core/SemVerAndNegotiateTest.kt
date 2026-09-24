package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ContextValidation
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.core.negotiate.Negotiate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SemVerAndNegotiateTest {
    @Test
    fun `comparacao de semver e ordinal e nao lexicografica`() {
        val a = SemVer.parse("8.10.0")!!
        val b = SemVer.parse("8.9.99")!!
        assertThat(a.ordinal).isGreaterThan(b.ordinal)
        assertThat(a).isGreaterThan(b)
        assertThat(VersionRange(SemVer(8, 10, 0), SemVer(8, 19, 99)).contains(a)).isTrue()
        assertThat(VersionRange(SemVer(8, 10, 0), SemVer(8, 19, 99)).contains(b)).isFalse()
        assertThat(VersionRange(SemVer(8, 20, 0), null).contains(SemVer(9, 0, 0))).isTrue()
    }

    @Test
    fun `parse aceita major e major minor completando com zero e recusa o resto`() {
        assertThat(SemVer.parse("17")).isEqualTo(SemVer(17, 0, 0))
        assertThat(SemVer.parse("17.4")).isEqualTo(SemVer(17, 4, 0))
        assertThat(SemVer.parse(" 17.4 ")).isEqualTo(SemVer(17, 4, 0))
        assertThat(SemVer.parse("17.4.1")).isEqualTo(SemVer(17, 4, 1))
        assertThat(SemVer.parse("17.")).isNull()
        assertThat(SemVer.parse(".4")).isNull()
        assertThat(SemVer.parse("17.4.1.2")).isNull()
        assertThat(SemVer.parse("17.x")).isNull()
        assertThat(SemVer.parse("")).isNull()
        assertThat(SemVer.parse(null)).isNull()
    }

    @Test
    fun `negotiate rejeita plataforma semver build e locale invalidos`() {
        val base = NegotiateHeaders(
            uiSchemaVersion = "3",
            clientPlatform = "ios",
            clientVersion = "8.14.2",
            clientBuild = "81420",
            acceptLanguage = "pt-BR",
            apiVersion = "1",
            osVersion = "18.1",
            componentCapabilities = "top_bar@1",
        )
        assertThat(Negotiate.negotiate(base)).isInstanceOf(ContextValidation.Valid::class.java)
        assertThat(Negotiate.negotiate(base.copy(clientPlatform = "web"))).isInstanceOf(ContextValidation.Invalid::class.java)
        assertThat(Negotiate.negotiate(base.copy(clientVersion = "8"))).isInstanceOf(ContextValidation.Invalid::class.java)
        assertThat(Negotiate.negotiate(base.copy(clientVersion = "x.y.z"))).isInstanceOf(ContextValidation.Invalid::class.java)
        assertThat(Negotiate.negotiate(base.copy(clientBuild = "abc"))).isInstanceOf(ContextValidation.Invalid::class.java)
        assertThat(Negotiate.negotiate(base.copy(acceptLanguage = null))).isInstanceOf(ContextValidation.Invalid::class.java)
        assertThat(Negotiate.negotiate(base.copy(uiSchemaVersion = null))).isInstanceOf(ContextValidation.Invalid::class.java)
    }

    @Test
    fun `semver com componente maior que Int MAX_VALUE retorna null e negotiate retorna Invalid`() {
        assertThat(SemVer.parse("99999999999.0.0")).isNull()
        assertThat(SemVer.parse("1.99999999999.0")).isNull()
        assertThat(SemVer.parse("1.0.99999999999")).isNull()
        assertThat(SemVer.parseThreePart("99999999999.0.0")).isNull()
        assertThat(SemVer.parse("99999999999")).isNull()
        assertThat(SemVer.parse("99999999999.0")).isNull()

        val headers = NegotiateHeaders(
            uiSchemaVersion = "3",
            clientPlatform = "ios",
            clientVersion = "99999999999.0.0",
            clientBuild = "81420",
            acceptLanguage = "pt-BR",
            apiVersion = "1",
            osVersion = "18.1",
            componentCapabilities = "top_bar@1",
        )
        assertThat(Negotiate.negotiate(headers)).isInstanceOf(ContextValidation.Invalid::class.java)
    }

    @Test
    fun `somente schema canonico suportado chega ao contexto e cache`() {
        val base = NegotiateHeaders("3", "ios", "8.14.2", "81420", "pt-BR", "1", "18.1", null)
        for (schema in listOf("2", "4", "03", "2147483648", "9999999999")) {
            assertThat(Negotiate.negotiate(base.copy(uiSchemaVersion = schema)))
                .isInstanceOf(ContextValidation.Invalid::class.java)
        }
        val valid = Negotiate.negotiate(base) as ContextValidation.Valid
        assertThat(valid.context.parsedSchemaVersion).isEqualTo(SemVer(3, 0, 0))
        assertThat(valid.context.copy(schemaVersion = "4").parsedSchemaVersion).isEqualTo(SemVer(4, 0, 0))
    }

    @Test
    fun `canal do cliente cai para stable e canal da governanca desconhecido e null`() {
        assertThat(Channel.parse(" Canary ")).isEqualTo(Channel.CANARY)
        assertThat(Channel.parse("qualquer")).isEqualTo(Channel.STABLE)
        assertThat(Channel.parse(null)).isEqualTo(Channel.STABLE)

        assertThat(Channel.parseOrNull(" INTERNAL ")).isEqualTo(Channel.INTERNAL)
        assertThat(Channel.parseOrNull("stable")).isEqualTo(Channel.STABLE)
        assertThat(Channel.parseOrNull("canry")).isNull()
        assertThat(Channel.parseOrNull("")).isNull()
        assertThat(Channel.parseOrNull(null)).isNull()
    }
}

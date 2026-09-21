package br.com.empresa.sdui.core

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
}

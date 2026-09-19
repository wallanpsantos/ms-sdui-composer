package br.com.empresa.sdui.adapters

import br.com.empresa.sdui.adapters.mongo.document.MongoIndexCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MongoIndexCatalogTest {
    @Test
    fun `colecoes e indices unicos do plano existem no catalogo de modelo`() {
        assertThat(MongoIndexCatalog.collections).containsExactlyInAnyOrder(
            "component_catalog",
            "skeletons",
            "specs",
            "pointers",
            "publish_requests",
            "diffs",
            "audit_log",
            "idempotency",
        )
        assertThat(MongoIndexCatalog.uniqueKeys).contains(
            "pointers.surface+platform+channel",
            "specs.specId+revision",
            "diffs.specId+fromRev+toRev",
            "skeletons.skeletonId+revision",
            "idempotency.key",
        )
    }
}

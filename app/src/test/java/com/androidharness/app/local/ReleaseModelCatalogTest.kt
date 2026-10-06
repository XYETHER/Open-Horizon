package com.androidharness.app.local
import com.androidharness.app.data.AppSettings
import org.junit.Assert.*
import org.junit.Test
class ReleaseModelCatalogTest {
    @Test fun onlyPinnedK2Q5IsAvailable() {
        val m = LocalModelCatalog.models.single()
        assertEquals("k2-horizon-09b-q5", m.id)
        assertEquals("K2-Horizon-1B-Q5_K_M.gguf", m.filename)
        assertEquals(773482880L, m.bytes)
        assertEquals("a2efdbbd55f8f6e2be983dcf570f406f0e4703d7027a4019b2388e686865e7fd", m.sha256)
        assertEquals("local-model:" + m.id, AppSettings.DEFAULT_ACTIVE_PROVIDER)
        assertEquals(4096, m.defaultContext)
        assertTrue(LocalModelCatalog.projectors.isEmpty())
    }
    @Test fun cacheChoicesRemainSeparateFromWeights() {
        val m=LocalModelCatalog.models.single()
        assertTrue(m.estimatedMemory(4096,KvCacheQuantization.Q5_0) < m.estimatedMemory(4096,KvCacheQuantization.Q8_0))
        assertEquals(KvCacheQuantization.Q8_0,LocalModelLimits().kvCache)
    }
}

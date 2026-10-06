package com.androidharness.app.local
import org.junit.Assert.*
import org.junit.Test
class SmallModelsTest {
    @Test fun requestedSmallModelsFitEightGbAtFourKAndKeepQ8Default() {
        val phone = LocalDeviceProfile(8 * GIB, 4 * GIB, 10 * GIB, "arm64-v8a", 8, false)
        listOf("k2-horizon-09b", "sharp-minicpm5-2b").forEach { id ->
            val model = LocalModelCatalog.find(id)!!
            val limits = initialLocalLimits(model, phone)
            assertEquals(4096, limits.context); assertEquals(KvCacheQuantization.Q8_0, limits.kvCache)
            assertTrue(phone.canLoad(model, limits.context)); assertEquals(131072, model.maxContext)
            assertFalse(phone.fits(model, 262144))
        }
    }
    @Test fun headerDerivedKvUsesEachModelsActualShape() {
        assertEquals(57344L, LocalModelCatalog.find("k2-horizon-09b")!!.kvBytesPerToken)
        assertEquals(43008L, LocalModelCatalog.find("sharp-minicpm5-2b")!!.kvBytesPerToken)
        val mini = LocalModelCatalog.find("sharp-minicpm5-2b")!!
        assertTrue(mini.estimatedMemory(4096, KvCacheQuantization.Q5_0) < mini.estimatedMemory(4096, KvCacheQuantization.Q8_0))
    }
    @Test fun smallerK2FitsWhileLargeK2CannotLoadWithTwoGbAvailable() {
        val phone = LocalDeviceProfile(8 * GIB, 2 * GIB, 10 * GIB, "arm64-v8a", 8, false)
        assertTrue(phone.canLoad(LocalModelCatalog.find("k2-horizon-09b")!!, 4096))
        assertFalse(phone.canLoad(LocalModelCatalog.find("k2-horizon-37b")!!, 4096))
    }
}

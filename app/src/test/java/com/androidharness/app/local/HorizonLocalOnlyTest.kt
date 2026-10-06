package com.androidharness.app.local
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderFactory
import com.androidharness.app.llm.ProviderType
import org.junit.Assert.*
import org.junit.Test
class HorizonLocalOnlyTest {
    @Test fun restoredCloudTaskCannotCreateInferenceClient() {
        val config = ProviderConfig("old-cloud", "Old account", ProviderType.OPENAI_COMPAT, "https://example.com/v1", "remote-model")
        val error = runCatching { ProviderFactory.create(config) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("on-device models only"))
    }
    @Test fun localIdCannotDisguiseRemoteEndpoint() {
        val config = ProviderConfig(LocalModelCatalog.PROVIDER_PREFIX + "k2-horizon-37b", "Forged", ProviderType.OPENAI_COMPAT, "https://example.com/v1", "remote-model")
        assertTrue(runCatching { ProviderFactory.create(config) }.exceptionOrNull() is IllegalArgumentException)
    }
}

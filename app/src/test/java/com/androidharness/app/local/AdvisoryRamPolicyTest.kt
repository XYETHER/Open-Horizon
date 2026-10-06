package com.androidharness.app.local
import org.junit.Assert.*
import org.junit.Test
class AdvisoryRamPolicyTest {
    @Test fun insufficientRamDoesNotBlockValidAttempt() {
        val phone=LocalDeviceProfile(3*GIB,256*1024*1024L,10*GIB,"arm64-v8a",8,true)
        val model=LocalModelCatalog.find("k2-horizon-09b-q5")!!
        assertFalse(phone.fits(model,131072)); assertFalse(phone.canLoad(model,131072))
        assertTrue(phone.canAttempt(model,131072))
    }
    @Test fun memoryOverrideDoesNotBypassArchitectureOrTrainedContext() {
        val model=LocalModelCatalog.find("k2-horizon-09b-q5")!!
        val phone=LocalDeviceProfile(8*GIB,4*GIB,10*GIB,"arm64-v8a",8,false)
        assertTrue(phone.canAttempt(model,28672));assertFalse(phone.canAttempt(model,262144))
        assertFalse(phone.copy(abi="armeabi-v7a").canAttempt(model,4096))
    }
}

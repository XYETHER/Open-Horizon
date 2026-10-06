package com.androidharness.app.local
import org.junit.Assert.*
import org.junit.Test
class UsageMathTest {
 @Test fun cpuIsNormalizedAcrossDeviceCores() {assertEquals(25.0,UsageMath.cpu(100,2100,0,1000,8)!!,0.001)}
 @Test fun firstAndResetSamplesAreUnknownNotZero() {assertNull(UsageMath.cpu(10,5,0,1000,8));assertNull(UsageMath.cpu(0,0,0,0,8))}
 @Test fun gpuCounterRequiresValidBusyAndTotal() {assertEquals(25.0,UsageMath.gpu("250 1000")!!,0.001);for(raw in listOf("0 0","-1 10","11 10","denied","1 2 3"))assertNull(raw,UsageMath.gpu(raw))}
}

package com.androidharness.app.local
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceTelemetryDeviceTest {
 @Test fun ownMemoryCpuAndOptionalGpuSampleWithoutPrivilegedTools() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val sampler=DeviceTelemetry(context);sampler.sample()
  val start=SystemClock.elapsedRealtime();var value=1L
  while(SystemClock.elapsedRealtime()-start<200)value=(value*1664525+1013904223) xor value
  val sample=sampler.sample()
  assertTrue(sample.appRam>0);assertTrue(sample.totalRam>0);assertTrue(sample.availableRam in 0..sample.totalRam)
  assertNotNull(sample.cpuPercent);assertTrue(sample.cpuPercent!! in 0.0..100.0)
  assertTrue(sample.gpuPercent==null || sample.gpuPercent in 0.0..100.0)
  File(context.cacheDir,"horizon-082-telemetry.json").writeText(JSONObject().put("appPssBytes",sample.appRam).put("totalPhysicalRamBytes",sample.totalRam).put("availableRamBytes",sample.availableRam).put("appCpuPercentOfCores",sample.cpuPercent).put("cores",sample.cores).put("deviceGpuPercent",sample.gpuPercent ?: JSONObject.NULL).put("gpuStatus",sample.gpuStatus).put("cpuWorkValue",value).toString(2))
 }
}

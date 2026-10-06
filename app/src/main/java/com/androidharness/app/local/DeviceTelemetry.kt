package com.androidharness.app.local

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File

internal data class DeviceUsage(val appRam:Long,val totalRam:Long,val availableRam:Long,val cpuPercent:Double?,val cores:Int,val gpuPercent:Double?,val gpuStatus:String)
internal object UsageMath {
 fun cpu(previousCpu:Long,currentCpu:Long,previousWall:Long,currentWall:Long,cores:Int):Double? {
  if(currentWall<=previousWall || currentCpu<previousCpu || cores<=0)return null
  return ((currentCpu-previousCpu).toDouble()*100/(currentWall-previousWall)/cores).coerceIn(0.0,100.0)
 }
 fun gpu(raw:String):Double? {
  val values=raw.trim().split(Regex("\\s+")).mapNotNull {it.toLongOrNull()}
  if(values.size!=2 || values[0]<0 || values[1]<=0 || values[0]>values[1])return null
  return values[0].toDouble()*100/values[1]
 }
}
/** Fixed read-only platform metrics, never advertised as agent tools. */
internal class DeviceTelemetry(context:Context) {
 private val activity=context.applicationContext.getSystemService(ActivityManager::class.java)
 private val cores=Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
 private var cpu=Process.getElapsedCpuTime();private var wall=SystemClock.elapsedRealtime()
 private var sampledMemoryAt=0L;private var appRam=0L
 fun sample():DeviceUsage {
  val now=SystemClock.elapsedRealtime();val nextCpu=Process.getElapsedCpuTime()
  val percent=UsageMath.cpu(cpu,nextCpu,wall,now,cores);cpu=nextCpu;wall=now
  val memory=ActivityManager.MemoryInfo();activity.getMemoryInfo(memory)
  if(sampledMemoryAt==0L || now-sampledMemoryAt>=5000) {
   val own=Debug.MemoryInfo();Debug.getMemoryInfo(own);appRam=own.totalPss.toLong()*1024;sampledMemoryAt=now
  }
  val gpu=runCatching {File("/sys/class/kgsl/kgsl-3d0/gpubusy").bufferedReader().use {it.readLine().orEmpty()}}.getOrNull()
  val gpuPercent=gpu?.let(UsageMath::gpu)
  return DeviceUsage(appRam,memory.totalMem,memory.availMem,percent,cores,gpuPercent,
   if(gpu==null)"Android does not expose the GPU counter to this app." else if(gpuPercent==null)"The driver has not provided a valid GPU sample." else "Device GPU busy time; includes other apps and screen rendering.")
 }
}

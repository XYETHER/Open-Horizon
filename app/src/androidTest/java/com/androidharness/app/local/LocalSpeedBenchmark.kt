package com.androidharness.app.local
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, real installed-weight benchmark. Never changes chats, limits or download state. */
@RunWith(AndroidJUnit4::class)
class LocalSpeedBenchmark {
    @Test fun installedK2DecodeSweep() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit benchmark invocation only", args.getString("horizon_benchmark") == "1")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(app.noBackupFilesDir, "HorizonAgent/models/k2-horizon-09b.gguf")
        assertTrue("Installed K2 0.9B required", model.isFile)
        assertEquals(887487872L, model.length())
        val threads = args.getString("threads", "2,3,4")!!.split(',').map { it.toInt() }
        val repeats = args.getString("repeats", "2")!!.toInt()
        val cap = args.getString("tokens", "128")!!.toInt()
        val tag = args.getString("tag", "cpu-baseline")!!
        val gpuLayers = args.getString("gpu_layers", "0")!!.toInt()
        val fastCpu = args.getString("fast_cpu", "0") == "1"
        LocalNative.configureVulkanCompatibility(args.getString("vk_compat", "0") == "1")
        val rows = JSONArray()
        val runtimeInfo = LocalNative.runtimeInfo()
        val file = File(app.cacheDir, "horizon-benchmark-$tag.json")
        val prompt = "Write a detailed, uninterrupted explanation of how a local AI assistant can help someone plan a community garden. Include concrete examples of soil preparation, planting, watering and maintenance. Keep writing for at least 1000 words; do not end early."
        fun run(n: Int, output: Int, round: Int, warmup: Boolean = false) {
            val handle = LocalNative.create()
            var callbacks = 0; var first = 0L; var last = 0L
            val text = StringBuilder()
            val start = SystemClock.elapsedRealtimeNanos()
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val thermal = (app.getSystemService(PowerManager::class.java)).currentThermalStatus
            try {
                val usage = LocalNative.generate(handle, model.absolutePath.toByteArray(), arrayOf("system","user"),
                    arrayOf("You are a helpful assistant. Answer directly.".toByteArray(), prompt.toByteArray()),
                    28672, 26624, output, n, "Q8_0", "low", byteArrayOf(), emptyArray(),
                    object : LocalNative.Callback {
                        override fun onToken(bytes: ByteArray): Boolean {
                            callbacks++
                            // The first callback is native IFM framing, not a generated token.
                            if (callbacks > 1) { val now = SystemClock.elapsedRealtimeNanos(); if(first == 0L) first=now; last=now }
                            text.append(bytes.toString(Charsets.UTF_8)); return true
                        }
                    }, gpuLayers, fastCpu)
                val end = SystemClock.elapsedRealtimeNanos()
                assertTrue("Model must generate a useful benchmark sample", usage[1] >= output / 2)
                if (!warmup) {
                    val row = JSONObject().put("runtime", runtimeInfo).put("fastCpu",fastCpu).put("gpuLayers",gpuLayers).put("threads", n).put("round", round).put("contextCapacity",28672).put("kv","Q8_0")
                        .put("inputTokens",usage[0]).put("outputTokens",usage[1]).put("finishCode",usage[2])
                        .put("firstTokenMs",(first-start)/1e6).put("totalMs",(end-start)/1e6)
                        .put("decodeTokensPerSecond", (usage[1]-1)*1e9/(last-first).coerceAtLeast(1))
                        .put("batteryTemperatureC",temp/10.0).put("thermalStatus",thermal)
                        .put("outputPreview",text.take(400))
                    rows.put(row)
                    file.writeText(JSONObject().put("tag",tag).put("rows",rows).toString(2))
                    val status=android.os.Bundle();status.putString("stream", "HORIZON_BENCH $row\n")
                    InstrumentationRegistry.getInstrumentation().sendStatus(0,status)
                }
            } finally { LocalNative.destroy(handle) }
        }
        run(4, 32, 0, true)
        // Alternating the order reduces systematic benefit from cold vs hot sample order.
        repeat(repeats) { round -> (if(round%2==0)threads else threads.reversed()).forEach { n -> run(n,cap,round); SystemClock.sleep(1500) } }
    }
}

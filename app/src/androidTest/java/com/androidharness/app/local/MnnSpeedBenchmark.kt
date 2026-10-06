package com.androidharness.app.local
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class MnnSpeedBenchmark {
    @Test fun installedMiniCpmSweep(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments();val app=instrumentation.targetContext
        assumeTrue("Explicit real-device benchmark",args.getString("mnn_benchmark")=="1")
        val original=app.packageName=="com.xyether.horizon.debug"
        assertTrue(original || app.packageName=="com.xyether.horizon.mnn.debug")
        val k2=!original && args.getString("model")=="k2"
        val k2Tokenizer=if(k2)K2Tokenizer(app.assets.open("k2/tokenizer.json").bufferedReader().use {it.readText()}) else null
        val model=File(app.noBackupFilesDir,if(original)"OpenHorizon/models/sharp-minicpm5-2b.gguf" else if(k2)"HorizonMNN/models/k2-horizon-09b-mnn/config.json" else "HorizonMNN/models/minicpm5-2b-mnn/config.json")
        assertTrue("Installed model required",model.isFile)
        val compute=args.getString("compute","cpu")!!;val threads=args.getString("threads","4")!!.toInt();val precision=args.getString("precision","low")!!;val memory=args.getString("memory","low")!!
        val attention=args.getString("attention","10")!!.toInt();val repeats=args.getString("repeats","3")!!.toInt().coerceIn(1,4);val cap=args.getString("tokens","128")!!.toInt().coerceIn(64,512)
        val tag=args.getString("tag","$compute-$threads")!!;require(tag.matches(Regex("[a-zA-Z0-9_-]+")))
        instrumentation.runOnMainSync {
            app.startActivity(Intent().setClassName(app.packageName,"com.androidharness.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        instrumentation.waitForIdleSync()
        val rows=JSONArray();val file=File(app.cacheDir,"mnn-benchmark-$tag.json")
        fun save(){file.writeText(JSONObject().put("tag",tag).put("originalEngine",original).put("model",if(original)"Sharp MiniCPM5 Q6_K_XL" else if(k2)"K2 Horizon 0.9B MNN INT8" else "Official MiniCPM5 MNN INT4").put("contextFilled",false).put("rows",rows).toString(2))}
        val garden="Write a detailed, uninterrupted explanation of how a local AI assistant can help someone plan a community garden. Include concrete examples of soil preparation, planting, watering and maintenance. Keep writing for at least 1000 words; do not end early."
        val nativeClass=Class.forName("com.androidharness.app.local.LocalNative");val instance=nativeClass.getField("INSTANCE").get(null)
        fun native(name:String,vararg arguments:Any?):Any?=nativeClass.methods.first{it.name==name && it.parameterCount==arguments.size}.invoke(instance,*arguments)
        fun run(prompt:String,budget:Int,round:Int,kind:String):String {
            val row=JSONObject().put("kind",kind).put("computeRequested",compute).put("threads",threads).put("precision",precision).put("memory",memory).put("attentionMode",attention).put("round",round).put("contextCapacity",28672)
            row.put("cpuset",runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("unavailable"))
            row.put("foregroundRequested",true)
            val battery=app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED));row.put("batteryTemperatureC",(battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)?:0)/10.0)
            row.put("thermalStatus",(app.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).currentThermalStatus)
            val output=ByteArrayOutputStream();var first=0L;var last=0L;var callbacks=0
            val callback=object:LocalNative.Callback{override fun onTokenId(id:Int)=onToken(requireNotNull(k2Tokenizer).decode(id));override fun onToken(bytes:ByteArray):Boolean{
                val framing=bytes.toString(Charsets.UTF_8);if(callbacks++>0){val now=SystemClock.elapsedRealtimeNanos();if(first==0L)first=now;last=now};output.write(bytes);return true
            }}
            val handle=native("create") as Long;val start=SystemClock.elapsedRealtimeNanos()
            try{
                val common=listOf(handle,model.absolutePath.toByteArray(),arrayOf("system","user"),arrayOf("You are a helpful assistant. Answer directly.".toByteArray(),prompt.toByteArray()),28672,28160,budget,threads,"Q8_0","low",byteArrayOf(),emptyArray<ByteArray>(),callback,0,false,!original)
                val arguments=if(original) common else common+listOf(compute,precision,memory,attention,k2Tokenizer?.encode(K2MnnPrompt.render(arrayOf("system","user"),arrayOf("You are a helpful assistant. Answer directly.".toByteArray(),prompt.toByteArray()),"low")))
                val counts=native("generate",*arguments.toTypedArray()) as IntArray
                row.put("status","ok").put("inputTokens",counts[0]).put("outputTokens",counts[1]).put("finishCode",counts[2])
                row.put("firstTokenMs",if(first>0)(first-start)/1e6 else JSONObject.NULL).put("totalMs",(SystemClock.elapsedRealtimeNanos()-start)/1e6)
                row.put("decodeTokensPerSecond",if(last>first&&counts[1]>1)(counts[1]-1)*1e9/(last-first) else JSONObject.NULL)
                row.put("runtime",native("runtimeInfo"));val mem=Debug.MemoryInfo();Debug.getMemoryInfo(mem);row.put("appPssKb",mem.totalPss)
                row.put("output",output.toString("UTF-8"));rows.put(row);save()
                instrumentation.sendStatus(0,android.os.Bundle().apply{putString("stream","MNN_BENCH $row\n")})
                assertTrue("No generated output",counts[1]>0)
                return output.toString("UTF-8")
            }catch(t:Throwable){row.put("status","error").put("error",t.cause?.message?:t.message);rows.put(row);save();throw t}
            finally{native("destroy",handle)}
        }
        try{
            val arithmetic=run("What is 2 + 2? Reply with the answer in one short sentence.",64,-1,"correctness")
            instrumentation.sendStatus(0,android.os.Bundle().apply{putString("stream","ARITHMETIC_OUTPUT $arithmetic\n")})
            run(garden,32,-1,"warmup")
            repeat(repeats){run(garden,cap,it,"sample")}
        }finally{native("evictSession");save()}
    }
}

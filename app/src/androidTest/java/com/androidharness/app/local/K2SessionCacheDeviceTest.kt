package com.androidharness.app.local
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import java.io.File
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class K2SessionCacheDeviceTest {
    @Test fun cachedPrefixMatchesColdGenerationAndEvictsOnChangesOrCancellation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("real_k2_cache")=="1")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp
        val path=File(app.container.localModels.storagePath,"k2-horizon-09b.gguf")
        assertEquals(887487872L,path.length())
        val rows=JSONArray()
        fun sample(label:String,user:String,context:Int=28672,reuse:Boolean=true,cancel:Boolean=false): Pair<String,IntArray> {
            val h=LocalNative.create();val output=ByteArrayOutputStream();val start=System.nanoTime();var first=0L;var callbacks=0
            try {
                val counts=LocalNative.generate(h,path.absolutePath.toByteArray(),arrayOf("system","user"),
                    arrayOf(("You are a concise assistant. " + "Use concise accurate answers. ".repeat(110)).toByteArray(),user.toByteArray()),context,context-1024,32,4,"Q8_0","low",byteArrayOf(),emptyArray(),object:LocalNative.Callback {
                        override fun onToken(bytes:ByteArray):Boolean {
                            if(callbacks++>0 && first==0L)first=System.nanoTime()
                            output.write(bytes)
                            return !cancel || callbacks<2
                        }
                    },reuseSession=reuse)
                rows.put(JSONObject().put("case",label).put("inputTokens",counts[0]).put("outputTokens",counts[1]).put("cachedInputTokens",counts[3]).put("ttftMs",(first-start)/1_000_000.0).put("elapsedMs",(System.nanoTime()-start)/1_000_000.0).put("output",output.toString("UTF-8")))
                File(app.cacheDir,"horizon-cache-check.json").writeText(rows.toString(2))
                return output.toString("UTF-8") to counts
            } finally {LocalNative.destroy(h)}
        }
        try {
            LocalNative.evictSession()
            val a=sample("cold","Reply in one short sentence: Local model cache works.")
            val b=sample("same-prompt-cached","Reply in one short sentence: Local model cache works.")
            assertEquals(0,a.second[3]);assertTrue(b.second[3]>0);assertEquals(a.first,b.first)
            val changed=sample("changed-prompt-cached","Reply in one short sentence: A different request.")
            assertTrue(changed.second[3]>0);assertTrue(changed.second[3]<changed.second[0]-1)
            LocalNative.evictSession()
            val changedCold=sample("changed-prompt-cold","Reply in one short sentence: A different request.")
            assertEquals(0,changedCold.second[3]);assertEquals(changedCold.first,changed.first)
            val profile=sample("different-context","Reply in one short sentence: A different request.",8192)
            assertEquals(0,profile.second[3])
            assertThrows(IllegalStateException::class.java) {sample("cancelled","Reply in one short sentence: A different request.",8192,cancel=true)}
            assertEquals(0,sample("after-cancel","Reply in one short sentence: A different request.",8192).second[3])
            assertEquals(0,sample("cache-disabled","Reply in one short sentence: A different request.",8192,reuse=false).second[3])
        } finally {LocalNative.evictSession()}
    }
}

package com.androidharness.app.local
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class MnnDeviceTest{
    @Test fun runtimeAndPrivateStorage(){
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.xyether.horizon.mnn.debug",c.packageName)
        assertTrue(LocalNative.runtimeInfo().contains("MNN"))
        assertTrue(AppStorageDirectories.models(c.noBackupFilesDir).absolutePath.endsWith("HorizonMNN/models"))
        val settings=MnnSettings(c);val old=settings.mode.value
        try{settings.set(MnnCompute.GPU);assertEquals(MnnCompute.GPU,MnnSettings(c).mode.value)}finally{settings.set(old)}
    }
    @Test fun nativeBudgetsCacheAndCancellation(){
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit model guards",args.getString("real_mnn")=="1")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val path=File(c.noBackupFilesDir,"HorizonMNN/models/minicpm5-2b-mnn/config.json")
        assertTrue(path.isFile)
        fun invoke(context:Int=4096,kv:String="Q8_0",callback:LocalNative.Callback):IntArray{
            val h=LocalNative.create()
            try{return LocalNative.generate(h,path.absolutePath.toByteArray(),arrayOf("user"),arrayOf("Say hello.".toByteArray()),context,512,64,4,kv,"low",byteArrayOf(),emptyArray(),callback,compute="opencl")}
            finally{LocalNative.destroy(h)}
        }
        val cb=object:LocalNative.Callback{override fun onToken(bytes:ByteArray)=true}
        assertThrows(IllegalStateException::class.java){invoke(context=262144,callback=cb)}
        val e=assertThrows(IllegalStateException::class.java){invoke(kv="Q5_0",callback=cb)}
        assertTrue(e.message.orEmpty().contains("Q5"))
        try{
            val result=invoke(callback=object:LocalNative.Callback{override fun onToken(bytes:ByteArray)=false})
            assertEquals("Cancel before first inference token",0,result[1]);assertEquals(2,result[2])
        }finally{LocalNative.evictSession()}
    }
    @Test fun applyTunedProfile()=runBlocking{
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit persistent profile selection",args.getString("mnn_apply")=="1")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=(c.applicationContext as HarnessApp).container.localModels
        val compute=if(args.getString("compute")=="opencl")MnnCompute.GPU else MnnCompute.CPU
        val threads=args.getString("threads","4")!!.toInt()
        manager.mnnSettings.set(compute,args.getString("precision","low")!!,args.getString("memory","low")!!,args.getString("attention","10")!!.toInt())
        manager.saveLimits("minicpm5-2b-mnn",LocalModelLimits(context=28672,input=26624,output=2048,threads=threads))
        assertEquals(compute,MnnSettings(c).mode.value)
        assertEquals(28672,manager.limits("minicpm5-2b-mnn").context)
        val runtime=File(c.noBackupFilesDir,"HorizonMNN/models/minicpm5-2b-mnn/runtime-cache").canonicalFile
        runtime.listFiles().orEmpty().filter{it.name !in setOf("cpu-low-low","opencl-low-low")}.forEach{entry->
            check(entry.canonicalFile.parentFile==runtime)
            check(if(entry.isDirectory)entry.deleteRecursively() else entry.delete())
        }
        File(c.cacheDir,"mnn-selected-profile.txt").writeText("compute=${compute.alias}\nthreads=$threads\ncontext=28672\nprecision=${manager.mnnSettings.precision.value}\nmemory=${manager.mnnSettings.memory.value}\nattention=${manager.mnnSettings.attention.value}")
    }
    @Test fun productionMiniCpmChat()=runBlocking{
        org.junit.Assume.assumeTrue("Explicit model test",InstrumentationRegistry.getArguments().getString("real_mnn")=="1")
        val c=InstrumentationRegistry.getInstrumentation().targetContext;val manager=(c.applicationContext as HarnessApp).container.localModels
        val id="minicpm5-2b-mnn";assertTrue(id in manager.installed.value)
        val config=ProviderConfig(LocalModelCatalog.PROVIDER_PREFIX+id,"MNN validation",ProviderType.OPENAI_COMPAT,"local://$id",LocalModelCatalog.PROVIDER_PREFIX+id)
        val text=StringBuilder();val errors=mutableListOf<String>();var done=false
        LocalModelProvider(manager).streamChat(config,"","You are a helpful assistant. Answer directly.",listOf(ChatMessage(Role.USER,"Reply exactly: Horizon is ready.")),emptyList(),RequestOptions(maxOutputTokens=64,thinking=ThinkingLevel.OFF)).collect { event->
            when(event){is StreamEvent.TextDelta->text.append(event.text);is StreamEvent.Failure->errors+=event.message;is StreamEvent.Done->done=true;else->Unit}
        }
        File(c.cacheDir,"mnn-production-chat.txt").writeText("answer=$text\nerrors=$errors\ndone=$done")
        assertTrue("$errors",errors.isEmpty());assertTrue(done);assertTrue("$text",text.contains("Horizon",true)&&text.contains("ready",true))
        manager.stop(id)
    }
    @Test fun productionK2Chat()=runBlocking{
        org.junit.Assume.assumeTrue("Explicit model test",InstrumentationRegistry.getArguments().getString("real_k2_mnn")=="1")
        val c=InstrumentationRegistry.getInstrumentation().targetContext;val manager=(c.applicationContext as HarnessApp).container.localModels
        val id="k2-horizon-09b-mnn";assertTrue(id in manager.installed.value)
        val config=ProviderConfig(LocalModelCatalog.PROVIDER_PREFIX+id,"MNN validation",ProviderType.OPENAI_COMPAT,"local://$id",LocalModelCatalog.PROVIDER_PREFIX+id)
        val started=System.nanoTime();var raw="";val text=StringBuilder();val errors=mutableListOf<String>();var done=false
        LocalModelProvider(manager,{raw=it}).streamChat(config,"","You are a helpful assistant. Answer directly.",listOf(ChatMessage(Role.USER,"Reply exactly: Horizon is ready.")),emptyList(),RequestOptions(maxOutputTokens=64,thinking=ThinkingLevel.OFF)).collect { event->
            when(event){is StreamEvent.TextDelta->text.append(event.text);is StreamEvent.Failure->errors+=event.message;is StreamEvent.Done->done=true;else->Unit}
        }
        File(c.cacheDir,"k2-mnn-production-chat.txt").writeText("answer=$text\nerrors=$errors\ndone=$done\nraw=$raw\nruntime=${LocalNative.runtimeInfo()}\nelapsedMs=${(System.nanoTime()-started)/1000000}")
        assertTrue("$errors",errors.isEmpty());assertTrue(done);assertTrue("$text",text.contains("Horizon",true)&&text.contains("ready",true))
        manager.stop(id)
    }
    @Test fun originalK2TokenizerOnAndroid(){
        val inst=InstrumentationRegistry.getInstrumentation()
        val tokenizer=K2Tokenizer(inst.targetContext.assets.open("k2/tokenizer.json").bufferedReader().use {it.readText()})
        val cases=org.json.JSONArray(inst.context.assets.open("k2-tokenizer-cases.json").bufferedReader().use {it.readText()})
        for(i in 0 until cases.length()) {
            val row=cases.getJSONObject(i);val ids=row.getJSONArray("ids")
            assertArrayEquals("fixture $i",IntArray(ids.length()){ids.getInt(it)},tokenizer.encode(row.getString("text")))
            assertEquals("decode $i",row.getString("decoded"),(0 until ids.length()).flatMap {tokenizer.decode(ids.getInt(it)).toList()}.toByteArray().toString(Charsets.UTF_8))
        }
    }
}

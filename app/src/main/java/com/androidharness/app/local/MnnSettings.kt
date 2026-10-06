package com.androidharness.app.local
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
enum class MnnCompute(val alias: String, val label: String) { CPU("cpu","CPU"), GPU("opencl","Adreno GPU") }
class MnnSettings(context: Context) {
    private val prefs=context.getSharedPreferences("mnn-runtime",Context.MODE_PRIVATE)
    private val current=MutableStateFlow(MnnCompute.entries.firstOrNull{it.alias==prefs.getString("compute","cpu")}?:MnnCompute.CPU)
    val mode=current.asStateFlow()
    private val prec=MutableStateFlow(prefs.getString("precision","low")!!.let{if(it=="normal")it else "low"})
    val precision=prec.asStateFlow()
    private val mem=MutableStateFlow(prefs.getString("memory","low")!!.let{if(it=="normal")it else "low"})
    val memory=mem.asStateFlow()
    private val att=MutableStateFlow(prefs.getInt("attention",10).let{if(it==8)8 else 10})
    val attention=att.asStateFlow()
    fun set(compute:MnnCompute,precision:String=prec.value,memory:String=mem.value,attention:Int=att.value){
        require(precision in setOf("low","normal") && memory in setOf("low","normal") && attention in setOf(8,10))
        check(prefs.edit().putString("compute",compute.alias).putString("precision",precision).putString("memory",memory).putInt("attention",attention).commit())
        current.value=compute;prec.value=precision;mem.value=memory;att.value=attention
    }
}

package com.androidharness.app.local

import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.*

/** Per run: identical web requests are bounded even when interleaved. */
internal class LocalToolLoopGuard {
    private val counts=mutableMapOf<String,Int>()
    fun repeatedRequest(calls: List<ToolCallData>): String? {
        val next=counts.toMutableMap()
        for(call in calls) {
            if(call.name !in setOf("web_search","web_fetch"))continue
            val parsed=runCatching { Json.parseToJsonElement(call.argumentsJson) }.getOrNull() ?: continue
            fun canonical(value: JsonElement): JsonElement = when(value) {
                is JsonObject -> JsonObject(value.toSortedMap().mapValues { (key,item) ->
                    if(key=="query" && item is JsonPrimitive && item.isString)
                        JsonPrimitive(item.content.trim().lowercase().replace(Regex("\\s+")," "))
                    else canonical(item)
                })
                is JsonArray -> JsonArray(value.map(::canonical))
                else -> value
            }
            val key=call.name+canonical(parsed).toString()
            val count=(next[key] ?: 0)+1
            if(count>2)return call.name
            next[key]=count
        }
        counts.clear();counts.putAll(next)
        return null
    }
}

package com.androidharness.app.local
import com.androidharness.app.core.*
import com.androidharness.app.agent.AssistantMode
import kotlinx.serialization.json.*

/** Completion checks require genuine page reads; search snippets alone are not research. */
internal object LocalWebVerification {
    /** Nudge a search loop toward real evidence; tools are still chosen by the model. */
    fun readingProgressPrompt(messages: List<ChatMessage>, available: Set<String>, mode: AssistantMode, reminders: Int): String? {
        if(mode != AssistantMode.RESEARCH || reminders !in 0..1 || !available.containsAll(setOf("web_search","web_fetch"))) return null
        val calls=messages.flatMap { it.toolCalls }.associateBy { it.id }
        val successful=messages.filter { it.role==Role.TOOL && !it.isError }.mapNotNull { m -> calls[m.toolCallId]?.let { it to m } }
        val searches=successful.count { it.first.name=="web_search" && it.second.text.contains("https://") }
        val pages=ResearchEvidence.sources(messages).map { it.url }
        if(searches < 3*(reminders+1) || pages.size>=3) return null
        return "You already have $searches search results but have read only ${pages.size} actual source pages. " +
            "Your next call should be web_fetch with an exact url from those results (url only). Read original artist/platform pages before more searching, " +
            "compare the identity, and save source-linked notes. Do not repeat the same query or treat snippets as verified facts. If a page is blocked, read another public source."
    }
    fun missingCheckPrompt(messages: List<ChatMessage>, available: Set<String>, mode: AssistantMode): String? {
        if (mode != AssistantMode.RESEARCH || !available.containsAll(setOf("web_search","web_fetch"))) return null
        val calls=messages.flatMap { it.toolCalls }.associateBy { it.id }
        val successful=messages.filter { it.role==Role.TOOL && !it.isError }.mapNotNull { m -> calls[m.toolCallId]?.let { it to m } }
        fun arg(c: ToolCallData,key:String)=runCatching { Json.parseToJsonElement(c.argumentsJson).jsonObject[key]?.jsonPrimitive?.content }.getOrNull()
        val searches=successful.filter { it.first.name=="web_search" && it.second.text.contains("https://") }
        if(searches.isEmpty()) return null
        val urls=ResearchEvidence.sources(messages).map { it.url }
        if(urls.isEmpty()) return "Before finishing, call web_fetch now with url from a relevant search result. It accepts only url. Read the real page and cite its exact URL. Search snippets alone do not verify the answer. If blocked, try another public source and report limitations."
        if(mode==AssistantMode.RESEARCH) {
            val queryCount=searches.mapNotNull { arg(it.first,"query")?.trim()?.lowercase() }.distinct().size
            val domains=urls.mapNotNull { runCatching { java.net.URI(it).host }.getOrNull() }.distinct().size
            if(queryCount<3 || urls.size<3 || domains<2)
                return "Deep research is incomplete: $queryCount distinct successful searches, ${urls.size} real pages read, $domains source domains. Use web_search and web_fetch to reach at least 3 different queries and 3 pages from at least 2 domains. Read original artist/platform sources; separate facts, opinions and unknowns. Do not finish yet."
            if(available.containsAll(setOf("write_file","read_file"))) {
                val writes=successful.filter { it.first.name=="write_file" || it.first.name=="edit_file" }
                val path=writes.lastOrNull()?.let { arg(it.first,"path") }
                val lastWrite=writes.lastOrNull()
                val writtenAt=lastWrite?.let { successful.indexOf(it) } ?: -1
                val readback=successful.drop(writtenAt+1).lastOrNull { it.first.name=="read_file" && arg(it.first,"path")==path }
                if(path==null || readback==null)
                    return "Save source-linked working notes and a cited final report in workspace-relative files, then call read_file on the final report to verify the saved contents before finishing."
                val linked=urls.count { readback.second.text.contains(it) }
                if(linked<minOf(2,urls.size))
                    return "The saved report is missing source links. Edit $path to cite at least two of these actually read URLs beside supported claims, then read it back again. Do not invent facts or references.\n"+urls.take(4).joinToString("\n")
            }
        }
        val final=messages.lastOrNull { it.role==Role.ASSISTANT && it.toolCalls.isEmpty() }?.text.orEmpty()
        return if(urls.none { final.contains(it) }) "Your final answer is missing its source URL. Copy at least one of these actually read URLs literally into your final answer as a clickable Markdown link [Source](URL).\n" + urls.take(4).joinToString("\n") + "\nPlace links beside supported facts, report unknowns honestly and do not invent references." else null
    }
}

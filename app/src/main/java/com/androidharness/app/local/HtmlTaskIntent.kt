package com.androidharness.app.local
import com.androidharness.app.agent.AssistantMode
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role

/** Only an explicit creation request promotes Chat; explanations/questions remain Chat. */
internal object HtmlTaskIntent {
    private val action=Regex("\\b(create|make|build|generate|design|write|code)\\b",RegexOption.IGNORE_CASE)
    private val target=Regex("\\bhtml\\b|\\.html\\b|\\b(webpage|website|web page|restaurant menu|restraunt menu)\\b",RegexOption.IGNORE_CASE)
    private val question=Regex("^\\s*(how|why|what|explain|teach|show me how)\\b",RegexOption.IGNORE_CASE)
    fun createsHtml(text:String):Boolean = !question.containsMatchIn(text) && action.containsMatchIn(text) && target.containsMatchIn(text)
    fun task(messages:List<ChatMessage>):String = messages.lastOrNull {it.role==Role.USER && !it.text.startsWith("Resume the interrupted task from saved progress.")}?.text.orEmpty()
    fun mode(text:String,current:AssistantMode):AssistantMode = if(current==AssistantMode.CHAT && createsHtml(text)) AssistantMode.CODE else current
}

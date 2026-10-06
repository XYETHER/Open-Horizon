package com.androidharness.app.ui.chat

internal object ChatLinks {
    private val pattern = Regex("""(?i)(?:https?://|www\.)[^\s<>`]+|(?<=URL:)\s*[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}(?:/[^\s<>`]*)?""")
    fun safeUrl(raw: String): String? {
        val value=raw.trim()
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value) && !value.startsWith("http://",true) && !value.startsWith("https://",true)) return null
        val url=if(value.startsWith("http://",true)||value.startsWith("https://",true)) value else "https://$value"
        return runCatching { java.net.URI(url).takeIf { it.scheme.lowercase() in setOf("http","https") && !it.host.isNullOrBlank() && it.userInfo==null }?.toASCIIString() }.getOrNull()
    }
    fun linkify(text:String):String {
        val out=StringBuilder();var index=0;var code=false
        val matches=pattern.findAll(text).associateBy {it.range.first}
        while(index<text.length) {
            if(text[index]=='`') {code=!code;out.append(text[index++]);continue}
            val match=matches[index]
            if(!code && match!=null) {
                val raw=match.value.trim().trimEnd('.',',',';',':','!','?',')',']')
                // Preserve existing markdown link destinations and autolinks.
                val existing=index>0 && (text[index-1]=='(' || text[index-1]=='<' || text[index-1]=='[')
                val url=safeUrl(raw)
                if(!existing && url!=null) {out.append(match.value.takeWhile { it.isWhitespace() }).append('[').append(raw).append("](").append(url).append(')');index+=match.value.length;out.append(match.value.drop(match.value.indexOf(raw)+raw.length))}
                else {out.append(match.value);index+=match.value.length}
            } else out.append(text[index++])
        }
        return out.toString()
    }
}

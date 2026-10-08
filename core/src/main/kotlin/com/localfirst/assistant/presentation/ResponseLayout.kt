package com.localfirst.assistant.presentation

/** Native rendering policy derived from real response content, independent of UI and model vendor. */
enum class ResponsePattern { BUBBLE, FULL_WIDTH, EXPANDABLE }

object ResponseLayout {
    private val tableSeparator=Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")
    fun choose(content:String):ResponsePattern {
        var fence:String?=null
        var previous=""
        var code=false
        for(line in content.lineSequence()) {
            val trimmed=line.trimStart()
            if(trimmed.startsWith("```")||trimmed.startsWith("~~~")) {
                val token=trimmed.take(3)
                if(fence==null){fence=token;code=true}else if(fence==token)fence=null
            } else if(fence==null&&tableSeparator.matches(line)&&previous.count { it=='|' }>=1)return ResponsePattern.FULL_WIDTH
            previous=if(fence==null)line else ""
        }
        if(code)return ResponsePattern.FULL_WIDTH
        return if(content.length>1600)ResponsePattern.EXPANDABLE else ResponsePattern.BUBBLE
    }
    /** Unknown HTML/component tags remain visible text rather than disappearing in Markdown. */
    fun requiresTextFallback(content:String):Boolean {
        val tag=Regex("<[/]?[A-Za-z][A-Za-z0-9_-]*(?:\\s[^>]*)?>")
        var fence:String?=null
        for(line in content.lineSequence()) {
            val trimmed=line.trimStart()
            if(trimmed.startsWith("```")||trimmed.startsWith("~~~")) {
                val token=trimmed.take(3);fence=if(fence==token)null else if(fence==null)token else fence
            } else if(fence==null&&tag.containsMatchIn(line))return true
        }
        return false
    }
    /** Preview is plain text; never execute or follow an incomplete Markdown link. */
    fun preview(content:String):String {
        if(content.length<=420)return content
        var end=420
        val paragraph=content.lastIndexOf('\n',end)
        if(paragraph>240)end=paragraph
        if(content[end-1].isHighSurrogate())end--
        return content.take(end).trimEnd()+"…"
    }
}

package com.localfirst.assistant.presentation

sealed interface ResponsePart {
    data class Prose(val markdown:String):ResponsePart
    data class Table(val headers:List<String>,val rows:List<List<String>>):ResponsePart
}

/** Conservative Markdown table adapter; malformed/unknown content stays in the prose fallback. */
object ResponseParts {
    private val separator=Regex("^:?-{3,}:?$")
    fun parse(content:String):List<ResponsePart> {
        val lines=content.lines();val result=mutableListOf<ResponsePart>();val prose=mutableListOf<String>()
        var i=0;var fence:String?=null
        fun flush() { if(prose.isNotEmpty()) { result+=ResponsePart.Prose(prose.joinToString("\n"));prose.clear() } }
        while(i<lines.size) {
            val trimmed=lines[i].trimStart()
            if(trimmed.startsWith("```")||trimmed.startsWith("~~~")) { val token=trimmed.take(3);fence=if(fence==token)null else if(fence==null)token else fence }
            val header=cells(lines[i]);val next=lines.getOrNull(i+1)?.let(::cells)
            if(fence==null&&header.size in 2..8&&next?.size==header.size&&next.all { separator.matches(it.trim()) }) {
                flush();i+=2;val rows=mutableListOf<List<String>>()
                while(i<lines.size&&lines[i].contains('|')) {
                    val row=cells(lines[i]);if(row.size!=header.size)break
                    rows+=row;i++
                }
                result+=ResponsePart.Table(header,rows)
            } else { prose+=lines[i];i++ }
        }
        flush();return result
    }
    private fun cells(line:String):List<String> {
        val values=mutableListOf<String>();val cell=StringBuilder();var escaped=false;var code=false
        for(char in line.trim()) {
            when {
                escaped -> { if(char!='|')cell.append('\\');cell.append(char);escaped=false }
                char=='\\' -> escaped=true
                char=='`' -> { code=!code;cell.append(char) }
                char=='|'&&!code -> { values+=cell.toString().trim();cell.clear() }
                else -> cell.append(char)
            }
        }
        if(escaped)cell.append('\\')
        values+=cell.toString().trim()
        if(values.firstOrNull().isNullOrEmpty()&&line.trimStart().startsWith('|'))values.removeAt(0)
        if(values.lastOrNull().isNullOrEmpty()&&line.trimEnd().endsWith('|'))values.removeAt(values.lastIndex)
        return values
    }
}

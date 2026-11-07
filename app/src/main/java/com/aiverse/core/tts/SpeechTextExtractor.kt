package com.aiverse.core.tts

import com.aiverse.feature.settings.data.repository.TtsCodeBlockMode

/**
 * 把 Markdown 提取为适合朗读的纯文本：
 * - 围栏代码块（```）按 [mode] 跳过或替换为提示语「代码块，请查看」；
 * - 行内代码保留内容（去掉反引号）；
 * - 图片替换为「图片」占位或移除；链接保留锚文本；表格转单元格序列；
 * - 去掉标题井号、列表符号、引用号等格式标记，压缩多余空白。
 *
 * 不解析完整 AST，按正则顺序替换——朗读场景对格式精度要求低，速度与鲁棒性更重要。
 */
object SpeechTextExtractor {

    private val fencedCode = Regex("""\u0060{3,}[\s\S]*?\u0060{3,}""")
    private val inlineCode = Regex("""`([^`]+)`""")
    private val image = Regex("""!\[[^\]]*\]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)\]\([^)]*\)""")
    private val heading = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
    private val listItem = Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE)
    private val orderedItem = Regex("""^\s*\d+\.\s+""", RegexOption.MULTILINE)
    private val blockquote = Regex("""^\s*>\s?""", RegexOption.MULTILINE)
    private val hr = Regex("""^\s*([-*_]\s?){3,}$""", RegexOption.MULTILINE)
    private val tablePipe = Regex("""\|""")
    private val tableSep = Regex("""^\s*:?-+:?\s*\|""", RegexOption.MULTILINE)
    private val emphasis = Regex("""(\*{1,3}|_{1,3})(.+?)\1""")
    private val multiBlank = Regex("""\n{3,}""")

    fun extract(markdown: String, mode: TtsCodeBlockMode): String {
        var text = markdown

        text = fencedCode.replace(text) { match ->
            when (mode) {
                TtsCodeBlockMode.SKIP -> ""
                TtsCodeBlockMode.ANNOUNCE -> "，代码块，请查看。"
            }
        }
        text = inlineCode.replace(text) { it.groupValues[1] }
        text = image.replace(text) { "" }
        text = link.replace(text) { it.groupValues[1] }
        text = hr.replace(text) { "" }
        text = tableSep.replace(text) { "" }
        text = tablePipe.replace(text) { " " }
        text = heading.replace(text) { "" }
        text = blockquote.replace(text) { "" }
        text = orderedItem.replace(text) { "" }
        text = listItem.replace(text) { "" }
        text = emphasis.replace(text) { it.groupValues[2] }
        text = multiBlank.replace(text) { "\n\n" }

        return text.trim().replace("\n", " ").replace("  ", " ").trim()
    }
}

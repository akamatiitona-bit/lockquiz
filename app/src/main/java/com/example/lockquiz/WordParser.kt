package com.example.lockquiz

object WordParser {
    private val japanese = Regex("[\u3040-\u30FF\u4E00-\u9FFF]")
    private val pairLine = Regex("^(.+?)\\s*[:：]\\s*(.+)$")

    private fun clean(lines: List<String>) =
        lines.map { it.trim().trimStart('\uFEFF') }.filter { it.isNotEmpty() }

    /** 1ファイル形式: 「english : 日本語」。【見出し】はカテゴリとして保存。 */
    fun parseSingle(text: String): List<Word> {
        var category = ""
        val out = mutableListOf<Word>()
        for (line in clean(text.lines())) {
            if (line.startsWith("【") && line.endsWith("】")) {
                category = line.substring(1, line.length - 1)
                continue
            }
            val m = pairLine.find(line) ?: continue
            out += Word(english = m.groupValues[1].trim(), japanese = m.groupValues[2].trim(), category = category)
        }
        return out
    }

    /** 2ファイル形式: 行番号で対応。どちらが日本語のファイルかは自動判定。 */
    fun parsePair(a: String, b: String): Result<List<Word>> {
        val aJa = japanese.containsMatchIn(a)
        val bJa = japanese.containsMatchIn(b)
        val (ja, en) = when {
            aJa && !bJa -> a to b
            bJa && !aJa -> b to a
            else -> return Result.failure(IllegalArgumentException("日本語のファイルと英語のファイルを1つずつ選んでください"))
        }
        val jaLines = clean(ja.lines())
        val enLines = clean(en.lines())
        if (jaLines.size != enLines.size) {
            return Result.failure(IllegalArgumentException("行数が違います（日本語 ${jaLines.size} 行 / 英語 ${enLines.size} 行）"))
        }
        return Result.success(jaLines.zip(enLines) { j, e -> Word(english = e, japanese = j) })
    }
}

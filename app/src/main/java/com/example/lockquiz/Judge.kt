package com.example.lockquiz

import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class Direction { JA_TO_EN, EN_TO_JA }

data class Question(val word: Word, val direction: Direction) {
    val prompt: String get() = if (direction == Direction.JA_TO_EN) word.japanese else word.english
    val expected: String get() = if (direction == Direction.JA_TO_EN) word.english else word.japanese
    val instruction: String get() = if (direction == Direction.JA_TO_EN) "英語で答えてください" else "日本語で答えてください"
}

/** 端末内の判定(通信なし)。 */
object Judge {
    private val splitter = Regex("[/、,，]")
    private val paren = Regex("[(（].*?[)）]")

    private fun norm(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFKC).trim().lowercase().replace(Regex("\\s+"), " ")

    // "尋ねる/言う" → {尋ねる, 言う}、"話す(言語)" → {話す(言語), 話す}
    private fun variants(expected: String): Set<String> {
        val set = mutableSetOf<String>()
        for (part in expected.split(splitter)) {
            norm(part).takeIf { it.isNotEmpty() }?.let { set += it }
            norm(part.replace(paren, "")).takeIf { it.isNotEmpty() }?.let { set += it }
        }
        return set
    }

    /** 答えのどれか1つ(または "/" 区切りで複数)が正解候補に含まれれば正解。 */
    fun local(q: Question, answer: String): Boolean {
        val ok = variants(q.expected)
        val parts = answer.split(splitter).map { norm(it) }.filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { it in ok }
    }
}

/** 同義語・表記ゆれ用のAI判定。サーバー未設定・通信失敗なら null を返す。 */
object AiJudge {
    suspend fun judge(serverUrl: String, token: String, q: Question, answer: String): Boolean? {
        if (serverUrl.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val conn = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (token.isNotBlank()) setRequestProperty("X-App-Token", token)
                }
                val body = JSONObject()
                    .put("question", q.prompt)
                    .put("expected", q.expected)
                    .put("answer", answer)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode != 200) return@withContext null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text).getBoolean("correct")
            } catch (e: Exception) {
                null
            }
        }
    }
}

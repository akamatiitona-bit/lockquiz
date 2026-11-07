package com.example.lockquiz

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全画面のクイズ画面を他アプリの上に重ねる。
 * 指定の問題数に正解するまで閉じない（戻るキーも無効）。
 */
class QuizOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    private val ctx = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(context)

    private var root: View? = null
    private var session: QuizSession? = null
    private var misses = 0

    var isShowing = false
        private set

    private lateinit var progress: TextView
    private lateinit var instruction: TextView
    private lateinit var prompt: TextView
    private lateinit var notice: TextView
    private lateinit var input: EditText
    private lateinit var button: Button

    fun show() {
        if (isShowing) return
        isShowing = true
        scope.launch {
            val words = withContext(Dispatchers.IO) { AppDb.get(context).words().all() }
            if (words.isEmpty()) {          // 単語がない時は絶対にロックしない
                isShowing = false
                onClosed()
                return@launch
            }
            session = QuizSession(words, prefs.questionCount)
            val view = build()
            wm.addView(view, layoutParams())
            root = view
            render()
        }
    }

    fun forceClose() = close()

    private fun close() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        isShowing = false
        onClosed()
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,   // FLAG_NOT_FOCUSABLE を付けない = キーボードが使える
        PixelFormat.OPAQUE,
    ).apply {
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun build(): View {
        val layout = object : LinearLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean =
                if (e.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(e)
        }.apply {
            orientation = VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(72), dp(24), dp(24))
        }

        progress = TextView(ctx).apply { setTextColor(Color.GRAY); textSize = 14f; gravity = Gravity.CENTER }
        instruction = TextView(ctx).apply { setTextColor(Color.LTGRAY); textSize = 16f; gravity = Gravity.CENTER }
        prompt = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 34f; gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(28))
        }
        notice = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FF6B6B")); textSize = 16f; gravity = Gravity.CENTER
        }
        input = EditText(ctx).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            hint = "ここに入力"
            textSize = 22f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        button = Button(ctx).apply { text = "答える"; setOnClickListener { submit() } }

        layout.addView(progress, wide())
        layout.addView(instruction, wide())
        layout.addView(prompt, wide())
        layout.addView(input, wide())
        layout.addView(notice, wide())
        layout.addView(button, wide())
        return layout
    }

    private fun render() {
        val s = session ?: return
        val q = s.current
        misses = 0
        progress.text = "${s.index + 1} / ${s.total}"
        instruction.text = q.instruction
        prompt.text = q.prompt
        notice.text = ""
        input.setText("")
        input.requestFocus()
        input.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submit() {
        val s = session ?: return
        val answer = input.text.toString()
        if (answer.isBlank() || !button.isEnabled) return
        button.isEnabled = false
        scope.launch {
            val q = s.current
            var ok = Judge.local(q, answer)
            // 日本語の答えは同義語がありうるので、端末内で外れたらAIに確認（3回目まで）
            if (!ok && misses < 3 && q.direction == Direction.EN_TO_JA) {
                ok = AiJudge.judge(prefs.serverUrl, prefs.aiToken, q, answer) == true
            }
            if (ok) {
                s.advance()
                if (s.finished) close() else render()
            } else {
                misses++
                input.setText("")
                notice.text = if (misses >= 3) {
                    "答え: ${q.expected}\n（入力すれば通過できます）"
                } else {
                    "ちがいます（${misses}回目）"
                }
            }
            button.isEnabled = true
        }
    }
}

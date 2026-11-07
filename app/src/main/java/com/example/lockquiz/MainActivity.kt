package com.example.lockquiz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }
    private lateinit var status: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }
        fun label(s: String, size: Float = 14f) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, dp(12), 0, dp(4))
        }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s; setOnClickListener { onClick() }
        }

        root.addView(label("ロッククイズ", 24f))
        status = label("", 16f)
        root.addView(status)

        // --- 単語の取り込み ---
        root.addView(button("単語ファイルを読み込む") { picker.launch(arrayOf("*/*")) })
        root.addView(label("ファイル1つ: 「english : 日本語」の行 / ファイル2つ: 日本語と英語を1行ずつ対応させたもの"))
        root.addView(button("単語を全削除") { clearWords() })

        // --- 問題数 ---
        val countLabel = label("解除に必要な問題数: ${prefs.questionCount}")
        val seek = SeekBar(this).apply {
            max = 19
            progress = prefs.questionCount - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    prefs.questionCount = value + 1
                    countLabel.text = "解除に必要な問題数: ${value + 1}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(countLabel)
        root.addView(seek)

        // --- AI判定サーバー(任意) ---
        root.addView(label("AI判定サーバー（任意。空なら端末内の判定のみ）"))
        root.addView(field("https://...", prefs.serverUrl) { prefs.serverUrl = it })
        root.addView(field("トークン（任意）", prefs.aiToken) { prefs.aiToken = it })

        // --- 有効化・テスト ---
        val sw = SwitchCompat(this).apply {
            text = "ロッククイズを有効にする"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { btn, checked ->
                if (checked) enable(btn as SwitchCompat) else disable()
            }
        }
        root.addView(sw)
        root.addView(button("今すぐテスト表示") { testShow() })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshCount()
    }

    private fun field(hint: String, initial: String, onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint
        setText(initial)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChange(s.toString().trim()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    private suspend fun wordCount(): Int =
        withContext(Dispatchers.IO) { AppDb.get(this@MainActivity).words().count() }

    private fun refreshCount() {
        lifecycleScope.launch { status.text = "登録単語数: ${wordCount()}" }
    }

    private fun clearWords() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AppDb.get(this@MainActivity).words().clear() }
            refreshCount()
        }
    }

    private fun importFiles(uris: List<Uri>) {
        lifecycleScope.launch {
            val parsed: Result<List<Word>> = withContext(Dispatchers.IO) {
                runCatching {
                    val texts = uris.map { uri ->
                        contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                    when (texts.size) {
                        1 -> WordParser.parseSingle(texts[0])
                        2 -> WordParser.parsePair(texts[0], texts[1]).getOrThrow()
                        else -> error("ファイルは1つまたは2つ選んでください")
                    }
                }
            }
            val words = parsed.getOrElse {
                toast(it.message ?: "読み込みに失敗しました")
                return@launch
            }
            if (words.isEmpty()) {
                toast("単語が見つかりませんでした（形式を確認してください）")
                return@launch
            }
            val added = withContext(Dispatchers.IO) {
                val dao = AppDb.get(this@MainActivity).words()
                val existing = dao.all().map { it.english to it.japanese }.toSet()
                val fresh = words
                    .distinctBy { it.english to it.japanese }
                    .filter { (it.english to it.japanese) !in existing }
                dao.insertAll(fresh)
                fresh.size
            }
            toast("${added}語を追加しました")
            refreshCount()
        }
    }

    private fun enable(sw: SwitchCompat) {
        if (!Settings.canDrawOverlays(this)) {
            toast("「他のアプリの上に表示」を許可してから、もう一度オンにしてください")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            sw.isChecked = false
            return
        }
        lifecycleScope.launch {
            if (wordCount() == 0) {
                toast("先に単語ファイルを読み込んでください")
                sw.isChecked = false
                return@launch
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            prefs.enabled = true
            ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, LockService::class.java))
        }
    }

    private fun disable() {
        prefs.enabled = false
        stopService(Intent(this, LockService::class.java))
    }

    private fun testShow() {
        if (!Settings.canDrawOverlays(this)) {
            toast("先に「他のアプリの上に表示」を許可してください")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        lifecycleScope.launch {
            if (wordCount() == 0) {
                toast("先に単語ファイルを読み込んでください")
                return@launch
            }
            ContextCompat.startForegroundService(
                this@MainActivity,
                Intent(this@MainActivity, LockService::class.java).setAction(LockService.ACTION_SHOW_NOW),
            )
        }
    }
}

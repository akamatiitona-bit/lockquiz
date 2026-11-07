package com.example.lockquiz

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lockquiz", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** 解除までに解く問題数 (1〜20) */
    var questionCount: Int
        get() = sp.getInt("count", 3).coerceIn(1, 20)
        set(v) = sp.edit().putInt("count", v.coerceIn(1, 20)).apply()

    var serverUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v).apply()

    var aiToken: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()
}

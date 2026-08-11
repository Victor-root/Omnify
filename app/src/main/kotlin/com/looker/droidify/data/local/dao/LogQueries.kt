package com.looker.droidify.data.local.dao

import android.util.Log
import com.looker.droidify.BuildConfig

fun logQuery(vararg param: Pair<String, Any?>) {
    if (!BuildConfig.DEBUG) return
    val message = buildString {
        appendLine("(")
        param.forEach { (key, value) ->
            appendLine("\t$key: $value,")
        }
        appendLine(")")
    }
    Log.d("RoomQuery", message)
}

package com.looker.droidify.data.local.dao

import android.util.Log

fun logQuery(vararg param: Pair<String, Any?>) {
    val message = buildString {
        appendLine("(")
        param.forEach { (key, value) ->
            appendLine("\t$key: $value,")
        }
        appendLine(")")
    }
    Log.d("RoomQuery", message)
}

package com.baidu.carlife.sdk.util

import java.text.SimpleDateFormat
import java.util.*

val threadFormatCache = ThreadLocal<SimpleDateFormat>()

fun Date.formatISO8601(): String {
    var format = threadFormatCache.get()
    if (format == null) {
        format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        threadFormatCache.set(format)
    }
    return format.format(this) ?: toString()
}

const val TIME_FORMAT_FOR_HMS = "yyyy-MM-dd-HH-mm-ss"

/**
 * 拓展 根据时间格式获取时间
 * @param time 时间毫秒数
 * @param format 时间格式
 * @return 时间Str
 */
fun Date.getDateFormat(time: Long, format: String?): String? {
    val sdf = SimpleDateFormat(format)
    val d1 = Date(time)
    return sdf.format(d1)
}
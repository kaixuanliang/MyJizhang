package com.mine.myjizhang.util

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateTimes {
    private val formats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-M-d H:mm",
        "yyyy-M-d H:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/M/d"
    )

    fun parse(text: String): Long? {
        val t = text.trim()
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.CHINA)
                sdf.isLenient = false
                return sdf.parse(t)?.time ?: continue
            } catch (_: ParseException) {
            }
        }
        return null
    }

    fun format(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    fun formatDay(millis: Long): String =
        SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(millis))

    fun monthStart(year: Int, month1: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month1 - 1, 1, 0, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun monthEnd(year: Int, month1: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month1 - 1, 1, 0, 0, 0)
        c.add(Calendar.MONTH, 1)
        return c.timeInMillis
    }

    fun dayStart(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}

object Money {
    /** 解析金额：兼容 "1,5253.46"、"￥88"、前后空格等 */
    fun parse(text: Any?): Double? {
        if (text == null) return null
        var s = text.toString().trim()
        if (s.isEmpty() || s == "-") return null
        s = s.replace("￥", "").replace("¥", "").replace(",", "").replace(" ", "")
        return s.toDoubleOrNull()
    }

    fun fmt(v: Double): String {
        val abs = Math.abs(v)
        return if (abs == abs.toLong().toDouble()) {
            abs.toLong().toString()
        } else {
            String.format(Locale.CHINA, "%.2f", abs)
        }
    }
}

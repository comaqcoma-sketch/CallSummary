package com.mplan.callsummary

import java.util.Locale

fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.KOREA, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.KOREA, "%02d:%02d", m, s)
}

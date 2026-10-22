package com.nzm.wallet.ui

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

private const val MAX_DIGITS = 9

fun shortAmount(raw: BigInteger, decimals: Int): String {
    if (raw.signum() == 0) return "0"
    val v = BigDecimal(raw).movePointLeft(decimals)
    val intDigits = if (v < BigDecimal.ONE) 0 else v.toBigInteger().toString().length
    if (intDigits > 15) {
        return "≈ " + v.round(MathContext(7, RoundingMode.DOWN)).toString()
    }
    val dec = (MAX_DIGITS - intDigits).coerceIn(0, MAX_DIGITS)
    val cut = v.setScale(dec, RoundingMode.DOWN)
    if (cut.signum() == 0) return "<" + BigDecimal.ONE.movePointLeft(MAX_DIGITS).toPlainString()
    val text = cut.stripTrailingZeros().toPlainString()
    return if (cut.compareTo(v) == 0) text else "≈ $text"
}

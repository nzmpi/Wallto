package com.nzm.wallet.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.AUTH_BIOMETRIC_STRONG
import android.security.keystore.KeyProperties.AUTH_DEVICE_CREDENTIAL
import android.security.keystore.KeyProperties.BLOCK_MODE_GCM
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
import android.security.keystore.KeyProperties.KEY_ALGORITHM_AES
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.utils.Constants.PROVIDER
import org.web3j.crypto.Keys
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object Helpers {
    /** Strips control and bidi/format chars: untrusted text cannot spoof the UI. */
    fun String.sanitize(max: Int = 2000): String =
        filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().take(max)

    fun ByteArray.toUtf8StringOrNull(): String? =
        try {
            decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }

    fun Context.getPrefs(key: String): SharedPreferences =
        getSharedPreferences(key, Context.MODE_PRIVATE)

    fun generateSecretKey(alias: String, strongBox: Boolean? = null): SecretKey {
        var base = KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
            .setBlockModes(BLOCK_MODE_GCM)
            .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox != null) {
            base = base.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)
                .setInvalidatedByBiometricEnrollment(true)
                .setIsStrongBoxBacked(strongBox)
        }
        val spec = base.build()
        return KeyGenerator.getInstance(KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    fun isValidAddress(input: String): Boolean {
        val s = input.trim()
        if (!Regex("^0x[0-9a-fA-F]{40}$").matches(s)) return false
        val body = s.substring(2)
        return body == body.lowercase() || body == body.uppercase() || Keys.toChecksumAddress(s) == s // no checksum info
    }

    fun formatUnits(value: BigInteger, decimals: Int, maxFraction: Int = 6): String =
        BigDecimal(value).movePointLeft(decimals)
            .setScale(maxFraction, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()

    fun parseAmount(text: String, decimals: Int): BigInteger {
        val d = text.trim().replace(',', '.').toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Invalid amount")
        require(d.signum() > 0) { "Amount must be above 0" }
        return try {
            d.movePointRight(decimals).toBigIntegerExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Too many decimals (max $decimals)")
        }
    }

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

    fun shortAddress(a: String) = a.take(6) + "…" + a.takeLast(4)

    fun fmt(value: BigInteger, c: Chain, maxFraction: Int? = null): String =
        fmt(value, c.decimals, c.symbol, maxFraction)

    fun fmt(value: BigInteger, t: Token, maxFraction: Int? = null): String =
        fmt(value, t.decimals, t.symbol, maxFraction)

    private fun fmt(v: BigInteger, d: Int, s: String, m: Int? = null): String =
        if (m == null) "${shortAmount(v, d)} $s" else "${formatUnits(v, d, m)} $s"
}

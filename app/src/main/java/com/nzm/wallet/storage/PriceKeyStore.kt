package com.nzm.wallet.storage

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.nzm.wallet.utils.Constants.PROVIDER
import com.nzm.wallet.utils.Constants.TRANSFORM
import com.nzm.wallet.utils.Helpers.generateSecretKey
import com.nzm.wallet.utils.Helpers.getPrefs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PriceKeyStore(context: Context) {
    private val prefs = context.getPrefs("price_prefs")

    fun get(): String? {
        val ct = prefs.getString("key_ct", null) ?: return null
        val iv = prefs.getString("key_iv", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun set(key: String?) {
        if (key.isNullOrBlank()) {
            prefs.edit { remove("key_ct").remove("key_iv") }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ct = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString("key_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString("key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return generateSecretKey(ALIAS)
    }

    private companion object {
        const val ALIAS = "price_api_key"
    }
}

package com.nzm.wallet.storage

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedContract(
    val id: String,
    val chainId: Long,
    val address: String,
    val name: String,
    val abi: String?
)

object SavedContracts {
    private const val PREFS = "saved_contracts"
    private const val KEY = "list"
    private var appContext: Context? = null
    val items = mutableStateListOf<SavedContract>()

    fun load(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                items.add(
                    SavedContract(
                        o.getString("id"), o.getLong("chainId"), o.getString("address"), o.getString("name"),
                        o.optString("abi", "").ifBlank { null }
                    )
                )
            }
        } catch (_: Exception) {
            items.clear()
        }
    }

    private fun persist() {
        val a = JSONArray()
        items.forEach {
            a.put(
                JSONObject().put("id", it.id).put("chainId", it.chainId).put("address", it.address)
                    .put("name", it.name).put("abi", it.abi ?: "")
            )
        }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, a.toString())?.apply()
    }

    fun find(chainId: Long, address: String): SavedContract? =
        items.firstOrNull { it.chainId == chainId && it.address.equals(address.trim(), true) }

    fun add(chainId: Long, address: String, name: String, abi: String?) {
        if (find(chainId, address) != null) return
        items.add(SavedContract(UUID.randomUUID().toString(), chainId, address, name.trim(), abi))
        persist()
    }

    fun setAbi(id: String, abi: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) { items[i] = items[i].copy(abi = abi); persist() }
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        persist()
    }
}

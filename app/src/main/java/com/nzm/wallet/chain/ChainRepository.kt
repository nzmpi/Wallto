package com.nzm.wallet.chain

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import androidx.core.net.toUri

class ChainRepository(context: Context) {
    private val dao = AppDatabase.get(context).chainDao()
    val chains: Flow<List<Chain>> = dao.observeAll()

    suspend fun seedDefaults() {
        if (dao.count() > 0) return
        dao.insertAll(
            listOf(
                Chain(1, "Ethereum", "https://ethereum-rpc.publicnode.com", "ETH", explorerUrl = "https://etherscan.io"),
                Chain(137, "Polygon", "https://polygon-bor-rpc.publicnode.com", "POL", explorerUrl = "https://polygonscan.com"),
                Chain(11155111, "Sepolia", "https://ethereum-sepolia-rpc.publicnode.com", "ETH",
                    explorerUrl = "https://sepolia.etherscan.io", isTestnet = true)
            )
        )
    }

    suspend fun remove(chainId: Long) = dao.delete(chainId)

    /** existing == null: add new chain. existing != null: edit it. Chain ID never changes on edit. */
    suspend fun saveChain(
        existing: Chain?, name: String, rpcUrl: String, chainIdText: String,
        symbol: String, explorerUrl: String, isTestnet: Boolean
    ): Chain {
        val id = existing?.chainId
            ?: (chainIdText.trim().toLongOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Chain ID must be a positive number"))
        if (existing == null) {
            require(dao.get(id) == null) { "Chain $id already exists. Select it and use Edit." }
        }
        val n = name.trim()
        require(n.isNotEmpty()) { "Name required" }
        val s = symbol.trim()
        require(s.isNotEmpty() && s.length <= 11) { "Symbol must be 1-11 characters" }
        val rpc = rpcUrl.trim()
        requireHttps(rpc, "RPC URL")
        val explorer = explorerUrl.trim().takeIf { it.isNotEmpty() }
            ?.also { requireHttps(it, "Explorer URL") }?.trimEnd('/')

        // new chain, or RPC changed: RPC must serve this chain ID
        if (existing == null || rpc != existing.rpcUrl) {
            val reported = Rpc.chainId(rpc)
            require(reported == id) { "RPC reports chain ID $reported, expected $id" }
        }
        return Chain(id, n, rpc, s, 18, explorer, isTestnet).also { dao.upsert(it) }
    }

    private fun requireHttps(url: String, label: String) {
        val u = url.toUri()
        require(u.scheme == "https" && !u.host.isNullOrEmpty()) { "$label must start with https://" }
    }
}
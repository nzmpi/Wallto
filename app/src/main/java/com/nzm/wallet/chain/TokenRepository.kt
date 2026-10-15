package com.nzm.wallet.chain

import android.content.Context
import kotlinx.coroutines.flow.Flow

class TokenRepository(context: Context) {
    private val dao = AppDatabase.get(context).tokenDao()
    fun observe(chainId: Long): Flow<List<Token>> = dao.observe(chainId)
    suspend fun get(chainId: Long, address: String): Token? = dao.get(chainId, address)
    suspend fun add(token: Token) = dao.upsert(token)
    suspend fun remove(chainId: Long, address: String) = dao.delete(chainId, address)
}

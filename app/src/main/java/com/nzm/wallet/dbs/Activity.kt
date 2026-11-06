package com.nzm.wallet.dbs

import androidx.room.*
import com.nzm.wallet.chain.TxPlan
import kotlinx.coroutines.flow.Flow

/** status: 0 pending, 1 confirmed, 2 failed (reverted) */
@Entity(tableName = "activity", primaryKeys = ["chainId", "hash"])
data class TxRecord(
    val chainId: Long,
    val hash: String,
    val sender: String,
    val recipient: String,
    val kind: String,          // "native" | "token" | "call"
    val amount: String,        // raw units, decimal string
    val symbol: String,
    val decimals: Int,
    val tokenAddress: String?,
    val dapp: String?,         // site name for WalletConnect transactions
    val note: String?,         // short description of a contract call
    val createdAt: Long,
    val status: Int
)

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TxRecord>>

    @Query("SELECT * FROM activity WHERE status = 0")
    suspend fun pending(): List<TxRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: TxRecord)

    @Query("UPDATE activity SET status = :status WHERE chainId = :chainId AND hash = :hash")
    suspend fun setStatus(chainId: Long, hash: String, status: Int)

    @Query("DELETE FROM activity WHERE chainId = :chainId AND hash = :hash")
    suspend fun delete(chainId: Long, hash: String)

    @Query("DELETE FROM activity")
    suspend fun deleteAll()
}

fun txRecordOf(plan: TxPlan, hash: String, dapp: String? = null, note: String? = null): TxRecord {
    val tk = plan.token
    val tkAmount = plan.tokenAmount
    val now = System.currentTimeMillis()
    return if (tk != null && tkAmount != null) {
        TxRecord(
            plan.chain.chainId, hash, plan.from, plan.recipient ?: plan.to, "token",
            tkAmount.toString(), tk.symbol, tk.decimals, tk.address, dapp, note, now, 0
        )
    } else {
        TxRecord(
            plan.chain.chainId, hash, plan.from, plan.to,
            if (plan.data.isNotEmpty()) "call" else "native",
            plan.value.toString(), plan.chain.symbol, plan.chain.decimals, null, dapp, note, now, 0
        )
    }
}

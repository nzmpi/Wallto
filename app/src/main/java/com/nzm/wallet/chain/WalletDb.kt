package com.nzm.wallet.chain

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Public info only. Secrets live in Vault. address "" = not known yet (migrated wallet, before first unlock). */
@Entity(tableName = "wallets")
data class WalletRow(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val createdAt: Long
)

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallets ORDER BY createdAt, id")
    fun observe(): Flow<List<WalletRow>>

    @Query("SELECT * FROM wallets ORDER BY createdAt, id")
    suspend fun all(): List<WalletRow>

    @Query("SELECT * FROM wallets WHERE id = :id")
    suspend fun get(id: String): WalletRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WalletRow)

    @Query("UPDATE wallets SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE wallets SET address = :address WHERE id = :id")
    suspend fun setAddress(id: String, address: String)

    @Query("DELETE FROM wallets WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM wallets")
    suspend fun deleteAll()
}

/**
 * Makes rows match the secrets on disk: adds a row for every vault id without one
 * (address filled in later), removes rows whose secret is gone.
 */
suspend fun WalletDao.syncWithVault(vaultIds: List<String>) {
    val rows = all()
    rows.filter { it.id !in vaultIds }.forEach { delete(it.id) }
    var n = rows.count { it.id in vaultIds }
    val now = System.currentTimeMillis()
    for (id in vaultIds) {
        if (rows.none { it.id == id }) {
            n++
            upsert(WalletRow(id, "Wallet $n", "", now + n))
        }
    }
}

package com.nzm.wallet.chain

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chains")
data class Chain(
    @PrimaryKey val chainId: Long,
    val name: String,
    val rpcUrl: String,
    val symbol: String,
    val decimals: Int = 18,
    val explorerUrl: String? = null,
    val isTestnet: Boolean = false
)

@Dao
interface ChainDao {
    @Query("SELECT * FROM chains ORDER BY isTestnet, name")
    fun observeAll(): Flow<List<Chain>>

    @Query("SELECT COUNT(*) FROM chains")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chain: Chain)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chains: List<Chain>)

    @Query("DELETE FROM chains WHERE chainId = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM chains WHERE chainId = :id")
    suspend fun get(id: Long): Chain?
}

@Database(entities = [Chain::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chainDao(): ChainDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "wallet.db")
                .build().also { instance = it }
        }
    }
}
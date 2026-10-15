package com.nzm.wallet.chain

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Entity(tableName = "tokens", primaryKeys = ["chainId", "address"])
data class Token(
    val chainId: Long,
    val address: String, // EIP-55 checksummed
    val symbol: String,
    val name: String,
    val decimals: Int
)

@Dao
interface ChainDao {
    @Query("SELECT * FROM chains ORDER BY isTestnet, name")
    fun observeAll(): Flow<List<Chain>>

    @Query("SELECT * FROM chains WHERE chainId = :id")
    suspend fun get(id: Long): Chain?

    @Query("SELECT COUNT(*) FROM chains")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chain: Chain)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chains: List<Chain>)

    @Query("DELETE FROM chains WHERE chainId = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TokenDao {
    @Query("SELECT * FROM tokens WHERE chainId = :chainId ORDER BY symbol")
    fun observe(chainId: Long): Flow<List<Token>>

    @Query("SELECT * FROM tokens WHERE chainId = :chainId AND address = :address")
    suspend fun get(chainId: Long, address: String): Token?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(token: Token)

    @Query("DELETE FROM tokens WHERE chainId = :chainId AND address = :address")
    suspend fun delete(chainId: Long, address: String)

    @Query("DELETE FROM tokens WHERE chainId = :chainId")
    suspend fun deleteAllFor(chainId: Long)
}

@Database(entities = [Chain::class, Token::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chainDao(): ChainDao
    abstract fun tokenDao(): TokenDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tokens` (" +
                            "`chainId` INTEGER NOT NULL, `address` TEXT NOT NULL, " +
                            "`symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `decimals` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`chainId`, `address`))"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "wallet.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
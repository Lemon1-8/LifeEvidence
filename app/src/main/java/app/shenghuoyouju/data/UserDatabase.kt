package app.shenghuoyouju.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history") data class HistoryRow(@PrimaryKey val query: String, val usedAt: Long)
@Entity(tableName = "favorites") data class FavoriteRow(@PrimaryKey val id: String, val documentJson: String, val sourceCommit: String, val savedAt: Long)
@Entity(tableName = "active_snapshot") data class ActiveSnapshotRow(@PrimaryKey val slot: Int = 1, val directory: String, val sourceCommit: String, val modelHash: String)
@Dao interface UserDao {
    @Query("SELECT * FROM history ORDER BY usedAt DESC LIMIT 50") fun history(): Flow<List<HistoryRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveHistory(row: HistoryRow)
    @Query("DELETE FROM history WHERE query=:query") suspend fun deleteHistory(query: String)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM history WHERE query NOT IN (SELECT query FROM history ORDER BY usedAt DESC LIMIT 50)") suspend fun trimHistory()
    @Query("SELECT * FROM favorites ORDER BY savedAt DESC") fun favorites(): Flow<List<FavoriteRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveFavorite(row: FavoriteRow)
    @Query("DELETE FROM favorites WHERE id=:id") suspend fun deleteFavorite(id: String)
    @Query("SELECT * FROM active_snapshot WHERE slot=1") suspend fun activeSnapshot(): ActiveSnapshotRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun activate(row: ActiveSnapshotRow)
}
@Database(entities = [HistoryRow::class, FavoriteRow::class, ActiveSnapshotRow::class], version = 1, exportSchema = false)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    companion object {
        @Volatile private var instance: UserDatabase? = null
        fun get(context: Context): UserDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, UserDatabase::class.java, "life-evidence.db").build().also { instance = it }
        }
    }
}

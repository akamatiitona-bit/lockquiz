package com.example.lockquiz

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "words")
data class Word(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val english: String,          // 例: "freeze / froze / frozen"
    val japanese: String,         // 例: "尋ねる/言う"
    val category: String = "",    // 例: "Drill 1 (1-20)"
)

@Dao
interface WordDao {
    @Insert suspend fun insertAll(words: List<Word>)
    @Query("SELECT * FROM words") suspend fun all(): List<Word>
    @Query("SELECT COUNT(*) FROM words") suspend fun count(): Int
    @Query("DELETE FROM words") suspend fun clear()
}

@Database(entities = [Word::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun words(): WordDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "lockquiz.db")
                .build().also { instance = it }
        }
    }
}

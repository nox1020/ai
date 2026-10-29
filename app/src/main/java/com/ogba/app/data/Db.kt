package com.ogba.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class Bot(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val systemPrompt: String)
@Entity data class Message(@PrimaryKey(autoGenerate = true) val id: Long = 0, val botId: Long, val role: String, val content: String, val ts: Long = System.currentTimeMillis())
@Entity data class Memory(@PrimaryKey(autoGenerate = true) val id: Long = 0, val botId: Long, val text: String)
@Entity data class Note(@PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String, val ts: Long = System.currentTimeMillis())
@Entity data class Routine(@PrimaryKey(autoGenerate = true) val id: Long = 0, val botId: Long, val name: String, val prompt: String, val intervalMinutes: Long, val enabled: Boolean = true)

@Dao interface AppDao {
    @Query("SELECT * FROM Bot ORDER BY id") fun bots(): Flow<List<Bot>>
    @Query("SELECT * FROM Bot WHERE id=:id") suspend fun bot(id: Long): Bot?
    @Query("SELECT COUNT(*) FROM Bot") suspend fun botCount(): Int
    @Upsert suspend fun upsertBot(b: Bot): Long
    @Delete suspend fun deleteBot(b: Bot)

    @Query("SELECT * FROM Message WHERE botId=:botId ORDER BY id") fun messages(botId: Long): Flow<List<Message>>
    @Query("SELECT * FROM (SELECT * FROM Message WHERE botId=:botId ORDER BY id DESC LIMIT :n) ORDER BY id") suspend fun lastMessages(botId: Long, n: Int): List<Message>
    @Insert suspend fun insertMessage(m: Message): Long
    @Query("DELETE FROM Message WHERE botId=:botId") suspend fun clearChat(botId: Long)

    @Query("SELECT * FROM Memory WHERE botId=:botId ORDER BY id") fun memories(botId: Long): Flow<List<Memory>>
    @Query("SELECT * FROM Memory WHERE botId=:botId ORDER BY id") suspend fun memoriesNow(botId: Long): List<Memory>
    @Insert suspend fun insertMemory(m: Memory)
    @Delete suspend fun deleteMemory(m: Memory)

    @Query("SELECT * FROM Note ORDER BY id DESC") fun notes(): Flow<List<Note>>
    @Query("SELECT * FROM Note ORDER BY id DESC") suspend fun notesNow(): List<Note>
    @Insert suspend fun insertNote(n: Note)
    @Delete suspend fun deleteNote(n: Note)

    @Query("SELECT * FROM Routine ORDER BY id") fun routines(): Flow<List<Routine>>
    @Query("SELECT * FROM Routine WHERE id=:id") suspend fun routine(id: Long): Routine?
    @Insert suspend fun insertRoutine(r: Routine): Long
    @Delete suspend fun deleteRoutine(r: Routine)
}

@Database(entities = [Bot::class, Message::class, Memory::class, Note::class, Routine::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "ogba.db").build().also { inst = it }
        }
    }
}

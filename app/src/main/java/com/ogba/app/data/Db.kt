package com.ogba.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class Bot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: String = "AI Assistant",
    val description: String = "",
    val avatar: String = "🤖",
    val accentColor: String = "#3b82f6",
    val systemPrompt: String,
    val model: String = "",           // GGUF file name; empty = default model
    val tools: String = "",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
/** sender: user | bot | error  (mirrors open-grok-bot message.sender) */
@Entity data class Message(@PrimaryKey(autoGenerate = true) val id: Long = 0, val botId: Long, val role: String, val content: String, val model: String = "", val ts: Long = System.currentTimeMillis())
@Entity data class Memory(@PrimaryKey(autoGenerate = true) val id: Long = 0, val botId: Long, val text: String)
@Entity data class Note(@PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String, val ts: Long = System.currentTimeMillis())
@Entity data class Routine(@PrimaryKey(autoGenerate = true) val id: Long = 0, val botId: Long, val name: String, val prompt: String, val intervalMinutes: Long, val enabled: Boolean = true)
@Entity data class AuditEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val botId: Long, val type: String, val tool: String, val summary: String, val detail: String = "", val ts: Long = System.currentTimeMillis())

@Dao interface AppDao {
    @Query("SELECT * FROM Bot ORDER BY pinned DESC, id") fun bots(): Flow<List<Bot>>
    @Query("SELECT * FROM Bot WHERE id=:id") suspend fun bot(id: Long): Bot?
    @Query("SELECT COUNT(*) FROM Bot") suspend fun botCount(): Int
    @Insert suspend fun insertBot(b: Bot): Long
    @Update suspend fun updateBot(b: Bot)
    @Delete suspend fun deleteBot(b: Bot)

    @Query("SELECT * FROM Message WHERE botId=:botId ORDER BY id") fun messages(botId: Long): Flow<List<Message>>
    @Query("SELECT * FROM (SELECT * FROM Message WHERE botId=:botId AND role!='error' ORDER BY id DESC LIMIT :n) ORDER BY id") suspend fun lastMessages(botId: Long, n: Int): List<Message>
    @Query("SELECT * FROM Message WHERE id IN (SELECT MAX(id) FROM Message GROUP BY botId)") fun lastPerBot(): Flow<List<Message>>
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

    @Query("SELECT * FROM AuditEvent ORDER BY id DESC LIMIT 200") fun audit(): Flow<List<AuditEvent>>
    @Insert suspend fun insertAudit(e: AuditEvent)
}

@Database(entities = [Bot::class, Message::class, Memory::class, Note::class, Routine::class, AuditEvent::class], version = 2)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "ogba.db")
                .fallbackToDestructiveMigration().build().also { inst = it }
        }
    }
}

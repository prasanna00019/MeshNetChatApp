package com.appsv.nearbyapi.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val msgId: String,
    val senderId: String,
    val recipientId: String,
    val messageType: String, // TEXT, IMAGE, KEY, PRESENCE
    val messageText: String,
    val timestamp: Long,
    val isDeleted: Boolean = false,
    val deletedForEveryone: Boolean = false
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val userId: String,
    val userName: String,
    val lastMessageText: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int = 0
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE (senderId = :userId OR recipientId = :userId OR recipientId = 'BROADCAST') AND isDeleted = 0 ORDER BY timestamp ASC")
    suspend fun getMessagesForUser(userId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE msgId = :msgId")
    suspend fun getMessageById(msgId: String): MessageEntity?

    @Query("UPDATE messages SET isDeleted = 1 WHERE msgId = :msgId")
    suspend fun deleteMessageForMe(msgId: String)

    @Query("UPDATE messages SET isDeleted = 1, deletedForEveryone = 1 WHERE msgId = :msgId")
    suspend fun deleteMessageForEveryone(msgId: String)

    @Query("SELECT * FROM messages WHERE msgId IN (:msgIds)")
    suspend fun getMessagesByIds(msgIds: List<String>): List<MessageEntity>

    @Query("DELETE FROM messages WHERE msgId = :msgId")
    suspend fun permanentlyDeleteMessage(msgId: String)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    suspend fun getAllMessages(): List<MessageEntity>
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE userId = :userId")
    suspend fun getConversation(userId: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE userId = :userId")
    suspend fun deleteConversation(userId: String)
}

@Database(
    entities = [MessageEntity::class, ConversationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getDatabase(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "chat_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
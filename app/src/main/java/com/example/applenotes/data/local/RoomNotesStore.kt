package com.example.applenotes.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.withTransaction
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "note_summary")
data class NoteSummaryEntity(
    @PrimaryKey val recordName: String,
    val title: String?,
    val snippet: String?,
    @ColumnInfo(name = "modified_ms") val modificationTimestampMs: Long?,
    val deleted: Boolean,
    @ColumnInfo(name = "folder") val folderRecordName: String?,
) {
    fun toSummary() = NoteSummary(recordName, title, snippet, modificationTimestampMs, deleted, folderRecordName)

    companion object {
        fun of(n: NoteSummary) = NoteSummaryEntity(
            n.recordName, n.title, n.snippet, n.modificationTimestampMs, n.deleted, n.folderRecordName,
        )
    }
}

@Entity(tableName = "folder")
data class FolderEntity(
    @PrimaryKey val recordName: String,
    val displayName: String?,
    val isTrash: Boolean,
    val isDefault: Boolean,
) {
    fun toSummary() = FolderSummary(recordName, displayName, isTrash, isDefault)

    companion object {
        fun of(f: FolderSummary) = FolderEntity(f.recordName, f.displayName, f.isTrash, f.isDefault)
    }
}

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)

/** The full CloudKit record of a note the user opened, for reading it offline. */
@Entity(tableName = "note_body")
data class NoteBodyEntity(
    @PrimaryKey val recordName: String,
    @ColumnInfo(name = "record_json") val recordJson: String,
    /** Visible text, for searching opened notes' bodies. */
    @ColumnInfo(name = "visible_text") val visibleText: String?,
    @ColumnInfo(name = "accessed_ms") val accessedMs: Long,
)

@Entity(tableName = "pending_draft")
data class PendingDraftEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "base_record_json") val baseRecordJson: String?,
    @ColumnInfo(name = "folder_ref_json") val folderRefJson: String?,
    @ColumnInfo(name = "full_text") val fullText: String,
    @ColumnInfo(name = "spans_signature") val spansSignature: String,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,
    @ColumnInfo(name = "updated_ms") val updatedAtMs: Long,
    val conflict: Boolean,
)

internal fun PendingDraftEntity.toDraft() = PendingDraft(
    key = key,
    baseRecord = StoreCodec.decodeRecord(baseRecordJson),
    folderRefJson = folderRefJson,
    fullText = fullText,
    spansSignature = spansSignature,
    snapshotJson = snapshotJson,
    updatedAtMs = updatedAtMs,
    conflict = conflict,
)

internal fun PendingDraft.toEntity() = PendingDraftEntity(
    key = key,
    baseRecordJson = baseRecord?.let { StoreCodec.encodeRecord(it) },
    folderRefJson = folderRefJson,
    fullText = fullText,
    spansSignature = spansSignature,
    snapshotJson = snapshotJson,
    updatedAtMs = updatedAtMs,
    conflict = conflict,
)

@Entity(tableName = "created_draft")
data class CreatedDraftEntity(@PrimaryKey val draftId: String, val recordName: String)

@Dao
interface NotesDao {
    @Query("SELECT * FROM note_summary")
    suspend fun summaries(): List<NoteSummaryEntity>

    @Upsert
    suspend fun upsertSummaries(rows: List<NoteSummaryEntity>)

    @Query("DELETE FROM note_summary WHERE recordName IN (:names)")
    suspend fun deleteSummaries(names: List<String>)

    @Query("SELECT * FROM folder")
    suspend fun folders(): List<FolderEntity>

    @Upsert
    suspend fun upsertFolders(rows: List<FolderEntity>)

    @Query("DELETE FROM folder WHERE recordName IN (:names)")
    suspend fun deleteFolders(names: List<String>)

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Upsert
    suspend fun putMeta(row: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun deleteMeta(key: String)

    @Query("SELECT * FROM note_body WHERE recordName = :name")
    suspend fun body(name: String): NoteBodyEntity?

    @Upsert
    suspend fun putBody(row: NoteBodyEntity)

    @Query(
        "DELETE FROM note_body WHERE recordName NOT IN " +
            "(SELECT recordName FROM note_body ORDER BY accessed_ms DESC LIMIT :keep)",
    )
    suspend fun trimBodies(keep: Int)

    @Query("DELETE FROM note_body WHERE recordName IN (:names)")
    suspend fun deleteBodies(names: List<String>)

    /** [pattern] is a LIKE pattern with '\' as the escape character. */
    @Query("SELECT recordName FROM note_body WHERE visible_text LIKE :pattern ESCAPE '\\' LIMIT :limit")
    suspend fun searchBodies(pattern: String, limit: Int): List<String>

    @Query("SELECT * FROM pending_draft WHERE `key` = :key")
    suspend fun draft(key: String): PendingDraftEntity?

    @Upsert
    suspend fun putDraft(row: PendingDraftEntity)

    @Query("DELETE FROM pending_draft WHERE `key` = :key")
    suspend fun deleteDraft(key: String)

    @Query("SELECT * FROM pending_draft ORDER BY updated_ms")
    suspend fun drafts(): List<PendingDraftEntity>

    @Query("SELECT * FROM pending_draft ORDER BY updated_ms")
    fun observeDrafts(): Flow<List<PendingDraftEntity>>

    @Query("SELECT recordName FROM created_draft WHERE draftId = :draftId")
    suspend fun createdRecordName(draftId: String): String?

    @Upsert
    suspend fun putCreated(row: CreatedDraftEntity)

    @Query("DELETE FROM note_summary")
    suspend fun clearSummaries()

    @Query("DELETE FROM folder")
    suspend fun clearFolders()

    @Query("DELETE FROM meta")
    suspend fun clearMeta()

    @Query("DELETE FROM note_body")
    suspend fun clearBodies()

    @Query("DELETE FROM pending_draft")
    suspend fun clearDrafts()

    @Query("DELETE FROM created_draft")
    suspend fun clearCreated()

    @Transaction
    suspend fun clearAll() {
        clearSummaries()
        clearFolders()
        clearMeta()
        clearBodies()
        clearDrafts()
        clearCreated()
    }
}

/**
 * Schema changes need a real Migration (schemas are exported to
 * app/schemas): never fall back to a destructive migration, which would
 * delete unsent drafts.
 */
@Database(
    entities = [
        NoteSummaryEntity::class,
        FolderEntity::class,
        MetaEntity::class,
        NoteBodyEntity::class,
        PendingDraftEntity::class,
        CreatedDraftEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun dao(): NotesDao

    companion object {
        const val NAME = "notes-cache.db"

        fun open(context: Context): NotesDatabase =
            Room.databaseBuilder(context.applicationContext, NotesDatabase::class.java, NAME).build()
    }
}

/** [NotesStore] on the app's Room database. */
class RoomNotesStore(private val db: NotesDatabase) : NotesStore {
    private val dao = db.dao()

    override suspend fun loadSummaries() = dao.summaries().map { it.toSummary() }

    override suspend fun upsertSummaries(notes: Collection<NoteSummary>) {
        // Large first syncs arrive a page at a time; chunk to stay well under
        // SQLite's bound-variable limit for the DELETE ... IN queries too.
        notes.chunked(CHUNK).forEach { chunk -> dao.upsertSummaries(chunk.map(NoteSummaryEntity::of)) }
    }

    override suspend fun deleteSummaries(recordNames: Collection<String>) {
        recordNames.chunked(CHUNK).forEach { dao.deleteSummaries(it) }
    }

    override suspend fun loadFolders() = dao.folders().map { it.toSummary() }

    override suspend fun upsertFolders(folders: Collection<FolderSummary>) {
        dao.upsertFolders(folders.map(FolderEntity::of))
    }

    override suspend fun deleteFolders(recordNames: Collection<String>) {
        recordNames.chunked(CHUNK).forEach { dao.deleteFolders(it) }
    }

    override suspend fun getMeta(key: String) = dao.meta(key)

    override suspend fun setMeta(key: String, value: String?) {
        if (value == null) dao.deleteMeta(key) else dao.putMeta(MetaEntity(key, value))
    }

    override suspend fun getBody(recordName: String): NoteRecord? =
        StoreCodec.decodeRecord(dao.body(recordName)?.recordJson)

    override suspend fun putBody(record: NoteRecord, visibleText: String?, nowMs: Long) {
        db.withTransaction {
            dao.putBody(NoteBodyEntity(record.recordName, StoreCodec.encodeRecord(record), visibleText, nowMs))
            dao.trimBodies(MAX_CACHED_BODIES)
        }
    }

    override suspend fun deleteBodies(recordNames: Collection<String>) {
        recordNames.chunked(CHUNK).forEach { dao.deleteBodies(it) }
    }

    override suspend fun searchBodies(query: String, limit: Int): List<String> =
        dao.searchBodies(likePattern(query), limit)

    override suspend fun getDraft(key: String) = dao.draft(key)?.toDraft()
    override suspend fun putDraft(draft: PendingDraft) = dao.putDraft(draft.toEntity())
    override suspend fun deleteDraft(key: String) = dao.deleteDraft(key)
    override suspend fun allDrafts() = dao.drafts().map { it.toDraft() }
    override fun observeDrafts(): Flow<List<PendingDraft>> = dao.observeDrafts().map { rows -> rows.map { it.toDraft() } }

    override suspend fun createdRecordName(draftId: String) = dao.createdRecordName(draftId)
    override suspend fun putCreatedRecordName(draftId: String, recordName: String) =
        dao.putCreated(CreatedDraftEntity(draftId, recordName))

    override suspend fun clearAll() = dao.clearAll()

    private companion object {
        const val CHUNK = 500
    }
}

/** A case-insensitive SQLite LIKE pattern matching [query] anywhere, with `%`, `_` and `\` escaped. */
internal fun likePattern(query: String): String = buildString {
    append('%')
    for (c in query) {
        if (c == '%' || c == '_' || c == '\\') append('\\')
        append(c)
    }
    append('%')
}

package com.deepak.videolibrary.artwork

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * One row per item that has stored artwork information. This is its own small database (one per library)
 * so the main library schema and its migration history stay untouched; rows are keyed by the same identity
 * strings the folder screens use, and by video id for videos.
 */
@Entity(tableName = "artwork")
data class ArtworkRow(
    @PrimaryKey val key: String,
    val scope: String,
    val mode: String,
    val manual: Boolean,
    val imageName: String?,
    val providerId: String?,
    val performerId: String?,
    val performerName: String?,
    val lookupStatus: String,
    val updatedAt: Long,
) {
    fun toModel(): ArtworkOverride = ArtworkOverride(
        key = key,
        mode = runCatching { ArtworkMode.valueOf(mode) }.getOrDefault(ArtworkMode.EXISTING),
        manual = manual,
        imageName = imageName,
        providerId = providerId,
        performerId = performerId,
        performerName = performerName,
        lookupStatus = lookupStatus,
        updatedAt = updatedAt,
    )

    companion object {
        fun of(model: ArtworkOverride) = ArtworkRow(
            key = model.key,
            scope = ArtworkKeys.scopeOf(model.key).name,
            mode = model.mode.name,
            manual = model.manual,
            imageName = model.imageName,
            providerId = model.providerId,
            performerId = model.performerId,
            performerName = model.performerName,
            lookupStatus = model.lookupStatus,
            updatedAt = model.updatedAt,
        )
    }
}

/** A suggestion found for a folder, kept until the user picks one or skips the folder. */
@Entity(tableName = "artwork_candidates", primaryKeys = ["key", "providerId", "performerId"])
data class ArtworkCandidateRow(
    val key: String,
    val providerId: String,
    val performerId: String,
    val providerName: String,
    val name: String,
    val disambiguation: String?,
    val imageUrl: String?,
    val previewUrl: String?,
    val position: Int,
)

@Dao
interface ArtworkDao {
    @Query("SELECT * FROM artwork")
    suspend fun all(): List<ArtworkRow>

    @Upsert
    suspend fun upsert(row: ArtworkRow)

    @Query("DELETE FROM artwork WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM artwork WHERE `key` IN (:keys)")
    suspend fun deleteAll(keys: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(rows: List<ArtworkCandidateRow>)

    @Query("SELECT * FROM artwork_candidates WHERE `key` = :key ORDER BY position")
    suspend fun candidates(key: String): List<ArtworkCandidateRow>

    @Query("DELETE FROM artwork_candidates WHERE `key` IN (:keys)")
    suspend fun deleteCandidates(keys: List<String>)

    /** Folders waiting for the user to pick a suggestion. */
    @Query("SELECT * FROM artwork WHERE manual = 0 AND imageName IS NULL AND lookupStatus IN ('REVIEW', 'AMBIGUOUS') ORDER BY `key`")
    fun observeReview(): kotlinx.coroutines.flow.Flow<List<ArtworkRow>>
}

@Database(entities = [ArtworkRow::class, ArtworkCandidateRow::class], version = 1, exportSchema = false)
abstract class ArtworkDatabase : RoomDatabase() {
    abstract fun dao(): ArtworkDao
}

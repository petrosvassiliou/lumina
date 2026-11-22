package app.lumina.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.lumina.player.data.local.entity.MediaItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query("SELECT * FROM media_items WHERE is_network_stream = 0 ORDER BY date_added_epoch_sec DESC")
    fun observeLocalMedia(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE is_network_stream = 1 ORDER BY date_added_epoch_sec DESC")
    fun observeNetworkStreams(): Flow<List<MediaItemEntity>>

    @Query(
        """
        SELECT m.*
        FROM media_items m
        INNER JOIN playback_states ps ON ps.media_uri = m.uri
        WHERE m.last_played_at IS NOT NULL
          AND m.mime_type NOT LIKE 'audio/%'
          AND ps.completed = 0
          AND ps.position_ms > 0
        ORDER BY m.last_played_at DESC
        LIMIT :limit
        """
    )
    fun observeContinueWatching(limit: Int = 20): Flow<List<MediaItemEntity>>

    @Query(
        """
        SELECT * FROM media_items
        WHERE is_network_stream = 0
          AND (title LIKE '%' || :query || '%'
            OR folder_name LIKE '%' || :query || '%'
            OR COALESCE(hdr_badge, '') LIKE '%' || :query || '%')
        ORDER BY COALESCE(last_played_at, 0) DESC, date_added_epoch_sec DESC
        """
    )
    fun searchMedia(query: String): Flow<List<MediaItemEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: MediaItemEntity): Long

    @Query(
        """
        UPDATE media_items
        SET title = :title,
            folder_name = :folderName,
            mime_type = :mimeType,
            duration_ms = CASE WHEN :durationMs > 0 THEN :durationMs ELSE duration_ms END,
            size_bytes = CASE WHEN :sizeBytes > 0 THEN :sizeBytes ELSE size_bytes END,
            width = CASE WHEN :width > 0 THEN :width ELSE width END,
            height = CASE WHEN :height > 0 THEN :height ELSE height END,
            hdr_badge = COALESCE(hdr_badge, :hdrBadge),
            video_codec = COALESCE(video_codec, :videoCodec),
            is_network_stream = :isNetworkStream,
            network_protocol = COALESCE(:networkProtocol, network_protocol),
            date_added_epoch_sec = CASE WHEN :dateAddedEpochSec > 0 THEN :dateAddedEpochSec ELSE date_added_epoch_sec END,
            last_played_at = COALESCE(:lastPlayedAt, last_played_at)
        WHERE uri = :uri
        """
    )
    suspend fun updatePreservingUserState(
        uri: String,
        title: String,
        folderName: String,
        mimeType: String,
        durationMs: Long,
        sizeBytes: Long,
        width: Int,
        height: Int,
        hdrBadge: String?,
        videoCodec: String?,
        isNetworkStream: Boolean,
        networkProtocol: String?,
        dateAddedEpochSec: Long,
        lastPlayedAt: Long?
    )

    @Transaction
    suspend fun upsert(item: MediaItemEntity) {
        val rowId = insertIgnore(item)
        if (rowId == -1L) {
            updatePreservingUserState(
                uri = item.uri,
                title = item.title,
                folderName = item.folderName,
                mimeType = item.mimeType,
                durationMs = item.durationMs,
                sizeBytes = item.sizeBytes,
                width = item.width,
                height = item.height,
                hdrBadge = item.hdrBadge,
                videoCodec = item.videoCodec,
                isNetworkStream = item.isNetworkStream,
                networkProtocol = item.networkProtocol,
                dateAddedEpochSec = item.dateAddedEpochSec,
                lastPlayedAt = item.lastPlayedAt
            )
        }
    }

    @Transaction
    suspend fun upsertAll(items: List<MediaItemEntity>) {
        if (items.isEmpty()) return
        for (item in items) {
            upsert(item)
        }
    }

    @Query("UPDATE media_items SET last_played_at = :timestamp WHERE uri = :uri")
    suspend fun markPlayed(uri: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE media_items SET last_played_at = NULL WHERE uri = :uri")
    suspend fun clearLastPlayedAt(uri: String)

    @Query("UPDATE media_items SET hdr_badge = :hdrBadge, video_codec = :videoCodec WHERE uri = :uri")
    suspend fun updateDetectedStreamInfo(uri: String, hdrBadge: String?, videoCodec: String?)

    @Query("UPDATE media_items SET is_favorite = :favorite WHERE uri = :uri")
    suspend fun setFavorite(uri: String, favorite: Boolean)

    @Query("SELECT uri FROM media_items WHERE is_network_stream = 0 AND uri LIKE 'content://media/%'")
    suspend fun getIndexedMediaStoreUris(): List<String>

    @Query("DELETE FROM media_items WHERE is_network_stream = 0 AND uri IN (:staleUris)")
    suspend fun deleteMediaByUris(staleUris: List<String>)

    @Query("DELETE FROM media_items WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}


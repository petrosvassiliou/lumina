package app.lumina.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.lumina.player.data.local.entity.MediaItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query("SELECT * FROM media_items WHERE is_network_stream = 0 ORDER BY date_added_epoch_sec DESC")
    fun observeLocalMedia(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE is_network_stream = 1 ORDER BY date_added_epoch_sec DESC")
    fun observeNetworkStreams(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE last_played_at IS NOT NULL ORDER BY last_played_at DESC LIMIT :limit")
    fun observeContinueWatching(limit: Int = 20): Flow<List<MediaItemEntity>>

    @Query(
        """
        SELECT * FROM media_items
        WHERE title LIKE '%' || :query || '%'
           OR folder_name LIKE '%' || :query || '%'
           OR COALESCE(hdr_badge, '') LIKE '%' || :query || '%'
        ORDER BY COALESCE(last_played_at, 0) DESC, date_added_epoch_sec DESC
        """
    )
    fun searchMedia(query: String): Flow<List<MediaItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaItemEntity)

    @Query("UPDATE media_items SET last_played_at = :timestamp WHERE uri = :uri")
    suspend fun markPlayed(uri: String, timestamp: Long = System.currentTimeMillis())

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

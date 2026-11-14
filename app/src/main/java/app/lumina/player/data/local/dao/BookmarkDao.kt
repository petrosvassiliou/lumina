package app.lumina.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.lumina.player.data.local.entity.VideoBookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM video_bookmarks WHERE media_uri = :mediaUri ORDER BY position_ms ASC, id ASC")
    fun observeBookmarksForMedia(mediaUri: String): Flow<List<VideoBookmarkEntity>>

    @Query("SELECT * FROM video_bookmarks WHERE media_uri = :mediaUri ORDER BY position_ms ASC, id ASC")
    suspend fun getBookmarksForMedia(mediaUri: String): List<VideoBookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: VideoBookmarkEntity): Long

    @Query("DELETE FROM video_bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmarkById(bookmarkId: Long)

    @Query("DELETE FROM video_bookmarks WHERE media_uri = :mediaUri")
    suspend fun clearBookmarksForMedia(mediaUri: String)
}

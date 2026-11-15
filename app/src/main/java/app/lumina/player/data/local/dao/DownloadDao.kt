package app.lumina.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.lumina.player.data.local.entity.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM stream_downloads ORDER BY created_at_epoch_ms DESC")
    fun observeAllDownloads(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM stream_downloads ORDER BY created_at_epoch_ms ASC")
    suspend fun getAllDownloadsChronological(): List<DownloadTaskEntity>

    @Query("SELECT * FROM stream_downloads WHERE id = :id LIMIT 1")
    suspend fun getDownloadById(id: Long): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(entity: DownloadTaskEntity): Long

    @Update
    suspend fun updateDownload(entity: DownloadTaskEntity)

    @Query("DELETE FROM stream_downloads WHERE id = :id")
    suspend fun deleteDownloadById(id: Long)

    @Query("DELETE FROM stream_downloads WHERE status IN ('COMPLETED', 'CANCELED')")
    suspend fun clearCompletedDownloads()
}

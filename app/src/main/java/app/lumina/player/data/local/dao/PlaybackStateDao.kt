package app.lumina.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.lumina.player.data.local.entity.PlaybackStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackStateDao {

    @Query("SELECT * FROM playback_states")
    fun observeAllPlaybackStates(): Flow<List<PlaybackStateEntity>>

    @Query("SELECT * FROM playback_states WHERE media_uri = :mediaUri LIMIT 1")
    suspend fun getPlaybackState(mediaUri: String): PlaybackStateEntity?

    @Query("SELECT * FROM playback_states WHERE media_uri = :mediaUri LIMIT 1")
    fun observePlaybackState(mediaUri: String): Flow<PlaybackStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackState(state: PlaybackStateEntity)

    @Query("DELETE FROM playback_states WHERE media_uri = :mediaUri")
    suspend fun clearPlaybackState(mediaUri: String)
}

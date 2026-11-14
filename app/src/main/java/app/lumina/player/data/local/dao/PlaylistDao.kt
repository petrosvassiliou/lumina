package app.lumina.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.lumina.player.data.local.entity.PlaylistEntity
import app.lumina.player.data.local.entity.PlaylistEntryEntity
import app.lumina.player.data.local.entity.PlaylistSummaryRow
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT p.id AS id,
               p.name AS name,
               p.updated_at_epoch_ms AS updated_at_epoch_ms,
               COUNT(e.id) AS item_count,
               COALESCE(SUM(e.duration_ms), 0) AS total_duration_ms
        FROM playlists p
        LEFT JOIN playlist_entries e ON e.playlist_id = p.id
        GROUP BY p.id
        ORDER BY p.updated_at_epoch_ms DESC
        """
    )
    fun observePlaylistSummaries(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :playlistId ORDER BY position_index ASC, id ASC")
    fun observePlaylistEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :playlistId ORDER BY position_index ASC, id ASC")
    suspend fun getPlaylistEntries(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: PlaylistEntryEntity): Long

    @Query("SELECT COALESCE(MAX(position_index), -1) + 1 FROM playlist_entries WHERE playlist_id = :playlistId")
    suspend fun getNextPositionIndex(playlistId: Long): Int

    @Query("UPDATE playlists SET name = :newName, updated_at_epoch_ms = :updatedAt WHERE id = :playlistId")
    suspend fun renamePlaylist(
        playlistId: Long,
        newName: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE playlists SET updated_at_epoch_ms = :updatedAt WHERE id = :playlistId")
    suspend fun touchPlaylist(playlistId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_entries WHERE id = :entryId")
    suspend fun deletePlaylistEntry(entryId: Long)

    @Transaction
    suspend fun appendItemToPlaylist(playlistId: Long, entryTemplate: PlaylistEntryEntity) {
        val nextIdx = getNextPositionIndex(playlistId)
        insertEntry(entryTemplate.copy(playlistId = playlistId, positionIndex = nextIdx))
        touchPlaylist(playlistId)
    }

    @Transaction
    suspend fun createPlaylistWithEntries(name: String, templates: List<PlaylistEntryEntity>): Long {
        val now = System.currentTimeMillis()
        val playlistId = insertPlaylist(
            PlaylistEntity(
                name = name.trim().ifEmpty { "Playlist" },
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
        if (templates.isNotEmpty()) {
            insertEntries(
                templates.mapIndexed { idx, item ->
                    item.copy(id = 0L, playlistId = playlistId, positionIndex = idx)
                }
            )
        }
        return playlistId
    }
}

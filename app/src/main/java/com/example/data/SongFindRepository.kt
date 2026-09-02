package com.example.data

import com.example.model.SongFind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SongFindRepository(private val songFindDao: SongFindDao) {

    val allSongFinds: Flow<List<SongFind>> = songFindDao.getAllSongFinds().map { list ->
        list.map { it.toSongFind() }
    }

    suspend fun insert(songFind: SongFind) {
        songFindDao.insertSongFind(SongFindEntity.fromSongFind(songFind))
    }

    suspend fun update(songFind: SongFind) {
        songFindDao.updateSongFind(SongFindEntity.fromSongFind(songFind))
    }

    suspend fun deleteById(id: String) {
        songFindDao.deleteSongFindById(id)
    }

    suspend fun setCompleted(id: String, completed: Boolean) {
        songFindDao.updateCompletedState(id, completed)
    }

    suspend fun findByUrl(url: String): SongFind? {
        return songFindDao.getSongFindByUrl(url)?.toSongFind()
    }

    suspend fun existsByUrl(url: String): Boolean {
        return songFindDao.countByUrl(url) > 0
    }

    suspend fun clearCompleted() {
        songFindDao.clearCompletedFinds()
    }
}

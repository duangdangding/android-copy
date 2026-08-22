package com.clipditto.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {

    @Query("SELECT * FROM clips ORDER BY favorite DESC, timestamp DESC")
    fun observeAll(): Flow<List<ClipItem>>

    @Query("SELECT * FROM clips ORDER BY favorite DESC, timestamp DESC")
    suspend fun getAll(): List<ClipItem>

    @Query("SELECT * FROM clips ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): ClipItem?

    @Query("SELECT * FROM clips WHERE timestamp BETWEEN :start AND :end")
    suspend fun getBetween(start: Long, end: Long): List<ClipItem>

    /** 时间段内的非收藏记录（用于"保留收藏"的删除） */
    @Query("SELECT * FROM clips WHERE timestamp BETWEEN :start AND :end AND favorite = 0")
    suspend fun getBetweenNonFavorite(start: Long, end: Long): List<ClipItem>

    /** 时间段内的收藏记录数 */
    @Query("SELECT COUNT(*) FROM clips WHERE timestamp BETWEEN :start AND :end AND favorite = 1")
    suspend fun countFavoritesBetween(start: Long, end: Long): Int

    @Insert
    suspend fun insert(item: ClipItem): Long

    @Update
    suspend fun update(item: ClipItem)

    /** 查找相同文本的记录（去重用） */
    @Query("SELECT * FROM clips WHERE type = :type AND text = :text LIMIT 1")
    suspend fun findByText(type: Int, text: String): ClipItem?

    /** 某类型的全部记录（媒体去重时逐个比对文件大小） */
    @Query("SELECT * FROM clips WHERE type = :type")
    suspend fun getByType(type: Int): List<ClipItem>

    /** 更新时间戳：把相同内容的记录顶到最前，收藏状态不变 */
    @Query("UPDATE clips SET timestamp = :ts WHERE id = :id")
    suspend fun touchTimestamp(id: Long, ts: Long)

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clips WHERE timestamp BETWEEN :start AND :end")
    suspend fun deleteBetween(start: Long, end: Long)

    /** 删除时间段内的非收藏记录（保留收藏） */
    @Query("DELETE FROM clips WHERE timestamp BETWEEN :start AND :end AND favorite = 0")
    suspend fun deleteBetweenNonFavorite(start: Long, end: Long)

    @Query("DELETE FROM clips")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM clips")
    suspend fun count(): Int

    /** 最旧的 n 条非收藏记录（用于超出上限时清理） */
    @Query("SELECT * FROM clips WHERE favorite = 0 ORDER BY timestamp ASC LIMIT :count")
    suspend fun oldestNonFavorite(count: Int): List<ClipItem>

    /** 局域网同步：比指定时间新的记录（增量拉取用） */
    @Query("SELECT * FROM clips WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getSince(since: Long): List<ClipItem>

    @Query("SELECT * FROM clips WHERE id = :id")
    suspend fun getById(id: Long): ClipItem?

    /** 局域网同步：来自某设备的全部记录 */
    @Query("SELECT * FROM clips WHERE remoteDeviceId = :deviceId")
    suspend fun getByRemoteDevice(deviceId: String): List<ClipItem>

    @Query("DELETE FROM clips WHERE remoteDeviceId = :deviceId")
    suspend fun deleteByRemoteDevice(deviceId: String)
}

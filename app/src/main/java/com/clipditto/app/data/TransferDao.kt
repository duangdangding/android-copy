package com.clipditto.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    /** 全部传送记录，最新在前 */
    @Query("SELECT * FROM transfer_records ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransferRecord>>

    @Insert
    suspend fun insert(record: TransferRecord): Long

    @Query("SELECT * FROM transfer_records WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TransferRecord>

    @Query("DELETE FROM transfer_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

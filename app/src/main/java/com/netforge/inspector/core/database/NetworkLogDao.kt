package com.netforge.inspector.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * File 10 — DAO for the traffic buffer.
 *
 * `upsert` supports the two-phase capture flow (request seen -> response arrives)
 * while keeping one row per requestId.
 */
@Dao
interface NetworkLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(packets: List<NetworkLogEntity>)

    @Upsert
    suspend fun upsert(packet: NetworkLogEntity)

    @Query("SELECT * FROM network_logs ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<NetworkLogEntity>>

    @Query("SELECT * FROM network_logs WHERE request_id = :requestId LIMIT 1")
    suspend fun getById(requestId: String): NetworkLogEntity?

    @Query("SELECT * FROM network_logs ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<NetworkLogEntity>

    @Query("SELECT COUNT(*) FROM network_logs")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM network_logs")
    suspend fun clearAll()

    @Query("DELETE FROM network_logs WHERE request_id = :requestId")
    suspend fun delete(requestId: String)
}

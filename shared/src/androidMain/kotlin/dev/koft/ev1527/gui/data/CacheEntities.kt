package dev.koft.ev1527.gui.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "devices")
internal data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val location: String,
    val codeOn: Long,
    val codeOff: Long,
    val rfProtocol: Int,
    val rfLength: Int,
    val rfRepeat: Int,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "device_groups")
internal data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val deviceIdsJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "schedules")
internal data class ScheduleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetType: String,
    val targetId: String,
    val action: String,
    val rrule: String,
    val timeZone: String,
    val startsAtEpochMs: Long,
    val enabled: Boolean,
    val nextRunAtEpochMs: Long?,
    val lastRunAtEpochMs: Long?,
    val lastResult: String?,
    val lastError: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "cache_meta")
internal data class CacheMetaEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val serverUrl: String,
    val lastSyncedAtEpochMs: Long,
) {
    companion object {
        const val SINGLETON_ID: Int = 1
    }
}

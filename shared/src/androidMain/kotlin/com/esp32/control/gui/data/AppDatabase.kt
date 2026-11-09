package com.esp32.control.gui.data

import androidx.room3.Database
import androidx.room3.RoomDatabase

/** Cache only: the server owns the data, so the schema can be recreated. */
@Database(
    entities = [
        DeviceEntity::class,
        GroupEntity::class,
        ScheduleEntity::class,
        CacheMetaEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}

package com.esp32.control.server.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

@Database(
    entities = [
        DeviceEntity::class,
        DeviceTombstoneEntity::class,
        IdempotencyEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    abstract fun tombstoneDao(): TombstoneDao

    abstract fun idempotencyDao(): IdempotencyDao
}

// The Room processor generates the actual implementation for every target.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

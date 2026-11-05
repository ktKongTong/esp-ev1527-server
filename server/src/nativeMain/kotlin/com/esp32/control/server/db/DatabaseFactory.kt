package com.esp32.control.server.db

import androidx.room3.Room
import androidx.room3.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext

// SQLite calls block the calling thread. Keep them off Dispatchers.Default so
// API coroutines are not starved by database work.
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
private val queryDispatcher = newFixedThreadPoolContext(4, "room-query")

// Version 1 tracked a TX echo as command confirmation. That signal cannot
// prove the actuator state, so published commands are now terminal. Keep
// historical rows by mapping them to the fire-and-forget semantics.
private val MIGRATION_1_2 = Migration(1, 2) { connection ->
    connection.execSQL("UPDATE devices SET stateEvidence = 'COMMANDED' WHERE stateEvidence = 'REPORTED'")
    connection.execSQL("UPDATE commands SET status = 'DISPATCHED' WHERE status = 'CONFIRMED'")
    connection.execSQL("ALTER TABLE commands DROP COLUMN confirmedAtEpochMs")
}

// Learning sessions are runtime state; drop the tables introduced in v1.
private val MIGRATION_2_3 = Migration(2, 3) { connection ->
    connection.execSQL("DROP TABLE IF EXISTS learning_candidates")
    connection.execSQL("DROP TABLE IF EXISTS learning_sessions")
}

// The RF link has no acknowledgement, so device state, per-device online and
// command history cannot be observed. Keep only the remote's two codes.
private val MIGRATION_3_4 = Migration(3, 4) { connection ->
    connection.execSQL("ALTER TABLE devices DROP COLUMN state")
    connection.execSQL("ALTER TABLE devices DROP COLUMN stateEvidence")
    connection.execSQL("ALTER TABLE devices DROP COLUMN online")
    connection.execSQL("ALTER TABLE devices DROP COLUMN lastActionType")
    connection.execSQL("ALTER TABLE devices DROP COLUMN lastActionAtEpochMs")
    connection.execSQL("ALTER TABLE devices DROP COLUMN lastActionCommandId")
    connection.execSQL("ALTER TABLE devices DROP COLUMN revision")
    connection.execSQL("ALTER TABLE device_tombstones DROP COLUMN revision")
    connection.execSQL("DROP TABLE IF EXISTS commands")
    connection.execSQL("ALTER TABLE idempotency DROP COLUMN commandId")
    connection.execSQL("ALTER TABLE idempotency ADD COLUMN dispatchedAtEpochMs INTEGER")
}

fun createDatabase(path: String): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryDispatcher)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()

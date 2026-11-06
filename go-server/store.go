package main

import (
	"fmt"
	"strings"

	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"
)

// The GORM models keep the Room column names so an existing server database
// can be opened without a migration step.
type deviceRow struct {
	ID               string `gorm:"column:id;primaryKey"`
	Name             string `gorm:"column:name"`
	Location         string `gorm:"column:location"`
	CodeOn           int64  `gorm:"column:codeOn"`
	CodeOff          int64  `gorm:"column:codeOff"`
	RFProtocol       int    `gorm:"column:rfProtocol"`
	RFLength         int    `gorm:"column:rfLength"`
	RFRepeat         int    `gorm:"column:rfRepeat"`
	CreatedAtEpochMs int64  `gorm:"column:createdAtEpochMs"`
}

func (deviceRow) TableName() string { return "devices" }

type deviceTombstoneRow struct {
	DeviceID         string `gorm:"column:deviceId;primaryKey"`
	RemovedAtEpochMs int64  `gorm:"column:removedAtEpochMs"`
}

func (deviceTombstoneRow) TableName() string { return "device_tombstones" }

type idempotencyRow struct {
	IdempotencyKey      string `gorm:"column:idempotencyKey;primaryKey"`
	DeviceID            string `gorm:"column:deviceId"`
	Action              string `gorm:"column:action"`
	CreatedAtEpochMs    int64  `gorm:"column:createdAtEpochMs"`
	DispatchedAtEpochMs *int64 `gorm:"column:dispatchedAtEpochMs"`
}

func (idempotencyRow) TableName() string { return "idempotency" }

func openDatabase(path string) (*gorm.DB, error) {
	dsn := path
	if path != ":memory:" && !strings.HasPrefix(path, "file:") {
		dsn = fmt.Sprintf("file:%s?_busy_timeout=5000&_journal_mode=WAL", path)
	}
	db, err := gorm.Open(sqlite.Open(dsn), &gorm.Config{
		Logger: gormlogger.Default.LogMode(gormlogger.Silent),
	})
	if err != nil {
		return nil, fmt.Errorf("open sqlite %s: %w", path, err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		return nil, fmt.Errorf("sqlite handle: %w", err)
	}
	// One writer at a time keeps SQLite transactions serialized and avoids
	// SQLITE_BUSY under the low request rate this server handles.
	sqlDB.SetMaxOpenConns(1)
	if path != ":memory:" {
		if err := db.Exec("PRAGMA busy_timeout = 5000").Error; err != nil {
			return nil, fmt.Errorf("sqlite busy_timeout: %w", err)
		}
	}
	if err := db.AutoMigrate(&deviceRow{}, &deviceTombstoneRow{}, &idempotencyRow{}); err != nil {
		return nil, fmt.Errorf("migrate sqlite: %w", err)
	}
	return db, nil
}

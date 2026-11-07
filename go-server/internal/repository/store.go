// Package repository owns the GORM row models, the Turso connection and the
// persistence operations used by the service layer.
package repository

import (
	"database/sql"
	"fmt"

	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"
	turso "turso.tech/database/tursogo-serverless"

	"esp32controlserver/internal/contract"
)

// The GORM models keep the existing table and column names so a database
// created by the previous server can be opened without a migration step.
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

// Open connects GORM to Turso and applies the versioned migrations. The GORM
// sqlite dialector supplies the SQLite SQL; it runs on the connection provided
// by the pure-Go Turso serverless driver instead of opening a local database
// file.
//
// The ":memory:" branch is reserved for tests. It uses mattn/go-sqlite3 through
// the same dialector, so tests exercise the same SQL dialect as production.
func Open(databaseURL, authToken string) (*gorm.DB, error) {
	var sqlDB *sql.DB
	if databaseURL == ":memory:" {
		var err error
		sqlDB, err = sql.Open("sqlite3", ":memory:")
		if err != nil {
			return nil, fmt.Errorf("open test sqlite: %w", err)
		}
		// A single connection keeps the in-memory database alive for the whole
		// test and serializes writers.
		sqlDB.SetMaxOpenConns(1)
	} else {
		sqlDB = sql.OpenDB(turso.NewConnector(databaseURL, authToken))
	}

	db, err := gorm.Open(sqlite.New(sqlite.Config{Conn: sqlDB}), &gorm.Config{
		Logger: gormlogger.Default.LogMode(gormlogger.Silent),
	})
	if err != nil {
		_ = sqlDB.Close()
		return nil, fmt.Errorf("open turso database: %w", err)
	}
	if err := migrate(sqlDB); err != nil {
		_ = sqlDB.Close()
		return nil, fmt.Errorf("migrate turso database: %w", err)
	}
	return db, nil
}

func (row deviceRow) toDevice() contract.Device {
	return contract.Device{
		ID:       row.ID,
		Name:     row.Name,
		Location: row.Location,
		CodeOn:   row.CodeOn,
		CodeOff:  row.CodeOff,
		RF: contract.RFParameters{
			Protocol: row.RFProtocol,
			Length:   row.RFLength,
			Repeat:   row.RFRepeat,
		},
		CreatedAtEpochMs: row.CreatedAtEpochMs,
	}
}

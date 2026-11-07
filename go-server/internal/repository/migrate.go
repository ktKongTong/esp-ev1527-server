package repository

import (
	"context"
	"database/sql"
	"embed"
	"esp32controlserver/internal/logging"
	"fmt"
	"io/fs"

	"github.com/pressly/goose/v3"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// migrate applies the embedded versioned migrations. goose records applied
// versions in goose_db_version, so schema changes are ordered and each version
// runs at most once.
func migrate(sqlDB *sql.DB) error {
	migrations, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		return fmt.Errorf("open migrations: %w", err)
	}
	provider, err := goose.NewProvider(goose.DialectSQLite3, sqlDB, migrations)
	if err != nil {
		return fmt.Errorf("create migration provider: %w", err)
	}
	results, err := provider.Up(context.Background())
	if err != nil {
		return fmt.Errorf("apply migrations: %w", err)
	}
	for _, result := range results {
		logging.Infof("migration %s", result)
	}
	return nil
}

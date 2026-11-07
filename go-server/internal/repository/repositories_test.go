package repository

import (
	"context"
	"testing"

	"esp32controlserver/internal/contract"
)

func newTestDB(t *testing.T) *DeviceRepository {
	t.Helper()
	db, err := Open(":memory:", "")
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	t.Cleanup(func() {
		if sqlDB, err := db.DB(); err == nil {
			_ = sqlDB.Close()
		}
	})
	return NewDeviceRepository(db)
}

func TestFindOwnerIgnoresProtocol(t *testing.T) {
	repo := newTestDB(t)

	value := contract.Device{
		ID:       "dev_test",
		Name:     "pump",
		Location: "pond",
		CodeOn:   1714739,
		CodeOff:  1714740,
		RF:       contract.RFParameters{Protocol: 22, Length: 24, Repeat: 5},
	}
	if err := repo.Insert(context.Background(), value); err != nil {
		t.Fatalf("insert: %v", err)
	}

	owner, err := repo.FindOwner(context.Background(), value.RF.Length, value.CodeOn)
	if err != nil {
		t.Fatalf("findOwner: %v", err)
	}
	if owner != value.ID {
		t.Fatalf("owner = %q, want %q", owner, value.ID)
	}

	owner, err = repo.FindOwner(context.Background(), value.RF.Length+1, value.CodeOn)
	if err != nil {
		t.Fatalf("findOwner wrong length: %v", err)
	}
	if owner != "" {
		t.Fatalf("owner for wrong length = %q, want empty", owner)
	}
}

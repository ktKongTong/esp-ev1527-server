package main

import (
	"context"
	"testing"
)

func TestFindOwnerIgnoresProtocol(t *testing.T) {
	db := newTestDB(t)
	repo := &deviceRepository{db: db}

	value := sampleDevice()
	value.RF.Protocol = 22
	if err := repo.insert(context.Background(), value); err != nil {
		t.Fatalf("insert: %v", err)
	}

	owner, err := repo.findOwner(context.Background(), value.RF.Length, value.CodeOn)
	if err != nil {
		t.Fatalf("findOwner: %v", err)
	}
	if owner != value.ID {
		t.Fatalf("owner = %q, want %q", owner, value.ID)
	}

	owner, err = repo.findOwner(context.Background(), value.RF.Length+1, value.CodeOn)
	if err != nil {
		t.Fatalf("findOwner wrong length: %v", err)
	}
	if owner != "" {
		t.Fatalf("owner for wrong length = %q, want empty", owner)
	}
}

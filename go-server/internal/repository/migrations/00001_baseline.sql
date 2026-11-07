-- +goose Up
-- Baseline schema. It matches the tables GORM AutoMigrate created before
-- migrations were versioned. IF NOT EXISTS adopts databases that were created
-- by that path, so upgrading records version 1 without changing existing rows.
CREATE TABLE IF NOT EXISTS `devices` (
    `id` text,
    `name` text,
    `location` text,
    `codeOn` integer,
    `codeOff` integer,
    `rfProtocol` integer,
    `rfLength` integer,
    `rfRepeat` integer,
    `createdAtEpochMs` integer,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `device_tombstones` (
    `deviceId` text,
    `removedAtEpochMs` integer,
    PRIMARY KEY (`deviceId`)
);

CREATE TABLE IF NOT EXISTS `idempotency` (
    `idempotencyKey` text,
    `deviceId` text,
    `action` text,
    `createdAtEpochMs` integer,
    `dispatchedAtEpochMs` integer,
    PRIMARY KEY (`idempotencyKey`)
);

-- +goose Down
DROP TABLE IF EXISTS `idempotency`;
DROP TABLE IF EXISTS `device_tombstones`;
DROP TABLE IF EXISTS `devices`;

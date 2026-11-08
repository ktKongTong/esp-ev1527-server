-- +goose Up
CREATE TABLE IF NOT EXISTS `groups` (
    `id` text NOT NULL,
    `name` text NOT NULL,
    `createdAtEpochMs` integer NOT NULL,
    `updatedAtEpochMs` integer NOT NULL,
    PRIMARY KEY (`id`)
);

-- A device can belong to several groups, so membership is its own relation.
CREATE TABLE IF NOT EXISTS `device_groups` (
    `deviceId` text NOT NULL,
    `groupId` text NOT NULL,
    `addedAtEpochMs` integer NOT NULL,
    PRIMARY KEY (`deviceId`, `groupId`)
);

CREATE INDEX IF NOT EXISTS `idx_device_groups_group`
    ON `device_groups` (`groupId`, `deviceId`);

-- Recurrence is an RFC 5545 RRULE value plus an IANA time zone and DTSTART.
-- The rule stays open-ended: once, daily, weekly, monthly and custom rules all
-- use the same columns. nextRunAtEpochMs is materialized so the scheduler can
-- restore jobs after a restart without rescanning every schedule.
CREATE TABLE IF NOT EXISTS `schedules` (
    `id` text NOT NULL,
    `name` text NOT NULL DEFAULT '',
    `targetType` text NOT NULL,
    `targetId` text NOT NULL,
    `action` text NOT NULL,
    `rrule` text NOT NULL,
    `timeZone` text NOT NULL,
    `startsAtEpochMs` integer NOT NULL,
    `enabled` integer NOT NULL DEFAULT 1,
    `nextRunAtEpochMs` integer,
    `lastRunAtEpochMs` integer,
    `lastResult` text,
    `lastError` text,
    `createdAtEpochMs` integer NOT NULL,
    `updatedAtEpochMs` integer NOT NULL,
    PRIMARY KEY (`id`)
);

CREATE INDEX IF NOT EXISTS `idx_schedules_due`
    ON `schedules` (`enabled`, `nextRunAtEpochMs`);

CREATE INDEX IF NOT EXISTS `idx_schedules_target`
    ON `schedules` (`targetType`, `targetId`);

-- +goose Down
DROP TABLE IF EXISTS `schedules`;
DROP TABLE IF EXISTS `device_groups`;
DROP TABLE IF EXISTS `groups`;

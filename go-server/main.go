package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
	_ "time/tzdata"

	"esp32controlserver/internal/config"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/logging"
	"esp32controlserver/internal/mqtt"
	"esp32controlserver/internal/repository"
	"esp32controlserver/internal/scheduler"
	"esp32controlserver/internal/server"
	"esp32controlserver/internal/service"
)

func main() {
	cfg, err := config.LoadConfig()
	if err != nil {
		logging.Errorf("configuration error: %v", err)
		os.Exit(1)
	}
	logging.SetLevel(cfg.LogLevel)
	logging.Infof(
		"esp32-control-server %s (contract %s) logLevel=%s",
		contract.ServerVersion, contract.ContractVersion, cfg.LogLevel,
	)
	logging.Infof("%s", cfg.MQTT)
	logging.Infof("%s", cfg.Auth)
	logging.Infof(
		"http=%s database=%s subscribe=%s command=%s scheduleGrace=%s",
		cfg.Host, cfg.Database, cfg.SubscribedTopic, cfg.CommandTopic,
		cfg.ScheduleMissedGrace,
	)

	db, err := repository.Open(cfg.Database.URL, cfg.Database.Token)
	if err != nil {
		logging.Errorf("database error: %v", err)
		os.Exit(1)
	}

	devices := repository.NewDeviceRepository(db)
	groups := repository.NewGroupRepository(db)
	schedules := repository.NewScheduleRepository(db)
	idempotency := repository.NewIdempotencyRepository(db)
	gateway := service.NewGatewayStatusStore(cfg.GatewayClientID)

	// MQTT callbacks need the ingest service, while the services need the MQTT
	// transmitter. The holder closes this wiring cycle without a god object.
	var ingest *service.GatewayIngest

	transport := mqtt.NewTransport(
		cfg.MQTT,
		cfg.SubscribedTopic,
		func(topic string, payload []byte) {
			if ingest != nil {
				ingest.OnMessage(topic, payload)
			}
		},
		func() {
			if ingest != nil {
				ingest.OnDisconnected()
			}
		},
	)
	transmitter := mqtt.NewTransmitter(transport, cfg.CommandTopic)

	commandService := service.NewCommandService(
		db, devices, idempotency, gateway, transmitter,
	)
	deviceService := service.NewDeviceService(db, devices)
	groupService := service.NewGroupService(
		db, groups, devices, commandService, gateway,
	)
	learningService := service.NewLearningService(db, devices, gateway, transmitter)
	ingest = service.NewGatewayIngest(cfg.MQTT.TopicPrefix, gateway, learningService)

	// The scheduler callback needs the schedule service, which itself needs
	// the registrar. The holder resolves that small cycle before Start.
	var scheduleService *service.ScheduleService
	runner, err := scheduler.New(
		func(ctx context.Context, scheduleID string, expectedNextRunAtEpochMs int64) {
			if scheduleService != nil {
				scheduleService.Fire(ctx, scheduleID, expectedNextRunAtEpochMs)
			}
		},
		cfg.ScheduleMissedGrace,
	)
	if err != nil {
		logging.Errorf("scheduler error: %v", err)
		os.Exit(1)
	}
	scheduleService = service.NewScheduleService(
		db, schedules, devices, groups, commandService, gateway,
		runner, cfg.ScheduleMissedGrace,
	)

	if err := scheduleService.Start(context.Background()); err != nil {
		logging.Errorf("restore schedules: %v", err)
		os.Exit(1)
	}
	maintenance := []struct {
		interval time.Duration
		name     string
		task     func()
	}{
		{500 * time.Millisecond, "learning-tick", learningService.Tick},
		{time.Minute, "idempotency-purge", func() {
			if err := commandService.Purge(time.Now().UnixMilli()); err != nil {
				logging.Errorf("maintenance idempotency-purge: %v", err)
			}
		}},
		{time.Hour, "tombstone-purge", func() {
			if err := deviceService.Purge(time.Now().UnixMilli()); err != nil {
				logging.Errorf("maintenance tombstone-purge: %v", err)
			}
		}},
	}
	for _, job := range maintenance {
		if err := runner.Every(job.interval, job.name, job.task); err != nil {
			logging.Errorf("scheduler error: %v", err)
			os.Exit(1)
		}
	}
	runner.Start()
	defer func() {
		shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := runner.Shutdown(shutdownContext); err != nil {
			logging.Errorf("scheduler shutdown failed: %v", err)
		}
	}()

	api := server.NewAPI(
		deviceService, commandService, learningService, groupService,
		scheduleService, gateway,
	)
	httpServer := server.NewEcho(api, cfg.Auth)

	transport.Start()
	logging.Infof("HTTP server starting on %s", server.ListenAddress(cfg))

	serverError := make(chan error, 1)
	go func() {
		serverError <- httpServer.Start(server.ListenAddress(cfg))
	}()

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	select {
	case err := <-serverError:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			logging.Errorf("HTTP server failed: %v", err)
			os.Exit(1)
		}
	case <-signals:
		logging.Infof("shutting down")
		transport.Close()
		shutdownContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := httpServer.Shutdown(shutdownContext); err != nil {
			logging.Errorf("HTTP shutdown failed: %v", err)
		}
	}
}

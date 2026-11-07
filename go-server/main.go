package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"
	"time"

	"esp32controlserver/internal/config"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/logging"
	"esp32controlserver/internal/mqtt"
	"esp32controlserver/internal/repository"
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
		"http=%s database=%s subscribe=%s command=%s",
		cfg.Host, cfg.Database, cfg.SubscribedTopic, cfg.CommandTopic,
	)

	db, err := repository.Open(cfg.Database.URL, cfg.Database.Token)
	if err != nil {
		logging.Errorf("database error: %v", err)
		os.Exit(1)
	}

	devices := repository.NewDeviceRepository(db)
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
	learningService := service.NewLearningService(db, devices, gateway, transmitter)
	ingest = service.NewGatewayIngest(cfg.MQTT.TopicPrefix, gateway, learningService)

	api := server.NewAPI(deviceService, commandService, learningService, gateway)
	httpServer := server.NewEcho(api, cfg.Auth)

	go runBackgroundTasks(learningService, commandService, deviceService)

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

func runBackgroundTasks(
	learning *service.LearningService,
	commands *service.CommandService,
	devices *service.DeviceService,
) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		func() {
			defer func() {
				if recovered := recover(); recovered != nil {
					logging.Errorf("background task panic: %v\n%s", recovered, debug.Stack())
				}
			}()
			now := time.Now().UnixMilli()
			learning.Tick()
			if err := commands.Purge(now); err != nil {
				logging.Errorf("background task failed: idempotency purge: %v", err)
			}
			if err := devices.Purge(now); err != nil {
				logging.Errorf("background task failed: tombstone purge: %v", err)
			}
		}()
	}
}

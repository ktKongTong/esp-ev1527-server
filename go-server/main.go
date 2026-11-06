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
)

func main() {
	cfg, err := loadConfig()
	if err != nil {
		errorf("configuration error: %v", err)
		os.Exit(1)
	}
	setLogLevel(cfg.LogLevel)
	infof(
		"esp32-control-server %s (contract %s) logLevel=%s",
		serverVersion, contractVersion, cfg.LogLevel,
	)
	infof("%s", cfg.MQTT)
	infof(
		"http=%s database=%s subscribe=%s command=%s",
		cfg.Host, cfg.DatabasePath, cfg.SubscribedTopic, cfg.CommandTopic,
	)

	startedAtEpochMs := nowEpochMs()
	db, err := openDatabase(cfg.DatabasePath)
	if err != nil {
		errorf("database error: %v", err)
		os.Exit(1)
	}

	devices := &deviceRepository{db: db}
	idempotency := &idempotencyRepository{db: db}
	gateway := newGatewayStatusStore(cfg.GatewayClientID)

	// MQTT callbacks need the ingest service, while the services need the MQTT
	// transmitter. The holder closes this wiring cycle without a god object.
	var ingest *gatewayIngest

	transport := newMQTTTransport(
		cfg.MQTT,
		cfg.SubscribedTopic,
		func(topic string, payload []byte) {
			if ingest != nil {
				ingest.onMessage(topic, payload)
			}
		},
		func() {
			if ingest != nil {
				ingest.onDisconnected()
			}
		},
	)
	transmitter := newMQTTTransmitter(transport, cfg.CommandTopic)

	commandService := newCommandService(db, devices, idempotency, gateway, transmitter)
	deviceService := newDeviceService(db, devices)
	learningService := newLearningService(db, devices, gateway, transmitter)
	ingest = newGatewayIngest(cfg, gateway, learningService)

	api := &apiServer{
		devices:          deviceService,
		commands:         commandService,
		learning:         learningService,
		gateway:          gateway,
		startedAtEpochMs: startedAtEpochMs,
	}
	server := newEchoServer(api)

	go runBackgroundTasks(learningService, commandService, deviceService)

	transport.start()
	infof("HTTP server starting on %s", listenAddress(cfg))

	serverError := make(chan error, 1)
	go func() {
		serverError <- server.Start(listenAddress(cfg))
	}()

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	select {
	case err := <-serverError:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			errorf("HTTP server failed: %v", err)
			os.Exit(1)
		}
	case <-signals:
		infof("shutting down")
		transport.close()
		shutdownContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownContext); err != nil {
			errorf("HTTP shutdown failed: %v", err)
		}
	}
}

func runBackgroundTasks(
	learning *learningService,
	commands *commandService,
	devices *deviceService,
) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		func() {
			defer func() {
				if recovered := recover(); recovered != nil {
					errorf("background task panic: %v\n%s", recovered, debug.Stack())
				}
			}()
			learning.tick()
			if err := commands.purge(nowEpochMs()); err != nil {
				errorf("background task failed: idempotency purge: %v", err)
			}
			if err := devices.purge(nowEpochMs()); err != nil {
				errorf("background task failed: tombstone purge: %v", err)
			}
		}()
	}
}

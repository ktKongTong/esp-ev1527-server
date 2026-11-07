package main

import (
	"errors"
	"fmt"
	"net"
	"net/http"
	"runtime/debug"
	"strconv"
	"time"

	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
)

const requestIDKey = "requestId"

type apiServer struct {
	devices          *deviceService
	commands         *commandService
	learning         *learningService
	gateway          *gatewayStatusStore
	startedAtEpochMs int64
}

func newEchoServer(api *apiServer) *echo.Echo {
	e := echo.New()
	e.HideBanner = true
	e.HidePort = true

	e.Use(middleware.CORSWithConfig(middleware.CORSConfig{
		AllowOrigins: []string{"*"},
		AllowMethods: []string{
			http.MethodGet,
			http.MethodPost,
			http.MethodDelete,
			http.MethodOptions,
		},
		AllowHeaders: []string{
			echo.HeaderAccept,
			echo.HeaderContentType,
			"Idempotency-Key",
			"X-Request-Id",
		},
		ExposeHeaders: []string{
			"X-Request-Id",
			"Idempotent-Replayed",
		},
	}))
	e.Use(requestContextMiddleware)
	e.Use(recoverMiddleware)
	e.HTTPErrorHandler = api.handleError

	e.GET("/", func(c echo.Context) error {
		return c.String(
			http.StatusOK,
			fmt.Sprintf(
				"ESP32 control server %s (contract %s)\nAPI base: /api/v1\n",
				serverVersion, contractVersion,
			),
		)
	})

	group := e.Group("/api/v1")
	group.GET("/health", api.health)
	group.GET("/devices", api.listDevices)
	group.GET("/devices/:deviceId", api.getDevice)
	group.PATCH("/devices/:deviceId", api.updateDevice)
	group.POST("/devices/:deviceId/commands", api.issueCommand)
	group.DELETE("/devices/:deviceId", api.deleteDevice)

	group.POST("/learning/sessions", api.startLearning)
	group.GET("/learning/sessions/:sessionId", api.getLearning)
	group.POST(
		"/learning/sessions/:sessionId/candidates/:candidateId/accept",
		api.acceptCandidate,
	)
	group.POST("/learning/sessions/:sessionId/rescan", api.rescanLearning)
	group.POST("/learning/sessions/:sessionId/test", api.testLearning)
	group.POST("/learning/sessions/:sessionId/confirm", api.confirmLearning)
	group.DELETE("/learning/sessions/:sessionId", api.cancelLearning)

	e.Server.ReadHeaderTimeout = 10 * time.Second
	return e
}

func requestContextMiddleware(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		incoming := c.Request().Header.Get("X-Request-Id")
		requestID := incoming
		if len(requestID) < 1 || len(requestID) > 128 {
			requestID = newID("req")
		}
		c.Set(requestIDKey, requestID)
		c.Response().Header().Set("X-Request-Id", requestID)
		c.Response().Header().Set("Cache-Control", "no-store")

		debugf(
			"[http] -> %s %s requestId=%s",
			c.Request().Method, c.Request().URL.RequestURI(), requestID,
		)
		err := next(c)
		if err != nil {
			// The error handler logs the final status.
			return err
		}
		debugf(
			"[http] <- %d %s %s requestId=%s",
			c.Response().Status,
			c.Request().Method,
			c.Request().URL.RequestURI(),
			requestID,
		)
		return nil
	}
}

func recoverMiddleware(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		defer func() {
			if recovered := recover(); recovered != nil {
				errorf(
					"[http] panic %s %s requestId=%s: %v\n%s",
					c.Request().Method,
					c.Request().URL.RequestURI(),
					currentRequestID(c),
					recovered,
					debug.Stack(),
				)
				if !c.Response().Committed {
					_ = writeAPIError(c, newAPIError(
						http.StatusInternalServerError,
						"internal_error",
						"Unexpected server error",
					))
				}
			}
		}()
		return next(c)
	}
}

func currentRequestID(c echo.Context) string {
	if value, ok := c.Get(requestIDKey).(string); ok {
		return value
	}
	return ""
}

func (s *apiServer) handleError(err error, c echo.Context) {
	if c.Response().Committed {
		return
	}
	apiErr := toAPIError(err)
	if apiErr.Status >= http.StatusInternalServerError {
		errorf(
			"[http] %d %s %s requestId=%s: %v",
			apiErr.Status,
			c.Request().Method,
			c.Request().URL.RequestURI(),
			currentRequestID(c),
			err,
		)
	} else {
		warnf(
			"[http] %d %s %s requestId=%s: %s",
			apiErr.Status,
			apiErr.Code,
			c.Request().URL.RequestURI(),
			currentRequestID(c),
			apiErr.Message,
		)
	}
	_ = writeAPIError(c, apiErr)
}

func toAPIError(err error) *apiError {
	var apiErr *apiError
	if errors.As(err, &apiErr) {
		return apiErr
	}

	var httpErr *echo.HTTPError
	if errors.As(err, &httpErr) {
		switch httpErr.Code {
		case http.StatusNotFound:
			return newAPIError(http.StatusNotFound, "route_not_found", "Route not found")
		case http.StatusMethodNotAllowed:
			return newAPIError(http.StatusMethodNotAllowed, "method_not_allowed", "Method not allowed")
		case http.StatusUnsupportedMediaType:
			return newAPIError(
				http.StatusUnsupportedMediaType,
				"unsupported_media_type",
				"Content-Type must be application/json",
			)
		case http.StatusBadRequest:
			return newAPIError(http.StatusBadRequest, "malformed_json", "Request body is not valid JSON")
		default:
			if httpErr.Code >= http.StatusInternalServerError {
				return newAPIError(
					http.StatusInternalServerError,
					"internal_error",
					"Unexpected server error",
				)
			}
			return newAPIError(httpErr.Code, "invalid_request", http.StatusText(httpErr.Code))
		}
	}

	return newAPIError(
		http.StatusInternalServerError,
		"internal_error",
		"Unexpected server error",
	)
}

func writeAPIError(c echo.Context, err *apiError) error {
	requestID := currentRequestID(c)
	if requestID == "" {
		requestID = newID("req")
	}
	c.Response().Header().Set("X-Request-Id", requestID)
	c.Response().Header().Set("Cache-Control", "no-store")
	return c.JSON(err.Status, apiErrorEnvelope{
		Error: apiErrorBody{
			Code:      err.Code,
			Message:   err.Message,
			RequestID: requestID,
			Details:   err.Details,
		},
	})
}

func (s *apiServer) health(c echo.Context) error {
	now := nowEpochMs()
	uptime := (now - s.startedAtEpochMs) / 1000
	if uptime < 0 {
		uptime = 0
	}
	return c.JSON(http.StatusOK, healthResponse{
		Status:            "ok",
		Service:           serviceName,
		APIVersion:        apiVersion,
		Version:           serverVersion,
		Runtime:           "Go",
		UptimeSeconds:     uptime,
		ServerTimeEpochMs: now,
		Gateway:           s.gateway.snapshot(),
	})
}

func (s *apiServer) listDevices(c echo.Context) error {
	response, err := s.devices.list(c.Request().Context())
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, response)
}

func (s *apiServer) getDevice(c echo.Context) error {
	deviceID := c.Param("deviceId")
	if deviceID == "" {
		return invalidDeviceID()
	}
	value, err := s.devices.get(c.Request().Context(), deviceID)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, value)
}

func (s *apiServer) updateDevice(c echo.Context) error {
	deviceID := c.Param("deviceId")
	if deviceID == "" {
		return invalidDeviceID()
	}
	var request updateDeviceRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	updated, err := s.devices.update(c.Request().Context(), deviceID, request)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, updated)
}

func (s *apiServer) issueCommand(c echo.Context) error {
	deviceID := c.Param("deviceId")
	if deviceID == "" {
		return invalidDeviceID()
	}
	idempotencyKey := c.Request().Header.Get("Idempotency-Key")
	if idempotencyKey == "" {
		return newAPIError(
			http.StatusBadRequest,
			"invalid_request",
			"Idempotency-Key is required",
		)
	}
	var request commandRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	result, replayed, err := s.commands.issue(
		c.Request().Context(), deviceID, request.Action, idempotencyKey,
	)
	if err != nil {
		return err
	}
	if replayed {
		c.Response().Header().Set("Idempotent-Replayed", "true")
	}
	return c.JSON(http.StatusAccepted, result)
}

func (s *apiServer) deleteDevice(c echo.Context) error {
	deviceID := c.Param("deviceId")
	if deviceID == "" {
		return invalidDeviceID()
	}
	if _, err := s.devices.delete(c.Request().Context(), deviceID); err != nil {
		return err
	}
	return c.NoContent(http.StatusNoContent)
}

func (s *apiServer) startLearning(c echo.Context) error {
	var request startLearningRequest
	if c.Request().ContentLength > 0 {
		if err := c.Bind(&request); err != nil {
			return err
		}
	}
	session, err := s.learning.start(request)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusCreated, session)
}

func (s *apiServer) getLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return learningNotFound()
	}
	session, err := s.learning.getLearning(sessionID)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, session)
}

func (s *apiServer) acceptCandidate(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return learningNotFound()
	}
	candidateID := c.Param("candidateId")
	if candidateID == "" {
		return newAPIError(http.StatusNotFound, "candidate_not_found", "Candidate not found")
	}
	session, err := s.learning.acceptCandidate(
		c.Request().Context(), sessionID, candidateID,
	)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, session)
}

func (s *apiServer) rescanLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return learningNotFound()
	}
	session, err := s.learning.rescan(c.Request().Context(), sessionID)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, session)
}

func (s *apiServer) testLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return learningNotFound()
	}
	var request testLearningRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	result, err := s.learning.testLearning(
		c.Request().Context(), sessionID, request.Slot,
	)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusAccepted, result)
}

func (s *apiServer) confirmLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return learningNotFound()
	}
	var request confirmLearningRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	confirmation, err := s.learning.confirmLearning(
		c.Request().Context(), sessionID, request,
	)
	if err != nil {
		return err
	}
	c.Response().Header().Set("Location", "/api/v1/devices/"+confirmation.Device.ID)
	return c.JSON(http.StatusCreated, confirmation)
}

func (s *apiServer) cancelLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return learningNotFound()
	}
	s.learning.cancelLearning(sessionID)
	return c.NoContent(http.StatusNoContent)
}

func listenAddress(cfg config) string {
	return net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))
}

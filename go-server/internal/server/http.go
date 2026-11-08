// Package server exposes the HTTP API over Echo. Routes stay thin: every
// handler binds a request, calls one service method, and writes the response.
package server

import (
	"crypto/subtle"
	"errors"
	"fmt"
	"net"
	"net/http"
	"runtime/debug"
	"strconv"
	"time"

	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"

	"esp32controlserver/internal/apierror"
	"esp32controlserver/internal/config"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/idgen"
	"esp32controlserver/internal/logging"
	"esp32controlserver/internal/service"
)

const requestIDKey = "requestId"

type API struct {
	devices          *service.DeviceService
	commands         *service.CommandService
	learning         *service.LearningService
	groups           *service.GroupService
	schedules        *service.ScheduleService
	gateway          *service.GatewayStatusStore
	startedAtEpochMs int64
	now              func() int64
}

func NewAPI(
	devices *service.DeviceService,
	commands *service.CommandService,
	learning *service.LearningService,
	groups *service.GroupService,
	schedules *service.ScheduleService,
	gateway *service.GatewayStatusStore,
) *API {
	now := func() int64 { return time.Now().UnixMilli() }
	return &API{
		devices:          devices,
		commands:         commands,
		learning:         learning,
		groups:           groups,
		schedules:        schedules,
		gateway:          gateway,
		startedAtEpochMs: now(),
		now:              now,
	}
}

func NewEcho(api *API, auth config.AuthConfig) *echo.Echo {
	e := echo.New()
	e.HideBanner = true
	e.HidePort = true

	e.Use(middleware.CORSWithConfig(middleware.CORSConfig{
		AllowOrigins: []string{"*"},
		AllowMethods: []string{
			http.MethodGet,
			http.MethodPost,
			http.MethodPatch,
			http.MethodDelete,
			http.MethodOptions,
		},
		AllowHeaders: []string{
			echo.HeaderAccept,
			echo.HeaderAuthorization,
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
	e.Use(authMiddleware(auth))
	e.HTTPErrorHandler = api.handleError

	e.GET("/", func(c echo.Context) error {
		return c.String(
			http.StatusOK,
			fmt.Sprintf(
				"ESP32 control server %s (contract %s)\nAPI base: /api/v1\n",
				contract.ServerVersion, contract.ContractVersion,
			),
		)
	})

	// Health stays public: the container healthcheck and external probes call
	// it without credentials, and it exposes no device data.
	e.GET("/api/v1/health", api.health)

	group := e.Group("/api/v1")
	group.GET("/devices", api.listDevices)
	group.GET("/devices/:deviceId", api.getDevice)
	group.PATCH("/devices/:deviceId", api.updateDevice)
	group.POST("/devices/:deviceId/commands", api.issueCommand)
	group.DELETE("/devices/:deviceId", api.deleteDevice)

	group.GET("/groups", api.listGroups)
	group.POST("/groups", api.createGroup)
	group.GET("/groups/:groupId", api.getGroup)
	group.PATCH("/groups/:groupId", api.updateGroup)
	group.DELETE("/groups/:groupId", api.deleteGroup)
	group.POST("/groups/:groupId/commands", api.issueGroupCommand)

	group.GET("/schedules", api.listSchedules)
	group.POST("/schedules", api.createSchedule)
	group.GET("/schedules/:scheduleId", api.getSchedule)
	group.PATCH("/schedules/:scheduleId", api.updateSchedule)
	group.DELETE("/schedules/:scheduleId", api.deleteSchedule)

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

// ListenAddress returns the host:port the HTTP server binds to.
func ListenAddress(cfg config.Config) string {
	return net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))
}

// authMiddleware requires the shared bearer token on every route except the
// public banner and health probe. It runs globally rather than on the /api/v1
// group because Echo group middleware installs a catch-all route, which turns
// method-mismatch 405 responses into 404.
func authMiddleware(auth config.AuthConfig) echo.MiddlewareFunc {
	return middleware.KeyAuthWithConfig(middleware.KeyAuthConfig{
		KeyLookup:  "header:" + echo.HeaderAuthorization,
		AuthScheme: "Bearer",
		Skipper: func(c echo.Context) bool {
			path := c.Request().URL.Path
			return path == "/" || path == "/api/v1/health"
		},
		Validator: func(key string, _ echo.Context) (bool, error) {
			return subtle.ConstantTimeCompare([]byte(key), []byte(auth.Token)) == 1, nil
		},
		// Keep the project error envelope; the Echo default would answer with
		// {"message":"Unauthorized"} and bypass the client's error mapping.
		ErrorHandler: func(_ error, c echo.Context) error {
			return writeAPIError(c, apierror.Unauthorized())
		},
	})
}

func requestContextMiddleware(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		incoming := c.Request().Header.Get("X-Request-Id")
		requestID := incoming
		if len(requestID) < 1 || len(requestID) > 128 {
			requestID = idgen.New("req")
		}
		c.Set(requestIDKey, requestID)
		c.Response().Header().Set("X-Request-Id", requestID)
		c.Response().Header().Set("Cache-Control", "no-store")

		logging.Debugf(
			"[http] -> %s %s requestId=%s",
			c.Request().Method, c.Request().URL.RequestURI(), requestID,
		)
		err := next(c)
		if err != nil {
			// The error handler logs the final status.
			return err
		}
		logging.Debugf(
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
				logging.Errorf(
					"[http] panic %s %s requestId=%s: %v\n%s",
					c.Request().Method,
					c.Request().URL.RequestURI(),
					currentRequestID(c),
					recovered,
					debug.Stack(),
				)
				if !c.Response().Committed {
					_ = writeAPIError(c, apierror.New(
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

func (s *API) handleError(err error, c echo.Context) {
	if c.Response().Committed {
		return
	}
	apiErr := toAPIError(err)
	if apiErr.Status >= http.StatusInternalServerError {
		logging.Errorf(
			"[http] %d %s %s requestId=%s: %v",
			apiErr.Status,
			c.Request().Method,
			c.Request().URL.RequestURI(),
			currentRequestID(c),
			err,
		)
	} else {
		logging.Warnf(
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

func toAPIError(err error) *apierror.Error {
	var apiErr *apierror.Error
	if errors.As(err, &apiErr) {
		return apiErr
	}

	var httpErr *echo.HTTPError
	if errors.As(err, &httpErr) {
		switch httpErr.Code {
		case http.StatusNotFound:
			return apierror.New(http.StatusNotFound, "route_not_found", "Route not found")
		case http.StatusMethodNotAllowed:
			return apierror.New(
				http.StatusMethodNotAllowed,
				"method_not_allowed",
				"Method not allowed",
			)
		case http.StatusUnsupportedMediaType:
			return apierror.New(
				http.StatusUnsupportedMediaType,
				"unsupported_media_type",
				"Content-Type must be application/json",
			)
		case http.StatusBadRequest:
			return apierror.New(
				http.StatusBadRequest,
				"malformed_json",
				"Request body is not valid JSON",
			)
		default:
			if httpErr.Code >= http.StatusInternalServerError {
				return apierror.New(
					http.StatusInternalServerError,
					"internal_error",
					"Unexpected server error",
				)
			}
			return apierror.New(
				httpErr.Code,
				"invalid_request",
				http.StatusText(httpErr.Code),
			)
		}
	}

	return apierror.New(
		http.StatusInternalServerError,
		"internal_error",
		"Unexpected server error",
	)
}

func writeAPIError(c echo.Context, err *apierror.Error) error {
	requestID := currentRequestID(c)
	if requestID == "" {
		requestID = idgen.New("req")
	}
	c.Response().Header().Set("X-Request-Id", requestID)
	c.Response().Header().Set("Cache-Control", "no-store")
	return c.JSON(err.Status, contract.APIErrorEnvelope{
		Error: contract.APIErrorBody{
			Code:      err.Code,
			Message:   err.Message,
			RequestID: requestID,
			Details:   err.Details,
		},
	})
}

func (s *API) health(c echo.Context) error {
	now := s.now()
	uptime := (now - s.startedAtEpochMs) / 1000
	if uptime < 0 {
		uptime = 0
	}
	return c.JSON(http.StatusOK, contract.HealthResponse{
		Status:            "ok",
		Service:           contract.ServiceName,
		APIVersion:        contract.APIVersion,
		Version:           contract.ServerVersion,
		Runtime:           "Go",
		UptimeSeconds:     uptime,
		ServerTimeEpochMs: now,
		Gateway:           s.gateway.Snapshot(),
	})
}

func (s *API) listDevices(c echo.Context) error {
	response, err := s.devices.List(c.Request().Context())
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, response)
}

func (s *API) getDevice(c echo.Context) error {
	deviceID := c.Param("deviceId")
	if deviceID == "" {
		return apierror.InvalidDeviceID()
	}
	value, err := s.devices.Get(c.Request().Context(), deviceID)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, value)
}

func (s *API) updateDevice(c echo.Context) error {
	deviceID := c.Param("deviceId")
	if deviceID == "" {
		return apierror.InvalidDeviceID()
	}
	var request contract.UpdateDeviceRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	updated, err := s.devices.Update(c.Request().Context(), deviceID, request)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, updated)
}

func (s *API) issueCommand(c echo.Context) error {
	deviceID := c.Param("deviceId")
	if deviceID == "" {
		return apierror.InvalidDeviceID()
	}
	idempotencyKey, err := requireIdempotencyKey(c)
	if err != nil {
		return err
	}
	var request contract.CommandRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	result, replayed, err := s.commands.Issue(
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

func (s *API) deleteDevice(c echo.Context) error {
	deviceID := c.Param("deviceId")
	if deviceID == "" {
		return apierror.InvalidDeviceID()
	}
	if _, err := s.devices.Delete(c.Request().Context(), deviceID); err != nil {
		return err
	}
	return c.NoContent(http.StatusNoContent)
}

func (s *API) listGroups(c echo.Context) error {
	response, err := s.groups.List(c.Request().Context())
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, response)
}

func (s *API) createGroup(c echo.Context) error {
	var request contract.CreateGroupRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	created, err := s.groups.Create(c.Request().Context(), request)
	if err != nil {
		return err
	}
	c.Response().Header().Set("Location", "/api/v1/groups/"+created.ID)
	return c.JSON(http.StatusCreated, created)
}

func (s *API) getGroup(c echo.Context) error {
	groupID := c.Param("groupId")
	if groupID == "" {
		return apierror.InvalidGroupID()
	}
	value, err := s.groups.Get(c.Request().Context(), groupID)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, value)
}

func (s *API) updateGroup(c echo.Context) error {
	groupID := c.Param("groupId")
	if groupID == "" {
		return apierror.InvalidGroupID()
	}
	var request contract.UpdateGroupRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	updated, err := s.groups.Update(c.Request().Context(), groupID, request)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, updated)
}

func (s *API) deleteGroup(c echo.Context) error {
	groupID := c.Param("groupId")
	if groupID == "" {
		return apierror.InvalidGroupID()
	}
	if err := s.groups.Delete(c.Request().Context(), groupID); err != nil {
		return err
	}
	return c.NoContent(http.StatusNoContent)
}

func (s *API) issueGroupCommand(c echo.Context) error {
	groupID := c.Param("groupId")
	if groupID == "" {
		return apierror.InvalidGroupID()
	}
	idempotencyKey, err := requireIdempotencyKey(c)
	if err != nil {
		return err
	}
	var request contract.CommandRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	result, err := s.groups.IssueCommand(
		c.Request().Context(), groupID, request.Action, idempotencyKey,
	)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusAccepted, result)
}

func (s *API) listSchedules(c echo.Context) error {
	response, err := s.schedules.List(c.Request().Context())
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, response)
}

func (s *API) createSchedule(c echo.Context) error {
	var request contract.CreateScheduleRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	created, err := s.schedules.Create(c.Request().Context(), request)
	if err != nil {
		return err
	}
	c.Response().Header().Set("Location", "/api/v1/schedules/"+created.ID)
	return c.JSON(http.StatusCreated, created)
}

func (s *API) getSchedule(c echo.Context) error {
	scheduleID := c.Param("scheduleId")
	if scheduleID == "" {
		return apierror.InvalidScheduleID()
	}
	value, err := s.schedules.Get(c.Request().Context(), scheduleID)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, value)
}

func (s *API) updateSchedule(c echo.Context) error {
	scheduleID := c.Param("scheduleId")
	if scheduleID == "" {
		return apierror.InvalidScheduleID()
	}
	var request contract.UpdateScheduleRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	updated, err := s.schedules.Update(c.Request().Context(), scheduleID, request)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, updated)
}

func (s *API) deleteSchedule(c echo.Context) error {
	scheduleID := c.Param("scheduleId")
	if scheduleID == "" {
		return apierror.InvalidScheduleID()
	}
	if err := s.schedules.Delete(c.Request().Context(), scheduleID); err != nil {
		return err
	}
	return c.NoContent(http.StatusNoContent)
}

func requireIdempotencyKey(c echo.Context) (string, error) {
	key := c.Request().Header.Get("Idempotency-Key")
	if key == "" {
		return "", apierror.InvalidRequest("Idempotency-Key is required")
	}
	return key, nil
}

func (s *API) startLearning(c echo.Context) error {
	var request contract.StartLearningRequest
	if c.Request().ContentLength > 0 {
		if err := c.Bind(&request); err != nil {
			return err
		}
	}
	session, err := s.learning.Start(request)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusCreated, session)
}

func (s *API) getLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return apierror.LearningNotFound()
	}
	session, err := s.learning.GetLearning(sessionID)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, session)
}

func (s *API) acceptCandidate(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return apierror.LearningNotFound()
	}
	candidateID := c.Param("candidateId")
	if candidateID == "" {
		return apierror.New(
			http.StatusNotFound,
			"candidate_not_found",
			"Candidate not found",
		)
	}
	session, err := s.learning.AcceptCandidate(
		c.Request().Context(), sessionID, candidateID,
	)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, session)
}

func (s *API) rescanLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return apierror.LearningNotFound()
	}
	session, err := s.learning.Rescan(c.Request().Context(), sessionID)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, session)
}

func (s *API) testLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return apierror.LearningNotFound()
	}
	var request contract.TestLearningRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	result, err := s.learning.TestLearning(
		c.Request().Context(), sessionID, request.Slot,
	)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusAccepted, result)
}

func (s *API) confirmLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return apierror.LearningNotFound()
	}
	var request contract.ConfirmLearningRequest
	if err := c.Bind(&request); err != nil {
		return err
	}
	confirmation, err := s.learning.ConfirmLearning(
		c.Request().Context(), sessionID, request,
	)
	if err != nil {
		return err
	}
	c.Response().Header().Set("Location", "/api/v1/devices/"+confirmation.Device.ID)
	return c.JSON(http.StatusCreated, confirmation)
}

func (s *API) cancelLearning(c echo.Context) error {
	sessionID := c.Param("sessionId")
	if sessionID == "" {
		return apierror.LearningNotFound()
	}
	s.learning.CancelLearning(sessionID)
	return c.NoContent(http.StatusNoContent)
}

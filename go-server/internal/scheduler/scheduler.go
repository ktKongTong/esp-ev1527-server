// Package scheduler adapts gocron to the two scheduling needs of the server:
// one-shot jobs restored from persisted schedules, and periodic maintenance
// tasks. The rest of the code depends on this small surface, not on gocron.
package scheduler

import (
	"context"
	"fmt"
	"runtime/debug"
	"sync"
	"time"

	"github.com/go-co-op/gocron/v2"
	"github.com/google/uuid"

	"esp32controlserver/internal/logging"
)

// FireFunc runs one due schedule. expectedNextRunAtEpochMs is the persisted
// occurrence that produced the job; the service uses it to reject jobs that
// were replaced by an edit or already fired by another replica.
type FireFunc func(
	ctx context.Context,
	scheduleID string,
	expectedNextRunAtEpochMs int64,
)

type Scheduler struct {
	jobs        gocron.Scheduler
	maintenance gocron.Scheduler
	fire        FireFunc
	grace       time.Duration

	mu     sync.Mutex
	jobIDs map[string]uuid.UUID
}

func New(fire FireFunc, grace time.Duration) (*Scheduler, error) {
	jobs, err := gocron.NewScheduler(
		gocron.WithLimitConcurrentJobs(1, gocron.LimitModeWait),
		gocron.WithStopTimeout(5*time.Second),
	)
	if err != nil {
		return nil, fmt.Errorf("create schedule scheduler: %w", err)
	}
	maintenance, err := gocron.NewScheduler(
		gocron.WithStopTimeout(5 * time.Second),
	)
	if err != nil {
		return nil, fmt.Errorf("create maintenance scheduler: %w", err)
	}
	return &Scheduler{
		jobs:        jobs,
		maintenance: maintenance,
		fire:        fire,
		grace:       grace,
		jobIDs:      make(map[string]uuid.UUID),
	}, nil
}

func (s *Scheduler) Start() {
	s.jobs.Start()
	s.maintenance.Start()
}

// Every registers a periodic maintenance task. The task must be quick: it
// shares one scheduler with the other maintenance work.
func (s *Scheduler) Every(
	interval time.Duration,
	name string,
	task func(),
) error {
	_, err := s.maintenance.NewJob(
		gocron.DurationJob(interval),
		gocron.NewTask(func() {
			runSafely(name, task)
		}),
		gocron.WithName(name),
	)
	if err != nil {
		return fmt.Errorf("register maintenance job %s: %w", name, err)
	}
	return nil
}

// Register replaces the pending job for a schedule. The one-time job carries
// the expected occurrence so a stale job cannot dispatch a newer occurrence.
func (s *Scheduler) Register(scheduleID string, at time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	previous, hadPrevious := s.jobIDs[scheduleID]
	expected := at.UnixMilli()
	job, err := s.jobs.NewJob(
		gocron.OneTimeJob(gocron.OneTimeJobStartDateTime(at)),
		gocron.NewTask(func() {
			runSafely("schedule:"+scheduleID, func() {
				s.fire(context.Background(), scheduleID, expected)
			})
		}),
		gocron.WithName("schedule:"+scheduleID),
		gocron.WithStartAtGrace(s.grace),
	)
	if err != nil {
		return fmt.Errorf("register schedule %s: %w", scheduleID, err)
	}
	s.jobIDs[scheduleID] = job.ID()
	if hadPrevious {
		removeJob(s.jobs, previous)
	}
	return nil
}

func (s *Scheduler) Unregister(scheduleID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	jobID, ok := s.jobIDs[scheduleID]
	if !ok {
		return
	}
	delete(s.jobIDs, scheduleID)
	removeJob(s.jobs, jobID)
}

func (s *Scheduler) Shutdown(ctx context.Context) error {
	jobsErr := s.jobs.ShutdownWithContext(ctx)
	maintenanceErr := s.maintenance.ShutdownWithContext(ctx)
	if jobsErr != nil {
		return jobsErr
	}
	return maintenanceErr
}

func removeJob(scheduler gocron.Scheduler, id uuid.UUID) {
	if err := scheduler.RemoveJob(id); err != nil {
		// A one-time job removes itself after it runs, so a missing job is a
		// normal outcome and not an error worth surfacing.
		logging.Debugf("[scheduler] remove job %s: %v", id, err)
	}
}

func runSafely(name string, task func()) {
	defer func() {
		if recovered := recover(); recovered != nil {
			logging.Errorf(
				"[scheduler] job %s panic: %v\n%s",
				name, recovered, debug.Stack(),
			)
		}
	}()
	task()
}

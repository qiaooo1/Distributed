package io.joblab.scheduler;

import io.joblab.scheduler.ScheduleModels.Definition;
import io.joblab.scheduler.ScheduleModels.Overlap;
import io.joblab.scheduler.ScheduleModels.SchedulePage;
import io.joblab.scheduler.ScheduleModels.ScheduleView;
import io.joblab.scheduler.security.TenantActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class ScheduleService {

    private final ScheduleStore schedules;
    private final JobRepository clock;
    private final JobService jobs;
    private final AuditLog audit;
    private final Duration misfireTolerance;

    public ScheduleService(ScheduleStore schedules, JobRepository clock, JobService jobs, AuditLog audit,
                           @Value("${joblab.schedule-misfire-seconds:30}") long misfireSeconds) {
        if (misfireSeconds < 1) {
            throw new IllegalArgumentException("Schedule misfire tolerance must be positive");
        }
        this.schedules = schedules;
        this.clock = clock;
        this.jobs = jobs;
        this.audit = audit;
        this.misfireTolerance = Duration.ofSeconds(misfireSeconds);
    }

    @Transactional
    public ScheduleView create(TenantActor actor, Definition input) {
        Definition definition = normalize(input);
        Instant next = nextOrReject(definition, clock.now());
        UUID id = UUID.randomUUID();
        try {
            schedules.create(actor.tenantId(), id, definition, next);
        } catch (DuplicateKeyException ex) {
            throw duplicateName();
        }
        audit.record(actor, "SCHEDULE_CREATED", id, null);
        return find(actor, id, false);
    }

    @Transactional
    public ScheduleView change(TenantActor actor, UUID id, Definition input) {
        Definition definition = normalize(input);
        ScheduleView schedule = find(actor, id, true);
        Instant next = nextOrReject(definition, clock.now());
        try {
            schedules.update(actor.tenantId(), id, definition, schedule.enabled() ? next : null);
        } catch (DuplicateKeyException ex) {
            throw duplicateName();
        }
        audit.record(actor, "SCHEDULE_UPDATED", id, null);
        return find(actor, id, false);
    }

    @Transactional
    public ScheduleView setEnabled(TenantActor actor, UUID id, boolean enabled) {
        ScheduleView schedule = find(actor, id, true);
        if (schedule.enabled() == enabled) {
            return schedule;
        }
        var plan = CronPlan.parse(schedule.cronExpression(), schedule.timeZone());
        Instant next = enabled ? plan.next(clock.now()) : null;
        if (enabled && next == null) {
            throw invalidSchedule("Schedule has no future occurrence within the database time range");
        }
        schedules.setEnabled(actor.tenantId(), id, enabled, next);
        audit.record(actor, enabled ? "SCHEDULE_RESUMED" : "SCHEDULE_PAUSED", id, null);
        return find(actor, id, false);
    }

    @Transactional(readOnly = true)
    public ScheduleView get(TenantActor actor, UUID id) {
        return find(actor, id, false);
    }

    @Transactional(readOnly = true)
    public SchedulePage list(TenantActor actor, String afterName, int limit) {
        var rows = schedules.list(actor.tenantId(), afterName, limit + 1);
        var page = rows.stream().limit(limit).toList();
        return new SchedulePage(page, rows.size() > limit ? page.get(page.size() - 1).name() : null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean triggerDue() {
        var due = schedules.due();
        if (due.isEmpty()) {
            return false;
        }
        var schedule = due.get();
        var definition = schedule.view();
        Instant now = clock.now();
        var plan = CronPlan.parse(definition.cronExpression(), definition.timeZone());
        var occurrence = plan.occurrence(definition.nextFireAt(), now, misfireTolerance,
                definition.misfirePolicy(), definition.lookbackDays());
        Instant firedAt = null;
        if (occurrence == null) {
            audit.system(schedule.tenantId(), "SCHEDULE_MISFIRE_SKIPPED", definition.id(), null);
        } else if (definition.overlapPolicy() == Overlap.SKIP_IF_ACTIVE
                && schedules.hasOutstandingJob(schedule.tenantId(), definition.id())) {
            audit.system(schedule.tenantId(), "SCHEDULE_OVERLAP_SKIPPED", definition.id(), null);
        } else {
            jobs.createOccurrence(schedule.tenantId(), definition.id(), definition.reportType(), occurrence.from(),
                    occurrence.to(), definition.maxAttempts(), occurrence.fireAt(), now);
            firedAt = occurrence.fireAt();
        }
        Instant next = plan.next(now);
        schedules.advance(schedule, next, firedAt);
        if (next == null) {
            audit.system(schedule.tenantId(), "SCHEDULE_EXHAUSTED", definition.id(), null);
        }
        return true;
    }

    private ScheduleView find(TenantActor actor, UUID id, boolean lock) {
        return schedules.find(actor.tenantId(), id, lock).map(ScheduleModels.Schedule::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
    }

    private static Instant nextOrReject(Definition definition, Instant now) {
        try {
            Instant next = CronPlan.parse(definition.cronExpression(), definition.timeZone()).next(now);
            if (next != null) {
                return next;
            }
        } catch (IllegalArgumentException ex) {
            throw invalidSchedule(ex.getMessage());
        }
        throw invalidSchedule("Schedule has no future occurrence within the database time range");
    }

    private static Definition normalize(Definition input) {
        return new Definition(input.name().strip(), input.cronExpression().strip(), input.timeZone().strip(),
                input.reportType(), input.lookbackDays(), input.maxAttempts(), input.misfirePolicy(), input.overlapPolicy());
    }

    private static ResponseStatusException duplicateName() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Schedule name already exists");
    }

    private static ResponseStatusException invalidSchedule(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

package io.joblab.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;

@Component
public class SchedulerLoop {

    private final JobService jobs;
    private final ScheduleService schedules;

    public SchedulerLoop(JobService jobs, ScheduleService schedules) {
        this.jobs = jobs;
        this.schedules = schedules;
    }

    @Scheduled(fixedDelay = 250)
    public void dispatch() {
        drain(jobs::dispatchDue);
    }

    @Scheduled(fixedDelay = 1000)
    public void recover() {
        drain(jobs::recoverExpired);
        drain(jobs::redispatchUnclaimed);
    }

    @Scheduled(fixedDelay = 1000)
    public void triggerSchedules() {
        drain(schedules::triggerDue);
    }

    private void drain(BooleanSupplier processNext) {
        for (int processed = 0; processed < 25 && processNext.getAsBoolean(); processed++) {
            // Each call commits separately to release the selected row.
        }
    }
}

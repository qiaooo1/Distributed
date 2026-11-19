package io.joblab.scheduler;

import io.joblab.scheduler.ScheduleModels.ChangeScheduleState;
import io.joblab.scheduler.ScheduleModels.Definition;
import io.joblab.scheduler.ScheduleModels.SchedulePage;
import io.joblab.scheduler.ScheduleModels.ScheduleView;
import io.joblab.scheduler.security.TenantActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService schedules;

    public ScheduleController(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public SchedulePage list(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(defaultValue = "") @Size(max = 80) String afterName,
                             @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        return schedules.list(TenantActor.from(jwt), afterName, limit);
    }

    @GetMapping("/{id}")
    public ScheduleView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return schedules.get(TenantActor.from(jwt), id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<ScheduleView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Definition input) {
        var created = schedules.create(TenantActor.from(jwt), input);
        return ResponseEntity.created(URI.create("/api/schedules/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ScheduleView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Definition input) {
        return schedules.change(TenantActor.from(jwt), id, input);
    }

    @PutMapping("/{id}/state")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ScheduleView changeState(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @Valid @RequestBody ChangeScheduleState input) {
        return schedules.setEnabled(TenantActor.from(jwt), id, input.enabled());
    }
}

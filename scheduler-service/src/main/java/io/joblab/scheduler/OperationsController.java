package io.joblab.scheduler;

import io.joblab.scheduler.JobModels.State;
import io.joblab.scheduler.security.TenantActor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {

    private final JobService jobs;
    private final AuditLog audit;

    public OperationsController(JobService jobs, AuditLog audit) {
        this.jobs = jobs;
        this.audit = audit;
    }

    @GetMapping("/overview")
    public Map<State, Long> overview(@AuthenticationPrincipal Jwt jwt) {
        return jobs.overview(TenantActor.from(jwt));
    }

    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public AuditPage audit(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) @Min(1) Long before,
                           @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        var rows = audit.page(TenantActor.from(jwt).tenantId(), before, limit + 1);
        var page = rows.stream().limit(limit).toList();
        return new AuditPage(page, rows.size() > limit ? page.get(page.size() - 1).id() : null);
    }

    public record AuditPage(List<AuditLog.Entry> items, Long nextBefore) {
    }
}

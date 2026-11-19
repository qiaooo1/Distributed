package io.joblab.scheduler.security;

import io.joblab.scheduler.security.IdentityStore.MemberView;
import io.joblab.scheduler.security.IdentityStore.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class IdentityController {

    private final IdentityService identities;
    private final ApplicationAvailability availability;

    public IdentityController(IdentityService identities, ApplicationAvailability availability) {
        this.identities = identities;
        this.availability = availability;
    }

    @PostMapping("/auth/token")
    public ResponseEntity<IdentityService.Token> login(@Valid @RequestBody Login request) {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity is starting");
        }
        var token = identities.login(request.tenant(), request.username(), request.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(token);
    }

    @GetMapping("/admin/members")
    @PreAuthorize("hasRole('ADMIN')")
    public List<MemberView> list(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam(defaultValue = "") @Size(max = 64) String afterUsername,
                                 @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        return identities.list(TenantActor.from(jwt), afterUsername, limit);
    }

    @PostMapping("/admin/members")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewMember request) {
        return identities.create(TenantActor.from(jwt), request.username(), request.password(), request.role());
    }

    @PutMapping("/admin/members/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MemberView changeAccess(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @Valid @RequestBody MemberAccess request) {
        return identities.update(TenantActor.from(jwt), id, request.role(), request.enabled());
    }

    public record Login(@NotNull @Pattern(regexp = "[a-z0-9-]{1,40}") String tenant,
                        @NotNull @Pattern(regexp = "[a-z0-9_.-]{1,64}") String username,
                        @NotNull @Size(min = 1, max = 72) String password) {
    }

    public record NewMember(@NotNull @Pattern(regexp = "[a-z0-9_.-]{3,64}") String username,
                            @NotNull @Size(min = 12, max = 72) String password, @NotNull Role role) {
    }

    public record MemberAccess(@NotNull Role role, @NotNull Boolean enabled) {
    }
}

package com.callverse.host.api.controllers;

import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.features.workforce.commands.ApplyWorkforceRecommendationCommandHandler;
import com.callverse.core.application.features.workforce.queries.GetWorkforceCandidatesQueryHandler;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.interfaces.WorkforceAdministration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path="/api/v1/supervision/workforce/recommendations",produces="application/json")
@PreAuthorize(Roles.SUPERVISION)
@SecurityRequirement(name="bearerAuth")
@RequiredArgsConstructor
@Slf4j
public class WorkforceApplicationController {
    private final GetWorkforceCandidatesQueryHandler candidates;
    private final ApplyWorkforceRecommendationCommandHandler apply;
    private final CurrentPrincipalProvider principals;

    @GetMapping("/{decisionId}/candidates")
    @Operation(operationId="getWorkforceApplicationCandidates",summary="Choose idle donor-only advisors for a live proposal")
    public WorkforceAdministration.Selection candidates(@PathVariable long decisionId) {
        return candidates.handle(actor(),decisionId);
    }

    @PostMapping(path="/{decisionId}/apply",consumes="application/json")
    @Operation(operationId="applyWorkforceRecommendation",summary="Apply a human-confirmed skill-pool transfer atomically",
            description="Explicitly selected idle advisors lose the donor skill and receive the target skill at the "
                    + "human-confirmed level. Other skills and credit authority stay unchanged. Proposals expire after 15 minutes. "
                    + "An identical retry returns the recorded result without changing staffing twice.")
    public WorkforceAdministration.Application apply(@PathVariable long decisionId,@Valid @RequestBody ApplyRequest request) {
        var result=apply.handle(actor(),decisionId,request.advisorIds(),request.targetSkillLevel());
        if (!result.alreadyApplied()) log.info("AUDIT workforce decision={} applied advisors={} by supervisor={}",decisionId,result.advisorIds(),result.approvedBy());
        return result;
    }

    public record ApplyRequest(@NotEmpty @Size(max=50) List<@NotNull UUID> advisorIds,@Min(1) @Max(3) int targetSkillLevel) {}
    private UUID actor() { return principals.current().orElseThrow(AuthenticationRequiredException::new).userId(); }
}

package com.callverse.host.api.controllers;

import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.features.admin.commands.ChangeUserAccessCommand;
import com.callverse.core.application.features.admin.commands.ChangeUserAccessCommandHandler;
import com.callverse.core.application.features.admin.commands.CreateUserCommand;
import com.callverse.core.application.features.admin.commands.CreateUserCommandHandler;
import com.callverse.core.application.features.admin.queries.GetUserQueryHandler;
import com.callverse.core.application.features.admin.queries.ListUsersQuery;
import com.callverse.core.application.features.admin.queries.ListUsersQueryHandler;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.interfaces.UserAccounts.AccountChange;
import com.callverse.core.application.interfaces.UserAccounts.AccountRecord;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.host.api.dto.request.ChangeRoleRequest;
import com.callverse.host.api.dto.request.CreateUserRequest;
import com.callverse.host.api.dto.response.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account administration: who may use CallVerse, with which role. ADMIN only.
 *
 * <p>Every change is effective on the target's next request: the security filter re-reads the
 * account on each call, and their open live sessions are cut. The schema has no actor column, so
 * each change writes an {@code AUDIT} log line naming the administrator and the account.
 */
@RestController
@RequestMapping(path = "/api/v1/admin/users", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Administration", description = "User accounts and access")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final ListUsersQueryHandler listUsers;
    private final GetUserQueryHandler getUser;
    private final CreateUserCommandHandler createUser;
    private final ChangeUserAccessCommandHandler changeAccess;
    private final CurrentPrincipalProvider principals;

    @GetMapping
    @PreAuthorize(Roles.ADMIN)
    @Operation(
            operationId = "listUsers",
            summary = "List accounts",
            description = "Newest first, paged (page from 0, size 1 to 100, default 20). Optional filters: role, "
                    + "active, and q (case-insensitive, matched against email and names).")
    public UserResponse.Page list(
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return UserResponse.Page.from(listUsers.handle(new ListUsersQuery(role, active, q, page, size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    @Operation(operationId = "getUser", summary = "One account", description = "Unknown id: 404.")
    public UserResponse get(@PathVariable UUID id) {
        return UserResponse.from(getUser.handle(id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.ADMIN)
    @Operation(
            operationId = "createUser",
            summary = "Give someone access",
            description = "Creates an active account with a role and a temporary password (12 characters to 72 "
                    + "bytes, not containing the email's name). The email is stored in lower case. An address "
                    + "already used: 409 EMAIL_ALREADY_USED. The password is never returned.")
    @ApiResponse(responseCode = "201", description = "Created")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        AuthenticatedPrincipal admin = caller();
        AccountRecord created = createUser.handle(new CreateUserCommand(admin.userId(),
                request.email(), request.firstName(), request.lastName(), request.role(), request.password()));
        log.info("AUDIT user={} created role={} by admin={}", created.id(), created.role(), admin.userId());
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + created.id()))
                .body(UserResponse.from(created));
    }

    @PutMapping(path = "/{id}/role", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.ADMIN)
    @Operation(
            operationId = "changeUserRole",
            summary = "Change an account's role",
            description = "Effective at once: the account's current token is refused and its live sessions cut; "
                    + "the next login carries the new role. Your own account: 409 SELF_LOCKOUT. The same role "
                    + "again changes nothing.")
    public UserResponse changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        AuthenticatedPrincipal admin = caller();
        return audited(changeAccess.handle(ChangeUserAccessCommand.role(admin.userId(), id, request.role())), admin,
                "role=" + request.role());
    }

    @PostMapping("/{id}/block")
    @PreAuthorize(Roles.ADMIN)
    @Operation(
            operationId = "blockUser",
            summary = "Block an account",
            description = "The account can no longer log in, its current token is refused on the next request, and "
                    + "its live sessions are cut. Your own account: 409 SELF_LOCKOUT. Already blocked: 200, "
                    + "unchanged.")
    public UserResponse block(@PathVariable UUID id) {
        AuthenticatedPrincipal admin = caller();
        return audited(changeAccess.handle(ChangeUserAccessCommand.active(admin.userId(), id, false)), admin, "blocked");
    }

    @PostMapping("/{id}/unblock")
    @PreAuthorize(Roles.ADMIN)
    @Operation(
            operationId = "unblockUser",
            summary = "Unblock an account",
            description = "The account can log in again. Already active: 200, unchanged.")
    public UserResponse unblock(@PathVariable UUID id) {
        AuthenticatedPrincipal admin = caller();
        return audited(changeAccess.handle(ChangeUserAccessCommand.active(admin.userId(), id, true)), admin, "unblocked");
    }

    private UserResponse audited(AccountChange change, AuthenticatedPrincipal admin, String what) {
        if (change.changed()) {
            log.info("AUDIT user={} {} by admin={}", change.account().id(), what, admin.userId());
        }
        return UserResponse.from(change.account());
    }

    private AuthenticatedPrincipal caller() {
        return principals.current().orElseThrow(AuthenticationRequiredException::new);
    }
}

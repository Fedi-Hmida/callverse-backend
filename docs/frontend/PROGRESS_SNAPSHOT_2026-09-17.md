# Backend progress snapshot — 2026-09-17

A dated checkpoint, re-derived from `git log` and the source tree at the commit below. It is not a
new audit; it records what moved since the last one and what did not.

**Commit:** `6b1b18d` · **Branch:** `main` · **Working tree:** clean · **Total commits:** 25
**Previous audit baseline:** `c3de5a5` (2026-09-15, `docs/user/` — documentation only, no code)

---

## 1. What has changed since the last audit

Four commits landed on 2026-09-17, all after the `docs/user/` audit set.

| Commit | Change | Significance |
|---|---|---|
| `868afdb` | fix(security): fail closed on the profile default and activate method security | **Sub-phase 2.0 is complete.** See §2. |
| `8f1abe2` | feat(api): publish a complete OpenAPI contract | The contract the frontend generates from is now real. See §3. |
| `7c240f1` | chore: add a Makefile for the common development commands | Developer tooling. `make api`, `make verify`. |
| `6b1b18d` | docs: require one-line commit messages | `CONTRIBUTING.md` §5 now caps commit messages at one line, no body. |

**Also closed since the last audit, though not by a commit:** the project context records Phase 1's
remaining gap as *"nothing has ever touched real Neon."* That gap is closed. The application was
booted against Neon on 2026-09-17 with Flyway enabled and `ddl-auto: validate`; both migrations
applied as real migrations and Hibernate validated the entity model against the live schema.

---

## 2. Sub-phase 2.0 — complete

`ACTION_PLAN.md` defines 2.0 as *"Fail closed, and make role annotations actually work ⭐ START
HERE"*, with three items. All three verified present at `6b1b18d`:

| Item | State | Evidence |
|---|---|---|
| Invert the compose profile default so it fails closed | Done | `docker-compose.yml:43` — `${SPRING_PROFILES_ACTIVE:-prod}` |
| Add `@EnableMethodSecurity` | Done | `SecurityConfiguration.java:39` |
| Correct the generated-password comment | Done | `SecurityConfiguration.java:71` |
| Correct the `JWT_SECRET` fail-fast comment | Done | `application.yml:81-85` |

**Residual, stated honestly:** `.env.example:43` still sets `SPRING_PROFILES_ACTIVE=dev`. That is
deliberate for a local developer template, but it means the *documented local* path still selects the
permit-all chain. The deployment path — which is what the original finding was about — now fails
closed.

**A consequence that is now live.** Sub-phase 2.3 warns that once `@EnableMethodSecurity` is enabled,
`GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)` matches
`AccessDeniedException` first, turning every routine permission denial into an HTTP 500 with an
ERROR-level stack trace. **2.0 has now landed, so that trap is armed.** It is harmless today because
zero `@PreAuthorize` annotations exist, and it becomes a live defect the moment the first one is
written. 2.3 must not be deferred past 2.1.

---

## 3. What the OpenAPI contract now publishes

Verified by fetching `/v3/api-docs` from a running instance on 2026-09-17:

- Title, version (substituted from the POM), servers, contact, licence.
- Two security schemes, **declared but not enforced**: `bearerAuth` (HTTP bearer, JWT) and
  `serviceKey` (API key, header `X-Internal-Key`). Declared so Swagger UI renders an Authorize
  button before the first protected endpoint exists.
- `ErrorResponse` and `HealthStatusResponse` schemas.
- The one existing operation carries `200`, `400`, `404`, `409` and `500`, each `application/json`.

**401 and 403 are deliberately absent from the document**, because Spring Security rejects at the
filter chain before the dispatcher and returns an empty body rather than the envelope. They appear
once sub-phase 2.3 wires an `AuthenticationEntryPoint`.

---

## 4. What has NOT changed — re-verified, not assumed

**Authentication has not been started.** No login endpoint, no JWT filter, no `UserDetailsService`,
no `PasswordEncoder`, no `@PreAuthorize` anywhere. `core/application/features/auth/{commands,queries}`
still contain only `package-info.java`, untouched since their creation on 2026-09-14. `868afdb`
hardened configuration; it added no authentication mechanism.

Counted directly from the source tree at `6b1b18d`:

| Item | Count | Note |
|---|---|---|
| Domain entity classes | **26** | 28 files in `entities/`; two are composite-key classes (`AdvisorSkillId`, `MetricSampleId`), not aggregates. The long-quoted "26" is correct under the aggregate definition. |
| Enums | **20** | Matches prior claim exactly. |
| Repository interfaces | **23** | Matches prior claim exactly. |
| Flyway migrations | **2** | `V1__init.sql` (26 tables), `V2__seed_reference.sql`. |
| Tables | **26** | |
| REST controllers | **1** | `HealthController`. |
| HTTP endpoints | **1** | `GET /api/v1/health/status`. |
| Real application feature slices | **1** | `health`. Ten other feature packages hold only `package-info.java`. |
| Test methods / executed test cases | **39 / 44** | 39 declared methods; 44 executed, because one `@ParameterizedTest` expands to six cases. The build reports 44. |

**Test suite state:** `make verify` run twice on 2026-09-17 — 44/44 passing, `BUILD SUCCESS`.

**WebSocket:** `host/api/websocket/` still contains exactly one file, `package-info.java`. No broker
configuration, no `@MessageMapping`, no endpoint registration anywhere in `src/main/java`. The
`spring-boot-starter-websocket` dependency is present in `pom.xml`, which makes the capability
available, not built.

---

## 5. Position in the roadmap

| Phase | State |
|---|---|
| Phase 1 — Data foundation | **Complete**, and its documented Neon gap is now closed. |
| Phase 2 — Security and identity | **Sub-phase 2.0 done. 2.1 through 2.9 not started.** |
| Phases 3–7 | Not started. |

Two escalated decisions remain open and still block nothing before sub-phase 2.3: customer
self-registration versus provisioning for all four roles, and stateless refresh versus a persisted
revocable `refresh_token` table. Several smaller policy questions also remain open, the most
consequential being whether human-in-the-loop approval *gates* an action or merely *records* it —
the difference between human-in-the-loop and human-on-the-loop, and an ESPRIT-facing claim.

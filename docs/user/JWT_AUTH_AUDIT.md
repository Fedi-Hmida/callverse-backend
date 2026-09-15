# CallVerse — JWT / Authentication Current-State Audit

| | |
|---|---|
| **Date** | 2026-09-15 |
| **Commit** | `225af017` on `main` |
| **Verdict** | The "documented skeleton" hypothesis is **CONFIRMED in substance** — no filter, no login endpoint, no `UserDetailsService`, no `PasswordEncoder`. But it is **incomplete in three ways**, and **two claims made in this repository's own files are factually wrong** — both disproven by running the application, not by reading it. |

---

## 0. Two corrections, empirically proven

These matter because they appear in `README.md`, in `SecurityConfiguration`'s javadoc, and in `docs/BACKEND_STATE_AUDIT.md` — a document I previously wrote. Both were tested by starting the application against a throwaway PostgreSQL.

### CORRECTION 1 — the app does **not** refuse to start without `JWT_SECRET`

**Claimed** at `application.yml:81-82` (*"the application must refuse to start rather than run on a well-known signing key"*), `README.md:66`, `AbstractPersistenceTest.java:60-61`, and `docs/BACKEND_STATE_AUDIT.md`.

**Test:** started the packaged jar with `JWT_SECRET` unset against a live database.

```
Started CallVerseApplication in 9.425 seconds
placeholder-resolution errors: 0
```

**The application started normally.** Spring resolves a property placeholder **when the property is read**. Nothing reads `jwt.secret` (§3), so `${JWT_SECRET}` is never resolved and the fail-fast never fires.

> **The protection is designed, not active.** It becomes real the moment the first `@Value("${jwt.secret}")` is written — which is the first task of Phase 2. Until then, **do not rely on it**, and do not state it in a report or a defence.

### CORRECTION 2 — a generated credential **is** written to the log on every boot

**Claimed** at `SecurityConfiguration.java:60-63`: *"Without this bean, a deployment that simply forgets to set the profile would fall back to Boot's auto-configured chain and come up with a generated password printed to the log."* The implication is that declaring these beans prevents it.

**Observed in the same probe run:**

```
WARN ...UserDetailsServiceAutoConfiguration :
Using generated security password: 4e0c90eb-4a53-4f3e-8f7c-babc00b1f0aa
This generated password is for development use only...
INFO ...InitializeUserDetailsManagerConfigurer : Global AuthenticationManager
configured with UserDetailsService bean with name inMemoryUserDetailsManager
```

**It happens today, with these beans present.** `UserDetailsServiceAutoConfiguration` backs off on an `AuthenticationManager`, `AuthenticationProvider`, `UserDetailsService` or `JwtDecoder` bean — **not** on a `SecurityFilterChain`. This project declares none of those four, so an `InMemoryUserDetailsManager` is created eagerly and logs a credential.

**Severity: low, but the false assurance is the problem.** No `httpBasic`/`formLogin` entry point exists, so the password is not usable over HTTP. It is a log-hygiene issue and a wrong comment. Declaring a `UserDetailsService` in Phase 2 removes it automatically.

**CORRECTION 2b —** the same javadoc says the fallback gives *"a uniform 401"*. With no `AuthenticationEntryPoint` registered, Spring Security falls back to `Http403ForbiddenEntryPoint`: **the non-dev chain returns 403, never 401.** A frontend written against "401 ⇒ redirect to login" will not fire that branch.

---

## 1. `SecurityConfiguration` — both chains, verified

77 lines total; ~20 executable.

| Line | Item | Status |
|---|---|---|
| `:32` | `@Configuration` | CONFIRMED |
| `:33` | `@EnableWebSecurity` | CONFIRMED present |
| — | `@EnableMethodSecurity` | **CONFIRMED ABSENT** — see §5 |
| — | `@Order` on either bean | **ABSENT** — both default to `LOWEST_PRECEDENCE` |

**Dev chain** (`:43-54`), `@Profile("dev")`: CSRF disabled (`:49`), `STATELESS` (`:50-51`), **`anyRequest().permitAll()` (`:52`)**. No authentication mechanism configured at all — zero hits repo-wide for `httpBasic`, `formLogin`, `addFilterBefore`.

**Default chain** (`:65-76`), `@Profile("!dev")`: CSRF disabled (`:69`), `STATELESS` (`:70-71`), `/actuator/health/**` permitted (`:73`), **`anyRequest().denyAll()` (`:74`)**.

`denyAll()` is not `authenticated()` — it denies **even a fully authenticated principal**. It is a wall, not a gate. In any non-dev profile the only controller in the repository is unreachable, and so is `/v3/api-docs`, which `README.md:95` describes as load-bearing for the Angular client.

**CSRF disabled is justified — CONFIRMED.** A grep for `cookie|HttpSession|JSESSIONID` across `src/` returns exactly one hit: the comment itself at `:47`. No cookie is read or written anywhere. Re-examine **only** if a refresh token lands in an `HttpOnly` cookie.

### The profile trace — CONFIRMED safe in Java, defeated in deployment

With `SPRING_PROFILES_ACTIVE` unset: `getActiveProfiles()` is empty → the *default* profile set `{"default"}` is consulted → `"dev" ∉ {"default"}` → `@Profile("dev")` is **false**, `@Profile("!dev")` is **true**. ⇒ **no profile set means deny-all.** The fail-safe direction.

> ⚠️ **But it never executes in practice.** `docker-compose.yml:40` — `${SPRING_PROFILES_ACTIVE:-dev}` — and `.env.example:43` — `SPRING_PROFILES_ACTIVE=dev`. Running the repository the documented way selects the **permit-all** chain. The correct `!dev` fallback is unreachable through any shipped path.
>
> **The cheapest security fix available in this repository is inverting that compose default to fail closed.**

⚠️ **One env var from unsafe:** setting `spring.profiles.default=dev` would make `@Profile("dev")` match while `getActiveProfiles()` stays empty — an open server whose health endpoint reports its profile as `"default"`. Currently zero hits repo-wide; worth never introducing.

---

## 2. JWT dependencies vs. actual usage

| Artifact | pom | Scope | Status |
|---|---|---|---|
| `jjwt-api` | `pom.xml:150-154` | compile | CONFIRMED |
| `jjwt-impl` | `:155-160` | runtime | CONFIRMED |
| `jjwt-jackson` | `:161-166` | runtime | CONFIRMED |

**`grep -rn "io.jsonwebtoken" src/` → 0 hits in production code, 0 in test code.**

> **Three dependencies are compiled into the fat jar and referenced by not a single line of Java.** `pom.xml:146-149` even documents *"only the API is compiled against"* — nothing is compiled against it.

**The `runtime` scoping is correct and should not be changed.** jjwt exposes `Jwts`/`JwtParser`/`Claims` from `jjwt-api`; `impl` and `jackson` are discovered at runtime via `ServiceLoader`. Runtime scope means code *cannot* accidentally import an internal `io.jsonwebtoken.impl.*` class. **No pom change is required to start using JWT.**

---

## 3. Property wiring — a startup precondition guarding nothing

`application.yml:80-84`:
```yaml
jwt:
  secret: ${JWT_SECRET}              # :83 — no default, deliberately
  expiration-ms: ${JWT_EXPIRATION_MS:3600000}   # :84 — 1 hour
```

| Pattern | Hits in `src/main/java` |
|---|---|
| `jwt.secret` | **1 — a javadoc reference**, `SecurityConfiguration.java:21` |
| `jwt.expiration` | **0** |
| `JWT_SECRET` | **0** |
| `@Value("${jwt` | **0** — the only three `@Value` in the repo are `PlatformMetadataAdapter.java:26-28` |
| `@ConfigurationProperties` | **0** repo-wide |

> **`jwt.secret` and `jwt.expiration-ms` are consumed by ZERO production code — CONFIRMED.**

The irony is sharper than it looks: `AbstractPersistenceTest.java:60-62` injects a test secret with the comment *"application.yml gives jwt.secret no default on purpose, so that the application refuses to start"*. **Per Correction 1, that workaround is unnecessary** — the context starts without it. Every integration test carries a stub for a constraint that does not fire, guarding code that does not exist.

---

## 4. Present / absent inventory

| # | Component | Status | Evidence |
|---|---|---|---|
| 1 | `JwtAuthenticationFilter` / any `OncePerRequestFilter` | **ABSENT** | 0 hits; 0 `addFilterBefore` |
| 2 | `AuthenticationManager` bean | **ABSENT** | 0 hits |
| 3 | `AuthenticationProvider` bean | **ABSENT** | named only in `infrastructure/security/package-info.java:2` prose |
| 4 | `UserDetailsService` impl | **ABSENT** | single hit is javadoc, `SecurityConfiguration.java:22` |
| 5 | **`PasswordEncoder` bean** | **ABSENT** | 0 hits. **Four BCrypt hashes are seeded and nothing can verify them** — see `USER_MANAGEMENT_AUDIT.md` |
| 6 | Login / token / refresh endpoint | **ABSENT** | **0 `@PostMapping` in the entire repository.** One controller, one `@GetMapping` |
| 7 | `SecurityContextHolder` usage | **ABSENT** | 0 hits — nothing anywhere reads the current principal |
| 8 | `@PreAuthorize` / `@Secured` / `@RolesAllowed` | **ABSENT (all three)** | 0 hits each — and see §5 |
| 9 | **CORS configuration** | **ABSENT** | `grep -i cors` over all of `src/` including yml → **0 hits**. The Angular origin cannot call this API from a browser in any profile |
| 10 | WebSocket / STOMP config | **ABSENT** | `host/api/websocket/` contains only `package-info.java` — yet `pom.xml:83-86` ships the starter |
| 11 | STOMP auth interceptor | **ABSENT** | follows from 10 |
| 12 | **`/internal` endpoint** | **ABSENT** | all `internal` hits are prose or `INTERNAL_SERVER_ERROR`. **No route is mapped** |
| 13 | **Service-key mechanism** | **ABSENT** | `X-API-Key`, `ServiceKey`, `ApiKey`, `api_key` → **0 hits each**. `docker-compose.yml:72` points the Python service at the backend with **no credential of any kind** |
| 14 | `@ConfigurationProperties` class | **ABSENT** | 0 hits |

**Present, for contrast:** `@EnableWebSecurity`, two filter chains, `AppUserRepository.findByEmailIgnoreCase` (documented as the login lookup), `app_user` with its role CHECK, four seeded accounts.

> **The data layer for authentication is complete. The mechanism layer is empty.**

**This audit therefore finds that Phase 2 and Phase 3 are both entirely ahead**, not partially done.

---

## 5. `@EnableMethodSecurity` is absent — the silent-failure trap

> **`@EnableMethodSecurity` and `@EnableGlobalMethodSecurity` are both ABSENT. CONFIRMED** (0 matches across `src/`).

`@PreAuthorize`, `@PostAuthorize` and `@Secured` are implemented by a method interceptor **registered only by that annotation**. Without it they are inert metadata: they compile, they pass review, they show in the IDE, and **they are enforced by nothing**. No warning, no log line, no startup failure, and no test fails — because no test exercises security.

This is acute here because `SecurityConfiguration.java:23` explicitly plans *"role-based rules for CUSTOMER, ADVISOR, SUPERVISOR and ADMIN"*. The project has already decided it will use role annotations; the annotation that makes them execute is not present.

**Cost of adding it now: one line, zero behaviour change (there are zero `@PreAuthorize` today). Cost of discovering it after 96 endpoints carry role annotations: a full re-audit.**

**Aggravating factor — see §6:** even once it is added, method-security denials will not produce 403s. **Two independent defects sit on the same future line of code, pointing in opposite directions.**

---

## 6. Auth error shape — off-contract in both directions

`GlobalExceptionHandler` handles five exception types plus a catch-all. **`AuthenticationException` and `AccessDeniedException` are handled NOWHERE — CONFIRMED** (neither is imported or referenced anywhere in `src/`).

`ErrorResponse` (`ErrorResponse.java:22-27`) is the frozen five-field contract `{timestamp, status, code, message, path}`, described at `:9-12` as agreed with the Angular team and the Python service, with `code` as the field clients branch on.

**Filter-chain denials never reach `@RestControllerAdvice`.** The security filter chain runs **before `DispatcherServlet` is entered**; `ExceptionTranslationFilter` commits the response via the entry point. No `@ExceptionHandler` is consulted. So a denial emits Boot's `BasicErrorController` shape — `{timestamp, status, error, path}`, with **`error` where the contract says `code`, and `message` absent** — or an empty body. **The client's single error-handling path breaks precisely on the field it branches on.**

> **The mirror-image bug, already armed.** `AccessDeniedException extends RuntimeException`. Method-security denials are thrown by an AOP interceptor **inside** `DispatcherServlet`, on the MVC side. There, `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)` (`:91`) **matches first**, producing **HTTP 500 `INTERNAL_ERROR` instead of 403**, plus an **ERROR-level stack trace for every routine permission denial** — the exact failure its own javadoc (`:26-31`) says it is designed to avoid.
>
> This is latent **only because `@EnableMethodSecurity` is absent**. Add that one annotation to fix §5 and this activates.

**Both fixes are required, together:** an `@ExceptionHandler({AuthenticationException, AccessDeniedException})` for the MVC side, **and** a custom `AuthenticationEntryPoint` + `AccessDeniedHandler` wired into `exceptionHandling(...)` in **both** chains for the filter side. Doing only one leaves half the denials off-contract.

---

## 7. The refresh-token persistence gap — NAMED FINDING

**Exhaustive search for any table capable of storing token or session state:**

| Scope | Searched for | Result |
|---|---|---|
| `V1__init.sql` | `refresh_token`, `session`, `token`, `revoke`, `jti`, `blacklist`, `denylist` | **ZERO matches** |
| `V2__seed_reference.sql` | same | **ZERO matches** |
| `docs/CALLVERSE_DB_SCHEMA.md` (**authoritative**) | same | **ZERO matches** |
| All 26 entities | same | **ZERO matches** — the only `session` hits are `SessionCreationPolicy.STATELESS` and prose |

**All 26 tables enumerated; not one can hold token state. CONFIRMED.**

### The consequence, precisely

**A JWT issued by this system cannot be revoked before it expires.** Validation is a signature check plus an `exp` comparison, entirely self-contained. The server holds no record of what it issued, so it cannot answer *"is this particular token still permitted?"*

`jwt.expiration-ms` defaults to `3600000` = **exactly 1 hour**. If a token is stolen, the only mitigations are:

1. **Wait out the expiry** — up to 60 minutes of full access at the victim's role. For an `ADMIN` token, an hour of unrestricted access.
2. **Rotate `JWT_SECRET` and restart** — invalidating every token ever issued and **logging out every user simultaneously**. During a jury demo this is indistinguishable from an outage.

There is no third option, and no amount of application code creates one without a persistence surface.

**Two compounding facts:** deactivating the user does **not** help — validation never touches the database, `active` cannot currently be flipped by any code, and `findByEmailIgnoreCase` does not filter on it. And deletion is not an emergency logout — `ON DELETE SET NULL` orphans the profile rather than removing access.

**Refresh-token lifetime is not configured at all** — no `jwt.refresh-expiration-ms` exists in `application.yml` or `.env.example`. The refresh half of the design has no configuration surface yet.

### ⚠️ ESCALATED DECISION — not resolved here. See `ACTION_PLAN.md` §3.

| | **Option 1 — persisted `refresh_token` table** | **Option 2 — stateless rotation** |
|---|---|---|
| Revocation | **Yes.** Logout works; "sign out all devices" possible; refresh-reuse detection (the strongest signal a token was stolen) becomes possible | **No.** Logout is client-side deletion; a copied token works until `exp` |
| Schema cost | **A 27th table in a schema a teammate provisions by hand** — doc edit → teammate applies → `V3` → new entity. A **cross-person round trip that must be scheduled** | **Zero.** Entirely within the backend lot's control |
| Runtime cost | One DB write + read per refresh, against a pool deliberately capped at 8 for Neon | None |
| Other | If the refresh token goes in an `HttpOnly` cookie, **the CSRF analysis changes** | Keeps the current CSRF posture valid |
| Defence | Answers *"how do you log a user out?"* | The honest answer is *"we don't, we wait"* |

`CALLVERSE_PROJECT_CONTEXT.md:516` already lists refresh tokens as the fifth thing to cut, *"a security grading point"*. **What this audit adds: Option 1's real cost is a dependency on another person's availability, not code.** Decide early; if there is any chance of Option 1, batch the schema edit with the other recommendations in one hand-provisioning request.

---

## 8. The service-key mechanism for `/internal` — NAMED FINDING

**It does not exist, and neither do the endpoints it would protect.**

- **No `/internal` route is mapped** — all `internal` hits are prose or `INTERNAL_SERVER_ERROR`.
- **No key mechanism** — `X-API-Key`, `ServiceKey`, `ApiKey` → 0 hits each.
- **No configuration slot** — no `SERVICE_KEY` / `INTERNAL_API_KEY` in `.env.example` or `application.yml`.
- **No credential passed** — `docker-compose.yml:69-72` gives the Python service only `CALLVERSE_BACKEND_BASE_URL`.

`CALLVERSE_PROJECT_CONTEXT.md:300` requires *"Protected by a **service key**, not a user JWT"*. This is a **second, distinct authentication scheme** and must not be folded into the JWT work.

**Recommendation: do NOT build a database-backed key store.** One caller, one key. A single shared secret in configuration, compared in **constant time** by a filter on `/internal/**`, satisfies the requirement with **zero schema change and zero hand-provisioning dependency**. A `service_key` table would buy per-client revocation with exactly one client — unambiguous over-engineering at this scope.

**The real gap to fix is config, not schema:** `.env.example` and `application.yml` have **no slot for this key at all.**

> ⚠️ **Why this outranks login in urgency.** `/internal` is the only surface that can create money (`commercial_credit`), and `CALLVERSE_PROJECT_CONTEXT.md:511` names the `/internal` endpoints **"Never cut."** An unauthenticated `/internal` makes the project's central claim — *"the backend, never the agent, is the authority on business rules"* — nominal: anyone who can reach port 8080 invokes the same credit-granting tools the agent does. And it would land into the `dev` chain's `permitAll`.

---

## 9. Git history

**No authentication work has ever been attempted, committed, or reverted — CONFIRMED.**

20 commits, single branch. No commit message contains `auth`, `jwt`, `login`, `token` or `revert` as a subject term. `git log --all --diff-filter=D` shows four deletions in all of history, none security-related. **`SecurityConfiguration.java` was introduced in `177cc41` and has never been modified since** — it is an original placeholder that was never filled in, not a stripped-down remnant.

---

## 10. The five most consequential gaps, ranked

1. **The shipped deployment default is `dev` = `permitAll`.** The only finding **exploitable today**, and unlike the rest it is not a missing feature but an active misconfiguration in a tracked file. Also the cheapest to fix.
2. **No authentication mechanism at all** — six missing pieces, while `app_user`, four seeded accounts, and a documented login lookup all sit waiting. `SecurityContextHolder` appears nowhere, so even once tokens exist, no code can identify the caller.
3. **`@EnableMethodSecurity` absent, and the catch-all would break it if present.** Two stacked silent failures on one future line. The correct fix is both changes together; either alone is worse than neither.
4. **No `/internal` protection and no service credential** — §8.
5. **The auth error shape is off-contract in both directions, and CORS is absent entirely** — two contract breaks with a consumer being built in parallel in another repository against a document that says these shapes are settled.

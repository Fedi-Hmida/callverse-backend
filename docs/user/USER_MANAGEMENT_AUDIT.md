# CallVerse — User Management Current-State Audit

| | |
|---|---|
| **Date** | 2026-09-15 |
| **Commit** | `225af017` on `main` |
| **Scope** | How `app_user` rows are created, updated and deactivated **today**. |

---

## The one sentence that matters

> # There is currently no way whatsoever to create a new real user through the running application.
>
> The repository contains exactly **one** controller (`HealthController`, a read-only `GET`), there is **no `@PostMapping` anywhere in the codebase**, `AppUserRepository` has **zero callers** in all of `src/main/java`, there is no `CommandLineRunner`, `@PostConstruct` or `data.sql` seeder — so the only users that will ever exist are the **four rows Flyway inserts from `V2__seed_reference.sql`** at migration time.

Adding a user today means a manual `INSERT` against Neon, with a BCrypt hash generated out-of-band.

---

## 1. Creation, update, deactivation — verified

| # | Claim | Status | Evidence |
|---|---|---|---|
| A1 | Exactly 1 `@RestController` | **CONFIRMED** | `HealthController.java:27`. The only other stereotype is `@RestControllerAdvice` at `GlobalExceptionHandler.java:33` |
| A2 | **Zero `@PostMapping` in the entire application** | **CONFIRMED** | Repo-wide grep for all HTTP method annotations returns exactly one hit: `HealthController.java:36` `@GetMapping("/status")` |
| A3 | No use case creates an `AppUser` | **CONFIRMED** | Every `features/*/commands` and `features/*/queries` package holds only `package-info.java`, except `features/health/queries`. `features/auth/commands/package-info.java` is an empty placeholder describing intent |
| A4 | **`AppUserRepository` caller count = 0** | **CONFIRMED** | `grep -rn "AppUserRepository" src/main/java/` returns one line — the declaration itself (`AppUserRepository.java:14`). Callers excluding declaration: **0**. In `src/test/`: **0** |
| A5 | Tests bypass the repository too | **CONFIRMED** | `SchemaValidationTest.java:100,297` construct `new AppUser()` and persist via `EntityManager`; `ConstraintEnforcementTest.java:214,225` use raw SQL |
| A6 | No startup seeder | **CONFIRMED** | Zero hits for `CommandLineRunner`, `ApplicationRunner`, `@PostConstruct`, `ApplicationReadyEvent`. No `data.sql` or `import.sql` in `src/main/resources/` |
| A7 | `V2` seeds exactly 4 accounts, one per role | **CONFIRMED** | `V2:87-100` — `customer@` (`:88`), `advisor@` (`:91`), `supervisor@` (`:94`), `admin@callverse.local` (`:97`), all `active = TRUE`, guarded `ON CONFLICT (email) DO NOTHING` (`:100`) |
| A8 | An `active` flag exists | **CONFIRMED** | `V1:41` `active BOOLEAN NOT NULL DEFAULT TRUE`; `AppUser.java:49-50` |
| A9 | **Nothing in the code can flip `active`** | **CONFIRMED** | Lombok's class-level `@Setter` (`AppUser.java:21`) does generate `setActive`, but `grep -rn "setActive\|setPasswordHash\|setRole\|new AppUser" src/main/java/` returns **zero matches**. `AppUser` is never instantiated or mutated in production code. Deactivation requires a manual `UPDATE` |
| A10 | The `active` flag is load-bearing for auth that does not exist | **CONFIRMED** | `V1:45-46` — *"inactive users are never routed to or authenticated"* + partial index `WHERE active`. The intent is documented; the enforcement is not written. `findByEmailIgnoreCase` (`:17`) does **not** filter `active`, so **a future login will authenticate a deactivated account** unless the check is added by hand |

**Practical consequence:** `app_user` is effectively a read-only, Flyway-provisioned fixture. There is no sign-up, no admin user-creation endpoint, no deactivation path, and no password-change path.

---

## 2. The seeded hashes — a named dead-end

| # | Claim | Status | Evidence |
|---|---|---|---|
| B1 | All four hashes use the `$2a$10$` prefix | **CONFIRMED** | measured from `V2:89,92,95,98` |
| B2 | All four are exactly 60 characters | **CONFIRMED** | BCrypt canonical length |
| B3 | 60 chars fits `VARCHAR(100)` | **CONFIRMED** | `V1:36`, `AppUser.java:35`. 40 chars of headroom — enough for a later `{bcrypt}` prefix (68 total) without a schema change |
| B4 | **No `PasswordEncoder` bean anywhere** | **CONFIRMED** | Exhaustive grep for `PasswordEncoder`, `BCrypt`, `DelegatingPassword`, `Argon2`, `Scrypt` across `src/main` and `src/test` returns **exactly one hit, and it is a comment**: `AppUser.java:34`. Corroborated independently: the **complete** set of `@Bean` methods in the application is four — `HealthFeatureConfiguration.java:49,54` and `SecurityConfiguration.java:43,65` — none returns a `PasswordEncoder` |
| B5 | `spring-boot-starter-security` is on the classpath | **CONFIRMED** | `pom.xml:140-143`, resolving Spring Security 6.3.3 |
| B6 | **Spring Boot does NOT auto-configure a `PasswordEncoder`** | **CONFIRMED — verified against the actual jars** | `UserDetailsServiceAutoConfiguration` *consumes* one via `ObjectProvider<PasswordEncoder>` and never declares one; `getIfAvailable()` returning null is the expected path, after which Boot prefixes the generated password with `{noop}`. Spring Security's `LazyPasswordEncoder` is a plain `static class` instantiated with `new` inside `HttpSecurityConfiguration`, **not** a `@Bean`. `@Autowired PasswordEncoder` in this codebase would fail with `NoSuchBeanDefinitionException` |

> ### NAMED FINDING — unverifiable committed credentials
>
> **The application ships four committed BCrypt password hashes that nothing in the running system is capable of verifying.**
>
> The plaintext (`CallVerse!Dev2026`) is documented at `README.md:282-290` and deliberately kept out of the SQL comment (`V2:81-83` — a genuinely good decision). The hashes reach the database. But there is no `PasswordEncoder`, no `UserDetailsService`, no `AuthenticationProvider`, no login endpoint, and no caller of `findByEmailIgnoreCase`. **`BCrypt.matches(raw, hash)` is never invoked anywhere, and cannot be without new code.**
>
> This is not a vulnerability — nothing is exposed, because nothing authenticates. It is a precise **dead-end**: the seed data is written against a contract that has no implementation on either side.

**Two traps to record now, before Phase 2 starts:**

1. **Do not use `PasswordEncoderFactories.createDelegatingPasswordEncoder()`.** It expects a `{bcrypt}` prefix and **will reject all four seeded hashes** with `There is no PasswordEncoder mapped for the id "null"`. Either declare a bare `BCryptPasswordEncoder`, or re-seed the hashes with the prefix.
2. ⚠️ **UNVERIFIED — the hashes have never been checked against the documented plaintext.** Running `BCrypt.matches("CallVerse!Dev2026", <hash>)` requires executing code and was out of scope for a read-only audit. **Verify this before a demo.** A mismatched seed hash presents as "login is broken" on the day the login endpoint ships, with an invisible cause.

---

## 3. FK and enum conformance — zero divergences

| # | Claim | Status | Both sides |
|---|---|---|---|
| C1–C2 | `customer.user_id` nullable + `ON DELETE SET NULL` | **CONFIRMED** | `V1:58` (no `NOT NULL`) ⟷ `Customer.java:49-51` (no `optional=false`, no `nullable=false`) |
| C3–C4 | `advisor.user_id` nullable + `ON DELETE SET NULL` | **CONFIRMED** | `V1:130` ⟷ `Advisor.java:51-53` |
| C5 | Both sides agree | **CONFIRMED** | `ddl-auto: validate` (`application.yml:36`) would fail startup on a nullability mismatch — a second, independent guarantee |
| C6 | `UserRole` matches the CHECK character for character, in order | **CONFIRMED** | `V1:39-40` ⟷ `UserRole.java:11-16`; `@Enumerated(EnumType.STRING)` at `AppUser.java:44`; `length=20` both sides |
| C7 | The authoritative document agrees with `V1` | **CONFIRMED** | `CALLVERSE_DB_SCHEMA.md:30-43` is byte-identical to `V1:33-46` including the partial index |

### `AppUserRepository`, in full

```java
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmailIgnoreCase(String email);   // :17
    boolean existsByEmailIgnoreCase(String email);           // :19
}
```

`findByEmailIgnoreCase` **is** the login lookup, backed by the unique index on `email` (`V1:35`), returning `Optional` — correct for the "user not found" branch.

> ⚠️ **Gap: there is no `findByEmailIgnoreCaseAndActiveTrue`.** `idx_user_role` is partial `WHERE active` and `V1:45` states *"inactive users are never routed to or authenticated"* — **the schema's own stated intent is not expressible through the repository as written.** The `active` check must be hand-written in the login use case, and is exactly the kind of thing that gets forgotten. **Add the variant when the login use case is written** — a change in this repo, no hand-provisioning involved.

---

## 4. Constraints that do not exist

Three findings carried from `USER_TAXONOMY.md`, restated here because they are user-management concerns:

| Finding | Evidence | Consequence |
|---|---|---|
| **No `UNIQUE` on `customer.user_id` or `advisor.user_id`** | `V1:58`, `:130` — verified, 0 matches | Two profiles may share one account; **one `app_user` can be simultaneously a customer and an advisor**, holding their own customer file and their own `credit_limit`. Also means `findByUserId` must return a collection, so the first hop of every ownership rule is ambiguous |
| **No CHECK enforcing `is_simulated ⇒ user_id IS NULL`** | `V1:56-69`, `:128-137` | The "simulated actors can never authenticate" guarantee is conventional, not structural |
| **No role constraint on the three approver FKs** | `V1:230`, `:241`, `:416` | A `CUSTOMER` is a schema-valid approver — see `OWNERSHIP_RULES.md` §C |

---

## 5. What else auth needs that the schema lacks

All five are **absent**. The honest assessment differs sharply between them — recommending everything is as useless as recommending nothing.

| Capability | Present? | Needed at this scope? | Recommendation |
|---|---|---|---|
| **Failed-login tracking / lockout** | ABSENT | **No — over-engineering** | Four seeded accounts with a published password. BCrypt cost 10 is already ~100 ms per attempt, a meaningful rate limit by construction. **Decline, and record the reason** — lockout also introduces a DoS vector (an attacker locks out any user by guessing wrong) |
| **Password reset tokens** | ABSENT | **No — over-engineering** | Requires an email delivery path this project does not have and should not acquire. With four fixed accounts, reset has no user. **Decline** |
| **`last_login_at` on `app_user`** | ABSENT (`created_at` present, `V1:42`) | **Marginal** | One nullable `TIMESTAMPTZ`. Useful for the demo narrative and the cheapest gesture toward an audit story. **Add only if a schema round trip is already happening for another reason.** Do not trigger one for this alone. Do **not** add a general audit table — `CALLVERSE_PROJECT_CONTEXT.md:513` already lists it as the second thing to cut |
| **Service-key store for `/internal`** | ABSENT | **YES — genuinely needed** | But **not as a table.** One caller, one key ⇒ a single config secret compared in constant time. **Zero schema change.** The real gap is that `.env.example` and `application.yml` have **no slot for it** |
| **Supervisor-to-advisor team scoping** | ABSENT | **No — and actively harmful** | One simulated contact centre. `CALLVERSE_PROJECT_CONTEXT.md:329` scopes **advisors** to their own queue, not supervisors; `/topic/supervision/kpi` is centre-wide **by design**. Partitioning would fragment the KPI dashboard that is the headline deliverable. **Decline and document**: *"one centre, one supervisory scope; SUPERVISOR is centre-wide by design"* — a design statement, not a gap |

**Of the five, only the service key is genuinely needed — and its recommended form requires no schema change at all.**

---

## 6. Consolidated recommendations

Nothing below was applied. Schema items are recommendations for `docs/CALLVERSE_DB_SCHEMA.md` and hand-provisioning.

| # | Recommendation | Type | Hand-provisioning? | Urgency |
|---|---|---|---|---|
| 1 | **Decide the refresh-token question** (`JWT_AUTH_AUDIT.md` §7) | **ESCALATED — owner's call** | Option 1 yes, Option 2 no | **Decide early** — Option 1's cost is a cross-person round trip |
| 2 | Declare a bare `BCryptPasswordEncoder` bean — **not** delegating, or the four seeded hashes break | Code | No | First commit of Phase 2 |
| 3 | Verify the four hashes actually match `CallVerse!Dev2026` | Verification | No | **Before any demo.** ⚠️ currently unverified |
| 4 | Add `INTERNAL_API_KEY` to `.env.example` + `application.yml`; constant-time compare in a filter on `/internal/**`. **No table** | Config + code | **No** | With Phase 3 |
| 5 | Add `findByEmailIgnoreCaseAndActiveTrue`, or enforce `active` explicitly in the login use case | Code | No | With the login use case |
| 6 | `UNIQUE` on `customer.user_id` and `advisor.user_id` | Schema | Yes — **batch with #1** | High — blocks unambiguous principal resolution |
| 7 | `CHECK (NOT is_simulated OR user_id IS NULL)` on both profile tables | Schema | Yes — **batch with #1** | Medium — makes a convention structural |
| 8 | `last_login_at TIMESTAMPTZ` | Schema | Yes — **batch with #1 only** | Low |
| 9 | Record the declines explicitly: no lockout, no password reset, no audit table, no supervisor team scoping — each with its reason | Documentation | No | Before the report |

**Zero divergences** were found between `docs/CALLVERSE_DB_SCHEMA.md`, `V1__init.sql` and the entities on every `app_user`-related surface. **Every gap in this report is a gap of absence, not of error.**

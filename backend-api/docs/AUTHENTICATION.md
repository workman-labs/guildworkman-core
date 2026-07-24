# Authentication, RBAC & Refresh-Token Rotation

Implements [#23](https://github.com/workman-labs/guildworkman-core/issues/23):
Spring Security JWT authentication with role-based authorization and secure
refresh-token rotation (revocation + reuse detection).

## New dependencies

| Dependency | Why |
|---|---|
| `spring-boot-starter-validation` | `@Valid` bean validation on the auth request DTOs |
| `springdoc-openapi-starter-webmvc-ui` `2.6.0` | OpenAPI 3 spec + Swagger UI (`/swagger-ui.html`, `/v3/api-docs`) |

`com.auth0:java-jwt` and `spring-boot-starter-security` were already present.

## Architecture decisions

1. **A dedicated `UserAccount` auth principal**, separate from the `Client` /
   `SkilledWorker` domain entities. Those model marketplace data; a
   `UserAccount` models a login identity (email + BCrypt hash + one `Role`).
   Keeping them apart let this feature ship without reworking the existing
   domain registration/login, and a `UserAccount` can later be linked to a
   profile without a schema change. The legacy `ClientServiceImpl.login` /
   `JwtUtils` stub is intentionally left untouched; migrating it onto this
   flow is a documented follow-up.

2. **Access token = short-lived JWT; refresh token = opaque and server-side.**
   The access token (HS256, default 15 min) carries `sub` (user id), `email`
   and `role`, so per-request authorization needs no database hit. The refresh
   token is *not* a JWT — it is 256 bits of CSPRNG output, stored only as a
   SHA-256 hash, so a database leak exposes no usable tokens.

3. **Rotation with families + reuse detection.** Every refresh rotates: the
   presented token is revoked and a successor is issued in the same *family*
   (a login-session lineage). Presenting an already-revoked token means it was
   replayed after the legitimate client moved on — so the **entire family is
   revoked**, killing the session. See `RefreshTokenServiceImpl`.

4. **Reuse revocation runs in its own committed transaction.** Detection
   happens inside `rotate()`, which then throws to signal the caller — and that
   throw would roll back the enclosing transaction, undoing the revocation. So
   the family revoke is delegated to `RefreshTokenFamilyRevoker`
   (`@Transactional(REQUIRES_NEW)`), which commits regardless. This was a real
   bug caught by the integration test before it could ship.

5. **Concurrency-safe rotation.** The revoke-old step is an atomic conditional
   update (`revokeIfActive`: `UPDATE … WHERE id=? AND revoked=false`). Two
   simultaneous refreshes of the same token cannot both mint a successor — the
   database serialises the row update; the loser is treated as reuse and the
   family is revoked.

6. **RBAC** via URL rules (`/api/v1/admin/** → hasRole('ADMIN')`) plus
   `@EnableMethodSecurity` for `@PreAuthorize` on handlers. Self-registration
   can never create an ADMIN (privilege-escalation guard in `AuthServiceImpl`).

7. **Consistent error contract.** All failures keep the existing
   `{ "error": …, "success": false }` shape (validation errors add a
   `fieldErrors` map), with accurate status codes — 400 validation, 401
   auth/refresh, 403 forbidden, 409 duplicate email — rendered uniformly,
   including for filter-level 401/403 via `RestAuthenticationEntryPoint` /
   `RestAccessDeniedHandler`.

## Configuration

| Property | Env | Default |
|---|---|---|
| `security.jwt.secret` | `JWT_SECRET` | dev-only placeholder (override in prod, ≥32 bytes) |
| `security.jwt.issuer` | `JWT_ISSUER` | `guildworkman-api` |
| `security.jwt.access-token-ttl` | `JWT_ACCESS_TTL` | `PT15M` |
| `security.jwt.refresh-token-ttl` | `JWT_REFRESH_TTL` | `P7D` |

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | Create account (CLIENT/SKILLED_WORKER only), returns token pair |
| POST | `/api/v1/auth/login` | public | Authenticate, returns token pair |
| POST | `/api/v1/auth/refresh` | refresh token | Rotate → new access + refresh token |
| POST | `/api/v1/auth/logout` | refresh token | Revoke the token's whole family |
| GET | `/api/v1/auth/me` | access token | Current caller's identity |
| GET | `/api/v1/admin/ping` | access token + ADMIN | RBAC demonstration |

`AuthenticationResponse`: `{ accessToken, refreshToken, tokenType: "Bearer",
expiresIn, userId, email, role }`, wrapped in the standard `{ data, status }`.

## Data model

- `user_accounts` — `id, email (unique), password (bcrypt), role, enabled, …`
- `refresh_tokens` — `id, tokenHash (unique), userAccountId, familyId,
  expiresAt, revoked, createdAt`

Tables are created by Hibernate (`ddl-auto=update`).

## Testing

- **Unit** (`JwtServiceTest`, `RefreshTokenServiceTest`) — no Spring/DB; cover
  JWT round-trip/expiry/tampering and rotation, expiry, reuse, race-loss.
- **Integration** (`AuthIntegrationTest`, `@SpringBootTest` + MockMvc) — full
  register/login/refresh/logout flow, RBAC 403/200, reuse → 401, and a
  **concurrent-refresh** test asserting at most one winner and a fully-revoked
  family. Runs against the Postgres service in CI (`test.yml`).

Maven dependencies are cached in CI via `actions/setup-java`’s `cache: maven`
in `.github/workflows/test.yml`.

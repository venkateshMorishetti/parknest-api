# ParkNest API

The backend for [ParkNest](https://github.com/venkateshMorishetti/parknest), the peer-to-peer parking web app. It is
built with Spring Boot 3.4, Java 21, PostgreSQL 16 and Flyway.

Every response uses the same envelope as the frontend's mock data:

```json
{ "success": true, "status": 200, "message": "OTP sent", "data": { }, "errors": null,
  "meta": { "requestId": "req_01M7...", "timestamp": "2026-11-17T22:18:59+05:30", "apiVersion": "v1" } }
```

A failed request has `"success": false`, `"data": null`, and `errors: [{ "code", "field", "message" }]`.

## Run it locally

```bash
docker compose up -d                                  # PostgreSQL on localhost:5432 (parknest_local and parknest_test)
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run   # API on http://localhost:8080
```

* The API is served at `http://localhost:8080/api/v1/...`.
* API docs are at `http://localhost:8080/swagger-ui.html` (off in production).
* The `local` profile changes a few things for development:
  * Every OTP is `123456`. The code is written to the log instead of being sent by SMS.
  * The frontend's mock Google button (`mock-google-id-token`) works.
  * Development secrets are filled in, so nothing needs setting up.
* Point the frontend at it with its `testing` proxy, or change `proxy/*.json` to `http://localhost:8080`.

```bash
./mvnw verify          # integration tests against parknest_test (real PostgreSQL)
```

## Environments

| Profile | Where | Notes |
|---|---|---|
| `local` | your machine | fixed OTP 123456, mock Google sign-in, dev secrets |
| `testing` | api.testing.parknest.in | `OTP_FIXED_CODE` allowed until an SMS provider is connected |
| `production` | api.parknest.in | random OTPs only, API docs off |

Settings come from environment variables (see [`.env.example`](.env.example)):

* `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
* `JWT_SECRET`, at least 32 characters
* `OTP_PEPPER`
* `OTP_FIXED_CODE`
* `GOOGLE_CLIENT_ID`
* `CORS_ALLOWED_ORIGINS`
* `PORT`

Each environment has its own database. Flyway creates and updates the tables on startup from
`src/main/resources/db/migration`. `V1__initial_schema.sql` is the 17-table schema; its design notes are in the
frontend repo's `db/README.md`. New changes go in new `V2__...sql` files; applied files are never edited.

## How it works

* **Code layout:**
  * `com.parknest.api.common`: envelope, errors, IDs (`usr_` + ULID) and request ids.
  * `config`: security, CORS and settings.
  * One package per area: `auth` and `users` so far.
* **Database access:** plain SQL with Spring `JdbcClient`, written against the schema. There is no ORM.
* **Sign-in:**
  * Phone OTP or Google. A user is created on first sign-in.
  * OTP codes are stored as an HMAC. The limits are a 30-second wait before resending, 5 codes per 15 minutes per
    number, and 5 tries per code.
  * The access token is a JWT that lasts 1 hour. The refresh token lasts 30 days, is stored only as a hash, and is
    replaced on every refresh.
* **Every account needs a verified mobile number.** A Google sign-in without one gets `403 PHONE_NOT_VERIFIED`
  everywhere except `GET/PUT /users/me` and `/users/me/phone/*`. The app sends that user to `/verify-phone`.
* **Blocked users** get `403 ACCOUNT_BLOCKED`, checked on every request.
* **Admin routes** (`/api/v1/admin/**`) need the `ADMIN` role, which is read from the token's `roles` claim.
* **SMS:** `LogSmsSender` writes codes to the log. Add a real provider (MSG91, Twilio...) as an `SmsSender` bean
  marked `@Primary` before production.

## Endpoints

These follow the contracts in the frontend's `public/mock-data` (`endpoints.json`).

| Area | Endpoint | Status |
|---|---|---|
| Sign-in | `POST /auth/otp/send`, `POST /auth/otp/verify`, `POST /auth/google` | Done |
| | `POST /auth/refresh`, `POST /auth/logout` | Done |
| Profile | `GET /users/me`, `PUT /users/me` | Done |
| | `POST /users/me/phone/otp`, `POST /users/me/phone/verify` | Done |
| | `POST /users/me/vehicles`, `PATCH` and `DELETE /users/me/vehicles/{id}` | Done |
| Search and spots | `POST /listings/search`, `GET /listings/{id}`, `POST /listings/{id}/quote`, `GET /meta/search-options` | Next |
| | `GET /locations/suggest`, `GET /locations/reverse`, `GET /content/home`, `GET /content/host` | Next |
| Hosting | `GET` and `POST /host/onboarding`, `PUT /host/payout-account`, `GET /host/settings`, `PUT /host/settings/notifications` | Next |
| | `POST /uploads`, `POST`, `GET` and `PUT /host/listings`, `PATCH /host/listings/{id}/status` | Next |
| Bookings | `POST /bookings`, `GET /bookings`, `GET` and `PATCH /bookings/{id}`, check-in, check-out | Planned |
| | `GET /bookings/{id}/cancellation`, `POST /bookings/{id}/cancel` | Planned |
| | `GET /host/dashboard`, `POST /host/booking-requests/{id}/decision`, `GET /host/bookings`, `GET /host/earnings` | Planned |
| Payments | `POST /payments/verify`, payment gateway webhooks, refunds, payouts | Planned (gateway not chosen) |
| Admin | overview, spots review, users, bookings, refunds, payouts, activity | Planned |

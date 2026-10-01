# TradeVision Backend — Startup Guide

Review finding (P1 #30 — "Documentation is inconsistent with the actual application"): this
guide previously referenced a specific MongoDB Atlas cluster hostname from an earlier version of
the project — the same one SETUP.md's security-rotation section already warns about. Removed
here; a real hostname belongs only in your own deployment's environment variables, never in a
committed document.

## What confirms it's working

Look for this line in the startup logs:
```
Started TradeVisionApplication in N seconds
```
That means the app is up on port 8080.

## SSL errors during startup against MongoDB Atlas

If you're pointing at a real Atlas cluster (production profile) and see something like:
```
MongoSocketWriteException: Exception sending message
Caused by: SSLException: internal_error
```
This is usually not fatal — Atlas runs multiple replica nodes, and the driver's TLS handshake can
fail against one node on the first attempt and succeed on retry. If the app fails to start
entirely rather than just logging a retried warning, check `MONGODB_URI` and your Atlas network
access list first.

## "Using generated security password"

If you see:
```
Using generated security password: <uuid>
```
This means Spring Security's default form-login auto-configuration is active — it shouldn't be,
since this app uses JWT auth exclusively. Check that `SecurityConfig`'s filter chain bean is
actually being picked up (it disables form login as part of its own configuration).

## Test the API — local development

`application-local.properties` sets `app.otp.allow-console-fallback=true`, so with no external
SMS/email provider configured, OTPs print straight to your console:

```bash
# Register (Step 1 - send OTP)
curl -X POST http://localhost:8080/api/auth/register/initiate \
  -H "Content-Type: application/json" \
  -d '{"firstName":"Test","mobile":"9876543210"}'

# Your console will show:
# ==========================================
#   OTP for 9876543210: 482931
# ==========================================

# Register (Step 2 - verify OTP)
curl -X POST http://localhost:8080/api/auth/register/verify \
  -H "Content-Type: application/json" \
  -d '{"mobile":"9876543210","otp":"482931"}'
```

In production, `app.otp.allow-console-fallback` is `false` — a misconfigured deployment with no
real SMS/email provider will return an honest delivery-failure error instead of logging the OTP.

## Database

Database name is `trade_vision` — configured via `spring.data.mongodb.database` in the common
`application.properties`, pointed at either a local Docker MongoDB (dev) or your own Atlas
cluster via `MONGODB_URI` (production) — see SETUP.md for both.

Collections are created automatically on first use:
- `users` — user profiles + favorites
- `otp_records` — OTPs (hashed, never stored raw), auto-expire after 5 minutes via TTL index
- See README.md for the fuller collection list.

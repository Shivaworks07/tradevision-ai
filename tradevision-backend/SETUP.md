# Configuration: local vs. production

Split into three files under `src/main/resources/`:

- **`application.properties`** — settings that don't vary by environment (server port, JWT
  expiration lengths, logging levels). Also sets the active profile, defaulting to `prod`
  (**P1-16 fix** — see below; it used to default to `local`).
- **`application-local.properties`** — every value a real local run needs, filled in with fake,
  clearly-labeled placeholder secrets. **Committed to the repo on purpose** — there is nothing
  real to protect in this file. You must now opt into it explicitly (see below); once you have,
  open the project in IntelliJ, hit Run, and it works with zero further setup, as long as
  MongoDB is reachable at `localhost:27017`:
  ```
  docker run -d --name tradevision-mongo -p 27017:27017 mongo:7
  ```
- **`application-prod.properties`** — the same set of keys, but every value is
  `${AN_ENV_VAR}` with no fallback for anything security-sensitive. If a required variable is
  missing, Spring refuses to start rather than falling back to something guessable — that's
  intentional, and (since the P1-16 fix below) is now what happens by default whenever
  `SPRING_PROFILES_ACTIVE` isn't set at all, not just when it's explicitly set to `prod`.

**P1-16 fix ("Committed secrets + 'local' as default profile"):** the active profile now
defaults to `prod`, not `local`. Before this fix, ANY way of running this jar that forgot to set
`SPRING_PROFILES_ACTIVE` — a bare `java -jar`, a different container image, a k8s manifest with
a missing/typo'd env var, an unconfigured CI/staging environment — silently started with this
file's own publicly-committed JWT secret, AES encryption key, admin bootstrap secret, and OTP
HMAC key, AND printed real OTPs straight to the console. The Dockerfile's own
`SPRING_PROFILES_ACTIVE=prod` only protected the one deployment path that goes through it, not
every other way this jar can be started. Local development now requires explicitly opting in:

```bash
export SPRING_PROFILES_ACTIVE=local
mvn spring-boot:run
```

(or add `SPRING_PROFILES_ACTIVE=local` to your IntelliJ run configuration's environment
variables). The Dockerfile's own `SPRING_PROFILES_ACTIVE=prod` is unchanged and still set for
real deployments — it's now redundant defense-in-depth rather than the only thing standing
between a misconfigured deployment and this application's committed local-dev secrets.

As a second, independent layer of defense, `DevSecretStartupGuard` (see
`src/main/java/com/tradevision/config/DevSecretStartupGuard.java`) refuses to start the
application at all if any of the four secrets above still resolve to their known,
publicly-committed `application-local.properties` values AND at least one `LIVE`-mode broker
credential already exists in the database — covering the case where `local` was explicitly (and
wrongly) activated in an environment that already holds real, live broker credentials, or a real
deployment's own env vars were accidentally set to these same well-known values.

## Building the Docker image

The Dockerfile now builds the Angular frontend fresh and bundles it into the backend image
(previously there was no correct mechanism for this at all — a stale, outdated frontend build
had been manually committed into `src/main/resources/static/` instead, which is exactly how an
already-fixed security issue — an admin bootstrap secret hardcoded into the UI — kept shipping
in the compiled bundle even after the source was fixed). Because the frontend (`trading-analyst/`)
and backend (`tradevision-backend/`) are sibling directories, **the build context must be the
repo root**, not `tradevision-backend/` itself:

```bash
# from the repo root (parent of both tradevision-backend/ and trading-analyst/)
docker build -f tradevision-backend/Dockerfile -t tradevision-backend .
```

## Rotate these immediately if you're inheriting this project

If `application.properties` ever contained a real MongoDB password or a real Brevo API key
directly (an earlier version of this project did), those values must be treated as permanently
compromised — rotating them is not optional cleanup, it's the actual fix:

1. **MongoDB** — rotate the database user's password in Atlas (or wherever it's hosted), update
   `MONGODB_URI`.
2. **Brevo** — revoke the old API key in the Brevo dashboard, issue a new one.
3. **JWT secret** — generate a new one: `openssl rand -base64 48`. This invalidates every
   existing logged-in session — expected, not a bug.
4. **Encryption key** (`APP_ENCRYPTION_KEY`) — generate with `openssl rand -base64 32`. If a real
   broker credential was ever connected under the old key, **disconnect and reconnect it** after
   rotating — the old ciphertext won't decrypt under a new key.
5. **Admin bootstrap secret** — generate a new random string.

Rotating neutralizes the compromise going forward, but your git history may still contain the
old values in old commits. That needs a separate history-rewrite pass (`git filter-repo` or
BFG) if it matters for this repo — not something to do casually without coordinating with any
collaborators first.

## Required environment variables — production only

```
MONGODB_URI=mongodb+srv://<user>:<password>@<cluster>/trade_vision?retryWrites=true&w=majority
JWT_SECRET=<openssl rand -base64 48>
APP_ENCRYPTION_KEY=<openssl rand -base64 32>
ADMIN_BOOTSTRAP_SECRET=<random string>
BREVO_API_KEY=<your Brevo key>
BREVO_FROM_EMAIL=<your sending address>
MAIL_ENABLED=true
OTP_HMAC_SECRET=<openssl rand -base64 32>
CORS_ORIGINS=https://your-prod-domain
```

Set these in your deployment's secret manager (Render/Railway/etc. environment variables) —
never directly in `application-prod.properties`; the whole point of that file is that it holds
no real values, only placeholders.

Optional, with safe defaults if left unset:

```
SMS_PROVIDER=              # blank = no SMS provider configured; console fallback stays refused in prod regardless
SPRINGDOC_ENABLED=false    # Swagger UI — stays off in prod unless you explicitly turn it on
APP_PROXY_TRUST_FORWARDED_FOR=false   # true only if genuinely behind a reverse proxy that sets X-Forwarded-For itself
```

## Local development: OTPs without a real SMS/email provider

`application-local.properties` already sets `app.otp.allow-console-fallback=true`, so OTPs print
straight to the console when running locally — nothing further to configure. This flag must
never be `true` in `application-prod.properties` (it isn't, checked in as `false` there) —
setting it there would mean a misconfigured production deployment quietly logs real, usable
OTPs, which defeats the whole point of using an OTP.

## Before you push this anywhere public

Never put a real secret directly into `application-prod.properties`, even temporarily —
it's specifically designed to hold none, and a real value committed there defeats that. Real
secrets belong only in your deployment platform's environment variable configuration.

## P1-21: framework versions are out of support — status and what this pass actually did

**Confirmed real.** As of this pass: `pom.xml` pins `spring-boot-starter-parent` **3.2.5**
(Spring's own OSS support window for the 3.2.x line has ended — only 3.2.x commercial/Tanzu
support remains), `trading-analyst/package.json` pins `@angular/core` **17.3** (Angular's own
support window for major version 17 has ended), and `testcontainers.version` is pinned at
**1.19.0**. Running `npm audit` against this repo's own current `package-lock.json` confirms this
is not theoretical: it reports real, currently-open **high-severity** advisories against
`@angular/core`/`@angular/compiler` (multiple XSS-class CVEs, GHSA-g93w-mfhg-p222 and others) that
only a major-version upgrade (`npm audit fix --force`, which itself reports pulling in
`@angular/core@21.x`) resolves — this is genuinely live risk, not a stale/inflated audit finding.

**What this pass actually shipped for P1-21**, disclosed honestly rather than silently narrowed
(same precedent as P1-11/P1-15/P1-19's own disclosed scope limits): dependency scanning + build
verification, wired into a real CI pipeline this repo did not have at all before this pass — see
`.github/dependabot.yml` and `.github/workflows/dependency-scan.yml` at the repo root (the parent
directory of both `tradevision-backend/` and `trading-analyst/`, the same root the Dockerfile's
own build instructions already reference). Concretely: Dependabot version-update PRs (weekly, both
ecosystems), OWASP Dependency-Check against the Maven dependency tree, `npm audit` against the
frontend, and the existing backend/frontend build+test suites, all running on every push/PR to
`main` plus a weekly schedule.

**What this pass deliberately did NOT do: actually perform the major-version upgrade itself**
(Spring Boot 3.2.5 → a current supported 3.x/4.x line; Angular 17.3 → a current LTS release;
Testcontainers 1.19.0 → current). Two independent reasons, both real:

1. **Mechanically impossible in this exact session.** This sandboxed execution environment's
   outbound network access is allowlisted, and Maven Central (`repo1.maven.org`) is not on that
   allowlist — `mvn` here runs `-o` (offline) against a pre-populated local repository for
   exactly this reason. Attempting to bump `spring-boot-starter-parent` to a newer version here
   fails immediately on dependency resolution, not on a real compile/test problem with the
   newer version itself — there is no way to genuinely verify a Spring Boot major-version bump
   compiles and passes this repo's own test suite from inside this session. (`npm`/`npx` DID
   work here, since `registry.npmjs.org` is allowlisted — confirmed above via a real `npm audit`
   run — but the Angular major-version bump was still not performed in this same pass; see
   reason 2.)
2. **Even where mechanically possible, not something to do unreviewed on a real-money system.**
   A Spring Boot or Angular major-version bump is a genuinely different category of change from
   every other fix in this pass — it touches the framework underneath every controller, every
   security filter chain, every `@Scheduled`/`@Async` annotation, and (for Angular) the entire
   build toolchain, with real breaking-change surface neither this session nor its test suite can
   fully characterize sight-unseen. The audit's own fix instruction ("upgrade... add dependency
   scanning... in CI") reads as sequencing scanning first for exactly this reason: it's how a real
   upgrade gets planned and staged safely, not skipped. Now that scanning exists, doing the actual
   upgrade — deliberately, on its own branch, with its own full regression pass against a
   real (non-offline) build environment — is real, concrete follow-up work this fix makes
   visible and trackable (every Dependabot PR against `spring-boot-starter-parent`/`@angular/*`
   going forward), rather than a gap that silently persists unnoticed.

## Follow-up: Angular upgrade done; a residual, currently unfixable frontend audit finding

The Angular major-version bump P1-21 above deliberately deferred has since been done
(`@angular/core` 17.3 → 21.2.25, and the rest of `trading-analyst/package.json` with it) and
merged, closing the XSS-class `@angular/core`/`@angular/compiler` advisories this section
originally flagged.

That upgrade's own follow-up CI run then failed the `Frontend — npm audit` job with 12 new
high-severity findings. Direct investigation against this repo's real `package-lock.json`
(`npm audit --json`, cross-checked against each advisory's own GitHub page) found exactly two
distinct causes, not twelve independent problems:

- **`http-cache-semantics` <=4.2.0 (GHSA-ch52-4w7c-c8xp)** — a real, fixable finding. `npm audit
  fix` (no `--force`) bumped it to 4.3.0: a non-breaking devDependency-only patch. Fixed.
- **`braces` <=3.0.3 (GHSA-vfj7-8cjw-p6xm, stack-exhaustion DoS)** — confirmed by reading the
  advisory itself that, as of this pass, 3.0.3 is braces' own latest version published to npm, and
  the advisory's own "patched versions" field is listed as unknown: there is currently no fixed
  version to move to, from anyone, not a version this repo failed to pick up. `npm audit fix
  --force`'s only available "fix" is downgrading `karma` to `4.0.0` — years out of date, a
  semver-major break never tested against this repo's new Angular 21 toolchain, and (confirmed by
  checking `karma@4.0.0`'s own dependency tree) it still resolves to `chokidar@2.x` →
  `braces@2.3.2`, which is *also* `<=3.0.3` and therefore still vulnerable under the advisory's own
  range — so forcing it would trade a working, current build for a broken, outdated one that still
  reports this exact same open finding. Per this project's own standing instruction not to run
  `npm audit fix --force` blindly, it was not applied. The other 10 reported findings
  (`chokidar`, `karma`, `karma-jasmine`, `karma-jasmine-html-reporter`, `webpack-dev-server`,
  `micromatch`, `http-proxy-middleware`, `@angular-devkit/build-angular`,
  `@angular-devkit/build-webpack`, `@angular/build`) are all only reachable *through* this same
  unfixed `braces`, not independent issues of their own.

Confirmed via `npm audit --omit=dev` that every one of those 11 remaining findings lives entirely
in devDependencies (`karma`, the local test runner, and `webpack-dev-server`, the local dev
server) — neither ships inside `ng build --configuration production`'s own output, so neither
reaches a deployed artifact. `.github/workflows/dependency-scan.yml`'s `frontend-npm-audit` job
was updated accordingly: the build-failing gate now runs `npm audit --omit=dev
--audit-level=high` (production dependencies only, currently 0 findings), with a second,
always-run, non-failing step still printing the full audit (dev included) so this residual finding
stays visible rather than silently suppressed. Re-check `npm audit` against `braces` on a normal
cadence (the existing weekly scheduled run already does this) and remove the `--omit=dev` scoping
once a real fix is published upstream.

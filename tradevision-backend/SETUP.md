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

## Follow-up: Spring Boot 3.2.5 → 3.5.16 (the P1-21 upgrade this section deferred, now done)

The OWASP Dependency-Check job this P1-21 pass added started doing exactly the job it was meant
for: a real CI run hard-failed it (`-DfailBuildOnCVSS=8`) against a long list of CVEs accumulated
against Spring Boot 3.2.5's own pinned versions — Spring Framework (CVSS up to 9.8, dozens of
CVEs), Tomcat (dozens more, several CVSS 9.8), Jackson, Spring Data MongoDB, Spring Security, the
MongoDB driver, Hibernate Validator, Log4j, and the bundled Swagger UI's own DOMPurify. None of
this is new risk this pass introduced — it is 3.2.5 being out of free/OSS support, exactly as
flagged above, finally showing up as a hard gate instead of a documented risk.

**What changed**: `spring-boot-starter-parent` 3.2.5 → **3.5.16** (the latest patch of the 3.5.x
line) — a minor version bump within Spring Boot 3 (no jakarta/javax namespace change, no Spring
Framework major-version jump to 7.x the way Spring Boot 4.x would require), the lower-risk upgrade
path for closing out-of-date transitive CVEs without the larger migration surface a Boot 4 jump
would carry. `springdoc-openapi-starter-webmvc-ui` 2.3.0 → **2.8.6** separately, since that
dependency is pinned independently of Spring Boot's own BOM and was the actual source of the
bundled Swagger UI's DOMPurify CVEs — bumping the parent alone does not move it.

**Honest limitation, same mechanism as reason 1 above and every other fix in this repo's CI
history that needed real dependency resolution**: this sandbox still has no Maven Central access
(`mvn` here still runs `-o`), so `mvn -o compile` against the bumped parent fails immediately on
`Cannot access central ... in offline mode` — not a real compile problem, but it does mean this
exact version combination has not been compiled or test-run from inside this session. What *was*
verified here: the POM is valid XML and resolves the dependency graph shape correctly up to the
network boundary; and every other code change landing in this same pass (the exposure-reservation
retry/backoff fix and the position-recovery test fix immediately below) was independently verified
against the *old*, resolvable 3.2.5 parent first (`mvn -o test -Dtest='!*IntegrationTest'` →
1164/1164, BUILD SUCCESS), so a failure after this bump lands specifically on the version change
itself, not on code this pass also touched. The next real CI run is what actually confirms this
upgrade compiles, the full test suite (including the Testcontainers-backed integration suite this
sandbox has never been able to run either) still passes, and the OWASP gate is actually green.

## Follow-up: two further real-CI-only bugs fixed in the same pass as the Spring Boot bump above

Two more real CI failures came in from the same run that surfaced the CVE gate above, both found
by downloading and reading the Actions log archive directly (the same honest-limitation pattern
as every other integration-test fix in this repository's history — no Docker/Testcontainers in
this sandbox, so neither could be caught here before a real run exposed it):

- **`MultiPlanExposureIntegrationTest`**: `threeConcurrentPlansSameSymbol_combinedExposureNeverExceedsAccountCap`
  expected 2 of 3 concurrent reservations to succeed, got 0. The retry loop
  `ExposureReservationService.reserveTransactionally` already added for this exact scenario was
  retrying real `WriteConflict`s back-to-back with zero delay — this document touches more fields
  per transaction than `PositionSlotReservationService`'s own equivalent (which does pass under
  the same concurrency), so it has more surface for repeated collision, and immediate lockstep
  retries maximize the odds of the same threads re-colliding rather than letting whichever one is
  ahead actually commit. Fixed with a short, randomized backoff before each retry (standard MongoDB
  guidance for this exact pattern), and — separately — the final non-retryable exception is now
  logged before it propagates, since the test's own `executor.submit(...)` never checks its
  `Future` and was silently swallowing whatever actually failed; the log line is the only way a
  future real CI run (if this doesn't fully resolve it) leaves an actual diagnosable trace instead
  of the near-total silence this failure showed here.
- **`PositionPersistenceRecoveryIntegrationTest.orderFilledWithMissingPosition_reconstructedCorrectly_noDuplicateBuy`**:
  expected status `OPEN`, got `CLOSED_UNVERIFIED_PNL` — again, after an earlier fix in this same
  test (giving the order a real SL/TP and a stubbed OCO placement) had already addressed one real
  flatten trigger but not the actual remaining one. `createPositionForLateDiscoveredFill` renews
  the reconciliation lock (`distributedLockService.renew(credentialId, instanceId, lockGeneration,
  ...)`) immediately before placing the protective OCO, and emergency-flattens if that renewal
  fails — the test called it directly with a hardcoded `lockGeneration=1L` and no matching
  `ReconciliationLock` ever acquired, so the renewal correctly found zero matching documents and
  returned false, exactly as it should for a caller with no real lock. Not a production bug — a
  missing test fixture, same category as the earlier SL/TP gap in this same test. Fixed by adding
  a `getInstanceId()` getter to `PositionMonitorService` (the field was private with no accessor,
  so a test could not even target the right lock to begin with) and having the test actually
  acquire a real lock, under that same instanceId, via `DistributedLockService.tryAcquireWithDiagnosis`
  before calling the method under test — verified via `mvn -o test -Dtest='!*IntegrationTest'`
  (1164/1164 unit tests, unaffected) and `mvn -o test-compile` (clean); the integration test itself
  still needs a real CI run to confirm, same limitation as everywhere else in this file.

## Follow-up: Spring Boot 3.5.16 → 4.1.1 (3.5.x ran out of patches)

The OWASP gate failed again on a fresh real CI run, this time against **3.5.16 itself** — not a
regression from anything in this pass, but confirmation that 3.5.16 (released June 26, 2026) was
the *final* patch Spring ever shipped for the 3.5.x line: Maven Central's own version listing
shows no 3.5.17, with the project's release cadence moving straight to 4.0.x/4.1.x after that
date. The new findings: CVSS 8.1–9.8 CVEs in `spring-core`, `spring-web`,
`spring-security-core`/`-web`, `tomcat-embed-core`, and `mongodb-driver-core` — all real,
production-shipped jars from the 3.5.16 BOM — plus `kotlin-stdlib-1.9.25.jar`, which turned out
to be a `provided`-scope transitive of `springdoc-openapi` (pulled in for optional Kotlin
support this pure-Java project never used, and never actually packaged into the runtime jar;
OWASP scans `provided` scope by default even though Spring Boot's packaging strips it out).

With no further 3.5.x patch available, suppressing the six real CVEs was explicitly ruled out
(this project's own `owasp-suppressions.xml` policy exists precisely to rule that out for a real,
unaddressed finding) — the user was asked and chose the major-version bump to **4.1.1** (Spring
Framework 7) over suppressing-for-now.

**What changed**, full rationale for each inline at the touched file/property (see `pom.xml`'s
own dated parent-version comment for the complete writeup):
- `spring-boot-starter-parent` 3.5.16 → **4.1.1**.
- `spring-boot-starter-web` → `spring-boot-starter-webmvc` (renamed in Boot 4).
- `springdoc-openapi` (Swagger UI) **removed entirely**, not upgraded. Its only Boot-4-compatible
  line (3.0.x) has an open, unresolved upstream bug (springdoc/springdoc-openapi#3200): swagger-core
  pulls in a Jackson version that conflicts with this project's own direct, classic Jackson 2
  usage (`ObjectMapper`/`JsonNode` in `BinanceBrokerAdapter`, `OrderService`, etc.), with reports
  of a `ClassNotFoundException` and no confirmed fix as of this writing — a risk that could fail
  the whole app's startup, not just `/swagger-ui`. Asked and confirmed with the user rather than
  assumed; losing interactive API docs was judged the safer trade. This also removes the
  `kotlin-stdlib` finding above for free, since that jar was a springdoc/swagger-core transitive.
- `spring-boot-jackson2` added (+ `spring.jackson.use-jackson2-defaults=true`): Boot 4 defaults
  to Jackson 3 (new `tools.jackson` groupId); this compatibility shim keeps this project's many
  direct, hand-constructed Jackson 2 usages working unchanged instead of rewriting and
  re-verifying every one of those call sites against Jackson 3 blind.
- `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` → Boot 4's own single
  `spring-boot-starter-opentelemetry` starter; `management.otlp.tracing.endpoint` renamed to
  `management.opentelemetry.tracing.export.otlp.endpoint` to match.
- `spring.data.mongodb.*` properties (and the matching `registry.add(...)` calls in every
  Testcontainers-backed integration test) → `spring.mongodb.*` (renamed, not just deprecated, in
  Boot 4).
- `@MockBean`/`@SpyBean` (`ReleaseTestSuiteIntegrationTest`, the only file using them) →
  `@MockitoBean`/`@MockitoSpyBean`.

**Honest limitation, more significant than usual**: this sandbox has no Maven Central access for
Spring Boot 4.1.1 / Spring Framework 7 artifacts at all (only 3.2.0/3.2.5 are cached locally), so
unlike the 3.2.5 → 3.5.16 bump above, this could not be round-tripped through a temporary
local-cache downgrade to compile-check it — none of this has been compiled or test-run from
inside this session. Every change above is backed by direct research against Spring's own current
documentation, release notes, or a specific open GitHub issue, not assumed — but a major framework
version jump is exactly the kind of change most likely to surface something no documentation
search catches. The real CI run (compile + full test suite + the OWASP gate itself) is what
actually confirms this; whoever merges this should watch that run closely.

## Follow-up: 4 new OWASP findings after the 4.1.1 bump, all suppressed with individual research

The first real CI run against Spring Boot 4.1.1 compiled and passed every other job, but OWASP
failed on 4 new findings. Each was individually researched (NVD/vendor advisories, not assumed)
before deciding suppress vs. fix — full reasoning for each lives in `owasp-suppressions.xml`
itself, right next to its `<suppress>` block:

- **`spring-boot-mongodb`/`spring-boot-data-mongodb` (Boot 4's own tiny MongoDB auto-config glue
  modules, ~27-51KB each)** — flagged for `CVE-2025-14847` ("MongoBleed"), `CVE-2026-9753`,
  `CVE-2021-32036` (all three confirmed real MongoDB **server** CVEs this project, which only
  ever connects to a server over the wire, cannot be vulnerable to), and `CVE-2014-8180` (ancient,
  also server-side). Root cause: NVD's CPE matching these jars by name+version coincidence — their
  version is `4.1.1`, this project's Spring Boot release, which happens to also be a real MongoDB
  server release number.
- **`spring-boot-data-mongodb`** additionally flagged for `CVE-2026-41717`/`CVE-2026-41696` —
  genuinely real Spring Data MongoDB CVEs (confirmed via spring.io's own security advisories),
  but both fixed in 5.0.6+, and this project's actual `spring-data-mongodb` (confirmed in the same
  CI run's own dependency resolution log) is **5.1.1** — already past the fix. The flagged `4.1.1`
  is, again, Boot's own release version coincidentally landing inside spring-data-mongodb's own
  *historical* vulnerable range (4.0.0-4.3.16) for a different artifact entirely.
- **`protobuf-java-4.35.1.jar`** (transitive via `spring-boot-starter-opentelemetry`'s OTLP
  exporter) — flagged for `CVE-2026-0994`, confirmed via osv.dev to be a **Python** protobuf
  (`google.protobuf.json_format.ParseDict()`) JSON-recursion DoS bug, not applicable to the Java
  artifact or this project's binary-wire-format-only OTLP usage.
- **`kotlin-stdlib-2.3.21.jar`** (transitive via okhttp, itself pulled in by the OTLP exporter's
  HTTP sender) — flagged for `CVE-2026-53914`, CVSS 9.8. Confirmed via JetBrains' own advisory:
  this is an unsafe-deserialization RCE in the **Kotlin compiler's own build-cache subsystem**,
  reachable only by a Kotlin/Gradle build consuming poisoned remote build-cache metadata — not by
  an application that merely has the kotlin-stdlib *runtime* jar on its classpath. This project is
  a plain Java/Maven build with no Kotlin compiler or Gradle build cache anywhere in it.

All 4 are the OWASP Dependency-Check false-positive pattern its own documentation and this
project's own suppression-file header describe: CPE name/version coincidence against an unrelated
product, or a vulnerability in a part of the dependency this project's own usage never reaches.
None were suppressed on assumption — each has a cited, checkable source. Versions were
deliberately NOT force-overridden as an alternative to suppression here: Spring Boot 4.1.1's own
BOM already pins kotlin-stdlib/protobuf-java/okhttp/opentelemetry at versions it tested together,
and this sandbox cannot compile-test a manually-forced alternative combination, so suppressing the
confirmed-inapplicable findings is the lower-risk fix for both of these.

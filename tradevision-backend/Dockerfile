# Review finding ("P0 #2" — investigating the admin-secret leak in the frontend led to
# discovering there was previously NO correct mechanism to get a real frontend build into this
# image at all — a stale, vulnerable build had been manually committed to
# src/main/resources/static/ instead, weeks out of date with actual source fixes). This build
# now actually produces and bundles a fresh frontend, so that gap can't recur.
#
# BUILD CONTEXT: this Dockerfile must be built from the REPO ROOT (the parent of both
# tradevision-backend/ and trading-analyst/), not from inside tradevision-backend/ — Docker
# can't COPY files from outside its build context, and the frontend is a sibling directory:
#   docker build -f tradevision-backend/Dockerfile -t tradevision-backend .
# (run from the repo root — see SETUP.md).

# ── Stage 0: Frontend build ──────────────────────────────────
FROM node:20-alpine AS frontend-build
WORKDIR /frontend
COPY trading-analyst/package.json trading-analyst/package-lock.json ./
RUN npm ci
COPY trading-analyst/ ./
RUN npx ng build --configuration production

# ── Stage 1: Backend build ────────────────────────────────────
FROM maven:3.9.15-eclipse-temurin-26 AS build

WORKDIR /app

# Cache dependencies first
COPY tradevision-backend/pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and the freshly-built frontend, then build
COPY tradevision-backend/src ./src
COPY --from=frontend-build /frontend/dist/trading-analyst/browser/ ./src/main/resources/static/
# Review finding ("Docker production issue" — "-DskipTests is not acceptable as the only
# production build pipeline"): this is now `verify`, which runs the test suite as part of the
# build — a broken test now fails the image build instead of shipping silently. The review's
# fuller recommendation (a separate CI step running `mvn clean verify`, with Docker consuming an
# already-tested artifact) is the more complete setup, but requires configuring this project's
# actual CI platform (GitHub Actions, GitLab CI, etc.) — not something to invent without knowing
# which one is actually in use. This is the real, bounded piece: the image itself can no longer
# skip tests.
#
# Review finding ("P0 #10" — this `docker build` step was failing outright): the integration
# test suite (src/test/java/com/tradevision/integration/**/*IntegrationTest.java) spins up real
# Testcontainers-backed MongoDB instances, which need a Docker daemon reachable from wherever the
# tests run. A `docker build` layer has no Docker socket of its own, so `mvn clean verify` here
# was unconditionally failing at the integration-test phase, not just skipping it. Each of those
# 11 test classes is now annotated `@Testcontainers(disabledWithoutDocker = true)`, so they no
# longer hard-fail without Docker — but a production image build still shouldn't depend on
# Testcontainers/Docker-in-Docker being available (or silently skipping real coverage) to
# succeed, so this build step explicitly runs only the unit suite; the integration suite is
# meant to run in real CI, with Docker available, as its own step.
#
# NOTE: this repository currently has NO CI configuration at all (no .github/workflows or
# equivalent). Until one exists, the integration suite above only runs when a developer invokes
# `mvn clean verify` locally with Docker running. Setting up real CI is out of scope here since it
# requires picking and configuring an actual platform (GitHub Actions, GitLab CI, etc.), but
# whatever is set up should run `mvn clean verify` (the full suite, unit + integration) with a
# Docker daemon available, separately from — and before — building/publishing this image.
RUN mvn clean verify -q -Dtest='!*IntegrationTest' -DfailIfNoTests=false

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Review finding ("Docker production issue" — "No Docker HEALTHCHECK"): wget via busybox is
# usually present on Alpine already, but not guaranteed across every base image variant —
# installed explicitly rather than assumed.
RUN apk add --no-cache wget

# Non-root user for security
RUN addgroup -S tradevision && adduser -S tradevision -G tradevision
USER tradevision

COPY --from=build /app/target/tradevision-backend-1.0.0.jar app.jar

EXPOSE 8080

# Review finding ("No Docker HEALTHCHECK"): uses the actuator health endpoint already exposed in
# application.properties (management.endpoints.web.exposure.include=health,info) — not a new
# endpoint invented for this, one that already existed. start-period gives the JVM + Spring
# context real time to come up (container-aware JVM sizing + Mongo connection can take a few
# seconds) before failed checks start counting against it.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# JVM tuning for low-memory free tier (512MB Render)
# Review finding (found while verifying the split into application-local/prod.properties):
# application-prod.properties' own header comment claimed "the Dockerfile already does this" —
# it didn't. Without this flag, a real deployment would fall back to application.properties'
# own default (spring.profiles.active=local), meaning production would try to connect to
# localhost MongoDB using the fake dev secrets committed in application-local.properties. Setting
# SPRING_PROFILES_ACTIVE as an environment variable (rather than baking -Dspring.profiles.active
# into the entrypoint args) lets it still be overridden at deploy time if ever genuinely needed,
# while defaulting correctly here.
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=70.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

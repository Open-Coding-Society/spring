# CS Pathway Architecture and Maintenance Guide

## Overview

CS Pathway is a feature area in the Spring application. It supports three product concerns:

- Setup: collect and review evidence that a student's local development tools are ready.
- Scoring: record and display progress through the five learning levels.
- Help: let a student send a request to a teacher. This is a teacher help-request queue, not OCS chat. OCS chat is a possible future direction and is not implemented by this package.

The Java implementation is in `src/main/java/com/open/spring/mvc/cspathway/`. The teacher dashboard template is `src/main/resources/templates/cs-pathway/read.html`. This guide describes current behavior; controller and service code remain authoritative if behavior changes.

## Application layers

The package follows the Spring MVC and persistence pattern used by the rest of this application:

- REST controllers expose JSON or plain-text HTTP operations. They translate HTTP input and authentication into service calls and translate results or errors into HTTP responses. The routes are use-case endpoints; they do not provide full CRUD for every resource.
- DTOs are Java records used to exchange request and response data. Jackson maps JSON properties to record components and back. Records are transport shapes, not database tables.
- JPA entities are Java objects mapped to database rows. `PathwayEntityReport` and `PathwayEntityHelp` are pathway-owned entities. Scores reuse the shared `Stats` entity.
- `JpaRepository` interfaces provide persistence operations and derived queries. They are the Java persistence interface, not a separate SQL database or a hand-written SQL layer.
- Services contain business rules and coordinate repositories or external systems. `PathwayServiceAI` calls Gemini; the other services manage pairing reports, scores, and help requests.
- `PathwayMvcController` serves the Thymeleaf teacher/admin page at `/mvc/cs-pathway/read`. It supplies dashboard data to `templates/cs-pathway/read.html`; this is a server-rendered UI route, not a REST endpoint.

## Current behavior

### REST operations

| Method | Route | Operation | Access |
| --- | --- | --- | --- |
| POST | `/api/cs-pathway/setup-report/pairing-code` | Issue or renew the signed-in student's terminal pairing code | Signed-in account |
| POST | `/api/cs-pathway/setup-report/{pairingCode}` | Submit plain-text verifier output | Public route; valid unexpired pairing code required |
| GET | `/api/cs-pathway/setup-report` | Read the signed-in student's latest report | Signed-in account |
| POST | `/api/cs-pathway/setup-report/review/{uid}` | Request an advisory AI review | Teacher or administrator |
| GET | `/api/cs-pathway/scores` | Read the signed-in student's level scores | Signed-in account |
| PUT | `/api/cs-pathway/scores/{levelKey}` | Record progress for one level | Signed-in account |
| POST | `/api/cs-pathway/help-requests` | Create a help request | Signed-in account |

These routes provide reads and specific create/update actions. There is no delete endpoint, and not every resource offers all four CRUD operations.

### Testing the REST API in VS Code

Use [`api/cspathway.http`](../api/cspathway.http) with the VS Code REST Client extension to send sample requests for authentication, pairing-code creation, help requests, and score read/update operations. Select the `local` or `deployed` REST Client environment before running a request. Run the authentication request first when testing endpoints that require a signed-in student; REST Client can reuse the returned authentication cookie for subsequent requests.

The file references variables such as `baseUrl`, `uid`, `adminUid`, and `adminPassword`. Define the values under `rest-client.environmentVariables` in `.vscode/settings.json`, which is excluded from Git. Keep credentials and other secret values in local settings only; do not copy them into the `.http` file, documentation, or commits. Use only an environment and account you are authorized to test.

### Setup report flow

1. A signed-in student calls `POST /api/cs-pathway/setup-report/pairing-code`. The response is a `PathwayDtoPairing` record with `code` and `expiresAt` (Unix epoch milliseconds). Codes are eight characters, expire after 30 minutes, and an active code for the student is reused with its expiration extended.
2. The student's local verifier sends its plain-text output to `POST /api/cs-pathway/setup-report/{pairingCode}`. This upload route is permitted without a login because the terminal does not have the browser's JWT cookie; the short-lived pairing code identifies the student. The service checks the report length and required summary lines before saving it.
3. The student reads their latest report from `GET /api/cs-pathway/setup-report`. When no report exists, the response contains null report fields.
4. A teacher or administrator can request an advisory review with `POST /api/cs-pathway/setup-report/review/{uid}`. `PathwayServiceAI` sends the report to Gemini when `gemini.api.key` is configured. The model's response is checked and combined with verifier warnings and failures. This is an assessment of submitted text, not independent proof that software is installed.

The persisted record is keyed by student UID in `cs_pathway_setup_report`. It stores the current pairing code and expiry, the latest verifier status and counts, the raw report, and its submission time. Timestamps are epoch milliseconds.

### Scoring flow

- A signed-in student reads progress with `GET /api/cs-pathway/scores`.
- The student records progress with `PUT /api/cs-pathway/scores/{levelKey}` and a JSON body such as `{"percentComplete": 60}`. The server derives the student UID from the authenticated principal rather than accepting it from the body.
- `PathwayEnumLevels` defines the five stable level keys and their `submodule` numbers. Those numbers identify rows in the shared `Stats` table where `module` is `cs-pathway`; do not renumber them after data has been written.
- Progress is constrained to 0 through 100 and only moves forward. Missing levels are returned as zero percent. A level is finished at 100 percent.
- The teacher page combines score rows, setup reports, and recent help requests.

### Help-request flow

- A signed-in student submits `POST /api/cs-pathway/help-requests` with `levelKey`, optional `station`, and `message`.
- The service validates the level and text lengths, then stores a request in `cs_pathway_help_request`. A student can submit at most one request per minute.
- The teacher/admin dashboard shows up to the 200 most recent requests at `/mvc/cs-pathway/read`.

## Persistence and security

- `cs_pathway_setup_report` and `cs_pathway_help_request` are created by `ApplicationRunner` components because Hibernate uses `ddl-auto=none`. Keep their DDL and corresponding entity mappings aligned for both SQLite and MySQL.
- Score data is stored in the shared `Stats` table. Pathway queries are isolated in `PathwayJpaRepositoryStats`; do not create a second score table without a deliberate data migration.
- The score and help endpoints use the authenticated student principal. The pairing-code issue route and AI review route have explicit role rules. The text report upload is public at the HTTP layer and relies on a valid, unexpired pairing code in the service. The MVC dashboard is restricted to teachers and administrators. See `SecurityConfig` and `MvcSecurityConfig` for the actual access rules.
- Treat pairing codes as credentials: keep them random, short-lived, validated on every upload, and out of logs. Avoid logging raw verifier reports, which may contain local machine details.

## Change and documentation checklist

When extending CS Pathway:

1. Keep responsibilities in their existing layer: HTTP handling in controllers, business rules in services, transport shapes in records, and table mappings in entities.
2. Keep API routes, DTO component names, HTTP status behavior, and security rules consistent. Update callers and tests when a JSON contract changes.
3. Keep the enum keys and score submodule numbers stable. Update both entity mappings and startup DDL when changing persisted columns.
4. Add or update focused JUnit/Mockito tests for the service behavior and controller contract where applicable.
5. Add plain-language JavaDoc to each new Java type and to public methods whose behavior or constraints are not obvious. Keep JavaDoc readable in the source editor; do not put HTML or Markdown diagrams in comments.
6. Update this guide when the architecture or observable behavior changes. The repository `README.md` and `AGENTS.md` link here so people and coding agents can find it.

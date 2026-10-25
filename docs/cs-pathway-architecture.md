# CS Pathway and Toolchain Trail Architecture Guide

## Overview

CS Pathway is a feature area in the Spring application. It supports three product concerns:

- Setup: collect and review evidence that a student's local development tools are ready.
- Scoring: record and display progress through the five learning levels.
- Help: let a student send a request to a teacher. This is a teacher help-request queue, not OCS chat. OCS chat is a possible future direction and is not implemented by this package.

The Java implementation is consolidated under one package:

- `src/main/java/com/open/spring/mvc/toolchain/` contains score tracking, student-to-teacher help requests, setup reports, and the interactive Toolchain Trail station-checking session and local-agent API.

The teacher dashboard template remains `src/main/resources/templates/cs-pathway/read.html`. The Java package and public URL names do not need to match: the package is now `mvc.toolchain`, while the existing `/api/cs-pathway/...` and `/mvc/cs-pathway/...` routes remain unchanged for client compatibility. The controller and service code remain authoritative if behavior changes.

## Two setup/report mechanisms

There are two setup mechanisms in the code. They should not be confused just because both use pairing codes:

| Mechanism | Java package and API | Purpose and persisted state |
| --- | --- | --- |
| Setup report | `mvc.toolchain`; `/api/cs-pathway/setup-report/...` | A terminal submits a plain-text verifier report. `ToolchainEntitySetupReport` stores the latest report keyed by student UID in `cs_pathway_setup_report`. An optional AI review summarizes the submitted evidence. |
| Interactive Toolchain Trail | `mvc.toolchain`; `/api/toolchain/...` | The game and a local agent exchange station-check requests and results. `ToolchainEntityAgentSession` stores a UUID session linked to a student UID and the current station in `toolchain_session`. Spring evaluates the submitted evidence against station rules. |

Neither client writes directly to the SQL table. The game and local agent call Spring REST endpoints; the services validate identity, pairing credentials, station, and evidence, and then Spring repositories persist the resulting state. The student UID associates a session with its owner, but the interactive agent protocol also uses a session UUID, current `stationId`, a one-time pairing code, and a scoped agent token. Score `levelKey` values are a separate concept from Toolchain `stationId` values.

The interactive Toolchain Trail flow is the request/response approach for live station checks and is the current integrated agent/session implementation. The setup-report flow is a separate report-submission and review implementation that remains in the code. Source code confirms both are implemented; it does not establish whether the report flow is still used by students. Confirm usage with the game owner before deprecating it. Do not silently conflate their records or routes.

## Application layers

The package follows the Spring MVC and persistence pattern used by the rest of this application:

- REST controllers expose JSON or plain-text HTTP operations. They translate HTTP input and authentication into service calls and translate results or errors into HTTP responses. The routes are use-case endpoints; they do not provide full CRUD for every resource.
- DTOs are Java records used to exchange request and response data. Jackson maps JSON properties to record components and back. Records are transport shapes, not database tables.
- JPA entities are Java objects mapped to database rows. `ToolchainEntitySetupReport`, `ToolchainEntityHelpRequest`, and `ToolchainEntityAgentSession` are feature-owned entities. Scores reuse the shared `Stats` entity.
- `JpaRepository` interfaces provide persistence operations and derived queries. They are the Java persistence interface, not a separate SQL database or a hand-written SQL layer.
- Services contain business rules and coordinate repositories or external systems. `ToolchainServiceSetupReview` calls Gemini; the other services manage pairing reports, scores, help requests, agent sessions, and station rules.
- `ToolchainMvcController` serves the Thymeleaf teacher/admin page at `/mvc/cs-pathway/read`. It supplies dashboard data to `templates/cs-pathway/read.html`; this is a server-rendered UI route, not a REST endpoint.
- `ToolchainApiRestControllerAgent`, `ToolchainServiceAgent`, `ToolchainStationRules`, and `ToolchainEntityAgentSession` implement the agent session lifecycle and station evidence evaluation.

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
| POST | `/api/toolchain/sessions` | Start an agent session and issue a pairing code | Signed-in account |
| POST | `/api/toolchain/pair` | Exchange a one-time code for a scoped agent token | Pairing code |
| POST | `/api/toolchain/sessions/{sessionId}/check` | Tell the paired agent which station to check | Session owner |
| GET | `/api/toolchain/sessions/{sessionId}/next` | Agent reads the current station request | Scoped agent token |
| POST | `/api/toolchain/sessions/{sessionId}/results` | Agent submits check evidence | Scoped agent token |
| GET | `/api/toolchain/sessions/{sessionId}` | Game reads the current session/check status | Session owner |

These routes provide reads and specific create/update actions. There is no delete endpoint, and not every resource offers all four CRUD operations.

For the Toolchain Trail, one pairing covers the whole trail; the game changes the current station request as the student advances. The session UUID is not a student UID or a level key. Spring associates the session with the authenticated student's UID, issues a single-use pairing code, and returns a short-lived agent token after pairing. Only a hash of each code/token is stored. The agent polls for the requested `stationId`, submits allow-listed check evidence, and Spring evaluates pass/fail. The browser then reads the resulting status. Lifetimes are configured by `toolchain.pairing-code-lifetime` and `toolchain.agent-token-lifetime` in `application.properties`.

### Testing the REST API in VS Code

Use [`api/cspathway.http`](../api/cspathway.http) with the VS Code REST Client extension to send sample requests for authentication, pairing-code creation, help requests, and score read/update operations. Select the `local` or `deployed` REST Client environment before running a request. Run the authentication request first when testing endpoints that require a signed-in student; REST Client can reuse the returned authentication cookie for subsequent requests.

The file references variables such as `baseUrl`, `uid`, `adminUid`, and `adminPassword`. Define the values under `rest-client.environmentVariables` in `.vscode/settings.json`, which is excluded from Git. Keep credentials and other secret values in local settings only; do not copy them into the `.http` file, documentation, or commits. Use only an environment and account you are authorized to test.

### Setup report flow

1. A signed-in student calls `POST /api/cs-pathway/setup-report/pairing-code`. The response is a `ToolchainDtoSetupPairing` record with `code` and `expiresAt` (Unix epoch milliseconds). Codes are eight characters, expire after 30 minutes, and an active code for the student is reused with its expiration extended.
2. The student's local verifier sends its plain-text output to `POST /api/cs-pathway/setup-report/{pairingCode}`. This upload route is permitted without a login because the terminal does not have the browser's JWT cookie; the short-lived pairing code identifies the student. The service checks the report length and required summary lines before saving it.
3. The student reads their latest report from `GET /api/cs-pathway/setup-report`. When no report exists, the response contains null report fields.
4. A teacher or administrator can request an advisory review with `POST /api/cs-pathway/setup-report/review/{uid}`. `ToolchainServiceSetupReview` sends the report to Gemini when `gemini.api.key` is configured. The model's response is checked and combined with verifier warnings and failures. This is an assessment of submitted text, not independent proof that software is installed.

The persisted record is keyed by student UID in `cs_pathway_setup_report`. It stores the current pairing code and expiry, the latest verifier status and counts, the raw report, and its submission time. Timestamps are epoch milliseconds.

### Scoring flow

- A signed-in student reads progress with `GET /api/cs-pathway/scores`.
- The student records progress with `PUT /api/cs-pathway/scores/{levelKey}` and a JSON body such as `{"percentComplete": 60}`. The server derives the student UID from the authenticated principal rather than accepting it from the body.
- `ToolchainEnumLevels` defines the five stable level keys and their `submodule` numbers. Those numbers identify rows in the shared `Stats` table where `module` is `cs-pathway`; do not renumber them after data has been written.
- Progress is constrained to 0 through 100 and only moves forward. Missing levels are returned as zero percent. A level is finished at 100 percent.
- The teacher page combines score rows, setup reports, and recent help requests.

### Help-request flow

- A signed-in student submits `POST /api/cs-pathway/help-requests` with `levelKey`, optional `station`, and `message`.
- The service validates the level and text lengths, then stores a request in `cs_pathway_help_request`. A student can submit at most one request per minute.
- The teacher/admin dashboard shows up to the 200 most recent requests at `/mvc/cs-pathway/read`.

## Persistence and security

- `cs_pathway_setup_report` and `cs_pathway_help_request` are created by `ApplicationRunner` components because Hibernate uses `ddl-auto=none`. Keep their DDL and corresponding entity mappings aligned for both SQLite and MySQL.
- Score data is stored in the shared `Stats` table. Pathway queries are isolated in `ToolchainJpaRepositoryStats`; do not create a second score table without a deliberate data migration.
- Interactive station sessions are stored in `toolchain_session`; this is separate from score rows and setup-report rows because it has its own pairing, token-expiry, station-request, and result lifecycle. `ToolchainStationRules` is the server-side allow-list and evaluator; a client must not determine its own pass/fail result.
- The score and help endpoints use the authenticated student principal. The pairing-code issue route and AI review route have explicit role rules. The text report upload is public at the HTTP layer and relies on a valid, unexpired pairing code in the service. The MVC dashboard is restricted to teachers and administrators. See `SecurityConfig` and `MvcSecurityConfig` for the actual access rules.
- Toolchain pair, poll, and result routes are public at the Spring Security matcher layer because the local agent has no browser JWT cookie; `ToolchainServiceAgent` requires a valid one-time pairing code or the session's scoped, unexpired bearer token. The game session routes require the signed-in owner.
- Treat pairing codes and agent tokens as credentials: keep them random, short-lived, validated on every use, and out of logs. Avoid logging raw verifier reports or command output, which may contain local machine details.

## Package organization and naming

The Java types are consolidated under `mvc.toolchain` so students can discuss scores, help requests, setup reports, and interactive station checks in one place. The files use consistent role-oriented names such as `ToolchainApiRestController...`, `ToolchainDto...`, `ToolchainEntity...`, `ToolchainJpaRepository...`, and `ToolchainService...`. The package move and Java type renames do not change the API routes or database tables.

The public routes retain both `/api/cs-pathway` and `/api/toolchain` prefixes. Those names are part of the existing game/agent contract and are deliberately not renamed along with Java packages and files. Renaming routes or database tables should be a separate, coordinated migration that updates external game/agent clients and persisted data as needed.

If the report-upload mechanism is no longer used, first confirm that its UI, REST clients, scripts, and saved reports are no longer needed. Then plan deprecation/removal separately instead of folding it into a package move.

The macOS zsh shell helper discussed alongside this feature is yet another separate piece: it watches selected commands in an interactive shell and can show local help, but it does not call `/api/toolchain`, pair with the game, or submit station results. It should not be confused with the local Toolchain Trail API agent.

## Change and documentation checklist

When extending CS Pathway or Toolchain Trail:

1. Keep responsibilities in their existing layer: HTTP handling in controllers, business rules in services, transport shapes in records, and table mappings in entities.
2. Keep API routes, DTO component names, HTTP status behavior, and security rules consistent. Update callers and tests when a JSON contract changes.
3. Keep the enum keys and score submodule numbers stable. Update both entity mappings and startup DDL when changing persisted columns.
4. Add or update focused JUnit/Mockito tests for the service behavior and controller contract where applicable.
5. Add plain-language JavaDoc to each new Java type and to public methods whose behavior or constraints are not obvious. Keep JavaDoc readable in the source editor; do not put HTML or Markdown diagrams in comments.
6. Update this guide when the architecture or observable behavior changes. The repository `README.md` and `AGENTS.md` link here so people and coding agents can find it.

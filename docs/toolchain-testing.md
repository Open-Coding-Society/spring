# Testing the Toolchain pairing flow

`api/toolchain_flow_test.py` runs the whole pairing flow against a LOCAL Spring and prints PASS or FAIL for each step.

1. Run Spring locally (`./mvnw spring-boot:run`) with the seed users from `.env`.
2. Run `python3 api/toolchain_flow_test.py`.

Each actor (student, teacher, agent, anonymous) gets its own cookie jar. Every response feeds the next request: the session id and pairing code from `start` go into `pair`, and the agent token from `pair` goes into the `Authorization` header of `next` and `results`. Pairing codes and tokens are never printed. The base URL comes from the `JAVA_URI` environment variable and defaults to `http://127.0.0.1:8585`. A non-local server is refused unless `TOOLCHAIN_TEST_ALLOW_REMOTE=1` is set, because the test creates sessions, and then `TOOLCHAIN_TEST_PASSWORD` (plus `TOOLCHAIN_STUDENT_UID` and `TOOLCHAIN_TEACHER_UID` if they differ from the seed users) is required. Do not point it at production data.

It covers the happy path and the cases that must fail: bogus or reused code, wrong user, wrong session, missing or garbage token, wrong station, empty checks, and the pass/fail rules in `ToolchainStationRules`.

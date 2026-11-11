# Faster password hashing in tests: a cheap bcrypt cost for the test instances

## Goals

- A user a test persists is stored with a password hash of bcrypt's lowest cost, so persisting it and logging it in cost a
  fraction of a millisecond each instead of a full bcrypt pass.
- Production and the dev server hash at the cost they use now, and nothing in production reads a test setting.
- The saving reaches every bundle that persists users: the controller bundle and both integration bundles.

Out of scope: the login's timing-attack dummy hash, the token scheme, a different password hash algorithm, the controller
reset before each test, Undertow's start per `withServer`.

## Measurements the design rests on

The controller bundle runs 162 tests with a median of about 150 ms each. The database reset before each test (wipe, `all.sql`,
Settings load) takes a median of 7.8 ms, so it is not what costs.

Every password hash is jBCrypt's default cost of 10 (`Util.hashPassword`: `BCrypt.hashpw(password, BCrypt.gensalt())`), and a
check runs at the cost the stored hash carries (`Util.checkPassword`). The hashes a run makes:

- Each integration test (SQLite and PostgreSQL bundles, about 550 tests each) persists one user in `IntegrationTestCore.beforeEach`
  (`testContext.persistRepository()` → `TestContext.persistUser` → `UserService.add` → `Util.hashPassword`), and some persist more.
- The controller tests persist their users the same way, through the suite's instance, and log them in with `login()` (134
  calls), whose `POST /login` checks the stored hash on the server's own instance (`App.altitude`,
  `UserService.loginAndSetUser`).

What a cost-10 hash and check take on the dev machine, and so what share of each bundle they are, is not measured: item 1 measures
it first, and the rest of the plan goes ahead only if the hashing is a share worth having.

## Decisions

| Concern | Decision |
|---|---|
| Where the cost lives | An `Altitude` constructor parameter, `passwordHashCost: Int`, after `newSceneEmbedder`, defaulting to the production cost. `UserService.add` hashes at `app.passwordHashCost`. No config key and no check of `Environment.CURRENT` |
| The production cost | 10, jBCrypt's default and what every stored hash uses now, named as a constant beside the hashing in `Util` |
| The test cost | 4, bcrypt's lowest, which `BCrypt.gensalt(4)` accepts |
| Which instances use it | The ones `TestAltitudeApp.build` makes: both integration bundles' instances, the controller suite's (`SqliteSuiteBundle.testApp`) and the second instance of `StartupTests` |
| The controller server's own instance (`App.altitude`) | Built with the production cost, as now. It hashes only when a test runs the setup route (`SystemService.initializeSystem`). Its login checks are fast anyway, since a check runs at the cost of the hash the suite's instance stored |
| The dummy hash of an unknown user's login | Unchanged at cost 10. A test of a failed login for an unknown email pays one cost-10 check, and production's timing protection is untouched |
| Stored hashes | No migration: a hash carries its own cost, so cost-10 hashes stored before keep checking, and a check never depends on the instance's setting |

## Constraints

- No change in production behavior: `new Altitude()` hashes at cost 10, as now.
- No schema change.
- No test reads or asserts a hash's cost or format, and none starts to.

## Changes

There is no new behavior to drive red-green. Group B is a refactor of where the cost comes from, guarded by the suites that log
users in (`LoginControllerTests`, `UserServiceTests`, the controller tests' `login()`), which fail if a hash or a check breaks.

### A. Measure first

1. **The hashing's share.** On the dev machine, time `Util.hashPassword` and `Util.checkPassword` at costs 10 and 4 in a
   throwaway JVM run or a temporary timing line, and count the hashes and checks a run of each bundle makes (temporary counters,
   or a temporarily raised `altitude.core.service.UserService` logger, whose `add` logs at DEBUG). Multiply out the time each
   bundle spends hashing. Remove the timing code and restore `logback-test.xml` afterwards. If the hashing is under about 10% of
   every bundle, stop here and report the numbers instead of making the change.

### B. The cost per instance

2. **`Util` (`../../../altitude/src/altitude/core/util/Util.scala`).** A named constant for the production cost (10), and
   `hashPassword(password: String, cost: Int)` hashing with `BCrypt.gensalt(cost)`. Scaladoc: a check runs at the cost the hash
   carries, so the cost decides only new hashes.
3. **`Altitude` (`../../../altitude/src/altitude/core/Altitude.scala`).** The constructor takes `passwordHashCost: Int` after
   `newSceneEmbedder`, defaulting to the constant, documented with `@param` beside `newSceneEmbedder`'s.
4. **`UserService.add` (`../../../altitude/src/altitude/core/service/UserService.scala`).** Hashes with `app.passwordHashCost`.
5. **The test instances (`../../../altitude/test/src/altitude/test/TestAltitudeApp.scala`).** `TestAltitudeApp.build` passes
   `passwordHashCost = 4`, with a constant named for it and a line on why: bcrypt's cost is the point of a password hash and
   no point of a test.

### C. Docs

6. **`../../../altitude/AGENTS.md`.** In **Test Structure**, beside the Scene models in tests: the test instances hash passwords at
   bcrypt's lowest cost, `App.altitude` at the production cost, and a check follows the stored hash's cost.

## Critical files

- `../../../altitude/src/altitude/core/util/Util.scala`, `altitude/src/altitude/core/Altitude.scala`,
  `altitude/src/altitude/core/service/UserService.scala`
- `../../../altitude/test/src/altitude/test/TestAltitudeApp.scala`
- `../../../altitude/AGENTS.md`

## Verification

1. `make compile`, then `make lint`.
2. `make test` passes every bundle.
3. Timings from `out/altitude/test/testOnly.dest/test-report.xml` against a run on the commit before: each bundle's test time
   drops by about what item 1 predicted, and the median controller test drops by about one cost-10 hash plus one check.
4. In the test database after a run, a user persisted by a test has a hash starting `$2a$04$`. The dev server's setup creates
   an admin whose hash starts `$2a$10$` (on a scratch instance with its own data directory, not the dev database).

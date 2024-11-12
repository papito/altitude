# Altitude #

## DEVELOPMENT SETUP

### Core system requirements

* Java (11)
* Postgres database (13)

## Build & Run

```sh
# Bring up the Postgres database (dev and test at once)
make db
# Run the tests
make test
# With hot reload
make watch
```

See the Makefile for all the available commands.

## Design philosophy, architecture, and patterns

These are covered in the [WIKI](https://github.com/papito/altitude/wiki). Topics:

* [How the tests work](https://github.com/papito/altitude/wiki/How-the-tests-work)
* [How to](https://github.com/papito/altitude/wiki/How-to...)

## Databases

* SQL schema needs to be updated for *both* Postgres and SQLite. The schemas are in `src/main/resources/migrations/`
* In development, the type of database running is configured in `core.Configuration` class

## Logging

See `logback.xml` for configuration.

## Style and formatting

For formatting, using `scalafmt`: `.scalafmt.conf`

For style, using `scalafix`: `.scalafix.conf`

`scalafixAll` will run with the pre-commit hook configured in `.git/hooks/pre-commit`

To configure the pre-commit hook:

```sh
#!/bin/sh

sbt scalafixAll
```

## Running a tagged test(s):
Update your test as such:

```
test("work in progress", Focused) {
}
```
    sbt> testFocused
    sbt> tetestFocusedSqlite
    sbt> tetestFocusedPostgres

## Running tests against a particular database:

    sbt> testOnly software.altitude.test.core.suites.[SqliteSuite|PostgresSuite]

## Remote debugging

1. Create a regular `Remote` run configuration (port 5005)
2. Start Jetty server via SBT as usual
3. Run the configuration created in Step 1

## Packaging
* Update `Environment` to `PROD`

        assembly

The jar will be in `target/`. The jar needs to reside along these assets:

* data/
* client/ (from src/main/webapp/WEB-INF/client)
* static/ (js/ css/ i/ from src/main/webapp)


    java -jar [jar]

# Altitude #

A self-hosted web-based digital asset manager (DAM) that is meant to be simple, resilient, and fast.

There are plenty of media managers in existence at this point, but they often require considerable technical
skills to just get up and running. Altitude is meant to be easy-to-use and easy-to-deploy.

The **only** thing needed is a Java runtime on the target system, and Altitude will take care 
of the rest. You don't need to know what Postgres or microservices are, or how to clear the cache state 
from Redis if something is broken (there are no microservices and there is no Redis).

Altitude will use Sqlite by default, so nothing else needs to be done. The database cannot be changed
after the fact, so if you want to use Postgres, 
move `application-dev.conf.postgres` into `application-dev.conf` before running the app for the first time
in setup mode.

## Technology Stack

* Scala & Scalatra
* Postgres OR Sqlite
* HTMX
* OpenCV
* Pekko (formerly Akka) Streams

## Status

This project is still in its "technology preview" stage. At this point it features:

* Web-based setup on the first run
* Streams-based fast import pipeline
* Facial detection and recognition
* Default result display with lazy loading and infinite scrolling
* Drag-and-drop file and folder management
* Rudimentary results display
* A test suite that runs against both Postgres and Sqlite

Missing features:

* Right now there is only the single-user, single-library mode.
* Results are displayed in their default order and there are no advanced display features yet.
* Search indexing is implemented but not yet wired to be a user-facing feature.
* No support for video files yet.
* No location editing or display.
* No metadata view (but it IS being extracted and saved).
* Facial recognition works at a basic level but most management features are still to be done.

## Development setup

### Core system requirements

* Java 11+
* Docker
* NPM

## Build & Run

```sh
# Bring up the Postgres database (dev and test at once)
make db
# Run the tests
make test
# With hot reload
make watch
```

Run `npm install` to install dev-time dependencies (these are just formatters and linters)
in order to have the `make lint` command work.

**See the Makefile for all the available commands.**

## Design decisions and other resources

These are covered in the [WIKI](https://github.com/papito/altitude/wiki)

## Databases

* SQL schema needs to be updated for *both* Postgres and SQLite. The schemas are in `src/main/resources/migrations/`
* In development, the type of database running is configured in `core.Configuration` class.

### PRE-COMMIT HOOK SETUP

In `.git/hooks/pre-commit `:

    #!/bin/sh

    make lint

## Logging

See `logback.xml` for configuration.

## Style and formatting

Linting configurations are in:

    * .scalafmt.conf
    * .scalafix.conf
    * .prettierignore
    * .prettierrc
    * .eslintrc

## Running a tagged test(s):
Update your test as such:

```
test("work in progress", Focused) {
}
```
    make test-focused-sqlite
    make test-focused-psql
    make test-focused-controller
    make test-focused-unit

## Running tests against a particular database:

    make test-sqlite
    make test-psql
    
## Running controller and unit tests separately
    make test-controller
    make test-unit
    
## Packaging

    make publish

The jar will be in `target/`. The jar can be run with:

    java -jar [jar name]

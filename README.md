# Altitude #

## DEVELOPMENT SETUP

### Core system requirements

* Java 11
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

These are covered in the [WIKI](https://github.com/papito/altitude/wiki).

## Databases

* SQL schema needs to be updated for *both* Postgres and SQLite. The schemas are in `src/main/resources/migrations/`
* In development, the type of database running is configured in `core.Configuration` class

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

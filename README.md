# Altitude #

## DEVELOPMENT SETUP

### Core system requirements

* Java (11)
* Postgres database (13)

## Build & Run

```sh
$ make up
$ sbt
> jetty:start
```

FIXME: does not seem to work anymore with newer sbt
`watch` starts Jetty server while watching files for changes. 

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

# Altitude DAM #

A self-hosted web-based digital asset manager (DAM) that is meant to be simple, resilient, and fast.

There are plenty of media managers in existence at this point, but they often require considerable technical
skills to just get up and running. Altitude is meant to be easy-to-use and easy-to-deploy.

The **only** thing needed is a Java runtime on the target system, and Altitude will take care 
of the rest (Dockerized runner will come later, when it makes more sense). 

You don't need to know what Postgres or microservices are, or how to clear the cache state 
from Redis if something is broken (there are no microservices and there is no Redis).

### Running under Postgres
Altitude will use Sqlite by default. The database cannot be changed after the fact, so if you would like to use Postgres, 
move `application-dev.conf.postgres` into `application-dev.conf` before running the app for the first time
in setup mode.

### Technology Stack

* Scala 3 & Cask
* Postgres OR Sqlite
* HTMX
* OpenCV
* Pekko (formerly Akka) Streams

## Status

This project is still in its "technology preview" stage. At this point it features:

* Web-based setup
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

## Development setup

You will need:

* Java 17 or up (21 recommended)
* Docker (if using Postgres)
* [Mill](https://mill-build.org/mill/cli/installation-ide.html)

Download [w600k_r50.onnx](https://huggingface.co/maze/faceX/blob/e010b5098c3685fd00b22dd2aec6f37320e3d850/w600k_r50.onnx) and place it in `./altitude/altitude/resources/opencv/`

### Developer settings

`application-dev.conf.example` has all supported overrides to:

* Enable Postgres as the data store.
* Define dev user and disable login gate after each hot reload cycle.

To enable, copy or rename the file as `application-dev.conf`

### Build & Run

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

[See the Makefile](https://github.com/papito/altitude/blob/trunk/Makefile) for all the available commands.


### Logging

See `logback.xml` for configuration.

### Style and formatting

Linting configurations are in:

* `.scalafmt.conf`
* `.scalafix.conf`
* `.prettierrc`
* `.eslintrc`
* `.prettierignore`
 
### Running a tagged test(s):
Update your test as such:

```
test("work in progress", Focused) {
}
```

Then:

    make test-focused

For integration tests, **this will run more than once, against all database types**.

To run with better accuracy, use one of the following Make targets:

    make test-focused-sqlite
    make test-focused-psql
    make test-focused-controller
    make test-focused-unit

### Running all tests against a particular database:

    make test-sqlite
    make test-psql
    
### Running controller and unit tests separately

    make test-controller
    make test-unit
    
### Packaging (make a fat jar)

    make publish

The jar will be in `target/`. The jar can be run with:

    java -jar [jar name]

## Design decisions and other resources

These are covered in the [WIKI](https://github.com/papito/altitude/wiki)

## Contacting the author
If you have any questions about this project, please use [this form](https://renegadeotter.com/contact.html) to drop a line.

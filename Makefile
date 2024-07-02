SHELL=/bin/sh

watch:
	sbt watch

test:
	ENV=test sbt test

test-focused:
	ENV=test sbt testFocused

test-focused-psql:
	ENV=test sbt testFocusedPostgres

test-focused-sqlite:
	ENV=test sbt testFocusedSqlite

# WEB tests (controllers) do need the ENV explicitly set,
# as this is picked up by the testing server that is spun up automatically.
#
# Other tests run in a single process and force the test environment themselves.
test-focused-controller:
	ENV=test sbt testFocusedController

test-controller:
	ENV=test sbt testController

test-psql:
	ENV=test sbt testPostgres

test-sqlite:
	ENV=test sbt testSqlite

test-unit:
	ENV=test sbt testUnit

lint:
	sbt scalafixAll

clean:
	rm -rf data/*
	mkdir -p data/db


publish:
	rm -rf release
	sbt assembly
	# we don't need this
	rm -rf target
	mkdir -p release/data/db


up:
	docker-compose -f docker-compose.yml -f docker-compose.test.yml up

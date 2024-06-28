SHELL=/bin/sh

watch:
	sbt watch

test:
	sbt test

test-focused:
	sbt testFocused

test-focused-psql:
	sbt testFocusedPostgres

test-focused-sqlite:
	sbt testFocusedSqlite

# WEB tests (controllers) do need the ENV explicitly set,
# as this is picked up by the testing server that is spun up automatically.
#
# Other tests run in a single process and force the test environment themselves.
test-focused-web:
	ENV=test sbt testFocusedWeb

test-web:
	ENV=test sbt testWeb

test-psql:
	sbt testPostgres

test-sqlite:
	sbt testSqlite

test-unit:
	sbt testUnit

lint:
	sbt scalafixAll

clean:
	rm -rf data/db
	rm -rf data/p/*
	rm -rf data/sorted/*
	rm -rf data/triage/*
	rm -rf data/trash/*

up:
	docker-compose -f docker-compose.yml -f docker-compose.test.yml up

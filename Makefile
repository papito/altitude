SHELL=/bin/sh

watch:
	ENV=dev sbt watch

compile:
	sbt compile

test:
	ENV=test sbt test

test-focused:
	ENV=test sbt testFocused

test-focused-psql:
	ENV=test sbt testFocusedPostgres

test-focused-sqlite:
	ENV=test sbt testFocusedSqlite

test-focused-unit:
	ENV=test sbt testFocusedUnit

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
	npm run format
	npm run lint:fix
	sbt scalafixAll

clean:
	rm -rf data/*

publish:
	rm -rf release
	sbt assembly
	# we don't need this
	rm -rf target

db:
	docker compose -f docker-compose.yml -f docker-compose.test.yml up

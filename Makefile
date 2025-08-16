SHELL=/bin/sh

run:
	ENV=dev mill altitude.run

watch:
	ENV=dev mill -w altitude.runBackground

compile:
	mill altitude.compile

test:
	ENV=test mill altitude.test

publish:
	mill altitude.assembly
	mill show altitude.assembly

lint:
	#npm run format
	#npm run lint:fix
	mill altitude.fix
	mill mill.scalalib.scalafmt/

#test-focused:
#	ENV=test sbt testFocused
#
#test-focused-psql:
#	ENV=test sbt testFocusedPostgres
#
#test-focused-sqlite:
#	ENV=test sbt testFocusedSqlite
#
#test-focused-unit:
#	ENV=test sbt testFocusedUnit
#
#test-focused-controller:
#	ENV=test sbt testFocusedController
#
#test-controller:
#	ENV=test sbt testController
#
#test-psql:
#	ENV=test sbt testPostgres
#
#test-sqlite:
#	ENV=test sbt testSqlite
#
#test-unit:
#	ENV=test sbt testUnit
#

#clean:
#	rm -rf data/*
#
db:
	docker compose -f docker-compose.yml -f docker-compose.test.yml up

SHELL=/bin/sh

run:
	ENV=dev mill altitude.run

watch:
	ENV=dev mill -w altitude.runBackground

compile:
	mill altitude.compile

clean-all:
	rm -rf data/*
	mill clean

clean-db:
	rm -rf data/*

publish:
	mill altitude.assembly
	mill show altitude.assembly

lint:
	#npm run format
	#npm run lint:fix
	mill altitude.fix
	mill mill.scalalib.scalafmt/


test:
	ENV=test mill -j1 altitude.test

test-focused:
	ENV=test mill -j1 altitude.test.testOnly -- -n focused -oD

test-focused-psql:
	ENV=test mill -j1 altitude.test.testOnly *PostgresSuiteBundle -- -n focused -oD

test-focused-sqlite:
	ENV=test mill -j1 altitude.test.testOnly *SqliteSuiteBundle -- -n focused -oD

test-focused-unit:
	ENV=test mill -j1 altitude.test.testOnly *UnitSuiteBundle -- -n focused -oD

# test-focused-controller:
# 	ENV=test sbt testFocusedController

# test-controller:
# 	ENV=test sbt testController

test-psql:
	ENV=test mill -j1 altitude.test.testOnly *PostgresSuiteBundle --show-output

test-sqlite:
	ENV=test mill -j1 altitude.test.testOnly *SqliteSuiteBundle --show-output

test-unit:
	ENV=test mill altitude.test.testOnly *UnitSuiteBundle --show-output


db:
	docker compose -f docker-compose.yml -f docker-compose.test.yml up

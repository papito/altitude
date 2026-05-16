SHELL=/bin/sh

run:
	ENV=dev mill altitude.run

watch:
	ENV=dev mill -w altitude.runBackground

compile:
	mill altitude.compile

clean:
	rm -rf out
	mill clean

clear-db:
	ENV=dev mill altitude.runMain altitude.tools.clearDb

# temp target to restore a DB after trying to reproduce a bug
# (to avoid importing all the time)
restore-db:
	cp data/db/altitude.db.bak data/db/altitude.db

publish:
	mill altitude.assembly
	mill show altitude.assembly

lint:
	npm run lint:fix
	npm run format
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

test-focused-controllers:
	ENV=test mill -j1 altitude.test.testOnly *ControllerSuiteBundle -- -n focused -oD

test-psql:
	ENV=test mill -j1 altitude.test.testOnly *PostgresSuiteBundle --show-output

test-sqlite:
	ENV=test mill -j1 altitude.test.testOnly *SqliteSuiteBundle --show-output

test-unit:
	ENV=test mill altitude.test.testOnly *UnitSuiteBundle --show-output

test-controllers:
	ENV=test mill altitude.test.testOnly *ControllerSuiteBundle --show-output

db:
	docker compose -f docker-compose.yml -f docker-compose.test.yml up

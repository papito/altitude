SHELL=/bin/sh

run:
	ALTITUDE_ENV=dev mill altitude.run

watch:
	ALTITUDE_ENV=dev mill -w altitude.runBackground

compile:
	mill altitude.compile

clean:
	rm -rf out
	mill clean

clear-db:
	ALTITUDE_ENV=dev mill altitude.runMain altitude.tools.clearDb

# Restores a backed-up dev DB after reproducing a bug, to avoid re-importing
restore-db:
	cp data/db/altitude.db.bak data/db/altitude.db

publish:
	mill altitude.assembly
	mill show altitude.assembly

# Vendors the tokentweak package (the design tweaks panel) into altitude/static/js/lib; see the README there.
vendor-tokentweak:
	sh scripts/vendor-tokentweak.sh $(TOKENTWEAK)

lint:
	npm run lint:fix
	npm run format
	mill altitude.fix
	mill mill.scalalib.scalafmt/


# Tests tagged Slow (org.scalatest.tagobjects.Slow) are left out of every target but test-with-slow and the focused ones
SKIP_SLOW = -l org.scalatest.tags.Slow

test:
	ALTITUDE_ENV=test mill -j4 altitude.test.testOnly '*SuiteBundle' -- $(SKIP_SLOW)

test-with-slow:
	ALTITUDE_ENV=test mill -j4 altitude.test.testOnly '*SuiteBundle'

test-local: test-js
	ALTITUDE_ENV=test mill -j3 altitude.test.testOnly '*UnitSuiteBundle' '*SqliteSuiteBundle' '*ControllerSuiteBundle' -- $(SKIP_SLOW)

test-focused:
	ALTITUDE_ENV=test mill -j4 altitude.test.testOnly '*SuiteBundle' -- -n focused -oD

test-focused-psql:
	ALTITUDE_ENV=test mill -j1 altitude.test.testOnly *PostgresSuiteBundle -- -n focused -oD

test-focused-sqlite:
	ALTITUDE_ENV=test mill -j1 altitude.test.testOnly *SqliteSuiteBundle -- -n focused -oD

test-focused-unit:
	ALTITUDE_ENV=test mill -j1 altitude.test.testOnly *UnitSuiteBundle -- -n focused -oD

test-focused-controllers:
	ALTITUDE_ENV=test mill -j1 altitude.test.testOnly *ControllerSuiteBundle -- -n focused -oD

test-psql:
	ALTITUDE_ENV=test mill -j1 altitude.test.testOnly *PostgresSuiteBundle --show-output -- $(SKIP_SLOW)

test-sqlite:
	ALTITUDE_ENV=test mill -j1 altitude.test.testOnly *SqliteSuiteBundle --show-output -- $(SKIP_SLOW)

test-unit:
	ALTITUDE_ENV=test mill altitude.test.testOnly *UnitSuiteBundle --show-output -- $(SKIP_SLOW)

test-controllers:
	ALTITUDE_ENV=test mill altitude.test.testOnly *ControllerSuiteBundle --show-output -- $(SKIP_SLOW)

# The client modules' tests, under Node's built-in runner with no dependencies
test-js:
	node --test 'scripts/test-js/*.test.mjs'

db:
	docker compose -f docker-compose.yml -f docker-compose.test.yml up

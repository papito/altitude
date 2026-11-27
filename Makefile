SHELL=/bin/sh

run:
	ALTITUDE_ENV=dev ./mill altitude.run

watch:
	ALTITUDE_ENV=dev ./mill -w altitude.runBackground

compile:
	./mill altitude.compile

clean:
	rm -rf out
	./mill clean

clear-db:
	ALTITUDE_ENV=dev ./mill altitude.runMain altitude.tools.clearDb

# Restores a backed-up dev DB after reproducing a bug, to avoid re-importing
restore-db:
	cp data/db/altitude.db.bak data/db/altitude.db

# The native jars, one per platform, all built on this machine: each altitude.platformJar run takes its natives from
# ALTITUDE_PLATFORM, and each jar is copied out of the task's folder, which the next run replaces, into out/dist-jars/.
DIST_PLATFORMS = linux-x86_64 linux-arm64 macosx-arm64 windows-x86_64

dist-jars:
	rm -rf out/dist-jars
	mkdir -p out/dist-jars
	for platform in $(DIST_PLATFORMS); do \
		ALTITUDE_PLATFORM=$$platform ./mill altitude.platformJar && \
		cp out/altitude/platformJar.dest/altitude-*-$$platform.jar out/dist-jars/ || exit 1; \
	done
	ls -l out/dist-jars

# Starts this machine's native jar (./mill altitude.platformJar first) from a scratch folder, imports into it, stops it and starts
# it again, checking what the jar promises; JAR=<path> tests another. See the script.
smoke-jar:
	bash scripts/smoke-jar.sh $(JAR)

# Vendors the tokentweak package (the design tweaks panel) into altitude/static/js/lib; see the README there.
vendor-tokentweak:
	sh scripts/vendor-tokentweak.sh $(TOKENTWEAK)

lint:
	npm run lint:fix
	npm run format
	./mill altitude.fix
	./mill mill.scalalib.scalafmt/


# Tests tagged Slow (org.scalatest.tagobjects.Slow) are left out of every target but test-with-slow and the focused ones
SKIP_SLOW = -l org.scalatest.tags.Slow

# The JS tests first: they take a second and fail before the Scala bundles start
test: test-js test-db
	ALTITUDE_ENV=test ./mill -j4 altitude.test.testOnly '*SuiteBundle' -- $(SKIP_SLOW)

test-with-slow: test-js test-db
	ALTITUDE_ENV=test ./mill -j4 altitude.test.testOnly '*SuiteBundle'

test-focused: test-db
	ALTITUDE_ENV=test ./mill -j4 altitude.test.testOnly '*SuiteBundle' -- -n focused -oD

test-focused-psql: test-db
	ALTITUDE_ENV=test ./mill -j1 altitude.test.testOnly *PostgresSuiteBundle -- -n focused -oD

test-focused-sqlite:
	ALTITUDE_ENV=test ./mill -j1 altitude.test.testOnly *SqliteSuiteBundle -- -n focused -oD

test-focused-unit:
	ALTITUDE_ENV=test ./mill -j1 altitude.test.testOnly *UnitSuiteBundle -- -n focused -oD

test-focused-controllers:
	ALTITUDE_ENV=test ./mill -j1 altitude.test.testOnly *ControllerSuiteBundle -- -n focused -oD

test-psql: test-db
	ALTITUDE_ENV=test ./mill -j1 altitude.test.testOnly *PostgresSuiteBundle --show-output -- $(SKIP_SLOW)

test-sqlite:
	ALTITUDE_ENV=test ./mill -j1 altitude.test.testOnly *SqliteSuiteBundle --show-output -- $(SKIP_SLOW)

test-unit:
	ALTITUDE_ENV=test ./mill altitude.test.testOnly *UnitSuiteBundle --show-output -- $(SKIP_SLOW)

test-controllers:
	ALTITUDE_ENV=test ./mill altitude.test.testOnly *ControllerSuiteBundle --show-output -- $(SKIP_SLOW)

# The client modules' tests, under Node's built-in runner with no dependencies
test-js:
	node --test 'scripts/test-js/*.test.mjs'

# The dev and test databases of a server run from the sources; compose.yaml is Altitude itself under Docker
db:
	docker compose -f compose.dev.yaml -f compose.test.yaml up

# The test database the PostgreSQL bundle reaches on port 5433 (compose.test.yaml), started unless it is up already;
# --wait returns once its health check passes, and fails if it turns unhealthy. The dev container is in the same compose
# project but not in this file, so compose would call it an orphan: COMPOSE_IGNORE_ORPHANS keeps that warning out and
# leaves the container alone.
test-db:
	COMPOSE_IGNORE_ORPHANS=true docker compose -f compose.test.yaml up --wait

.PHONY: dist-jars smoke-jar docker smoke-docker test-db

# Builds the image and loads it as altitude:local, for this machine's architecture. DOCKER_PLATFORM=linux/arm64 (or
# linux/amd64) builds the other one's, which needs QEMU for the image's one RUN:
# docker run --privileged --rm tonistiigi/binfmt --install arm64. The Dockerfile's cache mounts need BuildKit, so the
# docker-buildx package.
docker:
	docker buildx build --load $(if $(DOCKER_PLATFORM),--platform $(DOCKER_PLATFORM)) --tag altitude:local .

# Starts altitude:local, imports into it and stops it mid-import, checking what the image promises; see the script
smoke-docker:
	bash scripts/smoke-docker.sh

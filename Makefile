# Debunkax build Makefile.
# Conventions: `make debug` / `make release` build APKs; `make test` runs unit tests;
# `make connected-test` runs instrumented tests on a device/emulator.

# JDK 17, overridable: make JAVA_HOME=/path/to/jdk ...
# A valid JAVA_HOME from the environment is kept; a missing or wrong one is replaced.
JAVA_HOME ?=
ifeq ($(wildcard $(JAVA_HOME)/bin/java),)
JAVA_HOME := $(dir $(dir $(shell readlink -f /usr/bin/java)))
endif
export JAVA_HOME

GRADLE ?= ./gradlew

# Device or emulator serial for install/connected-test, e.g. make install DEVICE=emulator-5554.
# When unset, adb's default device is used.
DEVICE ?=
ifneq ($(DEVICE),)
export ANDROID_SERIAL := $(DEVICE)
endif

.DEFAULT_GOAL := help

.PHONY: help
help: ## List available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "Examples:"
	@echo "  make debug                       # debug APK (arm64 + x86_64)"
	@echo "  make release                     # signed release APK (arm64 only)"
	@echo "  make release EMULATOR_ABI=1      # also include x86_64 (emulator smoke test)"
	@echo "  make install DEVICE=emulator-5554  # install the debug APK on a device"
	@echo "  make connected-test DEVICE=emulator-5554"

.PHONY: build
build: test debug release ## Unit tests + debug and release APKs

.PHONY: debug
debug: ## Assemble the debug APK (app/build/outputs/apk/debug/)
	$(GRADLE) assembleDebug

.PHONY: release
release: ## Assemble the signed release APK (arm64-v8a only)
	@test -f keystore.properties || { echo "keystore.properties not found — see docs/release.md"; exit 1; }
	$(GRADLE) assembleRelease $(if $(EMULATOR_ABI),-PemulatorAbi)

.PHONY: test
test: ## Run JVM unit tests (incl. Robolectric)
	$(GRADLE) testDebugUnitTest

.PHONY: connected-test
connected-test: ## Run instrumented Compose tests on a connected device/emulator
	$(GRADLE) connectedDebugAndroidTest

.PHONY: bench
bench: ## Run the paid Verdict bench (see spikes/verdicts/README.md)
	$(GRADLE) testDebugUnitTest --tests '*VerdictBench*' -Pbench

.PHONY: lint
lint: ## Run Android Lint
	$(GRADLE) lintDebug

.PHONY: install
install: ## Install the debug APK on a connected device (DEVICE=<serial>)
	$(GRADLE) installDebug

.PHONY: run
run: install ## Install the debug APK and launch it
	adb shell monkey -p app.debunkax -c android.intent.category.LAUNCHER 1

.PHONY: clean
clean: ## Remove build outputs
	$(GRADLE) clean
	rm -rf app/build build

# CallVerse backend - developer shortcuts
#
# Usage:  make            (shows this help)
#         make api        (run the backend against Neon)
#
# This file contains NO credentials. Everything sensitive is read from .env,
# which is git-ignored. If .env is missing, targets that need it fail loudly
# rather than starting against a half-configured environment.

# --- Shell --------------------------------------------------------------------
# Every recipe runs in Git Bash, whichever shell you launched make from.
#
# `SHELL := /bin/bash` alone only works when make is started FROM Git Bash.
# Started from PowerShell or cmd, make cannot resolve that path, silently falls
# back to cmd.exe, and every recipe fails with
#     'test' is not recognized as an internal or external command
# Naming bash.exe by absolute path removes the dependency on the caller.
#
# The candidates are Git for Windows only, deliberately. The bash.exe that comes
# first on PowerShell's PATH is the one Windows ships in System32, which is the
# WSL launcher: a different machine, with a different filesystem, where this
# repository is not at this path. Picking it up would be worse than failing.
#
# The 8.3 short names are not cosmetic either - make cannot handle a SHELL path
# containing spaces, and every normal install location has one.
ifeq ($(OS),Windows_NT)
  BASH := $(firstword $(wildcard       C:/PROGRA~1/Git/bin/bash.exe       C:/PROGRA~2/Git/bin/bash.exe       $(subst \,/,$(LOCALAPPDATA))/Programs/Git/bin/bash.exe))
  ifeq ($(BASH),)
    $(error Git Bash not found. Install Git for Windows, or pass the path: make api BASH=/c/path/to/bash.exe)
  endif
else
  BASH := /bin/bash
endif
SHELL := $(BASH)
.SHELLFLAGS := -c

.DEFAULT_GOAL := help

# --- Toolchain ---------------------------------------------------------------
# Honours JAVA_HOME when the machine already sets one, and falls back to the
# Temurin 21 install otherwise. The normalisation matters: the Windows installer
# writes it with backslashes AND a trailing separator, which turns
# "$JAVA_HOME/bin/java" into a path with a doubled separator inside bash.
#
# There is deliberately no `export PATH := $(JAVA_HOME)/bin:$(PATH)` here. PATH
# arrives ':'-separated from Git Bash but ';'-separated from PowerShell, so that
# line built a corrupt PATH depending on which shell launched make. Nothing needs
# it: the Maven wrapper resolves the JDK from JAVA_HOME, and Git Bash supplies
# its own PATH for grep, awk, sed and curl.
#
# Override on the command line if yours differs:
#     make api JAVA_HOME=/c/path/to/jdk21
JAVA_HOME ?= /c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot
JAVA_HOME := $(patsubst %/,%,$(subst \,/,$(JAVA_HOME)))
export JAVA_HOME

# Invoked through an explicit interpreter, never as a bare './mvnw'.
#
# GNU Make built for Windows bypasses SHELL entirely for any recipe line that
# contains no shell metacharacter, launching it through the Windows API and
# falling back to cmd.exe. cmd.exe cannot resolve './mvnw' and fails with
#     '.' is not recognized as an internal or external command
# $(BASH) rather than a bare `bash` for the same reason as above: on that path
# make would resolve `bash` against the Windows PATH and find WSL.
MVNW := $(BASH) ./mvnw
PORT ?= 8080

# The profile "make api" runs under. Set here rather than inherited from .env,
# because .env.example deliberately ships no SPRING_PROFILES_ACTIVE: docker compose
# interpolates that same file, and a value in it silently defeated the fail-closed
# "prod" default in docker-compose.yml. The two paths are separate on purpose now -
# this target is local development and picks dev; a deployment gets prod unless it
# says otherwise. To see what a deployment actually serves:
#     make api PROFILE=prod
PROFILE ?= dev

# --- Environment -------------------------------------------------------------
# .env is sourced by the recipes themselves (not `include`d) so that values
# containing '#', ':' or spaces cannot be mangled by make's parser.
ENV_FILE := .env
LOAD_ENV := set -a; . ./$(ENV_FILE); set +a

define require_env
	@test -f $(ENV_FILE) || { \
	  echo ""; \
	  echo "  ERROR: $(ENV_FILE) not found."; \
	  echo ""; \
	  echo "  Create it from the template and fill in your Neon details:"; \
	  echo "      cp .env.example .env"; \
	  echo ""; \
	  echo "  You need the POOLED host (contains '-pooler') for DB_HOST and the"; \
	  echo "  DIRECT host (same host without '-pooler') for DB_DIRECT_HOST."; \
	  echo "  Generate a JWT secret with:  openssl rand -base64 48"; \
	  echo ""; \
	  exit 1; }
endef

# =============================================================================
.PHONY: help
help: ## Show this help
	@echo ""
	@echo "  CallVerse backend"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "    \033[36m%-14s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  Docs:   /swagger-ui.html   and   /v3/api-docs"
	@echo "  Note:   'make test' and 'make verify' require a running Docker daemon."
	@echo ""

# --- Running -----------------------------------------------------------------
.PHONY: api
api: ## Run the backend against Neon (dev profile, reads .env)
	$(require_env)
	@echo "  Starting CallVerse on port $(PORT), profile $(PROFILE) ..."
	@echo "  Neon cold start can take ~30s on first request after idle."
	@$(LOAD_ENV); SPRING_PROFILES_ACTIVE=$(PROFILE) $(MVNW) spring-boot:run

.PHONY: api-jar
api-jar: build ## Run the packaged jar instead of the Maven plugin (faster restart)
	$(require_env)
	@$(LOAD_ENV); "$$JAVA_HOME/bin/java" -jar target/callverse-backend-0.1.0-SNAPSHOT.jar

# --- Build and test ----------------------------------------------------------
.PHONY: build
build: ## Package the jar, skipping tests
	@$(MVNW) clean package -DskipTests

.PHONY: test
test: ## Run the full test suite (REQUIRES Docker - Testcontainers)
	@docker info >/dev/null 2>&1 || { \
	  echo "  ERROR: Docker is not running. The persistence tests start a real"; \
	  echo "         PostgreSQL via Testcontainers. Start Docker Desktop first."; \
	  exit 1; }
	@$(MVNW) test

.PHONY: verify
verify: ## Full build + all tests - run this before pushing (REQUIRES Docker)
	@docker info >/dev/null 2>&1 || { \
	  echo "  ERROR: Docker is not running. Start Docker Desktop first."; exit 1; }
	@$(MVNW) clean install

.PHONY: openapi
openapi: ## Regenerate the committed openapi.yaml from the live document (REQUIRES Docker)
	@docker info >/dev/null 2>&1 || { echo "  ERROR: Docker is not running."; exit 1; }
	@$(MVNW) test -Dtest=OpenApiContractTest -Dopenapi.update=true

.PHONY: arch
arch: ## Run only the architecture rules (fast, no Docker needed)
	@$(MVNW) test -Dtest=LayerDependencyTest

.PHONY: clean
clean: ## Remove build output
	@$(MVNW) clean

# --- Inspection --------------------------------------------------------------
.PHONY: swagger
swagger: ## Print the API documentation URLs
	@echo ""
	@echo "    Swagger UI     http://localhost:$(PORT)/swagger-ui.html"
	@echo "    OpenAPI JSON   http://localhost:$(PORT)/v3/api-docs"
	@echo "    Health         http://localhost:$(PORT)/api/v1/health/status"
	@echo ""
	@echo "    The backend must be running - use 'make api' in another terminal."
	@echo "    Frontend type generation:"
	@echo "      npx openapi-typescript http://localhost:$(PORT)/v3/api-docs -o src/app/core/api/callverse-api.d.ts"
	@echo ""

.PHONY: health
health: ## Curl the health endpoint of a running backend
	@curl -s http://localhost:$(PORT)/api/v1/health/status && echo "" \
	  || echo "  Not responding on port $(PORT). Is 'make api' running?"

.PHONY: db
db: ## Show which database is configured (never prints the password)
	$(require_env)
	@$(LOAD_ENV); \
	  echo ""; \
	  echo "    pooled  (app)     $$DB_HOST"; \
	  echo "    direct  (flyway)  $$DB_DIRECT_HOST"; \
	  echo "    database          $$DB_NAME"; \
	  echo "    user              $$DB_USERNAME"; \
	  echo "    sslmode           $$DB_SSLMODE"; \
	  echo "    profile           $$SPRING_PROFILES_ACTIVE"; \
	  echo ""; \
	  echo "    Neon opens 'neondb' by default in the console. Switch the database"; \
	  echo "    dropdown to $$DB_NAME or the tables will look empty."; \
	  echo ""

.PHONY: env-check
env-check: ## Verify every variable the application needs is present in .env
	$(require_env)
	@$(LOAD_ENV); missing=0; \
	  for v in DB_HOST DB_DIRECT_HOST DB_PORT DB_NAME DB_USERNAME DB_PASSWORD \
	           DB_SSLMODE JWT_SECRET JWT_EXPIRATION_MS AI_SERVICE_BASE_URL \
	           SERVER_PORT; do \
	    if [ -z "$${!v}" ]; then echo "    MISSING  $$v"; missing=1; \
	    else echo "    ok       $$v"; fi; \
	  done; \
	  echo ""; \
	  if [ -n "$$SPRING_PROFILES_ACTIVE" ]; then \
	    echo "    profile   SPRING_PROFILES_ACTIVE=$$SPRING_PROFILES_ACTIVE (from .env)"; \
	  else \
	    echo "    profile   unset - docker compose falls back to prod (deny-by-default)"; \
	    echo "              make api still uses dev; override with PROFILE=prod"; \
	  fi; \
	  test $$missing -eq 0 && echo "" && echo "    .env is complete." || \
	    { echo ""; echo "    .env is incomplete - see .env.example."; exit 1; }

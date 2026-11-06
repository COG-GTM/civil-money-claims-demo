.PHONY: up up-observability down reset test build logs
MVN = mvn -B -s .mvn/settings.xml
up:
	docker compose up --build -d
up-observability:
	./scripts/up-observability.sh
down:
	docker compose down --remove-orphans
reset:
	./scripts/reset.sh
test:
	cd citizen-ui && npm ci && npm run build && npm test
	node --test scripts/traffic.test.ts
	node citizen-ui/node_modules/typescript/bin/tsc -p scripts/tsconfig.json
	$(MVN) -f civil-case-api/pom.xml verify
	$(MVN) -f legacy-claim-store/pom.xml -DskipTests package
build:
	cd citizen-ui && npm ci && npm run build
	$(MVN) -f civil-case-api/pom.xml -DskipTests package
	$(MVN) -f legacy-claim-store/pom.xml -DskipTests package
logs:
	docker compose logs -f

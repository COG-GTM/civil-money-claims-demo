.PHONY: up down reset test build logs
MVN = mvn -B -s .mvn/settings.xml
up:
	docker compose up --build -d
down:
	docker compose down
reset:
	./scripts/reset.sh
test:
	cd citizen-ui && npm ci && npm run build && npm test
	$(MVN) -f civil-case-api/pom.xml verify
	$(MVN) -f legacy-claim-store/pom.xml -DskipTests package
build:
	cd citizen-ui && npm ci && npm run build
	$(MVN) -f civil-case-api/pom.xml -DskipTests package
	$(MVN) -f legacy-claim-store/pom.xml -DskipTests package
logs:
	docker compose logs -f

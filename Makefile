COMPOSE ?= docker compose
SERVICES := mariadb redis backend nginx prometheus grafana
JDBC_LOCAL := jdbc:mariadb://127.0.0.1:13306/cabi?serverTimezone=Asia/Seoul&useUnicode=true&characterEncoding=UTF-8&useSSL=false

.DEFAULT_GOAL := help
.PHONY: help env build up down reset seed logs ps check run test

help:
	@echo "사용법: make <대상>"
	@echo ""
	@echo "  env     .env 가 없으면 .env.example 을 복사해서 만든다"
	@echo "  build   jar 를 빌드한다 (테스트 제외)"
	@echo "  up      빌드 후 docker compose 로 전체 스택을 띄우고 기동을 기다린다"
	@echo "  seed    가짜 데이터 시드(사물함 90개, 데모 사용자)를 DB 에 넣는다"
	@echo "  check   로그인 없이 호출할 수 있는 API 로 응답을 확인한다"
	@echo "  logs    백엔드 로그를 따라 본다"
	@echo "  ps      컨테이너 상태를 본다"
	@echo "  down    컨테이너를 내린다 (데이터는 남는다)"
	@echo "  reset   컨테이너와 데이터(db_data, redis_data)를 모두 지운다"
	@echo "  run     DB 와 Redis 만 compose 로 띄우고 gradlew bootRun 으로 실행한다"
	@echo "  test    전체 테스트를 실행한다 (Docker 필요)"

env:
	@if [ ! -f .env ]; then cp .env.example .env && echo ".env 를 만들었습니다. 값을 확인하세요."; fi

build: env
	./gradlew clean build -x test

up: build
	$(COMPOSE) up -d --build --no-deps $(SERVICES)
	@echo "백엔드 기동을 기다립니다 (최대 120초)"
	@i=0; until $(COMPOSE) logs backend 2>/dev/null | grep -q "Started CabinetApplication"; do \
		i=$$((i+1)); \
		if [ $$i -gt 60 ]; then echo "시간 안에 기동하지 못했습니다. make logs 로 확인하세요."; exit 1; fi; \
		sleep 2; \
	done
	@echo "기동했습니다."
	@echo "  API      http://localhost"
	@echo "  Swagger  http://localhost/swagger-ui/index.html"
	@echo "  Grafana  http://localhost:3000 (admin / admin)"

seed: env
	@set -a; . ./.env; set +a; \
	docker exec -i cabi_db mariadb -u"$$DB_USER" -p"$$DB_PASSWORD" cabi < scripts/db/seed/demo-seed.sql \
	&& echo "시드를 넣었습니다."

check:
	@curl -fsS http://localhost/v4/cabinets/status-summary/all && echo ""

logs:
	$(COMPOSE) logs -f backend

ps:
	$(COMPOSE) ps

down:
	$(COMPOSE) down

reset:
	$(COMPOSE) down -v
	rm -rf db_data redis_data

run: env
	$(COMPOSE) up -d mariadb redis
	@echo "DB 기동을 기다립니다 (최대 60초)"
	@set -a; . ./.env; set +a; i=0; \
	until docker exec cabi_db mariadb-admin ping -h127.0.0.1 -u"$$DB_USER" -p"$$DB_PASSWORD" --silent >/dev/null 2>&1; do \
		i=$$((i+1)); \
		if [ $$i -gt 30 ]; then echo "DB 가 뜨지 않았습니다."; exit 1; fi; \
		sleep 2; \
	done
	@set -a; . ./.env; set +a; \
	DB_HOST=127.0.0.1 DB_PORT=13306 REDIS_ALLOW_INSECURE=true \
	SPRING_DATASOURCE_URL='$(JDBC_LOCAL)' \
	SPRING_CLOUD_AZURE_STORAGE_BLOB_CONNECTION_STRING="$$AZURE_STORAGE_CONNECTION_STRING" \
	./gradlew bootRun

test:
	./gradlew clean test

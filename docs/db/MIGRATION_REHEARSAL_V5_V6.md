# V2~V6 마이그레이션 리허설 결과와 운영 이행 체크리스트

> 코드/스키마를 바꾸는 문서가 아니라 **검증 보고 + 절차 문서**다. 리허설은 `MigrationRehearsalMysqlTest`(Testcontainers, `mariadb:10.6` 과 `mysql:8.0`)로 실제 DB 에서 돌렸고, 운영 DB 에는 접속하지 않았다. 비밀번호·접속 정보는 이 문서에 쓰지 않는다. 기본 절차는 `FLYWAY_BASELINE.md` 를 따른다.

## 1. 리허설로 확인한 것

| # | 시나리오 | 결과 (mariadb:10.6 / mysql:8.0 모두) |
|---|---|---|
| 1 | 운영 경로: V1 스키마가 이미 있고 데이터가 있는 DB 에 `baseline 1` → `migrate` | **V2~V6 5개만 실행**, V1 미실행, 기존 행 보존, `flyway validate` 통과, 모든 엔티티 `ddl-auto=validate` 통과. 기존 사용자는 `kakao_alarm=0`, `ft_grade=NULL`(일반 취급) |
| 2 | V5 중간 실패 (같은 이름의 `faq_question` 이 먼저 있어 둘째 CREATE 실패) | `faq` 는 **남는다**(DDL 롤백 불가). 이력에 `5:SQL:0`. 그대로 `migrate` 하면 "failed migration" 으로 거부. `repair` 만 하고 재시도하면 `faq` 가 이미 있어 **또 실패**. 남은 테이블을 지우고 `repair` → `migrate` 하면 V5, V6 가 이어서 적용 |
| 3 | V6 중간 실패 (같은 이름의 `kakao_notify_consent` 가 먼저 있어 CREATE 실패) | `user.kakao_alarm` 컬럼은 **이미 추가된 채 남는다**. 이력에 `6:SQL:0`. `repair` 만 하고 재시도하면 `Duplicate column name 'kakao_alarm'` 으로 **또 실패**. 컬럼과 남은 테이블을 지우고 `repair` → `migrate` 하면 적용 |
| 4 | `user` 에 26만 행을 넣고 V4, V6 의 `ADD COLUMN` 을 `ALGORITHM=INSTANT` 로 실행 | 둘 다 성공(테이블 재구성 없음). mariadb:10.6 에서 309ms / 66ms, mysql:8.0 에서 72ms / 60ms |
| 5 | 다른 세션이 `user` 를 읽는 트랜잭션을 연 채 `migrate` (`lock_wait_timeout=3`) | ALTER 가 메타데이터 락을 기다리다 약 3.3초 뒤 실패(V4 에서 `4:SQL:0`, 단일 문장이라 **반쯤 적용된 것 없음**). 그 사이 들어온 일반 `SELECT` 도 ALTER 뒤에 줄을 서서 **약 2.3초 막혔다**. 트랜잭션을 닫고 `repair` → `migrate` 하면 정상 적용 |

## 2. 문장별 재실행 안전성

V2~V6 어디에도 `IF NOT EXISTS` / `IF EXISTS` 가 없다. 그래서 "일부만 적용된 뒤 같은 파일을 다시 실행"하는 것은 안전하지 않다.

| 버전 | 문장 | 한 파일이 중간에 실패하면 | 비고 |
|---|---|---|---|
| V2 | `CREATE TABLE admin_action_log`, `CREATE TABLE admin_action_log_item`(FK) | 첫 테이블만 남을 수 있음 | 새 테이블만 만든다 |
| V3 | `ALTER TABLE admin_action_log ADD COLUMN ..., ADD UNIQUE KEY ...` | 한 문장이라 전부 적용되거나 전혀 안 됨 | V2 가 만든 빈 테이블 대상 |
| V4 | `ALTER TABLE user ADD COLUMN ft_grade` | 한 문장 | `user` 대상(INSTANT) |
| V5 | `CREATE TABLE faq`, `CREATE TABLE faq_question`(FK) | **`faq` 만 남을 수 있음** (리허설 2) | 새 테이블만 만든다 |
| V6 | `ALTER TABLE user ADD COLUMN kakao_alarm`, `CREATE TABLE kakao_notify_consent`(FK) | **`kakao_alarm` 컬럼만 남을 수 있음** (리허설 3) | `user` 대상(INSTANT) |

- **이미 적용된 마이그레이션 파일(V2~V6)을 `IF NOT EXISTS` 로 고치지 않는다.** 테스트 서버는 V1~V6 가 이미 적용돼 있어서, 파일 내용이 바뀌면 Flyway 체크섬이 달라져 검증이 실패한다(Flyway 의 정상 동작이며 이 리허설에서 직접 시험하지는 않았다). 실패는 아래 복구 절차로 처리한다.
- MySQL/MariaDB 의 DDL 은 트랜잭션 롤백이 되지 않는다. 실패하면 Flyway 가 해당 버전을 `success=0` 으로 기록하고 이후 `migrate` 를 거부한다.

## 3. 운영 영향 (락 범위)

- **테이블 재구성은 없다.** 기존 테이블을 바꾸는 문장은 `user` 의 `ADD COLUMN` 두 개(V4, V6)와 V3(새 테이블)뿐이고, `user` 두 개는 INSTANT 로 실행됨을 확인했다(`mysql:8.0` 은 8.0.12 이상, MariaDB 10.3 이상 필요). 나머지는 모두 새 테이블 `CREATE TABLE` 이다.
- 그래도 **ALTER 는 `user` 의 메타데이터 락을 잡아야 한다.** 오래 열린 트랜잭션이 `user` 를 쥐고 있으면 ALTER 가 기다리고, 그동안 `user` 를 읽으려는 **새 요청도 줄 서서 막힌다**(리허설 5). 서비스가 멈춘 것처럼 보일 수 있으므로 적용 전 확인과 `lock_wait_timeout` 이 필요하다.
- V6 의 `kakao_notify_consent` 는 `user(id)` 를 참조하는 FK 를 만든다. 짧은 공유 메타데이터 락이면 충분하다.
- 운영 `user` 행 수는 이 환경에서 알 수 없다. 체크리스트 2단계에서 확인한다.
- 모든 변경이 **추가 전용**(새 테이블, 기본값이 있는 새 컬럼)이라, 마이그레이션을 먼저 적용한 상태에서 **이전 이미지가 계속 떠 있어도** `validate` 와 쿼리가 깨지지 않는다(롤백 가능).

## 4. 운영 이행 체크리스트 (순서대로)

**권장 방식: 앱이 부팅하며 마이그레이션하게 두지 말고, 배포 전에 사람이 `flyway migrate` 를 먼저 실행한다.** 앱 부팅 중 실패하면 새 이미지가 뜨지 못한 채 재시작을 반복하고, 실패한 마이그레이션이 남아 계속 거부되기 때문이다. 미리 적용해 두면 새 이미지는 할 일이 없다.

`<...>` 는 자리표시자다. 비밀번호는 셸 히스토리에 남지 않게 환경변수로만 넘긴다(`export FLYWAY_PASSWORD=...` 을 대화형으로 입력).

1. **사전 확인 (읽기 전용)**
   ```sql
   SELECT VERSION();                                              -- MySQL 8.0.12+ 인지 (INSTANT 가능)
   SELECT COUNT(*) FROM `user`;                                   -- 행 수 (재구성 없음이지만 규모 파악)
   SHOW TABLES LIKE 'faq%';  SHOW TABLES LIKE 'kakao_notify%';    -- 빈 결과여야 함 (충돌하는 테이블 없음)
   SHOW COLUMNS FROM `user` LIKE 'kakao_alarm';                   -- 빈 결과여야 함
   SHOW COLUMNS FROM `user` LIKE 'ft_grade';                      -- 빈 결과여야 함
   SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;  -- 1(BASELINE)만 있어야 함
   ```
2. **백업 시점 기록**: 마이그레이션 직전 시각과 복원 가능 시점(자동 백업/PITR)을 확인해 적어 둔다. (운영 DB 설정은 이 환경에서 확인하지 못했다.)
3. **적용 계정 권한**: `CREATE, ALTER, INDEX, REFERENCES` (`DROP` 불필요).
4. **적용 시간대 선택**: 접속이 적은 시간. 직전에 오래 열린 트랜잭션이 없는지 확인한다.
   ```sql
   SELECT trx_id, trx_started, trx_mysql_thread_id FROM information_schema.innodb_trx ORDER BY trx_started;
   SHOW FULL PROCESSLIST;
   ```
   수 초 넘게 열린 트랜잭션이 보이면 끝나기를 기다리거나 원인을 먼저 확인한다.
5. **상태 확인**
   ```bash
   flyway -url="jdbc:mysql://<host>:3306/cabi" -user=<user> \
          -locations=filesystem:src/main/resources/db/migration info
   ```
   V1 `Baseline`, V2~V6 `Pending` 이어야 한다.
6. **적용** (메타데이터 락을 무한정 기다리지 않게 세션 타임아웃을 건다)
   ```bash
   flyway -url="jdbc:mysql://<host>:3306/cabi" -user=<user> \
          -locations=filesystem:src/main/resources/db/migration \
          -initSql="SET SESSION lock_wait_timeout=10" \
          migrate
   ```
   `baselineOnMigrate` 는 켜지 않는다. 락 대기로 실패하면(V4 에서 단일 문장 실패, 반쯤 적용된 것 없음) → 7번의 "잠금 실패" 로 간다.
7. **결과 확인**
   ```sql
   SELECT installed_rank, version, success FROM flyway_schema_history ORDER BY installed_rank;  -- 1~6 모두 success=1
   SHOW COLUMNS FROM `user` LIKE 'kakao_alarm';  SHOW COLUMNS FROM `user` LIKE 'ft_grade';
   SHOW TABLES LIKE 'faq%';  SHOW TABLES LIKE 'kakao_notify_consent';  SHOW TABLES LIKE 'admin_action_log%';
   ```
   그리고 `flyway ... validate` 가 통과해야 한다.
8. **배포**: App Settings 에 `FLYWAY_ENABLED=true` 를 켜고 새 이미지를 배포한다. 이미 적용돼 있으므로 부팅 중에 실행할 마이그레이션이 없다. `ddl-auto` 는 `validate` 그대로.

### 실패했을 때

| 상황 | 증상 | 복구 |
|---|---|---|
| 잠금 실패 (V4 또는 V6 의 ALTER 가 `Lock wait timeout`) | 이력에 `4:SQL:0` 또는 `6:SQL:0`. 단일 문장 ALTER 라 반쯤 적용된 것은 없다(V6 는 첫 문장이 ALTER 이므로 실패 시 아무것도 적용 안 됨) | 오래 열린 트랜잭션을 끝낸다 → `flyway repair` → `flyway migrate` |
| V5 중간 실패 | `5:SQL:0`, `faq` 테이블만 남아 있을 수 있음 | `DROP TABLE IF EXISTS faq_question; DROP TABLE IF EXISTS faq;` → `flyway repair` → `flyway migrate` |
| V6 중간 실패 | `6:SQL:0`, `user.kakao_alarm` 컬럼이 남아 있을 수 있음 | `DROP TABLE IF EXISTS kakao_notify_consent; ALTER TABLE \`user\` DROP COLUMN kakao_alarm;` → `flyway repair` → `flyway migrate` |
| 원인을 모르겠다 | — | `flyway info` 와 위 7번의 확인 쿼리로 무엇이 적용됐는지 먼저 파악한다. 남은 것을 임의로 지우기 전에 어떤 테이블/컬럼이 **이 마이그레이션이 만든 것인지** 확인한다(같은 이름의 기존 객체를 지우면 안 된다) |

- `repair` 는 실패 기록(`success=0`)을 지울 뿐 스키마를 되돌리지 않는다. 반쯤 남은 객체를 먼저 치우지 않고 `repair` 만 하면 같은 곳에서 다시 실패한다(리허설 2, 3).
- V2~V6 중 이미 적용된 파일은 절대 수정하지 않는다(체크섬).
- 전체 되돌림이 필요하면 백업에서 복원한다(`flyway undo` 는 쓰지 않는다). 복원하면 `flyway_schema_history` 도 같이 되돌아가므로 절차를 처음부터 다시 한다.

## 5. 리허설을 다시 돌리려면

```bash
./gradlew test --tests '*MigrationRehearsalMysqlTest'   # Docker 필요, 약 3분
cat build/migration-rehearsal.txt                        # 관찰값 기록
```

## 6. 한계

- 운영 DB 의 실제 버전, `user` 행 수, 계정 권한, 백업 설정은 이 환경에서 확인하지 못했다(체크리스트 1~3번에서 사람이 확인).
- 중간 실패는 "같은 이름의 객체가 먼저 있는 경우"로 시뮬레이션했다. 권한 부족·디스크·연결 끊김 같은 다른 원인도 결과 상태(일부만 적용 + `success=0`)는 같지만 시험하지는 않았다.
- 리허설 5 의 대기 시간은 테스트 설정(`lock_wait_timeout=3`) 기준이다.

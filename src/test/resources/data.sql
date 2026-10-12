-- 테스트(H2)용 가짜 시드. 실제 재학생 ID/이메일을 쓰지 않는다 (이름은 test- 접두어, 이메일은 example.test).
-- src/main/resources/data.sql 은 실제 데이터가 들어 있어 배포물에서 제외했다. 이 파일이 테스트 클래스패스에서 그 자리를 대신한다.
INSERT IGNORE INTO banned_user (id, intra_id, reason, banned_at) VALUES (1, 'test-banned', '테스트용', NOW());

INSERT IGNORE INTO user (id, blackholed_at, coin, deleted_at, email, email_alarm, name, push_alarm, role, slack_alarm, penalty_days, monthly_logtime, version, is_pisciner)
VALUES
(1, NULL, 0, NULL, 'test-admin@example.test', 0, 'test-admin', 0, 'ADMIN', 0, 0, 0, 0, 0),
(2, NULL, 0, NULL, 'test-user@example.test', 0, 'test-user', 0, 'USER', 0, 0, 0, 0, 0);

INSERT IGNORE INTO cabinet (id, visible_num, status, lent_type, max_user, floor, section, grid_row, grid_col)
VALUES
(1, 1, 'AVAILABLE', 'PRIVATE', 1, 2, 'A', 0, 0),
(2, 2, 'AVAILABLE', 'PRIVATE', 1, 2, 'A', 0, 1),
(3, 3, 'AVAILABLE', 'PRIVATE', 1, 2, 'A', 0, 2),
(4, 4, 'AVAILABLE', 'PRIVATE', 1, 2, 'A', 0, 3),
(5, 5, 'AVAILABLE', 'PRIVATE', 1, 2, 'A', 0, 4),
(6, 6, 'AVAILABLE', 'PRIVATE', 1, 2, 'B', 0, 0),
(7, 7, 'AVAILABLE', 'PRIVATE', 1, 2, 'B', 0, 1),
(8, 8, 'AVAILABLE', 'PRIVATE', 1, 2, 'B', 0, 2),
(9, 9, 'AVAILABLE', 'PRIVATE', 1, 2, 'B', 0, 3),
(10, 10, 'AVAILABLE', 'PRIVATE', 1, 2, 'B', 0, 4),
(11, 11, 'AVAILABLE', 'PRIVATE', 1, 3, 'A', 0, 0),
(12, 12, 'AVAILABLE', 'PRIVATE', 1, 3, 'A', 0, 1),
(13, 13, 'AVAILABLE', 'PRIVATE', 1, 3, 'A', 0, 2),
(14, 14, 'AVAILABLE', 'PRIVATE', 1, 3, 'A', 0, 3),
(15, 15, 'AVAILABLE', 'PRIVATE', 1, 3, 'A', 0, 4),
(16, 16, 'AVAILABLE', 'PRIVATE', 1, 3, 'B', 0, 0),
(17, 17, 'AVAILABLE', 'PRIVATE', 1, 3, 'B', 0, 1),
(18, 18, 'AVAILABLE', 'PRIVATE', 1, 3, 'B', 0, 2),
(19, 19, 'AVAILABLE', 'PRIVATE', 1, 3, 'B', 0, 3),
(20, 20, 'AVAILABLE', 'PRIVATE', 1, 3, 'B', 0, 4),
(21, 21, 'AVAILABLE', 'LAPISCINE', 1, 2, 'A', 1, 0);

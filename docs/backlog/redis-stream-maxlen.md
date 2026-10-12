# 백로그: Redis Streams 알림 스트림에 길이 상한(MAXLEN)이 없음

- 발견: 감사 H-7 작업 중(2026-10-12). 이번 H-7 수정 범위 밖이라 코드는 바꾸지 않고 기록만 한다.
- 상태: 미착수

## 현상
- `AlarmEventHandler` 가 `slack-alarm-stream` 에, `LogtimeScheduler` 가 `logtime-sync-stream` 에 `XADD` 할 때 `MAXLEN` 을 주지 않는다.
- 소비자가 처리하고 나면 리스너가 `XDEL` 하므로 정상 상태에서는 커지지 않는다. 하지만 소비가 멈추거나(H-7 수정 전의 구독 취소, 소비자 전원 중단) 처리되지 못한 메시지가 쌓이면 상한이 없어 Redis 메모리를 계속 쓴다.
- H-7 수정으로 추가된 `<스트림>:dead` 스트림은 `MAXLEN ~1000` 으로 제한돼 있다(`StreamPendingReclaimer`).

## 검토할 것
1. 생성 쪽 `XADD` 에 `XAddOptions.maxlen(N).approximateTrimming(true)` 를 줄지(상한을 넘으면 처리 안 된 오래된 메시지부터 조용히 사라지므로, 알림은 유실 허용 범위인지 정책 결정이 먼저).
2. 대안: 길이를 지표(`XLEN`)로 노출하고 임계값 알림만 둔다(유실 없이 감지).
3. 상한 값은 평소 분당 생성량과 소비 중단을 허용할 시간으로 정한다.

## 영향
- 로그타임 이벤트는 월 1회 전원 분량이 한꺼번에 들어가므로(사용자 수만큼) 상한은 그보다 커야 한다.

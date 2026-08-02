# Samsung background·복구 E2E

이 검증은 사용자 승인 테스트 창에서만 실행한다. `process kill`, `reboot`, Doze와 배터리 제한은
현재 작업을 중단할 수 있으므로 자동 CI나 일반 smoke test에서 실행하지 않는다.

## 실행 원칙

1. 승인 번호와 테스트 기기 모델/API만 기록한다.
2. 정상 foreground/background 전환과 notification/UI Stop을 먼저 검증한다.
3. process kill, reboot, Doze, 배터리 제한, 알림 권한 거부를 각각 독립 실행한다.
4. 매 단계에서 거짓 `RUNNING`, 고아 notification, zombie process가 없는지 확인한다.
5. 앱 재실행 뒤 이전 command·prompt가 자동 재실행되지 않는지 확인한다.
6. token, command, prompt, raw logcat은 보고서에 저장하지 않는다.

`integration-fixtures/samsung-lifecycle/report.template.json`을 외부 작업 디렉터리에 복사해
결과를 작성한 뒤 다음 명령으로 검증한다.

```bash
python3 scripts/verify-samsung-lifecycle-report.py /path/to/redacted-report.json --require-executed
```

모든 필수 check가 `PASS`일 때만 장시간 background 작업 지원 gate를 통과한 것으로 판단한다.

# Play Asset Delivery test track E2E

이 절차는 승인된 application ID, signing owner와 Play Console test track이 준비된 경우에만 실행한다.
저장소나 보고서에는 keystore, 비밀번호 또는 서명 key 정보를 기록하지 않는다.

## 필수 검증

1. signed AAB를 internal/closed/open test track 중 승인된 track에 게시한다.
2. Samsung 기기에서 Play Store 설치 후 asset pack 최초 fetch와 runtime start를 확인한다.
3. terminal, 앱 업데이트, offline 재실행과 rollback을 확인한다.
4. fetch 취소·네트워크 단절·checksum 불일치가 거짓 `READY` 없이 안정 오류로 끝나는지 확인한다.
5. PRoot/loader/PTY가 base APK JNI에만 존재하고 asset pack에는 rootfs/auxiliary payload만 있는지 확인한다.

결과는 `integration-fixtures/play-e2e/report.template.json`을 외부 작업 디렉터리에 복사해 작성한다.

```bash
python3 scripts/verify-play-e2e-report.py /path/to/redacted-report.json --require-executed
```

실행 전 template 검증은 보고서 형식만 확인하며 실제 Play E2E 성공을 의미하지 않는다.

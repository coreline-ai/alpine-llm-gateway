# 삼성폰 10턴 실제 Provider 대화 QA

## 개요

- 실행 시각: 2026-08-01 09:03 KST
- 기기: Samsung SM-S931N (`R3CY40PXCAP`)
- 앱 패키지: `dev.alpine.llm.demo`
- Provider: Codex OAuth
- 모델: `gpt-5.3-codex-spark`
- 방식: 실제 앱 UI에서 스킬과 페르소나를 매 턴 변경하고 실제 Provider 응답을 수집

## 최종 결과

| 항목 | 결과 | 비고 |
|---|---:|---|
| 요청 전송 및 응답 수신 | 10/10 PASS | 앱/인증/Provider 오류 없음 |
| 스킬·페르소나 변경 반영 | 10/10 PASS | 각 응답 metadata에서 선택 모드 확인 |
| 사용자/응답 메시지 정렬 | PASS | 사용자 오른쪽, 응답 왼쪽 |
| 앱 강제 종료 후 대화 복원 | PASS | Provider, 모델, 마지막 모드와 응답 복원 |
| 핵심 사실의 외부 웹 교차 검증 | PASS | NASA, RFC Editor, BusyBox, curl, Python, Alpine 공식 자료 사용 |
| 프롬프트의 형식·길이 준수 | 9/10 | 8번 답변이 `90 words 미만` 조건을 초과함 |

`passed_turns: 10`은 UI 전송과 Provider 응답 수신 기준이다. 내용 품질까지 포함하면 명확한 하드 제약 위반이 1건 있어 9/10이다.

## 10턴 결과

| 턴 | 스킬 · 페르소나 | 검증 주제 | E2E | 내용 평가 |
|---:|---|---|---:|---|
| 1 | General assistant · Balanced | 하늘이 파란 이유를 2문장으로 설명 | PASS | NASA 설명과 일치, 2문장 준수 |
| 2 | Alpine/Linux expert · Step-by-step solver | 삭제 없는 디스크 사용량 점검 3단계 | PASS | `df`, `du` 옵션은 BusyBox 문서와 일치. 본문 3단계는 읽기 전용이나 선택 사항의 `apk add ncdu`는 시스템 변경임 |
| 3 | Coding assistant · Expert engineer | 두 `Int`의 큰 값을 반환하는 Kotlin 함수 | PASS | 함수와 동률 edge case 모두 적절함 |
| 4 | Error analysis · Critical reviewer | `/var/log/app.log` 권한 거부 진단 | PASS | `EACCES`, 소유권/경로/LSM 점검은 타당. `<service>`를 그대로 셸에 붙여 넣지 않도록 실제 서비스명 예시가 더 안전함 |
| 5 | Code review · Concise | 빈 시퀀스의 `xs[0]` 버그 | PASS | `IndexError` 지적은 정확. `None` 반환은 API 계약을 바꾸므로 `Optional` 또는 명시적 예외 정책 선택이 필요함 |
| 6 | Command guide · Beginner friendly | `df -h` 초보자 설명 | PASS | 3개 짧은 bullet과 읽기 전용 설명 준수 |
| 7 | Documentation · Document writer | README Health Check 작성 | PASS | `curl -f`의 HTTP 오류 처리 설명이 curl 공식 문서와 일치 |
| 8 | Learning assistant · Beginner friendly | OAuth PKCE 비유, 90 words 미만 | WARN | PKCE 핵심 흐름은 맞지만 103 words로 길이 조건 위반. “normal login의 fake key” 표현도 부정확할 수 있음 |
| 9 | General assistant · Concise | 앱의 웹 탐색 가능 여부와 서울 날씨 검증 여부 | PASS | 웹을 확인하지 않았다고 명확히 밝혀 허위 최신 정보 생성을 피함 |
| 10 | Alpine/Linux expert · Critical reviewer | glibc 대상 바이너리를 Alpine/musl에서 실행할 위험 | PASS | ABI 위험과 musl 재빌드/호환 계층/격리 방안은 타당. 호환 shim은 본질적 취약점보다 운영·지원 위험으로 표현하는 편이 정확함 |

## UI 및 상태 검증

1. 사용자 메시지는 오른쪽, 응답은 왼쪽에 표시됐다.
2. 마지막 응답 metadata는 `Codex · gpt-5.3-codex-spark · Alpine/Linux expert · Critical reviewer`로 표시됐다.
3. 앱을 강제 종료한 뒤 다시 실행해도 대화 제목, 마지막 응답, Provider, 모델, 마지막 스킬·페르소나가 복원됐다.
4. 응답 본문의 Markdown이 렌더링되지 않아 `**bold**`, 백틱 코드 표기가 그대로 노출된다. 긴 기술 답변의 가독성을 떨어뜨리는 명확한 UI 개선 항목이다.
5. 이번 실행에서는 실제 429, 5xx, 비정상 SSE가 발생하지 않아 실기기 오류 복구 경로는 재검증하지 못했다.
6. 빠른 모델 전환 chip에 보이는 다른 모델은 이번 10턴에서는 실제 호출하지 않았다.

## 권장 개선 순서

1. **P1 — Markdown 렌더링:** 굵게, 인라인 코드, 코드 블록, 목록을 안전한 Compose Markdown으로 표시한다.
2. **P1 — 하드 제약 검증:** 문장 수, bullet 수, 단어 수 같은 측정 가능한 조건을 응답 후 검사하고 필요하면 1회 자동 교정한다.
3. **P2 — 안전 문구 강화:** 명령 가이드에서 변경 명령을 읽기 전용 단계와 명확히 분리하고 `<service>` 같은 placeholder를 복사 실행 가능한 형태로 안내한다.
4. **P2 — 코드 리뷰 계약 명시:** `None` 반환, 예외 유지, `Optional` 중 어느 계약인지 답변에서 구분한다.
5. **P2 — 실제 오류 E2E:** 429, 5xx, 잘린 SSE, 타임아웃을 주입해 재시도·취소·새 대화·모델 변경이 계속 동작하는지 확인한다.

## 증거 파일

- 전체 자동화 결과: `dev-plan/live-chat-qa-20260801.json`
- 최종 화면: `dev-plan/live-chat-qa-20260801.png`
- 메시지 정렬 화면: `dev-plan/live-chat-qa-20260801-alignment.png`
- UI hierarchy: `dev-plan/live-chat-qa-20260801.xml`

## 외부 검증 자료

- [NASA: Why Is the Sky Blue?](https://spaceplace.nasa.gov/blue-sky/en/)
- [RFC 7636: PKCE](https://www.rfc-editor.org/info/rfc7636/)
- [RFC 9700: OAuth 2.0 Security BCP](https://www.rfc-editor.org/info/rfc9700/)
- [BusyBox command reference](https://busybox.net/BusyBox.html)
- [curl FAQ](https://curl.se/docs/faq.html)
- [Python IndexError](https://docs.python.org/3/library/exceptions.html#IndexError)
- [Linux access(2) EACCES](https://www.man7.org/linux/man-pages/man2/access.2.html)
- [Alpine gcompat package](https://pkgs.alpinelinux.org/package/v3.22/main/x86/gcompat)
- [기상청 서울·경기도 중기예보](https://www.weather.go.kr/w/forecast/overall/mid-term.do?stnId1=109)

## 후속 구현 상태

`implement_20260801_093437.md` 작업으로 아래 항목을 반영했다.

- [x] raw Markdown 대신 native Compose 제목·목록·강조·code block 렌더링
- [x] word/sentence/bullet 제한 감지와 완료 후 검증
- [x] 1차 위반 시 같은 Provider·모델·Assistant mode의 최대 1회 자동 교정
- [x] 읽기 전용 명령·shell placeholder·코드 반환/예외 계약 prompt 강화
- [x] API 35 emulator instrumentation 24개와 Samsung 실제 Codex 19 words·2 bullet smoke
- [ ] 실제 429·5xx·잘린 SSE 계정 E2E

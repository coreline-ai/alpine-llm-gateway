# 통합 MVP Phase 1 공통 Chat Feature 검증

작성 일시: `2026-08-02 KST`

## 결과

전체 제품 계획의 **Phase 1 공통 Chat Feature 모듈화가 완료**됐다. `demo-chatbot`의 공통
대화 기능은 `:alpine-chat-feature`로 이동했고, 데모는 Provider/OAuth를 조립하는 thin host로
축소됐다. `integrated-app` 실제 연결은 다음 Phase 2 범위다.

## 모듈 경계

| 영역 | 소유 모듈 | 책임 |
|---|---|---|
| 공통 Feature | `:alpine-chat-feature` | 대화·암호화 저장·ViewModel·Skill·Persona·Compose UI |
| Backend 계약 | `:alpine-chat-feature` | descriptor, connection, normalized delta, closed failure code |
| 직접 Provider Host | `:demo-chatbot` | Provider profile CRUD, OAuth session, raw SSE/error 정규화 |
| 공통 라우팅 | `:alpine-chat-routing` | 실행 모드와 향후 direct/Alpine backend 라우팅 계약 |

공통 Feature는 `:alpine-chat-routing`만 project dependency로 가진다. `:android`, Provider
profile, OAuth, HostBridge, Alpine Runtime, rootfs, PRoot, PTY와 Python Gateway를 의존하지 않는다.

## 구현 내용

- 다중 대화, 대화별 draft·Provider·모델·Skill·Persona·생성 상태를 공통 모듈로 이동했다.
- 대화 codec, AES-GCM 저장, 손상 격리와 process 재시작 복원을 공통 모듈로 이동했다.
- 공통 ViewModel이 demo 전용 `ChatCompletionSession` 대신 `ChatBackendSession`을 사용한다.
- Provider raw SSE는 demo Host adapter 안에서 `ChatBackendDelta`로 정규화된다.
- Provider 예외는 원문 message/body/URL/header를 포함하지 않는 닫힌 failure code로 변환된다.
- `demo-chatbot`은 공통 화면과 상태를 사용하고 Provider 관리와 실제 OAuth만 소유한다.
- `alpine-chat-feature`를 Maven publication과 no-runtime/full consumer matrix에 추가했다.

## 검증 결과

| 검증 | 결과 |
|---|---|
| Feature JVM | 43 tests, 0 failures |
| Demo Host JVM | 45 tests, 0 failures |
| Feature release AAR / lint | PASS |
| Demo debug APK / test APK / lint | PASS |
| Demo Runtime payload 부재 | PASS |
| Samsung `SM-S931N` Android 16 instrumentation | 30 tests, 0 failures |
| Android module boundary Python test | 20 tests, 0 failures |
| SDK publication | 18 artifacts, PASS |
| 외부 published consumer matrix | 8 release/R8 apps, PASS |
| no-runtime consumer | Chat Feature 사용, Runtime/native/payload/FGS 권한 없음 |

Samsung instrumentation은 credential-free fake session으로 실행했으며 실제 Provider 계정이나
OAuth token을 사용하지 않았다. 실계정 E2E는 Phase 9 외부 승인 Gate로 유지한다.

## 완료 판정

- Phase 1: **COMPLETE**
- Phase 2 시작 조건: **SATISFIED**
- 통합 앱 빠른 채팅: **아직 미연결 — Phase 2 대상**
- 외부 공개 배포: **범위 밖이며 기존 `INTERNAL_ONLY` 유지**

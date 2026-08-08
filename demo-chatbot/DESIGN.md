# Alpine Chat Material 3 디자인 가이드

이 문서는 `demo-chatbot`의 Android UI 기준이다. 디자인 시스템은 Material 3 Compose를 사용하고, OAuth·프로필 저장소·LLM 세션 같은 도메인 코드는 UI와 분리한다. 세 화면은 Compose로 전환되었으며 아래 token과 접근성 계약을 공통 기준으로 사용한다.

## 선택 이유

Material 3는 Android의 공식 Compose 디자인 시스템이며 Light/Dark Theme, Android 12+ Dynamic Color, 접근성 컴포넌트, 적응형 레이아웃과 잘 맞는다. 채팅 UI는 Google의 Jetchat 샘플을, 공통 테마·상태·테스트 구조는 Now in Android를 참고한다. Provider 설정은 Material 3의 `Card`, `AlertDialog`, `OutlinedTextField`, `Scaffold` 조합으로 구현한다.

화면은 `AlpineChatTheme` 안에서만 렌더링한다. 네트워크 응답이나 OAuth 토큰을 Composable 상태에 저장하지 않고 ViewModel/session 계층에서 관리한다.

## 색상

브랜드 seed는 Alpine blue `#2855D9`이다. 개별 화면에 색상 값을 직접 사용하지 않고 Material 역할을 사용한다.

| 의미 | Light 기본 역할 | 사용처 |
| --- | --- | --- |
| 주요 동작 | `primary` / `onPrimary` | 보내기, 저장, 연결 |
| 사용자 메시지 | `primaryContainer` / `onPrimaryContainer` | 사용자 말풍선 |
| LLM 메시지 | `surfaceContainerHigh` / `onSurface` | 응답 말풍선 |
| 보조 정보 | `onSurfaceVariant` | 모델명, endpoint 설명 |
| 오류 | `errorContainer` / `onErrorContainer` | 검증 오류, 요청 실패 |
| 연결됨 | semantic `connected` | 상태 텍스트와 아이콘 |
| 재인증 필요 | semantic `warning` | 재로그인 안내 |

Android 12 이상은 시스템 Dynamic Color를 기본 허용하되, 브랜드 일관성이 필요한 제품 설정에서는 `dynamicColor = false`로 Alpine light/dark scheme을 사용한다. Dynamic Color를 사용해도 상태의 의미는 색상만으로 전달하지 않는다. 연결 상태에는 텍스트와 아이콘을 함께 표시한다. Dark scheme에서는 밝기 대비를 유지하고, `primaryContainer`를 본문 배경처럼 넓게 사용하지 않는다.

## 타입, 형태, 간격

- 본문 기본 글꼴은 Material 3 `bodyLarge`/`bodyMedium`이다.
- 화면 제목은 `headlineSmall` 또는 `titleLarge`, Provider·모델 메타 정보는 `labelMedium`을 사용한다.
- Alpine theme의 제목·버튼은 과도한 대문자 대신 문장형 라벨을 사용한다.
- 모서리 토큰은 8dp extra-small, 12dp small, 16dp medium, 24dp large를 기준으로 한다.
- 간격은 4/8/12/16/24/32dp만 사용한다. 화면 외곽 기본 여백은 16dp, 섹션 사이 큰 여백은 24dp다.
- 모든 버튼·아이콘 버튼·클릭 가능한 Provider 행은 최소 48x48dp 터치 영역을 갖는다. 시각적 아이콘이 24dp여도 hit target은 줄이지 않는다.
- 메시지 말풍선은 compact 화면에서 가로 공간의 최대 84% 이내로 제한하고, 본문은 읽기 좋은 줄 길이를 유지한다.

## 화면과 컴포넌트 매핑

### 채팅

- 상단: `CenterAlignedTopAppBar`, 현재 대화 제목, `conversation_history`, `new_chat`, `manage_providers` 아이콘 버튼.
- Provider 선택: `ExposedDropdownMenuBox` 또는 `SingleChoice` bottom sheet. 각 항목은 프로필명, Provider, 모델, 연결 상태를 함께 노출한다.
- Assistant mode: Provider·빠른 모델 선택 아래 `assistant_mode_selector` 칩에 현재 스킬과
  페르소나를 표시한다. `ModalBottomSheet` 안에서 각각 하나를 고르고, 새 대화 기본값 저장과
  `General assistant · Balanced` reset을 제공한다. 생성 중에는 다음 메시지부터 적용된다고
  명시한다.
- 메시지: `LazyColumn` + 안정적인 message id key. 사용자 메시지는 오른쪽 `primaryContainer`, LLM 메시지는 왼쪽 `surfaceContainerHigh`에 배치한다.
- assistant Markdown: 제목·문단·목록·인용·표·강조·inline code·fenced code block을 native
  Compose로 렌더링한다. 표와 code block은 bubble 폭 안에서 각자 가로 스크롤하며, 알려진 언어는
  keyword·문자열·숫자·주석만 경량 강조한다. HTML·image를 WebView나 원격 리소스로 실행하지
  않는다. 절대 http/https 링크도 확인 dialog 전에는 열지 않고 위험 scheme은 inert text로
  남긴다. streaming 중 닫히지 않은 marker와 표는 일반 text 또는 열린 code block으로 안전하게
  표시한다.
- 응답 형식 제한: 측정 가능한 단어 상한·정확한 문장/목록 수가 있으면 완료 후 검사하고
  같은 요청을 최대 1회 교정한다. 교정 중에는 `Correcting response format…`, 최종 미준수나
  교정 실패에는 원문 오류가 없는 상태 문구를 표시한다.
- 외부 검증 경계: 최신·실시간·웹 확인 요청에는 현재 runtime에 웹 도구가 없음을 지시하고,
  명시적 허위 웹 확인 주장은 형식 제한과 공유하는 최대 1회 교정 예산으로 처리한다. 일반 사실
  정확성을 자동 판정하거나 웹 검색을 수행하는 기능으로 표시하지 않는다.
- 메타 정보: 응답 말풍선 위에 실제 사용한 `Provider · model · skill · persona`를
  `labelMedium`으로 표시한다.
- 입력: 하단 고정 `Surface` 안의 `OutlinedTextField`와 `FilledIconButton`. 스트리밍 중 `send_button`은 `stop_button`으로 바꾸고 입력 중복 전송을 막는다.
- 오류: 긴 원문 stack trace를 표시하지 않고, 짧은 설명과 `retry_button`을 제공한다. 401은 재인증,
  429는 busy, 5xx는 unavailable, malformed stream은 unreadable, timeout과 network interruption은
  각각 구분된 재시도 안내를 사용한다.

### 대화 기록

- Compact 화면에서는 왼쪽 `ModalNavigationDrawer`를 사용한다. 닫힌 drawer 내용은 semantics
  tree에서도 제거해 현재 메시지와 중복으로 읽히지 않게 한다.
- `New chat`은 확인 dialog 없이 즉시 active 대화를 바꾸며, 현재 대화가 비어 있으면 재사용한다.
- 각 행은 제목, 최근 메시지 최대 2줄, Provider·모델, 수정 시각을 보여준다.
- `Generating`, `New`, `Failed`, `Stopped` 상태를 색상만이 아닌 텍스트 badge로 표시한다.
- 행 전체를 선택할 수 있고 48dp overflow action에서 이름 변경과 삭제를 제공한다. 이름 변경은
  inline dialog에서 저장하며 삭제만 비가역 동작 확인 dialog를 사용한다.
- 연결이 사라진 Provider는 과거 대화를 그대로 읽을 수 있게 두고 `Disconnected LLM`으로 표시한다.
- 동시 생성 수가 2개이면 상단 제목 아래에 전체 생성 수를 표시한다. `Stop`은 active 대화에만 작동한다.

### Provider 목록과 추가

- `provider_list_screen`은 `TopAppBar`와 `LazyColumn`을 사용한다.
- `add_provider`는 Extended FAB 또는 top bar action으로 제공한다.
- `profile_card_{id}`는 Provider 아이콘, 프로필 라벨, 모델, 연결 상태를 한 행에 보여준다. 수정·로그아웃·삭제는 overflow menu에 둔다.
- Provider 선택은 휴대폰에서 `ModalBottomSheet`, expanded 화면에서는 supporting pane 또는 왼쪽 목록으로 표시한다.

### Provider 설정 폼

- `provider_edit_screen`은 Provider 유형, 인증 endpoint, token endpoint, inference endpoint, client id, scopes, model을 섹션으로 나눈다.
- 입력은 `OutlinedTextField`와 inline supporting/error text를 사용한다. 오류는 필드 아래에 즉시 표시하고 색상만으로 표시하지 않는다.
- Gemini 모델 endpoint의 `{model}` 같은 placeholder는 helper text로 설명한다.
- OAuth credential 값·access token·refresh token은 UI나 프로필 JSON에 표시하지 않는다. 연결 상태만 보여준다.

## 반응형 레이아웃

가용 화면 폭과 콘텐츠 최대 폭을 기준으로 한다.

- Compact: 채팅과 Provider 목록을 별도 destination으로 표시한다. 메시지 본문은 최대 84% 폭이다.
- Medium: Provider 목록과 설정을 modal 또는 navigation pane으로 표시한다.
- Expanded: 왼쪽 Provider pane은 최대 320dp, 채팅 콘텐츠는 최대 840dp로 제한한다. 설정 폼은 최대 720dp로 제한한다.
- 화면 회전, 폴더블 자세, 멀티윈도우를 가정하며 방향 잠금과 고정 화면 폭을 사용하지 않는다.

## 접근성 계약

- TalkBack label에는 `프로필 이름, Provider, 모델, 연결 상태`를 한 번에 읽을 수 있도록 semantics를 병합한다.
- 연결 상태는 `연결됨`, `로그인 필요`, `재인증 필요`처럼 텍스트와 아이콘으로 전달한다.
- 스트리밍 토큰마다 live region 알림을 보내지 않는다. 응답 완료·중단·오류 같은 상태 변화만 `Polite` 수준으로 알린다.
- 입력창은 목적을 설명하는 label을 가지며 placeholder만 label로 사용하지 않는다.
- 200% 글꼴 크기에서 버튼과 폼이 겹치지 않도록 열 방향 배치와 줄바꿈을 허용한다.
- 색상 대비, 키보드/스위치 접근, 48dp 터치 영역을 Compose UI test와 실기기 TalkBack으로 확인한다.

## 안정적인 테스트 태그

태그는 화면 표시 문자열과 분리하고 아래 이름을 유지한다.

| 화면 | 태그 |
| --- | --- |
| 채팅 루트 | `chat_screen` |
| Provider 선택 | `provider_selector` |
| Assistant mode 진입/시트 | `assistant_mode_selector`, `assistant_mode_sheet` |
| 기본 스킬/페르소나 | `skill_option_{id}`, `persona_option_{id}` |
| Assistant 기본값/reset | `assistant_mode_default_toggle`, `assistant_mode_reset` |
| 메시지 목록 | `messages_list` |
| assistant code block | `message_code_block` |
| assistant Markdown 표 | `message_markdown_table` |
| 외부 링크 확인 | `markdown_link_dialog`, `markdown_link_open`, `markdown_link_cancel` |
| 입력 | `message_input` |
| 전송/중단 | `send_button`, `stop_button` |
| Provider 관리/새 대화 | `manage_providers`, `new_chat` |
| 대화 기록 진입/목록 | `conversation_history`, `conversation_history_list` |
| 기록 안의 새 대화 | `history_new_chat` |
| 개별 대화 | `conversation_item_{id}` |
| 대화 이름 변경 | `rename_conversation_input`, `confirm_rename_conversation` |
| 대화 삭제 확인 | `confirm_delete_conversation` |
| 실패 재시도 | `retry_button` |
| Provider 목록 루트 | `provider_list_screen` |
| Provider 추가 | `add_provider` |
| 프로필 목록 | `profile_list` |
| 개별 프로필 | `profile_card_{id}` |
| Provider 편집 루트 | `provider_edit_screen` |

폼 태그는 `provider_type`, `profile_label`, `authorization_endpoint`, `token_endpoint`, `inference_endpoint`, `client_id`, `scopes`, `model`, `callback_port`, `anthropic_beta`, `google_project`, `save_and_login`, `save_for_later`를 사용한다. 동적 Provider 필드는 화면에 없을 때 태그도 렌더링하지 않는다.

## 검증 기준

- Light/Dark/Dynamic Color Preview에서 텍스트 대비와 상태 의미가 유지된다.
- Compact와 Expanded Preview에서 메시지·Provider·설정 폼의 최대 폭이 지켜진다.
- 모든 태그가 Compose UI 테스트에서 검색 가능하고, 태그에 사용자 이름이나 token을 포함하지 않는다.
- 회전 후에는 ViewModel의 active 대화, drawer 전환과 Provider 선택을 유지한다. 프로세스 종료 후
  encrypted conversation store에서 대화·초안·선택 Provider·모델·스킬·페르소나를 복원하고 중단된
  `STREAMING`은 `CANCELLED`로 정상화한다.
- TalkBack이 토큰 단위로 문장을 끊어 읽지 않고, 응답 완료와 오류만 안내한다.

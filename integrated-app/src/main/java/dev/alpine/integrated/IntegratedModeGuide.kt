package dev.alpine.integrated

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.alpine.chat.feature.ui.designsystem.AlpinePrimaryAction
import dev.alpine.chat.feature.ui.designsystem.AlpineSectionCard
import dev.alpine.chat.feature.ui.designsystem.AlpineStatusRail
import dev.alpine.chat.feature.ui.designsystem.AlpineStatusTone
import dev.alpine.chat.routing.ChatExecutionMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IntegratedModeGuideSheet(
    onDismiss: () -> Unit,
    onStartMode: (ChatExecutionMode) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("mode_guide_sheet"),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "어떤 모드로 시작할까요?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "같은 대화를 사용하지만 실행 경로와 지원 기능이 다릅니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("dismiss_mode_guide"),
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("나중에")
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("mode_guide_content"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ModeGuideCard(
                        title = "빠른 채팅",
                        route = "Android → 외부 LLM Provider",
                        preparation = "Provider OAuth 로그인과 모델 선택",
                        features = "일반 대화, 빠른 응답, 대화 기록, persona·skill",
                        limitation = "Linux 터미널·Python·Git·package 도구는 사용할 수 없음",
                        recovery = "LLM 연결 화면에서 상태를 확인하고 다시 로그인",
                        modifier = Modifier.testTag("guide_fast_chat_card"),
                    )
                }
                item {
                    ModeGuideCard(
                        title = "Alpine 작업",
                        route = "Android Host → Alpine Runtime → Python Gateway → 외부 LLM",
                        preparation = "Runtime 설치·시작과 Provider 연결",
                        features = "Gateway 채팅, Linux 터미널, Python, Git, package·작업공간 도구",
                        limitation = "최초 설치와 시작 시간이 필요하며 Android 백그라운드 정책의 영향을 받음",
                        recovery = "상태 확인 후 시작·재시작·복구하고 터미널·도구에서 Runtime 점검",
                        modifier = Modifier.testTag("guide_alpine_workspace_card"),
                    )
                }
                item {
                    AlpineStatusRail(
                        label = "자동 fallback 없음",
                        message = "Alpine Gateway가 준비되지 않아도 사용자 승인 없이 빠른 채팅으로 자동 전송하지 않습니다. 요청별 승인 후에만 Android 직접 Provider 경로를 사용합니다.",
                        tone = AlpineStatusTone.WARNING,
                        modifier = Modifier.testTag("guide_fallback_policy"),
                    )
                }
                item {
                    Text(
                        text = "‘나중에’를 누르면 이번 화면만 닫히며 다음 새 실행에서 다시 안내합니다. 상단 모드 선택의 ‘안내’ 버튼으로 언제든 다시 볼 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AlpinePrimaryAction(
                        text = "빠른 채팅으로 시작",
                        onClick = { onStartMode(ChatExecutionMode.FAST_CHAT) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("guide_start_fast_chat")
                            .semantics {
                                contentDescription = "빠른 채팅 모드로 시작"
                            },
                    )
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("guide_start_alpine_workspace")
                            .semantics {
                                contentDescription = "Alpine 작업 모드로 시작"
                            },
                        onClick = { onStartMode(ChatExecutionMode.ALPINE_WORKSPACE) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Text("Alpine 작업으로 시작")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeGuideCard(
    title: String,
    route: String,
    preparation: String,
    features: String,
    limitation: String,
    recovery: String,
    modifier: Modifier = Modifier,
) {
    AlpineSectionCard(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        ModeGuideFact("실행 경로", route)
        ModeGuideFact("먼저 필요한 것", preparation)
        ModeGuideFact("할 수 있는 일", features)
        ModeGuideFact("제한", limitation)
        ModeGuideFact("문제가 생기면", recovery)
    }
}

@Composable
private fun ModeGuideFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.Bold,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

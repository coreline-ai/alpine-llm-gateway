package dev.alpine.chat.provider.android.session

import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind

/**
 * Redacted, allowlist-based authorization failure shown by product UI.
 *
 * Throwable messages and Provider response bodies must never be copied into this model.
 */
class ProviderConnectionIssue private constructor(
    val code: String,
    val message: String,
    val nextAction: String,
) {
    companion object {
        fun authorizationInterrupted(): ProviderConnectionIssue = ProviderConnectionIssue(
            code = "AUTH_FLOW_INTERRUPTED",
            message = "로그인 도중 앱 실행이 중단되었습니다.",
            nextAction = "기존 로그인 창을 닫고 처음부터 다시 로그인하세요.",
        )

        fun authorizationExpired(): ProviderConnectionIssue = ProviderConnectionIssue(
            code = "AUTH_SESSION_EXPIRED",
            message = "중단된 OAuth 로그인 세션이 만료되었습니다.",
            nextAction = "기존 로그인 창을 닫고 처음부터 다시 로그인하세요.",
        )

        fun from(error: Throwable): ProviderConnectionIssue {
            val kind = (error as? OAuthException)?.kind
            return when (kind) {
                OAuthFailureKind.USER_DENIED -> ProviderConnectionIssue(
                    code = "AUTH_USER_DENIED",
                    message = "로그인 요청이 취소되었습니다.",
                    nextAction = "로그인이 필요하면 다시 시도하세요.",
                )
                OAuthFailureKind.CALLBACK_TIMEOUT -> ProviderConnectionIssue(
                    code = "AUTH_CALLBACK_TIMEOUT",
                    message = "제한 시간 안에 로그인 결과를 받지 못했습니다.",
                    nextAction = "브라우저 로그인 창을 닫고 처음부터 다시 시도하세요.",
                )
                OAuthFailureKind.STATE_MISMATCH -> ProviderConnectionIssue(
                    code = "AUTH_STATE_REJECTED",
                    message = "안전하지 않은 OAuth callback을 거부했습니다.",
                    nextAction = "기존 로그인 창을 닫고 새 로그인을 시작하세요.",
                )
                OAuthFailureKind.TRANSACTION_EXPIRED -> ProviderConnectionIssue(
                    code = "AUTH_SESSION_EXPIRED",
                    message = "OAuth 로그인 세션이 만료되었습니다.",
                    nextAction = "처음부터 다시 로그인하세요.",
                )
                OAuthFailureKind.INVALID_GRANT -> ProviderConnectionIssue(
                    code = "AUTH_CREDENTIAL_REJECTED",
                    message = "저장된 인증을 더 이상 사용할 수 없습니다.",
                    nextAction = "다시 로그인해 새 인증을 저장하세요.",
                )
                OAuthFailureKind.STORAGE_INVALIDATED -> ProviderConnectionIssue(
                    code = "AUTH_STORAGE_INVALIDATED",
                    message = "Android 보안 저장소의 인증이 무효화되었습니다.",
                    nextAction = "화면 잠금을 확인한 뒤 다시 로그인하세요.",
                )
                OAuthFailureKind.STORAGE_FAILURE -> ProviderConnectionIssue(
                    code = "AUTH_STORAGE_FAILURE",
                    message = "인증 정보를 안전하게 저장하지 못했습니다.",
                    nextAction = "기기 저장 공간과 보안 설정을 확인하세요.",
                )
                OAuthFailureKind.PROVIDER_ERROR -> ProviderConnectionIssue(
                    code = "AUTH_PROVIDER_REJECTED",
                    message = "Provider가 로그인 요청을 완료하지 못했습니다.",
                    nextAction = "계정 권한과 앱 OAuth registration을 확인하세요.",
                )
                OAuthFailureKind.NETWORK -> ProviderConnectionIssue(
                    code = "AUTH_NETWORK",
                    message = "로그인 서버에 연결하지 못했습니다.",
                    nextAction = "네트워크를 확인한 뒤 다시 시도하세요.",
                )
                OAuthFailureKind.PROTOCOL -> ProviderConnectionIssue(
                    code = "AUTH_PROTOCOL",
                    message = "OAuth 응답 형식이 제품 계약과 일치하지 않습니다.",
                    nextAction = "Provider 설정과 앱 버전을 확인하세요.",
                )
                OAuthFailureKind.CONFIGURATION -> ProviderConnectionIssue(
                    code = "AUTH_CONFIGURATION",
                    message = "OAuth 앱 설정이 올바르지 않습니다.",
                    nextAction = "앱 소유 Client ID와 callback 설정을 확인하세요.",
                )
                null -> ProviderConnectionIssue(
                    code = "AUTH_UNKNOWN",
                    message = "로그인을 완료하지 못했습니다.",
                    nextAction = "잠시 후 다시 시도하고 계속 실패하면 진단 기록을 확인하세요.",
                )
            }
        }
    }
}

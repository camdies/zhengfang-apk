package com.tyust.course.login

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 由真实协议验证(2026-08-12)确认的 SCNU SSO 证据档案。
 * 链路：auth.html -> user/login.html -> auth.html?sysname -> fastlogin.html -> jwxt/sso/oauthLogin
 */
object ScnuEvidenceProfile {
    val profile = ScnuSsoProtocol.EvidenceProfile(
        allowedOrigins = setOf(
            "https://sso.scnu.edu.cn",
            "https://jwxt.scnu.edu.cn"
        ),
        entryUrl = "https://sso.scnu.edu.cn/AccountService/openapi/auth.html?client_id=9347e8e342e93da94c8ecf27a9de2599&response_type=code&redirect_url=https://jwxt.scnu.edu.cn/sso/oauthLogin".toHttpUrl(),
        teachingCallbackUrl = "https://jwxt.scnu.edu.cn/sso/oauthLogin".toHttpUrl()
    )

    /** 教务服务 audience：教务根，用于导出能匹配教务的 Cookie。 */
    const val teachingAudienceUrl = "https://jwxt.scnu.edu.cn"

    const val SSO_CLIENT_ID = "9347e8e342e93da94c8ecf27a9de2599"
    const val SSO_APP_ID = "96"
    const val SSO_CONFIRM_SYSNAME = "教务信息服务平台"
}
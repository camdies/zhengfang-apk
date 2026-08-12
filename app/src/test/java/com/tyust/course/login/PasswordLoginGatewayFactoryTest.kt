package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import com.tyust.course.session.SchoolSessionScope
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordLoginGatewayFactoryTest {
    @Test
    fun createsTyustSsoGatewayOnlyForTyust() {
        val tyust = SchoolConfig("tyust", "TYUST", "newjwc.tyust.edu.cn", "https")
        val other = SchoolConfig("other", "Other", "jw.example.edu.cn", "https")

        assertTrue(PasswordLoginGatewayFactory.create(tyust) is TyustSsoLoginManager)
        assertTrue(PasswordLoginGatewayFactory.create(other) is PasswordLoginManager)
    }

    @Test
    fun canonicalScnuIsNeverRoutedThroughLegacyStringGateway() {
        val canonicalScnu = SchoolConfig(
            "scnu",
            "SCNU",
            SchoolSessionScope.CANONICAL_SCNU_HOST,
            "https"
        ).apply {
            basePath = ""
        }

        // SCNU must never go through the legacy String gateway factory:
        // it would flatten the RFC CookieBundle into a header.
        assertTrue(PasswordLoginGatewayFactory.create(canonicalScnu) is PasswordLoginManager)
    }

    @Test
    fun canonicalScnuUsesSessionGatewayNotLegacyAdapter() {
        val canonicalScnu = SchoolConfig(
            "scnu",
            "SCNU",
            SchoolSessionScope.CANONICAL_SCNU_HOST,
            "https"
        ).apply {
            basePath = ""
        }

        assertTrue(SessionLoginGatewayFactory.create(canonicalScnu) is ScnuSsoLoginManager)
    }
}

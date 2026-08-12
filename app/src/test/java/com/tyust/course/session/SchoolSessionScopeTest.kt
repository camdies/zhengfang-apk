package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolSessionScopeTest {
    @Test
    fun canonicalScnuRequiresExactHttpsHostAndEmptyBasePath() {
        assertTrue(
            SchoolSessionScope.isCanonicalScnu(
                school(protocol = "HTTPS", domain = "JWXT.SCNU.EDU.CN.", basePath = "")
            )
        )

        assertFalse(
            SchoolSessionScope.isCanonicalScnu(
                school(protocol = "http", domain = "jwxt.scnu.edu.cn", basePath = "")
            )
        )
        assertFalse(
            SchoolSessionScope.isCanonicalScnu(
                school(protocol = "https", domain = "portal.scnu.edu.cn", basePath = "")
            )
        )
        assertFalse(
            SchoolSessionScope.isCanonicalScnu(
                school(protocol = "https", domain = "jwxt.scnu.edu.cn", basePath = "/")
            )
        )
        assertFalse(
            SchoolSessionScope.isCanonicalScnu(
                school(protocol = "https", domain = "jwxt.scnu.edu.cn", basePath = "/jwglxt")
            )
        )
    }

    @Test
    fun normalizesScopeFieldsWithoutCollapsingRootPathIntoEmptyPath() {
        val scope = SchoolSessionScope.fromSchool(
            school(protocol = " HTTPS ", domain = " JwXt.ScNu.EdU.Cn. ", basePath = "jwglxt/")
        )

        assertEquals("https", scope.scheme)
        assertEquals("jwxt.scnu.edu.cn", scope.host)
        assertEquals("/jwglxt", scope.basePath)
        assertEquals("", SchoolSessionScope.normalizeBasePath(""))
        assertEquals("/", SchoolSessionScope.normalizeBasePath("/"))
        assertEquals("/", SchoolSessionScope.normalizeBasePath("///"))
    }

    @Test
    fun canonicalDecisionDoesNotDependOnSchoolIdOrName() {
        val config = SchoolConfig(
            "custom-id",
            "Custom label",
            "jwxt.scnu.edu.cn",
            "https"
        ).apply {
            basePath = ""
        }

        assertTrue(SchoolSessionScope.fromSchool(config).isCanonicalScnu)
    }

    private fun school(protocol: String, domain: String, basePath: String): SchoolConfig =
        SchoolConfig("scnu-test", "SCNU test", domain, protocol).apply {
            this.basePath = basePath
        }
}

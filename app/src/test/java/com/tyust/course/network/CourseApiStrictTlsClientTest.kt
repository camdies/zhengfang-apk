package com.tyust.course.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class CourseApiStrictTlsClientTest {
    @Test
    fun strictClientRejectsUntrustedSelfSignedCertificate() {
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("synthetic"))
        server.start()

        try {
            val request = Request.Builder().url(localUrl(server, "/strict-reject")).build()
            try {
                strictClient().newCall(request).execute().use {
                    fail("the production strict client must reject an untrusted certificate")
                }
            } catch (error: IOException) {
                assertTrue(
                    error is SSLHandshakeException ||
                        error is SSLPeerUnverifiedException ||
                        error.cause is SSLHandshakeException ||
                        error.cause is SSLPeerUnverifiedException
                )
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun strictClientCanUseExplicitTestCaAndSharesCookieJar() {
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "strict_cookie=synthetic-cookie; Path=/")
                .setBody("first")
        )
        server.enqueue(MockResponse().setBody("second"))
        server.start()

        try {
            // The custom trust manager exists only in this test to model a CA
            // trusted by the platform. Production strictTlsClient uses defaults.
            val testClient = strictClient().newBuilder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
            val url = localUrl(server, "/cookie")
            testClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertTrue(response.isSuccessful)
            }
            testClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertTrue(response.isSuccessful)
            }

            server.takeRequest()
            val secondRequest = server.takeRequest()
            assertNotNull(secondRequest.getHeader("Cookie"))
            assertTrue(secondRequest.getHeader("Cookie")!!.contains("strict_cookie=synthetic-cookie"))
        } finally {
            server.shutdown()
        }
    }

    private fun strictClient(): OkHttpClient {
        val field = CourseApiClient::class.java.getDeclaredField("strictTlsClient")
        field.isAccessible = true
        return field.get(CourseApiClient.getInstance()) as OkHttpClient
    }

    private fun localUrl(server: MockWebServer, path: String) =
        server.url(path).newBuilder().host("127.0.0.1").build()
}

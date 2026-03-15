package com.sonarpromptstudio.backend

import com.sonarpromptstudio.model.AuthMode
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.DiscoveredSonarProject
import com.sonarpromptstudio.model.SonarProfileType
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonarBackendTest {
    private val backend = SonarHttpBackend()
    private val cloudProject = DiscoveredSonarProject("/tmp/repo", "demo-key", "demo-org")
    private val serverProject = DiscoveredSonarProject("/tmp/repo", "demo-key", null)
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `reports invalid url diagnostics for malformed base url`() {
        val diagnostics = backend.testConnection(serverProfile("not-a-url"), "token", serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Invalid URL", diagnostics.summary)
    }

    @Test
    fun `reports invalid url diagnostics for blank base url on server profiles`() {
        val diagnostics = backend.testConnection(serverProfile("  "), "token", serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Invalid URL", diagnostics.summary)
        assertContains(diagnostics.details.single(), "required")
    }

    @Test
    fun `reports invalid url diagnostics when host is missing`() {
        val diagnostics = backend.testConnection(serverProfile("https:///missing-host"), "token", serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Invalid URL", diagnostics.summary)
        assertContains(diagnostics.details.single(), "full http(s) URL")
    }

    @Test
    fun `reports invalid url diagnostics when scheme is unsupported`() {
        val diagnostics = backend.testConnection(serverProfile("ftp://localhost"), "token", serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Invalid URL", diagnostics.summary)
        assertContains(diagnostics.details.single(), "full http(s) URL")
    }

    @Test
    fun `reports authentication failure when token is missing`() {
        val diagnostics = backend.testConnection(serverProfile("http://localhost"), null, serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Authentication failure", diagnostics.summary)
    }

    @Test
    fun `reports connection success when project metadata is unavailable`() {
        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/authentication/validate" -> exchange.respond("""{"valid":true}""")
                else -> exchange.respond("""{}""")
            }
        }

        val diagnostics = backend.testConnection(serverProfile(serverBaseUrl()), "token", null)

        assertTrue(diagnostics.success)
        assertEquals("Connection successful", diagnostics.summary)
        assertContains(diagnostics.details.single(), "Project validation was skipped")
    }

    @Test
    fun `reports invalid sonarcloud organization when missing`() {
        val diagnostics = backend.testConnection(cloudProfile("http://localhost"), "token", serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Invalid SonarCloud organization", diagnostics.summary)
    }

    @Test
    fun `uses default sonarcloud url when cloud base url is blank`() {
        val diagnostics = backend.testConnection(cloudProfile("   "), null, null)

        assertFalse(diagnostics.success)
        assertEquals("Authentication failure", diagnostics.summary)
    }

    @Test
    fun `reports authentication failure when validate endpoint rejects credentials`() {
        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/authentication/validate" -> exchange.respond("""{"valid":false}""")
                else -> exchange.respond("""{"components":[]}""")
            }
        }

        val diagnostics = backend.testConnection(cloudProfile(serverBaseUrl()), "token", cloudProject)

        assertFalse(diagnostics.success)
        assertEquals("Authentication failure", diagnostics.summary)
    }

    @Test
    fun `reports project validation failure when project is not accessible`() {
        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/authentication/validate" -> exchange.respond("""{"valid":true}""")
                "/api/components/search_projects" -> exchange.respond("""{"components":[{"key":"other"}]}""")
                else -> exchange.respond("""{}""")
            }
        }

        val diagnostics = backend.testConnection(cloudProfile(serverBaseUrl()), "token", cloudProject)

        assertFalse(diagnostics.success)
        assertEquals("Project validation failure", diagnostics.summary)
    }

    @Test
    fun `reports connection success and sends cloud organization query`() {
        val requests = mutableListOf<RecordedRequest>()
        startServer { exchange ->
            requests += exchange.record()
            when (exchange.requestURI.path) {
                "/api/authentication/validate" -> exchange.respond("""{"valid":true}""")
                "/api/components/search_projects" -> exchange.respond("""{"components":[{"key":"demo-key"}]}""")
                else -> exchange.respond("""{}""")
            }
        }

        val diagnostics = backend.testConnection(cloudProfile(serverBaseUrl()), "token", cloudProject)

        assertTrue(diagnostics.success)
        assertEquals("Connection successful", diagnostics.summary)
        val projectRequest = requests.single { it.path == "/api/components/search_projects" }
        assertEquals("demo-key", projectRequest.query["projects"])
        assertEquals("demo-org", projectRequest.query["organization"])
        assertEquals("Bearer token", projectRequest.authorization)
    }

    @Test
    fun `reports network failure when server is unreachable`() {
        val port = ServerSocket(0).use { it.localPort }
        val diagnostics = backend.testConnection(serverProfile("http://127.0.0.1:$port"), "token", serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Network failure", diagnostics.summary)
    }

    @Test
    fun `reports network failure when api returns unauthorized`() {
        startServer { exchange ->
            exchange.respond("""{"errors":[{"msg":"unauthorized"}]}""", 401)
        }

        val diagnostics = backend.testConnection(serverProfile(serverBaseUrl()), "token", serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Network failure", diagnostics.summary)
        assertContains(diagnostics.details.single(), "Authentication failure (401)")
    }

    @Test
    fun `reports network failure when api returns forbidden`() {
        startServer { exchange ->
            exchange.respond("""{"errors":[{"msg":"forbidden"}]}""", 403)
        }

        val diagnostics = backend.testConnection(serverProfile(serverBaseUrl()), "token", serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Network failure", diagnostics.summary)
        assertContains(diagnostics.details.single(), "Authentication failure (403)")
    }

    @Test
    fun `reports project validation failure when response payload is invalid`() {
        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/authentication/validate" -> exchange.respond("""{"valid":true}""")
                "/api/components/search_projects" -> exchange.respond("""not-json""")
                else -> exchange.respond("""{}""")
            }
        }

        val diagnostics = backend.testConnection(serverProfile(serverBaseUrl()), "token", serverProject)

        assertFalse(diagnostics.success)
        assertEquals("Project validation failure", diagnostics.summary)
    }

    @Test
    fun `loads findings and maps server responses`() {
        val requests = mutableListOf<RecordedRequest>()
        startServer { exchange ->
            requests += exchange.record()
            when (exchange.requestURI.path) {
                "/api/issues/search" -> exchange.respond(
                    """
                    {"issues":[
                      {"key":"I-1","severity":"MAJOR","type":"BUG","rule":"rule-1","component":"src/App.kt","line":14,"status":"OPEN","effort":"5min","tags":["bug"],"message":"Fix bug"},
                      {"key":"I-2","message":"Defaults apply"}
                    ]}
                    """.trimIndent(),
                )
                "/api/measures/component_tree" -> when (exchange.requestURI.query.orEmpty().contains("coverage")) {
                    true -> exchange.respond(
                        """
                        {"components":[
                          {"key":"C-1","path":"src/App.kt","measures":[
                            {"metric":"coverage","value":"75.5"},
                            {"metric":"line_coverage","value":"80.0"},
                            {"metric":"branch_coverage","value":"66.7"},
                            {"metric":"uncovered_lines","value":"4"},
                            {"metric":"uncovered_conditions","value":"2"}
                          ]},
                          {"key":"C-2","path":"src/Ignore.kt","measures":[
                            {"metric":"coverage","value":"100"},
                            {"metric":"uncovered_lines","value":"0"},
                            {"metric":"uncovered_conditions","value":"0"}
                          ]}
                        ]}
                        """.trimIndent(),
                    )
                    false -> exchange.respond(
                        """
                        {"components":[
                          {"key":"D-1","name":"App.kt","measures":[
                            {"metric":"duplicated_lines_density","value":"12.3"},
                            {"metric":"duplicated_lines","value":"8"},
                            {"metric":"duplicated_blocks","value":"2"}
                          ]},
                          {"key":"D-2","name":"Ignore.kt","measures":[
                            {"metric":"duplicated_lines","value":"0"},
                            {"metric":"duplicated_blocks","value":"0"}
                          ]}
                        ]}
                        """.trimIndent(),
                    )
                }
                "/api/hotspots/search" -> exchange.respond(
                    """
                    {"hotspots":[
                      {"key":"H-1","component":"src/Security.kt","line":9,"status":"TO_REVIEW","vulnerabilityProbability":"HIGH","message":"Review sanitizer"}
                    ]}
                    """.trimIndent(),
                )
                else -> exchange.respond("""{}""")
            }
        }

        val snapshot = backend.loadFindings(
            profile = ConnectionProfile(
                id = "server",
                name = "Server",
                type = SonarProfileType.SERVER,
                baseUrl = serverBaseUrl(),
                branchOverride = "main",
                pullRequestOverride = "42",
                authMode = AuthMode.BASIC_TOKEN,
            ),
            token = "secret",
            project = serverProject,
        )

        assertEquals(2, snapshot.issues.size)
        assertEquals("UNKNOWN", snapshot.issues[1].severity)
        assertEquals(1, snapshot.coverage.size)
        assertEquals("src/App.kt", snapshot.coverage.single().path)
        assertEquals(1, snapshot.duplication.size)
        assertEquals("App.kt", snapshot.duplication.single().path)
        assertEquals(1, snapshot.hotspots.size)
        assertEquals("TO_REVIEW", snapshot.hotspots.single().status)

        val issueRequest = requests.single { it.path == "/api/issues/search" }
        assertEquals("demo-key", issueRequest.query["componentKeys"])
        assertEquals("main", issueRequest.query["branch"])
        assertEquals("42", issueRequest.query["pullRequest"])
        assertEquals(
            "Basic ${Base64.getEncoder().encodeToString("secret:".toByteArray(StandardCharsets.UTF_8))}",
            issueRequest.authorization,
        )
        val hotspotRequest = requests.single { it.path == "/api/hotspots/search" }
        assertEquals("demo-key", hotspotRequest.query["projectKey"])
    }

    @Test
    fun `loads findings with optional field fallbacks and filters only actionable files`() {
        val requests = mutableListOf<RecordedRequest>()
        startServer { exchange ->
            requests += exchange.record()
            when (exchange.requestURI.path) {
                "/api/issues/search" -> exchange.respond(
                    """
                    {"issues":[
                      {"key":"I-1"}
                    ]}
                    """.trimIndent(),
                )
                "/api/measures/component_tree" -> when (exchange.requestURI.query.orEmpty().contains("coverage")) {
                    true -> exchange.respond(
                        """
                        {"components":[
                          {"key":"C-1","name":"Named.kt","measures":[
                            {"metric":"uncovered_conditions","value":"3"}
                          ]},
                          {"key":"C-2","measures":[
                            {"metric":"coverage","value":"oops"},
                            {"metric":"uncovered_lines","value":"0"},
                            {"metric":"uncovered_conditions","value":"0"}
                          ]}
                        ]}
                        """.trimIndent(),
                    )
                    false -> exchange.respond(
                        """
                        {"components":[
                          {"key":"D-1","measures":[
                            {"metric":"duplicated_blocks","value":"2"}
                          ]},
                          {"key":"D-2","name":"Ignore.kt","measures":[
                            {"metric":"duplicated_lines","value":"0"},
                            {"metric":"duplicated_blocks","value":"0"}
                          ]}
                        ]}
                        """.trimIndent(),
                    )
                }
                "/api/hotspots/search" -> exchange.respond(
                    """
                    {"hotspots":[
                      {"key":"H-1"}
                    ]}
                    """.trimIndent(),
                )
                else -> exchange.respond("""{}""")
            }
        }

        val snapshot = backend.loadFindings(
            profile = ConnectionProfile(
                id = "cloud",
                name = "Cloud",
                type = SonarProfileType.CLOUD,
                baseUrl = "${serverBaseUrl()}/",
                branchOverride = "feature/test branch",
                pullRequestOverride = "77",
                authMode = AuthMode.BEARER,
            ),
            token = "token",
            project = cloudProject,
        )

        assertEquals("", snapshot.issues.single().component)
        assertEquals("CODE_SMELL", snapshot.issues.single().type)
        assertEquals(emptyList(), snapshot.issues.single().tags)
        assertEquals("Named.kt", snapshot.coverage.single().path)
        assertNull(snapshot.coverage.single().coverage)
        assertEquals(3, snapshot.coverage.single().uncoveredBranches)
        assertEquals("D-1", snapshot.duplication.single().path)
        assertEquals(2, snapshot.duplication.single().duplicatedBlocks)
        assertEquals("", snapshot.hotspots.single().component)
        assertEquals("", snapshot.hotspots.single().message)

        val issueRequest = requests.single { it.path == "/api/issues/search" }
        assertEquals("feature/test branch", issueRequest.query["branch"])
        assertEquals("demo-org", issueRequest.query["organization"])
        val coverageRequest = requests.first { it.path == "/api/measures/component_tree" }
        assertEquals("demo-key", coverageRequest.query["component"])
        assertEquals("77", coverageRequest.query["pullRequest"])
    }

    @Test
    fun `omits optional query parameters for cloud findings without organization branch or pull request`() {
        val requests = mutableListOf<RecordedRequest>()
        startServer { exchange ->
            requests += exchange.record()
            when (exchange.requestURI.path) {
                "/api/issues/search" -> exchange.respond("""{"issues":[]}""")
                "/api/measures/component_tree" -> exchange.respond(
                    """
                    {"components":[
                      {"key":"C-1","path":"src/App.kt","measures":[
                        {"metric":"uncovered_lines","value":"1"}
                      ]}
                    ]}
                    """.trimIndent(),
                )
                "/api/hotspots/search" -> exchange.respond("""{"hotspots":[]}""")
                else -> exchange.respond("""{}""")
            }
        }

        val projectWithoutOrg = DiscoveredSonarProject("/tmp/repo", "demo-key", " ")
        val snapshot = backend.loadFindings(
            profile = ConnectionProfile(
                id = "cloud",
                name = "Cloud",
                type = SonarProfileType.CLOUD,
                baseUrl = serverBaseUrl(),
                branchOverride = null,
                pullRequestOverride = null,
                authMode = AuthMode.BEARER,
            ),
            token = "token",
            project = projectWithoutOrg,
        )

        assertEquals(1, snapshot.coverage.size)
        requests.forEach { request ->
            assertFalse(request.query.containsKey("organization"))
            assertFalse(request.query.containsKey("branch"))
            assertFalse(request.query.containsKey("pullRequest"))
        }
    }

    @Test
    fun `uses insecure client path when tls verification is disabled`() {
        val clientMethod = SonarHttpBackend::class.java.getDeclaredMethod("client", ConnectionProfile::class.java)
        clientMethod.isAccessible = true

        val client = clientMethod.invoke(
            backend,
            ConnectionProfile(
                id = "server",
                name = "Server",
                type = SonarProfileType.SERVER,
                baseUrl = "https://example.test",
                tlsVerificationEnabled = false,
                authMode = AuthMode.BEARER,
            ),
        )

        assertNotNull(client)
    }

    @Test
    fun `omits sonarcloud organization parameter when testing server findings`() {
        val requests = mutableListOf<RecordedRequest>()
        startServer { exchange ->
            requests += exchange.record()
            when (exchange.requestURI.path) {
                "/api/issues/search" -> exchange.respond("""{"issues":[]}""")
                "/api/measures/component_tree" -> exchange.respond("""{"components":[]}""")
                "/api/hotspots/search" -> exchange.respond("""{"hotspots":[]}""")
                else -> exchange.respond("""{}""")
            }
        }

        backend.loadFindings(
            profile = ConnectionProfile(
                id = "server",
                name = "Server",
                type = SonarProfileType.SERVER,
                baseUrl = serverBaseUrl(),
                authMode = AuthMode.BEARER,
            ),
            token = "token",
            project = serverProject,
        )

        requests.forEach { request ->
            assertFalse(request.query.containsKey("organization"))
        }
    }

    @Test
    fun `wraps api failures as project validation failures`() {
        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/authentication/validate" -> exchange.respond("""{"valid":true}""")
                "/api/components/search_projects" -> exchange.respond("""{"error":"boom"}""", 500)
                else -> exchange.respond("""{}""")
            }
        }

        val diagnostics = backend.testConnection(cloudProfile(serverBaseUrl()), "token", cloudProject)

        assertFalse(diagnostics.success)
        assertEquals("Network failure", diagnostics.summary)
        assertContains(diagnostics.details.single(), "Sonar API request failed")
    }

    @Test
    fun `creates backend instance from factory`() {
        assertTrue(SonarBackendFactory.create() is SonarHttpBackend)
    }

    @Test
    fun `builds insecure ssl context when tls verification is disabled`() {
        val method = SonarHttpBackend::class.java.getDeclaredMethod("insecureSslContext")
        method.isAccessible = true

        val sslContext = method.invoke(backend) as SSLContext

        assertNotNull(sslContext.socketFactory)
    }

    private fun serverProfile(baseUrl: String) = ConnectionProfile(
        id = "server",
        name = "Server",
        type = SonarProfileType.SERVER,
        baseUrl = baseUrl,
        authMode = AuthMode.BEARER,
    )

    private fun cloudProfile(baseUrl: String) = ConnectionProfile(
        id = "cloud",
        name = "Cloud",
        type = SonarProfileType.CLOUD,
        baseUrl = baseUrl,
        authMode = AuthMode.BEARER,
    )

    private fun startServer(handler: (HttpExchange) -> Unit) {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
        }
    }

    private fun serverBaseUrl(): String = "http://127.0.0.1:${checkNotNull(server).address.port}"

    private data class RecordedRequest(
        val path: String,
        val query: Map<String, String>,
        val authorization: String?,
    )

    private fun HttpExchange.record(): RecordedRequest = RecordedRequest(
        path = requestURI.path,
        query = requestURI.queryParameters(),
        authorization = requestHeaders.getFirst("Authorization"),
    )

    private fun URI.queryParameters(): Map<String, String> =
        rawQuery
            ?.split("&")
            ?.filter { it.isNotBlank() }
            ?.associate { entry ->
                val (key, value) = entry.split("=", limit = 2).let { it.first() to it.getOrElse(1) { "" } }
                key.decodeUrl() to value.decodeUrl()
            }
            .orEmpty()

    private fun String.decodeUrl(): String = java.net.URLDecoder.decode(this, StandardCharsets.UTF_8)

    private fun HttpExchange.respond(body: String, status: Int = 200) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

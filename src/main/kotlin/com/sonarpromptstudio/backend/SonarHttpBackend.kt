package com.sonarpromptstudio.backend

import com.sonarpromptstudio.model.ConnectionDiagnostics
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.CoverageFinding
import com.sonarpromptstudio.model.DiscoveredSonarProject
import com.sonarpromptstudio.model.DuplicationFinding
import com.sonarpromptstudio.model.FindingsSnapshot
import com.sonarpromptstudio.model.HotspotFinding
import com.sonarpromptstudio.model.IssueFinding
import com.sonarpromptstudio.model.SonarProfileType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SonarHttpBackend : SonarBackend {
    private val json = Json { ignoreUnknownKeys = true }

    override fun testConnection(
        profile: ConnectionProfile,
        token: String?,
        project: DiscoveredSonarProject?,
    ): ConnectionDiagnostics {
        val baseUrl = profile.effectiveBaseUrl().trim()
        if (baseUrl.isBlank()) {
            return ConnectionDiagnostics(false, INVALID_URL, listOf("Base URL is required."))
        }
        val uri = try {
            URI(baseUrl)
        } catch (_: Exception) {
            return ConnectionDiagnostics(false, INVALID_URL, listOf("Base URL is not a valid URI."))
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return ConnectionDiagnostics(false, INVALID_URL, listOf("Use a full http(s) URL with a host."))
        }
        if (token.isNullOrBlank()) {
            return ConnectionDiagnostics(false, "Authentication failure", listOf("No token is available. Save one securely or set SONAR_TOKEN in .env."))
        }
        if (project == null) {
            return ConnectionDiagnostics(false, "Missing sonar.projectKey", listOf("No supported sonar-project.properties file was discovered in the repository root or one level below it."))
        }
        if (profile.type == SonarProfileType.CLOUD && project.sonarOrganization.isNullOrBlank()) {
            return ConnectionDiagnostics(false, "Invalid SonarCloud organization", listOf("sonar.organization is required for SonarQube Cloud projects."))
        }

        return try {
            request(profile, token, "/api/authentication/validate").let { authBody ->
                val auth = json.decodeFromString(AuthenticationValidateResponse.serializer(), authBody)
                if (!auth.valid) {
                    return ConnectionDiagnostics(false, "Authentication failure", listOf("Sonar rejected the provided credentials."))
                }
            }

            val projectQuery = linkedMapOf(
                "projects" to project.sonarProjectKey,
            )
            if (profile.type == SonarProfileType.CLOUD) {
                projectQuery["organization"] = project.sonarOrganization ?: ""
            }
            val componentsBody = request(profile, token, "/api/components/search_projects", projectQuery)
            val response = json.decodeFromString(ComponentsResponse.serializer(), componentsBody)
            if (response.components.none { it.key == project.sonarProjectKey }) {
                return ConnectionDiagnostics(false, "Project validation failure", listOf("The configured credentials can authenticate, but the project was not found or is not accessible."))
            }
            ConnectionDiagnostics(true, "Connection successful", listOf("Authenticated and validated project ${project.sonarProjectKey}."))
        } catch (e: SSLHandshakeException) {
            ConnectionDiagnostics(false, "TLS/certificate failure", listOf(e.message ?: "TLS handshake failed."))
        } catch (e: IOException) {
            ConnectionDiagnostics(false, "Network failure", listOf(e.message ?: "The Sonar server could not be reached."))
        } catch (e: Exception) {
            ConnectionDiagnostics(false, "Project validation failure", listOf(e.message ?: "Validation failed."))
        }
    }

    override fun loadFindings(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): FindingsSnapshot {
        val issues = loadIssues(profile, token, project)
        val coverage = loadCoverage(profile, token, project)
        val duplication = loadDuplication(profile, token, project)
        val hotspots = loadHotspots(profile, token, project)
        return FindingsSnapshot(
            issues = issues,
            coverage = coverage,
            duplication = duplication,
            hotspots = hotspots,
        )
    }

    private fun loadIssues(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): List<IssueFinding> {
        val params = issueParams(profile, project).apply {
            put("resolved", "false")
            put("ps", "500")
        }
        val body = request(profile, token, "/api/issues/search", params)
        return json.decodeFromString(IssuesResponse.serializer(), body).issues.map {
            IssueFinding(
                key = it.key,
                severity = it.severity ?: "UNKNOWN",
                type = it.type ?: "CODE_SMELL",
                rule = it.rule ?: "",
                component = it.component ?: "",
                line = it.line,
                status = it.status ?: "",
                effort = it.effort,
                tags = it.tags ?: emptyList(),
                message = it.message ?: "",
            )
        }
    }

    private fun loadCoverage(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): List<CoverageFinding> {
        val params = componentTreeParams(profile, project).apply {
            put("qualifiers", "FIL")
            put("metricKeys", "coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions")
            put("ps", "500")
        }
        val body = request(profile, token, "/api/measures/component_tree", params)
        return json.decodeFromString(ComponentTreeResponse.serializer(), body).components.map { component ->
            val measures = component.measures.orEmpty().associateBy { it.metric }
            CoverageFinding(
                key = component.key,
                path = component.path ?: component.name ?: component.key,
                coverage = measures["coverage"]?.value?.toDoubleOrNull(),
                lineCoverage = measures["line_coverage"]?.value?.toDoubleOrNull(),
                branchCoverage = measures["branch_coverage"]?.value?.toDoubleOrNull(),
                uncoveredLines = measures["uncovered_lines"]?.value?.toIntOrNull(),
                uncoveredBranches = measures["uncovered_conditions"]?.value?.toIntOrNull(),
            )
        }.filter { it.uncoveredLines ?: 0 > 0 || it.uncoveredBranches ?: 0 > 0 }
    }

    private fun loadDuplication(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): List<DuplicationFinding> {
        val params = componentTreeParams(profile, project).apply {
            put("qualifiers", "FIL")
            put("metricKeys", "duplicated_lines_density,duplicated_lines,duplicated_blocks")
            put("ps", "500")
        }
        val body = request(profile, token, "/api/measures/component_tree", params)
        return json.decodeFromString(ComponentTreeResponse.serializer(), body).components.map { component ->
            val measures = component.measures.orEmpty().associateBy { it.metric }
            DuplicationFinding(
                key = component.key,
                path = component.path ?: component.name ?: component.key,
                duplication = measures["duplicated_lines_density"]?.value?.toDoubleOrNull(),
                duplicatedLines = measures["duplicated_lines"]?.value?.toIntOrNull(),
                duplicatedBlocks = measures["duplicated_blocks"]?.value?.toIntOrNull(),
            )
        }.filter { it.duplicatedLines ?: 0 > 0 || it.duplicatedBlocks ?: 0 > 0 }
    }

    private fun loadHotspots(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): List<HotspotFinding> {
        val params = hotspotParams(profile, project).apply {
            put("ps", "500")
        }
        val body = request(profile, token, "/api/hotspots/search", params)
        return json.decodeFromString(HotspotsResponse.serializer(), body).hotspots.map {
            HotspotFinding(
                key = it.key,
                component = it.component ?: "",
                line = it.line,
                status = it.status,
                vulnerabilityProbability = it.vulnerabilityProbability,
                message = it.message ?: "",
            )
        }
    }

    private fun componentTreeParams(profile: ConnectionProfile, project: DiscoveredSonarProject): LinkedHashMap<String, String> =
        linkedMapOf<String, String>().apply {
            put("component", project.sonarProjectKey)
            if (profile.type == SonarProfileType.CLOUD && !project.sonarOrganization.isNullOrBlank()) {
                put("organization", project.sonarOrganization)
            }
            profile.branchOverride?.let { put("branch", it) }
            profile.pullRequestOverride?.let { put("pullRequest", it) }
        }

    private fun issueParams(profile: ConnectionProfile, project: DiscoveredSonarProject): LinkedHashMap<String, String> =
        linkedMapOf<String, String>().apply {
            put("componentKeys", project.sonarProjectKey)
            if (profile.type == SonarProfileType.CLOUD && !project.sonarOrganization.isNullOrBlank()) {
                put("organization", project.sonarOrganization)
            }
            profile.branchOverride?.let { put("branch", it) }
            profile.pullRequestOverride?.let { put("pullRequest", it) }
        }

    private fun hotspotParams(profile: ConnectionProfile, project: DiscoveredSonarProject): LinkedHashMap<String, String> =
        linkedMapOf<String, String>().apply {
            put("projectKey", project.sonarProjectKey)
            if (profile.type == SonarProfileType.CLOUD && !project.sonarOrganization.isNullOrBlank()) {
                put("organization", project.sonarOrganization)
            }
            profile.branchOverride?.let { put("branch", it) }
            profile.pullRequestOverride?.let { put("pullRequest", it) }
        }

    private fun request(
        profile: ConnectionProfile,
        token: String,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        val query = params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val url = buildString {
            append(profile.effectiveBaseUrl().trim().trimEnd('/'))
            append(path)
            if (query.isNotBlank()) append('?').append(query)
        }
        val request = HttpRequest.newBuilder(URI(url))
            .header("Accept", "application/json")
            .apply {
                when (profile.authMode) {
                    com.sonarpromptstudio.model.AuthMode.BEARER -> header("Authorization", "Bearer $token")
                    com.sonarpromptstudio.model.AuthMode.BASIC_TOKEN -> header(
                        "Authorization",
                        "Basic ${java.util.Base64.getEncoder().encodeToString("$token:".toByteArray(StandardCharsets.UTF_8))}",
                    )
                }
            }
            .GET()
            .build()
        val response = client(profile).send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw IOException("Authentication failure (${response.statusCode()})")
        }
        if (response.statusCode() >= 400) {
            throw IOException("Sonar API request failed (${response.statusCode()}): ${response.body()}")
        }
        return response.body()
    }

    private fun client(profile: ConnectionProfile): HttpClient {
        val builder = HttpClient.newBuilder()
        if (!profile.tlsVerificationEnabled) {
            builder.sslContext(insecureSslContext())
        }
        return builder.build()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    @Suppress("kotlin:S4830", "kotlin:S4423")
    private fun insecureSslContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return try {
            SSLContext.getInstance("TLS").apply {
                init(null, trustAll, SecureRandom())
            }
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException(e)
        } catch (e: KeyManagementException) {
            throw IllegalStateException(e)
        }
    }

    companion object {
        private const val INVALID_URL = "Invalid URL"
    }
}

@Serializable
private data class AuthenticationValidateResponse(val valid: Boolean)

@Serializable
private data class ComponentsResponse(val components: List<ComponentEntry> = emptyList())

@Serializable
private data class ComponentEntry(val key: String, val name: String? = null)

@Serializable
private data class IssuesResponse(val issues: List<IssueEntry> = emptyList())

@Serializable
private data class IssueEntry(
    val key: String,
    val severity: String? = null,
    val type: String? = null,
    val rule: String? = null,
    val component: String? = null,
    val line: Int? = null,
    val status: String? = null,
    val effort: String? = null,
    val tags: List<String>? = null,
    val message: String? = null,
)

@Serializable
private data class ComponentTreeResponse(val components: List<ComponentMeasureEntry> = emptyList())

@Serializable
private data class ComponentMeasureEntry(
    val key: String,
    val path: String? = null,
    val name: String? = null,
    val measures: List<MeasureEntry>? = null,
)

@Serializable
private data class MeasureEntry(val metric: String, val value: String? = null)

@Serializable
private data class HotspotsResponse(
    @SerialName("hotspots")
    val hotspots: List<HotspotEntry> = emptyList(),
)

@Serializable
private data class HotspotEntry(
    val key: String,
    val component: String? = null,
    val line: Int? = null,
    val status: String? = null,
    val vulnerabilityProbability: String? = null,
    val message: String? = null,
)

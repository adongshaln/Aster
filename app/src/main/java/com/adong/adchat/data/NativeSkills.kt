package com.adong.adchat.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MAX_GITHUB_ARCHIVE_BYTES = 16 * 1024 * 1024
private const val MAX_SKILL_BUNDLE_BYTES = 10 * 1024 * 1024
private const val MAX_SKILL_FILE_BYTES = 5 * 1024 * 1024
private const val MAX_SKILL_FILES = 256

/** A complete GitHub skill package, not just a copied prompt. */
data class SkillBundle(
    val name: String,
    val sourceUrl: String,
    val resolvedSkillUrl: String,
    val skillMarkdown: String,
    val sha256: String,
    val fileCount: Int,
    val zipBytes: ByteArray
)

fun interface SkillBundleLoader {
    fun loadBundle(sourceUrl: String): SkillBundle
}

data class NativeSkillReference(
    val skillId: String,
    val version: String?,
    val name: String,
    val bundleSha256: String
)

fun interface NativeSkillUploader {
    fun upload(profile: ApiProfile, bundle: SkillBundle): NativeSkillReference
}

class NativeSkillsUnsupportedException(message: String) : IllegalStateException(message)

/** Downloads the actual GitHub skill directory and repacks it as a clean skill ZIP. */
object GitHubSkillBundleRuntime : SkillBundleLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun loadBundle(sourceUrl: String): SkillBundle {
        val target = GitHubSkillRuntime.resolve(sourceUrl)
        val archiveUrl = HttpUrl.Builder()
            .scheme("https")
            .host("api.github.com")
            .addPathSegment("repos")
            .addPathSegment(target.owner)
            .addPathSegment(target.repository)
            .addPathSegment("zipball")
            .addPathSegment(target.ref)
            .build()
        val request = Request.Builder()
            .url(archiveUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Aster-Skills/1.0")
            .get()
            .build()
        val archiveBytes = client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "GitHub Skill 仓库下载失败（HTTP ${response.code}）" }
            require(response.request.url.host.lowercase() in setOf("api.github.com", "github.com", "codeload.github.com")) {
                "GitHub Skill 仓库下载被重定向到不受信任的站点"
            }
            val body = requireNotNull(response.body) { "GitHub Skill 仓库返回空内容" }
            body.contentLength().takeIf { it >= 0 }?.let {
                require(it <= MAX_GITHUB_ARCHIVE_BYTES) { "GitHub Skill 仓库压缩包超过 ${MAX_GITHUB_ARCHIVE_BYTES / 1024 / 1024} MB 限制" }
            }
            readLimited(body.byteStream(), MAX_GITHUB_ARCHIVE_BYTES, "GitHub Skill 仓库压缩包")
        }

        val skillDirectory = target.path.substringBeforeLast('/', missingDelimiterValue = "").trim('/')
        val selected = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                val repositoryPath = normalized.substringAfter('/', missingDelimiterValue = "")
                if (repositoryPath.isBlank()) continue
                val relative = when {
                    skillDirectory.isBlank() -> repositoryPath
                    repositoryPath == skillDirectory -> continue
                    repositoryPath.startsWith("$skillDirectory/") -> repositoryPath.removePrefix("$skillDirectory/")
                    else -> continue
                }
                validateBundlePath(relative)
                require(selected.size < MAX_SKILL_FILES) { "Skill 文件超过 $MAX_SKILL_FILES 个，请精简 Skill" }
                val bytes = readLimited(zip, MAX_SKILL_FILE_BYTES, "Skill 文件 $relative")
                selected[relative] = bytes
                require(selected.values.sumOf { it.size.toLong() } <= MAX_SKILL_BUNDLE_BYTES) {
                    "Skill 解压内容超过 ${MAX_SKILL_BUNDLE_BYTES / 1024 / 1024} MB 限制"
                }
            }
        }

        val skillFiles = selected.keys.filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
        require(skillFiles.size == 1 && skillFiles.single().equals("SKILL.md", ignoreCase = true)) {
            if (skillFiles.isEmpty()) "指定 GitHub 目录中没有 SKILL.md"
            else "指定目录包含多个 SKILL.md，请发送单个 Skill 的 tree 目录链接"
        }
        val skillBytes = selected.getValue(skillFiles.single())
        val skillMarkdown = skillBytes.toString(Charsets.UTF_8)
        require(skillMarkdown.isNotBlank()) { "SKILL.md 为空" }

        val packed = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                selected.toSortedMap().forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        require(packed.size <= MAX_SKILL_BUNDLE_BYTES) { "Skill ZIP 超过 ${MAX_SKILL_BUNDLE_BYTES / 1024 / 1024} MB 限制" }
        return SkillBundle(
            name = GitHubSkillRuntime.nameFrom(skillMarkdown, target),
            sourceUrl = sourceUrl.trim(),
            resolvedSkillUrl = target.rawUrl,
            skillMarkdown = skillMarkdown,
            sha256 = sha256(packed),
            fileCount = selected.size,
            zipBytes = packed
        )
    }

    private fun validateBundlePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\u0000')) { "Skill 包含非法文件路径" }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Skill 包含目录穿越路径" }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int, label: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "$label 超过 ${limit / 1024} KB 限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Real OpenAI-compatible Skills API client. It uploads the ZIP to POST /skills and
 * returns the provider-issued skill id/version. Unsupported endpoints are surfaced
 * separately so Aster can use the explicit function-call fallback without pretending
 * the native API succeeded.
 */
object NativeSkillsApi : NativeSkillUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun upload(profile: ApiProfile, bundle: SkillBundle): NativeSkillReference {
        val url = skillsUrl(profile)
        val safeName = bundle.name.replace(Regex("[^A-Za-z0-9_.-]"), "-").trim('-').ifBlank { "aster-skill" }.take(80)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files",
                "$safeName.zip",
                bundle.zipBytes.toRequestBody("application/zip".toMediaType())
            )
            .build()
        val request = requestBuilder(profile, url).post(multipart).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    val root = JSONObject(text)
                    root.optJSONObject("error")?.optString("message") ?: root.optString("message")
                }.getOrNull().orEmpty()
                if (response.code in setOf(404, 405, 501) ||
                    (response.code == 400 && listOf("unsupported", "unknown endpoint", "not found", "unknown url")
                        .any { message.contains(it, ignoreCase = true) })) {
                    throw NativeSkillsUnsupportedException("当前 Responses 服务不支持原生 /skills 接口")
                }
                throw IllegalStateException(
                    "原生 Skill 上传失败（HTTP ${response.code}）${message.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()}"
                )
            }
            val root = runCatching { JSONObject(text) }
                .getOrElse { throw IllegalStateException("/skills 返回的不是有效 JSON") }
            val id = root.optString("id")
            require(id.isNotBlank()) { "/skills 成功响应缺少 skill id" }
            val version = root.optString("default_version").ifBlank { root.optString("latest_version") }.takeIf(String::isNotBlank)
            return NativeSkillReference(
                skillId = id,
                version = version,
                name = root.optString("name").ifBlank { bundle.name },
                bundleSha256 = bundle.sha256
            )
        }
    }

    internal fun skillsUrl(profile: ApiProfile): String {
        val responsePath = profile.responsesPath.trim().ifBlank { "/v1/responses" }
        if (responsePath.startsWith("http://") || responsePath.startsWith("https://")) {
            val url = responsePath.toHttpUrlOrNull() ?: throw IllegalArgumentException("Responses URL 不合法")
            val segments = url.pathSegments.toMutableList().apply {
                if (isNotEmpty()) removeAt(lastIndex)
                add("skills")
            }
            return url.newBuilder().encodedPath("/").apply { segments.filter(String::isNotBlank).forEach(::addPathSegment) }.build().toString()
        }
        val parent = responsePath.trimStart('/').substringBeforeLast('/', missingDelimiterValue = "")
        val relative = if (parent.isBlank()) "skills" else "$parent/skills"
        val base = profile.baseUrl.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "Base URL 必须以 http:// 或 https:// 开头" }
        return if (base.endsWith("/v1", ignoreCase = true) && relative.startsWith("v1/", ignoreCase = true)) {
            "$base/${relative.substring(3)}"
        } else "$base/$relative"
    }

    private fun requestBuilder(profile: ApiProfile, url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (profile.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${profile.apiKey.trim()}")
        profile.extraHeaders.lineSequence().forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                val name = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
            }
        }
        return builder
    }
}

internal fun nativeSkillShellTool(reference: NativeSkillReference): JSONObject = JSONObject()
    .put("type", "shell")
    .put("environment", JSONObject()
        .put("type", "container_auto")
        .put("network_policy", JSONObject().put("type", "disabled"))
        .put("skills", org.json.JSONArray().put(JSONObject()
            .put("type", "skill_reference")
            .put("skill_id", reference.skillId)
            .apply { reference.version?.let { put("version", it) } })))

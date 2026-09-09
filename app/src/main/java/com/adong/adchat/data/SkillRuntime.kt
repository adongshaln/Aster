package com.adong.adchat.data

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal const val LOAD_SKILL_TOOL = "load_skill"
internal const val LIST_SKILLS_TOOL = "list_skills"
internal const val MAX_SKILL_BYTES = 256 * 1024
private const val MAX_INSTALLED_SKILLS = 32

data class LoadedSkill(
    val name: String,
    val sourceUrl: String,
    val resolvedUrl: String,
    val sha256: String,
    val content: String,
    val installedAt: Long = System.currentTimeMillis()
)

fun interface SkillLoader {
    fun load(sourceUrl: String): LoadedSkill
}

interface SkillLibrary {
    fun list(): List<LoadedSkill>
    fun find(selector: String): LoadedSkill?
    fun save(skill: LoadedSkill)
}

class MemorySkillLibrary : SkillLibrary {
    private val values = linkedMapOf<String, LoadedSkill>()

    @Synchronized override fun list(): List<LoadedSkill> = values.values.sortedBy { it.name.lowercase() }

    @Synchronized override fun find(selector: String): LoadedSkill? = findSkill(values.values.toList(), selector)

    @Synchronized override fun save(skill: LoadedSkill) {
        values[sourceKey(skill.sourceUrl)] = skill
    }
}

/**
 * Internal app storage for installed skills. A skill survives app restarts and is not re-downloaded
 * when the model later loads it by installed name. Sending the GitHub URL again deliberately refreshes it.
 */
class FileSkillLibrary(context: Context) : SkillLibrary {
    private val directory = File(context.filesDir, "skills").apply { mkdirs() }

    @Synchronized override fun list(): List<LoadedSkill> = directory.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension == "json" && it.length() <= MAX_SKILL_BYTES * 2L }
        .mapNotNull { file -> runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrNull() }
        .sortedBy { it.name.lowercase() }
        .toList()

    @Synchronized override fun find(selector: String): LoadedSkill? = findSkill(list(), selector)

    @Synchronized override fun save(skill: LoadedSkill) {
        require(skill.content.toByteArray(Charsets.UTF_8).size <= MAX_SKILL_BYTES) { "SKILL.md 超过 ${MAX_SKILL_BYTES / 1024} KB 限制" }
        val current = list()
        val key = sourceKey(skill.sourceUrl)
        if (current.none { sourceKey(it.sourceUrl) == key }) {
            require(current.size < MAX_INSTALLED_SKILLS) { "已安装 Skill 达到 $MAX_INSTALLED_SKILLS 个上限，请先移除旧 Skill" }
        }
        val target = File(directory, "$key.json")
        val temporary = File(directory, "$key.tmp")
        temporary.writeText(encode(skill).toString(), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun encode(skill: LoadedSkill) = JSONObject()
        .put("name", skill.name)
        .put("source_url", skill.sourceUrl)
        .put("resolved_url", skill.resolvedUrl)
        .put("sha256", skill.sha256)
        .put("installed_at", skill.installedAt)
        .put("content", skill.content)

    private fun decode(value: String): LoadedSkill {
        val root = JSONObject(value)
        val content = root.getString("content")
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_SKILL_BYTES)
        return LoadedSkill(
            name = root.getString("name"),
            sourceUrl = root.getString("source_url"),
            resolvedUrl = root.getString("resolved_url"),
            sha256 = root.getString("sha256"),
            installedAt = root.optLong("installed_at").takeIf { it > 0 } ?: 0L,
            content = content
        )
    }
}

/** Real Aster skill runtime: GitHub URLs perform a network fetch + install, names load local installed bytes. */
class SkillRuntime(
    private val library: SkillLibrary,
    private val remoteLoader: SkillLoader = GitHubSkillRuntime
) {
    fun hasInstalledSkills(): Boolean = library.list().isNotEmpty()
    fun listInstalled(): List<LoadedSkill> = library.list()

    fun load(sourceOrName: String): LoadedSkill {
        val selector = sourceOrName.trim()
        require(selector.isNotBlank()) { "Skill 来源不能为空" }
        return if (isGitHubSkillUrl(selector)) {
            remoteLoader.load(selector).also(library::save)
        } else {
            library.find(selector) ?: throw IllegalArgumentException("未安装 Skill：$selector。请先发送它的 GitHub 链接进行安装。")
        }
    }

    companion object {
        fun inMemory(loader: SkillLoader = GitHubSkillRuntime): SkillRuntime = SkillRuntime(MemorySkillLibrary(), loader)
        fun persistent(context: Context): SkillRuntime = SkillRuntime(FileSkillLibrary(context.applicationContext), GitHubSkillRuntime)
    }
}

internal data class GitHubSkillTarget(
    val sourceUrl: String,
    val rawUrl: String,
    val owner: String,
    val repository: String,
    val ref: String,
    val path: String
)

/**
 * Resolves a public GitHub link to an actual SKILL.md download target.
 * Supported forms:
 * - https://github.com/owner/repo
 * - https://github.com/owner/repo/blob/<ref>/path/SKILL.md
 * - https://github.com/owner/repo/tree/<ref>/path/to/skill
 * - https://raw.githubusercontent.com/owner/repo/<ref>/path/SKILL.md
 * Repository-root links resolve the repository's real default branch through the GitHub API.
 */
internal fun resolveGitHubSkillTarget(
    sourceUrl: String,
    defaultBranchResolver: (owner: String, repository: String) -> String
): GitHubSkillTarget {
    val parsed = sourceUrl.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Skill 链接不是有效 URL")
    require(parsed.scheme == "https") { "Skill 只允许通过 HTTPS 加载" }
    require(parsed.query == null && parsed.fragment == null) { "Skill 链接不能包含 query 或 fragment" }
    val host = parsed.host.lowercase()
    val segments = parsed.pathSegments.filter { it.isNotBlank() }

    fun rawTarget(owner: String, repository: String, ref: String, path: String): GitHubSkillTarget {
        require(owner.matches(Regex("[A-Za-z0-9_.-]+")) && repository.matches(Regex("[A-Za-z0-9_.-]+"))) {
            "GitHub 仓库地址不合法"
        }
        require(ref.isNotBlank() && ref.length <= 200 && !ref.contains("..")) { "GitHub ref 不合法" }
        val normalizedPath = path.trim('/').replace("\\", "/")
        require(normalizedPath.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Skill 路径不合法" }
        require(normalizedPath.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)) {
            "Skill 必须指向 SKILL.md 或包含 SKILL.md 的目录"
        }
        val raw = HttpUrl.Builder()
            .scheme("https")
            .host("raw.githubusercontent.com")
            .addPathSegment(owner)
            .addPathSegment(repository)
            .addPathSegment(ref)
            .apply { normalizedPath.split('/').forEach(::addPathSegment) }
            .build()
            .toString()
        return GitHubSkillTarget(sourceUrl.trim(), raw, owner, repository, ref, normalizedPath)
    }

    if (host == "raw.githubusercontent.com") {
        require(segments.size >= 4) { "Raw GitHub Skill 链接缺少仓库、ref 或 SKILL.md 路径" }
        return rawTarget(segments[0], segments[1], segments[2], segments.drop(3).joinToString("/"))
    }

    require(host == "github.com" || host == "www.github.com") { "仅支持公开 GitHub Skill 链接" }
    require(segments.size >= 2) { "GitHub Skill 链接缺少 owner/repository" }
    val owner = segments[0]
    val repository = segments[1].removeSuffix(".git")
    if (segments.size == 2) {
        val ref = defaultBranchResolver(owner, repository).trim()
        require(ref.isNotBlank()) { "无法确定 GitHub 仓库默认分支" }
        return rawTarget(owner, repository, ref, "SKILL.md")
    }

    return when (segments[2].lowercase()) {
        "blob" -> {
            require(segments.size >= 5) { "GitHub blob 链接缺少 ref 或 SKILL.md 路径" }
            rawTarget(owner, repository, segments[3], segments.drop(4).joinToString("/"))
        }
        "tree" -> {
            require(segments.size >= 4) { "GitHub tree 链接缺少 ref" }
            val directory = segments.drop(4).joinToString("/").trim('/')
            rawTarget(owner, repository, segments[3], if (directory.isBlank()) "SKILL.md" else "$directory/SKILL.md")
        }
        else -> throw IllegalArgumentException("请发送 GitHub 仓库、tree 目录、blob/SKILL.md 或 raw/SKILL.md 链接")
    }
}

internal fun requestedGitHubSkillUrl(history: List<ChatMessage>): String? {
    val latest = history.lastOrNull { it.role == "user" }?.content.orEmpty()
    if (latest.isBlank()) return null
    val urls = Regex("https://(?:www\\.)?github\\.com/[^\\s<>()]+|https://raw\\.githubusercontent\\.com/[^\\s<>()]+", RegexOption.IGNORE_CASE)
        .findAll(latest)
        .map { it.value.trimEnd('.', ',', ';', '，', '。', '；', ')', '）', ']', '】') }
        .toList()
    if (urls.isEmpty()) return null
    val lower = latest.lowercase()
    val explicitlyRequested = lower.contains("skill") || lower.contains("技能") ||
        urls.any { it.contains("/SKILL.md", ignoreCase = true) || it.contains("/skills/", ignoreCase = true) }
    return urls.firstOrNull().takeIf { explicitlyRequested }
}

internal fun isGitHubSkillUrl(value: String): Boolean {
    val parsed = value.trim().toHttpUrlOrNull() ?: return false
    return parsed.scheme == "https" && parsed.host.lowercase() in setOf("github.com", "www.github.com", "raw.githubusercontent.com")
}

internal object GitHubSkillRuntime : SkillLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun load(sourceUrl: String): LoadedSkill {
        val target = resolveGitHubSkillTarget(sourceUrl, ::defaultBranch)
        val request = Request.Builder()
            .url(target.rawUrl)
            .header("Accept", "text/plain, text/markdown;q=0.9, */*;q=0.1")
            .header("User-Agent", "Aster-Skills/1.0")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "GitHub Skill 下载失败（HTTP ${response.code}）" }
            val finalHost = response.request.url.host.lowercase()
            require(finalHost == "raw.githubusercontent.com") { "GitHub Skill 下载被重定向到不受信任的站点" }
            val bytes = readLimited(requireNotNull(response.body) { "GitHub Skill 返回空内容" })
            val content = decodeUtf8(bytes)
            require(content.isNotBlank()) { "SKILL.md 为空" }
            return LoadedSkill(
                name = skillName(content, target),
                sourceUrl = target.sourceUrl,
                resolvedUrl = response.request.url.toString(),
                sha256 = sha256(bytes),
                content = content
            )
        }
    }

    private fun defaultBranch(owner: String, repository: String): String {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.github.com")
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(repository)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Aster-Skills/1.0")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "无法读取 GitHub 仓库信息（HTTP ${response.code}）" }
            return JSONObject(requireNotNull(response.body).string()).optString("default_branch")
                .takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("GitHub 仓库没有可用的默认分支")
        }
    }

    private fun readLimited(body: ResponseBody): ByteArray {
        body.contentLength().takeIf { it >= 0 }?.let { require(it <= MAX_SKILL_BYTES) { "SKILL.md 超过 ${MAX_SKILL_BYTES / 1024} KB 限制" } }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_SKILL_BYTES) { "SKILL.md 超过 ${MAX_SKILL_BYTES / 1024} KB 限制" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun skillName(content: String, target: GitHubSkillTarget): String {
        val normalized = content.replace("\r\n", "\n")
        if (normalized.startsWith("---\n")) {
            val end = normalized.indexOf("\n---", startIndex = 4)
            if (end > 0) {
                Regex("(?m)^name\\s*:\\s*[\"']?([^\n\"']+)[\"']?\\s*$", RegexOption.IGNORE_CASE)
                    .find(normalized.substring(4, end))
                    ?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)?.let { return it.take(120) }
            }
        }
        return target.path.split('/').dropLast(1).lastOrNull()?.takeIf { it.isNotBlank() } ?: target.repository
    }
}

private fun findSkill(values: List<LoadedSkill>, selector: String): LoadedSkill? {
    val key = selector.trim()
    values.firstOrNull { it.sourceUrl == key || it.resolvedUrl == key || it.sha256.equals(key, ignoreCase = true) }?.let { return it }
    val byName = values.filter { it.name.equals(key, ignoreCase = true) }
    require(byName.size <= 1) { "存在多个同名 Skill，请使用 GitHub 来源链接或 SHA-256 精确加载" }
    return byName.singleOrNull()
}

private fun sourceKey(value: String): String = sha256(value.trim().toByteArray(Charsets.UTF_8))
private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun installedSkillsJson(skills: List<LoadedSkill>): String = JSONObject()
    .put("ok", true)
    .put("skills", JSONArray().apply {
        skills.forEach { skill ->
            put(JSONObject()
                .put("name", skill.name)
                .put("source_url", skill.sourceUrl)
                .put("resolved_url", skill.resolvedUrl)
                .put("sha256", skill.sha256)
                .put("installed_at", skill.installedAt))
        }
    })
    .toString()

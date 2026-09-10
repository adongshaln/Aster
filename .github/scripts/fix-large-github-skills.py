from pathlib import Path


def load(path):
    return Path(path).read_text(encoding="utf-8")


def save(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


runtime_path = "app/src/main/java/com/adong/adchat/data/SkillRuntime.kt"
text = load(runtime_path)
text = replace_once(
    text,
    '''    val files: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
) {
    val containsScripts: Boolean get() = files.keys.any { it.startsWith("scripts/") || it.substringAfterLast('.') in setOf("py", "sh", "js", "mjs", "bat") }
}''',
    '''    val files: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val remoteFiles: Set<String> = emptySet()
) {
    val filePaths: Set<String> get() = files.keys + remoteFiles
    val containsScripts: Boolean get() = filePaths.any { it.startsWith("scripts/") || it.substringAfterLast('.') in setOf("py", "sh", "js", "mjs", "bat") }
}''',
    "LoadedSkill remote manifest",
)
text = replace_once(
    text,
    '''        val map = fileObject?.keys()?.asSequence()?.associateWith { fileObject.getString(it) }.orEmpty()
        map.keys.forEach(SkillPackages::validatePath)
        if (map.isNotEmpty() && (includeFiles || !root.has("payload_sha"))) require(SkillPackages.packageHash(map) == root.getString("sha256"))
        LoadedSkill(root.getString("name"), root.getString("source_url"), root.getString("resolved_url"),
            root.getString("sha256"), content, root.optLong("installed_at"),
            root.optString("description", SkillPackages.metadata(content, "description")).take(600),
            if (includeFiles) map else map.mapValues { "" }, root.optBoolean("enabled", true))''',
    '''        val map = fileObject?.keys()?.asSequence()?.associateWith { fileObject.getString(it) }.orEmpty()
        map.keys.forEach(SkillPackages::validatePath)
        val remoteFiles = root.optJSONArray("remote_files")?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.orEmpty()
        remoteFiles.forEach(SkillPackages::validatePath)
        if (map.isNotEmpty() && (includeFiles || !root.has("payload_sha"))) require(SkillPackages.packageHash(map) == root.getString("sha256"))
        LoadedSkill(root.getString("name"), root.getString("source_url"), root.getString("resolved_url"),
            root.getString("sha256"), content, root.optLong("installed_at"),
            root.optString("description", SkillPackages.metadata(content, "description")).take(600),
            if (includeFiles) map else map.mapValues { "" }, root.optBoolean("enabled", true), remoteFiles)''',
    "persist remote manifest read",
)
text = replace_once(
    text,
    '''            .put("installed_at", skill.installedAt).put("description", skill.description)
            .put("enabled", skill.enabled).put("files", JSONObject(skill.files))''',
    '''            .put("installed_at", skill.installedAt).put("description", skill.description)
            .put("enabled", skill.enabled).put("files", JSONObject(skill.files))
            .put("remote_files", JSONArray(skill.remoteFiles.sorted()))''',
    "persist remote manifest save",
)
text = replace_once(
    text,
    '''    override fun load(sourceOrName: String): LoadedSkill {
        val selector = sourceOrName.trim()
        require(selector.isNotBlank()) { "Skill 来源不能为空" }
        return if (isGitHubSkillUrl(selector)) {
            library.find(selector) ?: install(selector)
        } else {
            library.find(selector) ?: throw IllegalArgumentException("未安装 Skill：$selector。请先发送它的 GitHub 链接进行安装。")
        }
    }

    companion object {
        fun inMemory(loader: SkillLoader = GitHubSkillRuntime): SkillRuntime = SkillRuntime(MemorySkillLibrary(), loader)
        fun persistent(context: Context): SkillRuntime = SkillRuntime(FileSkillLibrary(context.applicationContext), GitHubSkillRuntime, GitHubSkillBundleRuntime)
    }''',
    '''    override fun load(sourceOrName: String): LoadedSkill {
        val selector = sourceOrName.trim()
        require(selector.isNotBlank()) { "Skill 来源不能为空" }
        return if (isGitHubSkillUrl(selector)) {
            library.find(selector) ?: install(selector)
        } else {
            library.find(selector) ?: throw IllegalArgumentException("未安装 Skill：$selector。请先发送它的 GitHub 链接进行安装。")
        }
    }

    override fun readFile(selector: String, path: String, offset: Int): JSONObject {
        require(offset == 0) { "read_skill_file 已改为整文件读取，请不要使用 offset 分页" }
        val skill = library.find(selector) ?: error("未安装 Skill：${selector.trim()}")
        val normalizedPath = path.trim()
        SkillPackages.validatePath(normalizedPath)
        val content = when {
            normalizedPath == "SKILL.md" -> skill.content
            skill.files.containsKey(normalizedPath) -> SkillPackages.decodeText(java.util.Base64.getDecoder().decode(skill.files.getValue(normalizedPath)))
            normalizedPath in skill.remoteFiles -> GitHubSkillRuntime.readRemoteFile(skill, normalizedPath)
            else -> error("技能包中没有该文件：$normalizedPath")
        }
        return JSONObject().put("ok", true).put("trust", "untrusted_external_instructions")
            .put("sha256", skill.sha256).put("path", normalizedPath).put("content", content)
            .put("total_characters", content.length).put("estimated_tokens", ContextTokenEstimate.text(content)).put("complete", true)
    }

    companion object {
        fun inMemory(loader: SkillLoader = GitHubSkillRuntime): SkillRuntime = SkillRuntime(MemorySkillLibrary(), loader)
        // GitHub Skills use a pinned remote manifest and lazy per-file reads. Do not download the whole repository archive.
        fun persistent(context: Context): SkillRuntime = SkillRuntime(FileSkillLibrary(context.applicationContext), GitHubSkillRuntime)
    }''',
    "SkillRuntime lazy reads",
)

marker = "internal fun requestedGitHubSkillUrl(history: List<ChatMessage>): String? {"
helpers = '''internal data class GitHubTreeFile(val path: String, val sha: String, val size: Long)

internal fun selectGitHubSkillPath(
    requestedPath: String,
    allowUniqueDiscovery: Boolean,
    files: List<GitHubTreeFile>
): String {
    files.firstOrNull { it.path == requestedPath }?.let { return it.path }
    require(allowUniqueDiscovery) { "GitHub Skill 中找不到 $requestedPath" }
    val candidates = files.filter { it.path.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
    require(candidates.isNotEmpty()) { "GitHub 仓库中没有找到 SKILL.md" }
    require(candidates.size == 1) { "GitHub 仓库包含多个 Skill，请发送具体的 tree 技能目录链接" }
    return candidates.single().path
}

internal fun relativeGitHubSkillFiles(skillPath: String, files: List<GitHubTreeFile>): List<GitHubTreeFile> {
    val directory = skillPath.substringBeforeLast('/', "")
    val prefix = if (directory.isBlank()) "" else "$directory/"
    return files.asSequence()
        .filter { prefix.isBlank() || it.path.startsWith(prefix) }
        .map { it.copy(path = it.path.removePrefix(prefix)) }
        .filter { it.path.isNotBlank() }
        .sortedBy { it.path }
        .toList()
}

'''
text = replace_once(text, marker, helpers + marker, "tree helpers")

start = text.index("internal object GitHubSkillRuntime : SkillLoader {")
end = text.index("\nprivate fun findSkill(", start)
new_object = r'''internal object GitHubSkillRuntime : SkillLoader {
    private const val MAX_REMOTE_SKILL_FILE_BYTES = 5 * 1024 * 1024
    private const val MAX_GITHUB_TREE_FILES = 20_000

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class ManifestResolution(
        val target: GitHubSkillTarget,
        val files: List<GitHubTreeFile>
    )

    override fun load(sourceUrl: String): LoadedSkill {
        val resolution = resolveManifest(sourceUrl)
        val target = resolution.target
        val bytes = downloadRaw(target.rawUrl, MAX_SKILL_BYTES, "SKILL.md")
        val content = SkillPackages.decodeText(bytes)
        require(content.isNotBlank()) { "SKILL.md 为空" }
        val relativeFiles = relativeGitHubSkillFiles(target.path, resolution.files)
        require(relativeFiles.any { it.path == "SKILL.md" }) { "技能目录中没有 SKILL.md" }
        val versionMaterial = buildString {
            append(target.owner).append('/').append(target.repository).append('\n')
            append(target.ref).append('\n').append(target.path).append('\n')
            relativeFiles.forEach { file ->
                append(file.path).append('\t').append(file.sha).append('\t').append(file.size).append('\n')
            }
        }.toByteArray(Charsets.UTF_8)
        return LoadedSkill(
            name = skillName(content, target),
            sourceUrl = target.sourceUrl,
            resolvedUrl = target.rawUrl,
            sha256 = skillDigest(versionMaterial),
            content = content,
            remoteFiles = relativeFiles.map { it.path }.toSet()
        )
    }

    internal fun resolve(sourceUrl: String): GitHubSkillTarget = resolveGitHubSkillTarget(sourceUrl, ::defaultBranch)

    /** Resolve branch/tag to an immutable commit and discover one nested Skill for repository-root links. */
    internal fun resolveForInstall(sourceUrl: String): GitHubSkillTarget = resolveManifest(sourceUrl).target

    internal fun nameFrom(content: String, target: GitHubSkillTarget): String = skillName(content, target)

    internal fun readRemoteFile(skill: LoadedSkill, path: String): String {
        val normalizedPath = path.trim()
        SkillPackages.validatePath(normalizedPath)
        require(normalizedPath in skill.remoteFiles) { "技能包中没有该文件：$normalizedPath" }
        val manifest = skill.resolvedUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("远程 Skill 固定版本地址无效")
        require(manifest.scheme == "https" && manifest.host.lowercase() == "raw.githubusercontent.com") {
            "远程 Skill 文件来源不受信任"
        }
        val base = manifest.pathSegments.dropLast(1)
        require(base.size >= 3) { "远程 Skill 固定版本地址不完整" }
        val url = HttpUrl.Builder().scheme("https").host("raw.githubusercontent.com")
            .apply { base.forEach(::addPathSegment); normalizedPath.split('/').forEach(::addPathSegment) }
            .build().toString()
        return SkillPackages.decodeText(downloadRaw(url, MAX_REMOTE_SKILL_FILE_BYTES, "Skill 文件"))
    }

    private fun resolveManifest(sourceUrl: String): ManifestResolution {
        val requested = resolveGitHubSkillTarget(sourceUrl, ::defaultBranch)
        val commit = commitSha(requested.owner, requested.repository, requested.ref)
        val tree = repositoryTree(requested.owner, requested.repository, commit)
        val actualPath = selectGitHubSkillPath(
            requested.path,
            allowUniqueDiscovery = allowsUniqueNestedDiscovery(sourceUrl),
            files = tree
        )
        val pinned = requested.copy(
            ref = commit,
            path = actualPath,
            rawUrl = rawUrl(requested.owner, requested.repository, commit, actualPath)
        )
        return ManifestResolution(pinned, tree)
    }

    private fun allowsUniqueNestedDiscovery(sourceUrl: String): Boolean {
        val parsed = sourceUrl.trim().toHttpUrlOrNull() ?: return false
        if (parsed.host.lowercase() !in setOf("github.com", "www.github.com")) return false
        val segments = parsed.pathSegments.filter(String::isNotBlank)
        return segments.size == 2 || (segments.size == 4 && segments[2].equals("tree", ignoreCase = true))
    }

    private fun commitSha(owner: String, repository: String, ref: String): String {
        val url = HttpUrl.Builder().scheme("https").host("api.github.com")
            .addPathSegment("repos").addPathSegment(owner).addPathSegment(repository)
            .addPathSegment("commits").addPathSegment(ref).build()
        val root = requestJson(url, "无法固定 GitHub Skill 版本")
        return root.optString("sha").takeIf { it.matches(Regex("[A-Fa-f0-9]{40,64}")) }
            ?: throw IllegalArgumentException("GitHub Skill 没有可用的 commit SHA")
    }

    private fun repositoryTree(owner: String, repository: String, commit: String): List<GitHubTreeFile> {
        val url = HttpUrl.Builder().scheme("https").host("api.github.com")
            .addPathSegment("repos").addPathSegment(owner).addPathSegment(repository)
            .addPathSegment("git").addPathSegment("trees").addPathSegment(commit)
            .addQueryParameter("recursive", "1").build()
        val root = requestJson(url, "无法读取 GitHub Skill 文件清单")
        require(!root.optBoolean("truncated", false)) { "GitHub 仓库文件清单过大，请发送具体的 tree 技能目录链接" }
        val array = root.optJSONArray("tree") ?: JSONArray()
        require(array.length() <= MAX_GITHUB_TREE_FILES) { "GitHub 仓库文件条目过多，请发送具体的 tree 技能目录链接" }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("type") != "blob") continue
                val path = item.optString("path")
                val sha = item.optString("sha")
                if (path.isBlank() || sha.isBlank()) continue
                add(GitHubTreeFile(path, sha, item.optLong("size", 0L)))
            }
        }
    }

    private fun defaultBranch(owner: String, repository: String): String {
        val url = HttpUrl.Builder().scheme("https").host("api.github.com")
            .addPathSegment("repos").addPathSegment(owner).addPathSegment(repository).build()
        return requestJson(url, "无法读取 GitHub 仓库信息").optString("default_branch")
            .takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("GitHub 仓库没有可用的默认分支")
    }

    private fun requestJson(url: HttpUrl, errorLabel: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Aster-Skills/1.0").get().build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "$errorLabel（HTTP ${response.code}）" }
            require(response.request.url.host.lowercase() == "api.github.com") { "$errorLabel：响应来源不受信任" }
            return JSONObject(requireNotNull(response.body) { "$errorLabel：返回空内容" }.string())
        }
    }

    private fun rawUrl(owner: String, repository: String, ref: String, path: String): String =
        HttpUrl.Builder().scheme("https").host("raw.githubusercontent.com")
            .addPathSegment(owner).addPathSegment(repository).addPathSegment(ref)
            .apply { path.split('/').forEach(::addPathSegment) }.build().toString()

    private fun downloadRaw(url: String, maxBytes: Int, label: String): ByteArray {
        val request = Request.Builder().url(url)
            .header("Accept", "text/plain, text/markdown;q=0.9, */*;q=0.1")
            .header("User-Agent", "Aster-Skills/1.0").get().build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "$label 下载失败（HTTP ${response.code}）" }
            require(response.request.url.host.lowercase() == "raw.githubusercontent.com") { "$label 下载被重定向到不受信任的站点" }
            val body = requireNotNull(response.body) { "$label 返回空内容" }
            body.contentLength().takeIf { it >= 0 }?.let {
                require(it <= maxBytes) { "$label 超过 ${maxBytes / 1024} KB 单文件限制" }
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            body.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "$label 超过 ${maxBytes / 1024} KB 单文件限制" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }

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
'''
text = text[:start] + new_object + text[end:]
save(runtime_path, text)

packages_path = "app/src/main/java/com/adong/adchat/data/SkillPackages.kt"
text = load(packages_path)
text = replace_once(
    text,
    '''        val text = if (normalizedPath == "SKILL.md") skill.content else SkillPackages.decodeText(Base64.getDecoder().decode(
            skill.files[normalizedPath] ?: error("技能包中没有该文件：$normalizedPath")))''',
    '''        val text = when {
            normalizedPath == "SKILL.md" -> skill.content
            skill.files.containsKey(normalizedPath) -> SkillPackages.decodeText(Base64.getDecoder().decode(skill.files.getValue(normalizedPath)))
            normalizedPath in skill.remoteFiles -> GitHubSkillRuntime.readRemoteFile(skill, normalizedPath)
            else -> error("技能包中没有该文件：$normalizedPath")
        }''',
    "SkillSession lazy remote read",
)
save(packages_path, text)

protocol_path = "app/src/main/java/com/adong/adchat/data/ToolProtocol.kt"
text = load(protocol_path)
text = replace_once(
    text,
    '.put("files", JSONArray(skill.files.keys.sorted()))',
    '.put("files", JSONArray(skill.filePaths.sorted()))',
    "load tool remote file manifest",
)
save(protocol_path, text)

docs_path = "docs/SKILLS.md"
text = load(docs_path)
text = replace_once(
    text,
    "支持公开 GitHub 根目录、tree 目录、blob/SKILL.md、raw 链接，以及根目录或单一外层目录包含 SKILL.md 的 ZIP。\nGitHub 根链接要求仓库根存在 SKILL.md；多技能仓库应指定具体技能目录。",
    "支持公开 GitHub 根目录、tree 目录、blob/SKILL.md、raw 链接，以及根目录或单一外层目录包含 SKILL.md 的 ZIP。\nGitHub 根链接若仓库根没有 SKILL.md，会通过 GitHub 文件树自动发现唯一的嵌套 SKILL.md；若存在多个 Skill，则要求指定具体 tree 目录。",
    "docs root discovery",
)
text = replace_once(
    text,
    "- 完整保存说明、参考、模板、脚本和其他资源的原始字节；脚本仅作为文件保存，不执行。",
    "- ZIP 导入继续完整保存说明、参考、模板、脚本和其他资源的原始字节；GitHub 安装改为保存固定 commit 的文件清单，引用文件在模型实际读取时按需下载，不再下载/缓存整个仓库压缩包。脚本仅作为可读取文件，不执行。",
    "docs lazy remote",
)
text = replace_once(
    text,
    "- ZIP 10 MB、解压总量 10 MB、单文件 5 MB、文件数 256、目录项 512、安装数量 32。",
    "- 本地 ZIP 仍限制 10 MB、解压总量 10 MB、单文件 5 MB、文件数 256、目录项 512；GitHub 远程 Skill 不再受整个仓库压缩包大小或整包 10 MB 限制，只保留远程单文件 5 MB 与文件树安全上限。安装数量仍为 32。",
    "docs limits",
)
save(docs_path, text)

test_path = "app/src/test/java/com/adong/adchat/data/SkillRuntimeTest.kt"
text = load(test_path)
marker = '''    @Test
    fun nonGitHubAndNonSkillTargetsAreRejected() {'''
addition = '''    @Test
    fun repositoryRootDiscoversExactlyOneNestedSkillWithoutDownloadingArchive() {
        val files = listOf(
            GitHubTreeFile("README.md", "1", 10),
            GitHubTreeFile("skills/dashi-ppt/SKILL.md", "2", 20),
            GitHubTreeFile("skills/dashi-ppt/references/themes.md", "3", 30),
            GitHubTreeFile("other.bin", "4", 40)
        )
        val selected = selectGitHubSkillPath("SKILL.md", allowUniqueDiscovery = true, files)
        assertEquals("skills/dashi-ppt/SKILL.md", selected)
        assertEquals(
            listOf("SKILL.md", "references/themes.md"),
            relativeGitHubSkillFiles(selected, files).map { it.path }
        )
    }

    @Test
    fun repositoryRootWithMultipleSkillsRequiresSpecificTreeDirectory() {
        val files = listOf(
            GitHubTreeFile("skills/a/SKILL.md", "1", 10),
            GitHubTreeFile("skills/b/SKILL.md", "2", 10)
        )
        val error = runCatching { selectGitHubSkillPath("SKILL.md", true, files) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("多个 Skill"))
    }

'''
text = replace_once(text, marker, addition + marker, "SkillRuntime discovery tests")
save(test_path, text)

persistence_path = "app/src/test/java/com/adong/adchat/data/SkillPackagePersistenceTest.kt"
text = load(persistence_path)
marker = '''    @Test fun invalidUpdateKeepsExistingPackageAndLegacyTextRemainsReadable() {'''
addition = '''    @Test fun remoteManifestSurvivesRestartWithoutRepositoryArchivePayload() {
        val context = RuntimeEnvironment.getApplication()
        val source = "https://github.com/example/huge-skill"
        val skill = LoadedSkill(
            name = "huge-skill",
            sourceUrl = source,
            resolvedUrl = "https://raw.githubusercontent.com/example/huge-skill/0123456789012345678901234567890123456789/skills/huge/SKILL.md",
            sha256 = "a".repeat(64),
            content = "---\\nname: huge-skill\\n---\\nRead references/a.md",
            remoteFiles = setOf("SKILL.md", "references/a.md", "project/assets/template.html")
        )
        FileSkillLibrary(context).save(skill)
        val restarted = FileSkillLibrary(context)
        assertEquals(skill.remoteFiles, restarted.list().single().remoteFiles)
        assertTrue(restarted.list().single().files.isEmpty())
        assertEquals(skill.remoteFiles, restarted.find(source)!!.remoteFiles)
    }

'''
text = replace_once(text, marker, addition + marker, "remote manifest persistence test")
save(persistence_path, text)

protocol_test = "app/src/test/java/com/adong/adchat/data/SkillPackageProtocolTest.kt"
text = load(protocol_test)
text = text.replace('.put("path", "references/rules.md").put("offset", 0)', '.put("path", "references/rules.md")')
save(protocol_test, text)

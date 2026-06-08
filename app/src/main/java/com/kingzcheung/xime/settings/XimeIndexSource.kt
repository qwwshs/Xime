package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 安装结果（带失败原因 + 未解决依赖，供 ViewModel 映射文案）。 */
data class InstallResult(
    val success: Boolean,
    val unresolvedDeps: List<String> = emptyList(),
    val failureReason: String? = null,
)

/** 方案列表拉取结果（含命中的来源主机名，供 UI 显示「从哪个端点拉的」）。 */
data class SchemesFetch(
    val schemes: List<MarketSchemeItem>,
    val source: String,
)

/**
 * 方案市场数据源：读取 ximeiorg/xime-index 精选索引（根→子→逐方案，CDN 优先 + raw 回退），
 * 并按版本 sha256 安装；安装后用 [RimeDependencyResolver] 按索引声明的 dependencies 补齐编译依赖。
 * 网络/Android 依赖集中在此层；解析/版本/路径/兼容性逻辑都在 [XimeIndexParser] 纯函数里。
 */
object XimeIndexSource {
    private const val TAG = "XimeIndexSource"
    private const val REPO = "ximeiorg/xime-index"
    private const val BRANCH = "main"

    // Xime 官方索引端点（代理 ximeiorg/xime-index，附正确 text/yaml + CORS，大陆可达性好）。
    // 仅服务索引 .yaml（方案 zip 仍走各 version 的 downloadUrl，直连上游）。
    private const val OFFICIAL_BASE = "https://index.ximei.me/"

    // 顺序即回退优先级：官方端点优先，失败回退 jsDelivr CDN，最后 raw。
    private val MIRRORS = listOf(
        OFFICIAL_BASE,
        "https://fastly.jsdelivr.net/gh/$REPO@$BRANCH/",
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/",
        "https://raw.githubusercontent.com/$REPO/$BRANCH/",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 取文本的结果：成功带命中来源 [base]+[text]；失败时 [error] 记录最后一个源的可读原因。 */
    private data class TextFetch(val base: String? = null, val text: String? = null, val error: String? = null)

    /** 按镜像优先级取一个 repo 相对路径的文本；首个 2xx 即返回，全失败返回带原因的结果。 */
    private fun fetchTextWithSource(repoPath: String): TextFetch {
        var lastError = "网络不可用"
        for (base in MIRRORS) {
            val host = hostOf(base)
            try {
                client.newCall(Request.Builder().url(base + repoPath).build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val s = resp.body?.string()
                        if (!s.isNullOrBlank()) return TextFetch(base = base, text = s)
                        lastError = "$host 返回空内容"
                    } else {
                        lastError = "$host HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) {
                lastError = "$host ${friendlyCause(e)}"
                Log.w(TAG, "fetch $base$repoPath failed: ${e.message}")
            }
        }
        return TextFetch(error = lastError)
    }

    private fun fetchText(repoPath: String): String? = fetchTextWithSource(repoPath).text

    /** 把网络异常转成可读原因（超时 / 无法解析域名 / 无法连接…），免得给用户抛英文堆栈。 */
    private fun friendlyCause(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "无法解析域名(网络不可用?)"
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.ConnectException -> "无法连接"
        is javax.net.ssl.SSLException -> "SSL 错误"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** 镜像 base → 展示用主机名（如 index.ximei.me / fastly.jsdelivr.net）。 */
    private fun hostOf(base: String): String =
        base.substringAfter("://").substringBefore("/")

    /** 跟随索引跳转：根 → 子 → 逐方案（并行、部分失败容忍）。 */
    suspend fun fetchSchemes(appVersion: String): Result<SchemesFetch> =
        withContext(Dispatchers.IO) {
            try {
                val rootFetch = fetchTextWithSource("index.yaml")
                val rootText = rootFetch.text
                    ?: return@withContext Result.failure(
                        IOException("无法连接到方案市场，请检查网络"),
                    )
                val source = hostOf(rootFetch.base!!)
                val root = XimeIndexParser.parseIndex(rootText)
                val subPath = XimeIndexParser.resolveRepoPath(
                    "index.yaml", root.schemas?.from ?: "./rimes/index.yaml",
                )
                val subFetch = fetchTextWithSource(subPath)
                val subText = subFetch.text
                    ?: return@withContext Result.failure(
                        IOException("方案列表加载失败（${subFetch.error}）"),
                    )
                val sub = XimeIndexParser.parseSubIndex(subText)

                val schemes = coroutineScope {
                    sub.schemas.map { entry ->
                        async {
                            val p = XimeIndexParser.resolveRepoPath(subPath, entry.file)
                            val text = fetchText(p) ?: return@async null
                            runCatching { XimeIndexParser.parseScheme(text) }.getOrNull()
                        }
                    }.awaitAll()
                }.filterNotNull()
                    .distinctBy { it.id }
                    .map { XimeIndexParser.toItem(it, appVersion) }

                Result.success(SchemesFetch(schemes, source))
            } catch (e: Exception) {
                Log.e(TAG, "fetchSchemes failed", e)
                Result.failure(e)
            }
        }

    /**
     * 安装一个方案：按版本 downloadUrl（+sha256）落盘，再按索引声明依赖补齐编译依赖。
     * @param resolveDepUrl 依赖包 id → 下载 URL（由调用方从已取的方案列表构造）。
     */
    suspend fun installScheme(
        context: Context,
        scheme: MarketScheme,
        resolveDepUrl: (String) -> String? = { null },
    ): InstallResult = withContext(Dispatchers.IO) {
        val v = scheme.resolvedVersion()
            ?: return@withContext InstallResult(false, failureReason = "无可用版本")
        if (v.downloadUrl.isBlank()) {
            return@withContext InstallResult(false, failureReason = "缺少下载地址")
        }

        val before = SchemaManager.discoverSchemas(context).map { it.schemaId }.toSet()
        val ok = SchemaManager.importFromUrl(context, v.downloadUrl, v.sha256)
        if (!ok) return@withContext InstallResult(false, failureReason = "安装失败或文件校验失败")

        // 找到新落盘的真实 rime schema id（索引 id 不保证等于 rime schema_id / 文件名）
        val after = SchemaManager.discoverSchemas(context).map { it.schemaId }.toSet()
        val newSchemaId = (after - before).firstOrNull() ?: scheme.id

        // 依赖补齐（不剥离）：按索引声明的 dependencies 递归补齐，让方案带反查完整编译
        val completion = RimeDependencyResolver.complete(
            context = context,
            schemaId = newSchemaId,
            dependencies = scheme.dependencies,
            resolveUrl = resolveDepUrl,
        )
        val unresolved = (completion.unresolved + completion.stillMissingFiles).distinct()
        if (unresolved.isNotEmpty()) Log.w(TAG, "install ${scheme.id}: unresolved=$unresolved")
        InstallResult(success = true, unresolvedDeps = unresolved)
    }
}

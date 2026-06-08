package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.MarketSchemeItem
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.XimeIndexSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SchemaMarketUiState(
    val schemes: List<MarketSchemeItem> = emptyList(),
    val isLoading: Boolean = false,
    val installingId: String? = null,
    val installedIds: Set<String> = emptySet(),
    val isDeploying: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val searchQuery: String = "",
    // 本次方案列表实际命中的来源端点主机名（如 index.ximei.me），用于在界面上显示「从哪个端点拉的」
    val source: String = "",
) {
    val filteredSchemes: List<MarketSchemeItem>
        get() = if (searchQuery.isBlank()) schemes else schemes.filter {
            val q = searchQuery.trim()
            it.scheme.name.contains(q, true) ||
                it.scheme.description.contains(q, true) ||
                it.scheme.tags.any { t -> t.contains(q, true) }
        }
}

class SchemaMarketViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(SchemaMarketUiState())
    val uiState: StateFlow<SchemaMarketUiState> = _uiState.asStateFlow()

    init {
        loadSchemes()
    }

    /** 加载/刷新方案列表。[manual] 为 true 时（用户点刷新）成功也弹 toast 并报告来源端点。 */
    fun loadSchemes(manual: Boolean = false) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = XimeIndexSource.fetchSchemes(BuildConfig.VERSION_NAME)
            // 「已安装」用持久记录(市场主动安装过的 id)，不靠本地文件存在性
            // —— 方案可能仅作为依赖落盘，文件存在 ≠ 用户装过它。
            val installedRecord = SettingsPreferences.getInstalledMarketIds(context)
            result.onSuccess { fetch ->
                if (fetch.schemes.isEmpty()) {
                    // 索引可达但没取到任何方案：当作软失败处理，不要用空列表覆盖已有数据
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            source = fetch.source,
                            errorMessage = if (it.schemes.isEmpty())
                                "未获取到方案（来源：${fetch.source}），请检查网络后刷新" else it.errorMessage,
                            toastMessage = if (it.schemes.isEmpty()) "未获取到方案（来源：${fetch.source}）"
                                else "刷新未获取到方案，已保留当前列表",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            schemes = fetch.schemes,
                            isLoading = false,
                            source = fetch.source,
                            errorMessage = null,
                            installedIds = fetch.schemes.map { item -> item.scheme.id }
                                .filter { id -> id in installedRecord }.toSet(),
                            toastMessage = if (manual) "已刷新 · 来源：${fetch.source}" else it.toastMessage,
                        )
                    }
                }
            }.onFailure { e ->
                val msg = e.message ?: "加载方案市场失败"
                // 失败一律弹 toast；已有列表时还保留旧数据，空列表时另外保留整页错误+重试
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (it.schemes.isEmpty()) msg else it.errorMessage,
                        toastMessage = if (it.schemes.isEmpty()) msg else "刷新失败：$msg",
                    )
                }
            }
        }
    }

    fun setSearchQuery(q: String) = _uiState.update { it.copy(searchQuery = q) }

    fun installScheme(item: MarketSchemeItem) {
        if (_uiState.value.installingId != null) return
        if (!item.compatible) {
            showToast("需 App ≥ ${item.minAppVersion}")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(installingId = item.scheme.id) }
            // 依赖包 id → 下载 URL，取自已加载的方案列表（含登记为库包的条目）
            val urlById = _uiState.value.schemes.associate { si ->
                si.scheme.id to si.scheme.resolvedVersion()?.downloadUrl
            }
            val result = withContext(Dispatchers.IO) {
                XimeIndexSource.installScheme(context, item.scheme) { id ->
                    urlById[id]?.takeIf { it.isNotBlank() }
                }
            }
            if (result.success) {
                // 持久记录"通过市场主动安装"，跨重启保持，且与依赖/文件存在性解耦
                SettingsPreferences.addInstalledMarketId(context, item.scheme.id)
            }
            _uiState.update { st ->
                st.copy(
                    installingId = null,
                    installedIds = if (result.success) st.installedIds + item.scheme.id else st.installedIds,
                )
            }
            val msg = when {
                !result.success -> result.failureReason ?: "安装失败"
                result.unresolvedDeps.isNotEmpty() ->
                    "已安装，部分依赖未获取：${result.unresolvedDeps.joinToString("、").take(60)}"
                else -> "已安装「${item.scheme.name}」，点「部署」生效"
            }
            showToast(msg)
        }
    }

    /** 部署(全局编译已启用方案)。rime 部署是全局的,装好后点一次即可让方案生效。 */
    fun deploy() {
        if (_uiState.value.isDeploying) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeploying = true) }
            val success = withContext(Dispatchers.IO) {
                val engine = RimeEngine.getInstance()
                engine.startMaintenance(false)
                var waited = 0L
                while (engine.isMaintaining() && waited < 120_000L) {
                    Thread.sleep(100)
                    waited += 100
                }
                val done = !engine.isMaintaining()
                if (done) engine.updateLastBuildTime()
                done
            }
            _uiState.update { it.copy(isDeploying = false) }
            showToast(if (success) "部署完成" else "部署失败")
        }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    private fun showToast(message: String) = _uiState.update { it.copy(toastMessage = message) }
}

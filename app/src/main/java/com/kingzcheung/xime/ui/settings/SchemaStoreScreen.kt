package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SchemaStoreFetcher
import com.kingzcheung.xime.settings.SchemaStoreInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaStoreScreen(
    onBack: () -> Unit,
    onRefreshSchemas: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var schemas by remember { mutableStateOf<List<SchemaStoreInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }

    // 已安装的方案 ID 列表
    val installedSchemas = remember {
        SchemaManager.discoverSchemas(context).map { it.schemaId }.toSet()
    }

    fun loadSchemas() {
        scope.launch {
            isLoading = true
            errorMessage = null
            val result = SchemaStoreFetcher.fetchAllSchemas()
            result.onSuccess { list ->
                schemas = list
                isLoading = false
            }.onFailure { e ->
                errorMessage = e.message ?: "加载失败"
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadSchemas() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("方案市场") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "正在加载方案列表...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                errorMessage != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { loadSchemas() }) {
                                Text("重试")
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(
                            schemas.size,
                            key = { index -> "${schemas[index].id}_$index" }) { index ->
                            val schema = schemas[index]
                            SchemaStoreItem(
                                schema = schema,
                                isInstalled = schema.id in installedSchemas,
                                isDownloading = downloadingId == schema.id,
                                onDownload = {
                                    val downloadUrl = schema.versions.firstOrNull()?.downloadUrl
                                    if (downloadUrl.isNullOrBlank()) {
                                        Toast.makeText(context, "暂无下载地址", Toast.LENGTH_SHORT)
                                            .show()
                                        return@SchemaStoreItem
                                    }
                                    scope.launch {
                                        downloadingId = schema.id
                                        val success =
                                            SchemaManager.importFromUrl(context, downloadUrl)
                                        downloadingId = null
                                        if (success) {
                                            Toast.makeText(
                                                context,
                                                "${schema.name} 下载成功",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onRefreshSchemas()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "${schema.name} 下载失败",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemaStoreItem(
    schema: SchemaStoreInfo,
    isInstalled: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = schema.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isInstalled) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = schema.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (schema.author.isNotBlank()) {
                    Text(
                        text = schema.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (schema.description.isNotBlank()) {
                    Text(
                        text = schema.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (schema.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        schema.tags.take(3).forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (isInstalled) {
                Text(
                    text = "已安装",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Button(
                    onClick = onDownload,
                    enabled = !isDownloading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("下载", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

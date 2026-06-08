package com.kingzcheung.xime.settings

import android.util.Log
import com.charleskorn.kaml.Yaml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object SchemaStoreFetcher {
    private const val TAG = "SchemaStoreFetcher"
    private const val INDEX_URL = "https://index.ximei.me/rimes/index.yaml"
    private const val SCHEMA_BASE_URL = "https://index.ximei.me/rimes"

    suspend fun fetchIndex(): Result<SchemaIndex> = withContext(Dispatchers.IO) {
        try {
            val yaml = URL(INDEX_URL).readText()
            val index = Yaml.default.decodeFromString(SchemaIndex.serializer(), yaml)
            Log.d(TAG, "Fetched index: ${index.schemas.size} schemas")
            Result.success(index)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch index", e)
            Result.failure(e)
        }
    }

    suspend fun fetchSchemaInfo(schemaFile: String): Result<SchemaStoreInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "$SCHEMA_BASE_URL/${schemaFile.removePrefix("./")}"
            val yaml = URL(url).readText()
            val info = Yaml.default.decodeFromString(SchemaStoreInfo.serializer(), yaml)
            Log.d(TAG, "Fetched schema info: ${info.id} (${info.name})")
            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch schema info from $schemaFile", e)
            Result.failure(e)
        }
    }

    suspend fun fetchAllSchemas(): Result<List<SchemaStoreInfo>> = withContext(Dispatchers.IO) {
        try {
            val index = fetchIndex().getOrElse { return@withContext Result.failure(it) }
            val results = index.schemas.map { entry ->
                val schemaFile = entry.file.removePrefix("./")
                fetchSchemaInfo(schemaFile).getOrNull()
            }
            val schemas = results.filterNotNull()
            Log.d(TAG, "Fetched ${schemas.size}/${index.schemas.size} schema details")
            Result.success(schemas)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch all schemas", e)
            Result.failure(e)
        }
    }
}

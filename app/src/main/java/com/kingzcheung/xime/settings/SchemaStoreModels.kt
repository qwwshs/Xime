package com.kingzcheung.xime.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SchemaIndex(
    @SerialName("index_version")
    val indexVersion: Int = 1,
    @SerialName("updated_at")
    val updatedAt: String = "",
    val schemas: List<SchemaIndexEntry> = emptyList()
)

@Serializable
data class SchemaIndexEntry(
    val file: String = "",
    val version: String = ""
)

@Serializable
data class SchemaStoreInfo(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val description: String = "",
    val type: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("appVersion")
    val appVersion: String = "",
    @SerialName("currentVersion")
    val currentVersion: String = "",
    val versions: List<SchemaVersionInfo> = emptyList()
)

@Serializable
data class SchemaVersionInfo(
    val version: String = "",
    val date: String = "",
    val changelog: String = "",
    @SerialName("downloadUrl")
    val downloadUrl: String = "",
    val size: String = "",
    val sha256: String = ""
)

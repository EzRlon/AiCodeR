package com.aicode.feature.backup.domain

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.schedule.domain.ScheduledTask
import com.aicode.feature.settings.data.repository.ContainerSettingsSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMetadataTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun chatBackupDoesNotIncludeAppSettings() {
        val metadata = BackupMetadata(schemaVersion = 55, createdAt = 0)
        val restored = json.decodeFromString<BackupMetadata>(json.encodeToString(BackupMetadata.serializer(), metadata))
        assertFalse(restored.includesAppSettings)
    }

    @Test
    fun legacyMetadataInfersSettingsFromThemeMode() {
        assertTrue(json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":55,"createdAt":0,"themeMode":"DARK"}"""
        ).includesAppSettings)
        assertFalse(json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":55,"createdAt":0,"themeMode":null}"""
        ).includesAppSettings)
    }

    @Test
    fun explicitSettingsScopeOverridesLegacyInference() {
        assertFalse(json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":55,"createdAt":0,"themeMode":"DARK","includesAppSettings":false}"""
        ).includesAppSettings)
    }

    @Test
    fun legacySnapshotPreservesSettingsScope() {
        assertFalse(BackupSnapshot(schemaVersion = 55, createdAt = 0).toMetadata().includesAppSettings)
        assertTrue(BackupSnapshot(schemaVersion = 55, createdAt = 0, themeMode = "AUTO").toMetadata().includesAppSettings)
    }

    @Test
    fun legacyMetadataDefaultsNewSections() {
        val restored = json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":66,"createdAt":0}"""
        )
        assertTrue(restored.scheduledTasks.isEmpty())
        assertTrue(restored.toolPermissionTiers.isEmpty())
        assertNull(restored.watchModeArmed)
        assertNull(restored.containerSettings)
    }

    @Test
    fun newSectionsRoundTrip() {
        val metadata = BackupMetadata(
            schemaVersion = 66,
            createdAt = 0,
            scheduledTasks = listOf(ScheduledTask(id = "t1", name = "每日构建", command = "gradle build")),
            toolPermissionTiers = mapOf("readFile" to "auto"),
            watchModeArmed = false,
            containerSettings = ContainerSettingsSnapshot(
                activeProfileId = "builtin-alpine",
                defaultContainerId = "builtin-alpine",
                customProfiles = listOf(
                    ContainerProfile(
                        id = "c1",
                        name = "自定义",
                        rootfsSource = RootfsSource.Asset("a.bin"),
                        shellPath = null,
                        isBuiltin = false
                    )
                )
            )
        )
        val restored = json.decodeFromString<BackupMetadata>(
            json.encodeToString(BackupMetadata.serializer(), metadata)
        )
        assertEquals(1, restored.scheduledTasks.size)
        assertEquals("t1", restored.scheduledTasks.first().id)
        assertEquals(mapOf("readFile" to "auto"), restored.toolPermissionTiers)
        // false 与 null 语义不同（false = 明确关闭，null = 备份未包含），round-trip 必须保真。
        assertEquals(false, restored.watchModeArmed)
        assertEquals("builtin-alpine", restored.containerSettings?.activeProfileId)
        assertEquals(1, restored.containerSettings?.customProfiles?.size)
    }
}

package com.adhook.ui

import com.adhook.config.model.AppConfig
import com.adhook.config.model.FeatureMeta
import com.adhook.ui.data.*
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppListRepositoryTest {
    @Test fun pluginTargetEnablesSystemHostAndUsesItsFeatures() = runBlocking {
        val feature = FeatureMeta("feed", "Feed", "")
        val catalog = object : AppCatalog {
            override fun installedApps() = listOf(
                InstalledApp("com.normal", "Normal"),
                InstalledApp("com.system.host", "System Host", true),
                InstalledApp("com.adhook", "Self"),
                InstalledApp("android", "Android", true)
            )
            override fun pluginApps() = listOf(
                PluginTargetApp("com.system.host", "System Host", "plugin", "Plugin", "1.0", true, listOf(feature))
            )
        }
        val config = AppConfig("com.normal").apply { isInspectorEnabled = true }
        val loaded = AppListRepository(catalog, AppConfigReader { mapOf("com.normal" to config) }).load()
        assertEquals(listOf("com.normal", "com.system.host"), loaded.apps.map { it.pkg })
        assertEquals(1, loaded.pluginCount)
        assertEquals(1, loaded.apps.single { it.pkg == "com.system.host" }.totalNative)
        assertNull(loaded.apps.single { it.pkg == "com.normal" }.pluginId)
        assertTrue(loaded.apps.single { it.pkg == "com.normal" }.isInspectorOn)
    }

    @Test fun packageQueriesAndSnapshotsRunOffCallerThreadWithoutCreatingConfigs() = runBlocking {
        val caller = Thread.currentThread()
        val snapshots = linkedMapOf<String, AppConfig>()
        val observedThreads = mutableListOf<Thread>()
        val catalog = object : AppCatalog {
            override fun installedApps(): List<InstalledApp> {
                observedThreads.add(Thread.currentThread())
                return listOf(InstalledApp("com.new.host", "New"), InstalledApp("com.system.app", "System", true), InstalledApp("com.adhook", "Self"))
            }
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val repository = AppListRepository(catalog, AppConfigReader {
                observedThreads.add(Thread.currentThread()); snapshots.toMap()
            }, io)
            val loaded = repository.load()
            assertEquals(listOf("com.new.host"), loaded.apps.map { it.pkg })
            assertEquals(0, loaded.apps.single().customCount)
            assertFalse(loaded.apps.single().isInspectorOn)
            assertTrue(snapshots.isEmpty())
            assertTrue(observedThreads.all { it != caller })
        }
    }

    @Test fun pluginFeaturesKeepAliasesAndDropUnimplementedEntries() = runBlocking {
        val primary = "com.list.primary"
        val variant = "com.list.variant"
        val features = listOf(FeatureMeta("feed", "Feed", ""), FeatureMeta("missing", "Missing", "", true, false))
        com.adhook.core.FeatureGuard.registerAlias(primary, "feed", "old-feed")
        com.adhook.core.FeatureGuard.registerCatalog(variant, primary, features)
        val config = AppConfig(variant).apply { setFeatureEnabled("old-feed", false) }
        val catalog = object : AppCatalog {
            override fun installedApps() = listOf(InstalledApp(variant, "Variant", true))
            override fun pluginApps() = listOf(
                PluginTargetApp(variant, "Variant", "plugin", "Plugin", "1.0", true, features)
            )
        }
        val app = AppListRepository(catalog, AppConfigReader { mapOf(variant to config) }).load().apps.single()
        assertEquals(1, app.totalNative)
        assertEquals(0, app.enabledNative)
        assertEquals(1, config.nativeFeatures.size)
    }

    @Test fun hiddenSystemAppsStayHiddenAndTheFirstPluginWins() = runBlocking {
        val catalog = object : AppCatalog {
            override fun installedApps() = listOf(
                InstalledApp("com.a", "A"),
                InstalledApp("com.system.hidden", "Hidden", true)
            )
            override fun pluginApps() = listOf(
                PluginTargetApp("com.a", "A", "first", "First", "1.0", true, emptyList()),
                PluginTargetApp("com.a", "A", "second", "Second", "2.0", true, emptyList()),
                PluginTargetApp("com.absent", "Absent", "third", "Third", "3.0", true, emptyList())
            )
        }
        val loaded = AppListRepository(catalog, AppConfigReader { emptyMap() }).load()
        assertEquals(listOf("com.a"), loaded.apps.map { it.pkg })
        assertEquals("first", loaded.apps.single().pluginId)
        assertEquals(1, loaded.pluginCount)
    }
}

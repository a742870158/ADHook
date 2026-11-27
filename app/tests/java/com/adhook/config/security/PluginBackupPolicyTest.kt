package com.adhook.config.security

import android.content.Context
import com.adhook.config.backup.BackupManager
import com.adhook.config.model.GlobalConfig
import com.adhook.plugin.InstalledPlugin
import com.adhook.plugin.PluginMarketSource
import com.adhook.plugin.PluginSourceStore
import com.adhook.plugin.PluginStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Plugin metadata travels in backups as sources + installed identities only.
 * Binaries, publisher trust pins and host authorization must never be importable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PluginBackupPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setup() {
        resetManagers()
        context = newContext("files")
    }

    private fun newContext(folder: String): Context {
        val app: android.app.Application = RuntimeEnvironment.getApplication()
        val files = temporary.newFolder(folder)
        return object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): java.io.File = files
            override fun getDataDir(): java.io.File = temporary.root
            override fun createDeviceProtectedStorageContext(): Context = this
        }
    }

    @After fun teardown() {
        runCatching {
            val cm = com.adhook.config.ConfigManager.getInstance(context)
            val executor = com.adhook.config.ConfigManager::class.java.getDeclaredField("ioExecutor").let {
                it.isAccessible = true; it.get(cm) as java.util.concurrent.ExecutorService
            }
            executor.shutdown()
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        resetManagers()
    }

    private fun resetManagers() {
        for ((type, field) in listOf(
            com.adhook.config.ConfigManager::class.java to "sInstance",
            com.adhook.config.subscription.SubscriptionManager::class.java to "instance",
            PluginStore::class.java to "instance",
            PluginSourceStore::class.java to "instance"
        )) {
            runCatching { type.getDeclaredField(field).apply { isAccessible = true }.set(null, null) }
        }
    }

    private fun installed(id: String, versionCode: Int, enabled: Boolean): InstalledPlugin =
        InstalledPlugin(id, "测试插件", "1.0.0", versionCode, 1, 1,
            "com.adhook.plugin.sample.SampleHook", "作者", "描述", emptyList(), emptyList(),
            "", enabled, System.currentTimeMillis(), "0011223344556677", "fingerprint",
            "a".repeat(64), "b".repeat(64), "{}")

    @Test fun backupCarriesSourcesAndInstalledIdentityOnly() {
        PluginSourceStore.get(context).upsert(
            PluginMarketSource(7L, "社区镜像", "https://example.com/plugins/index.json", true, 0L, ""))
        assertTrue(PluginStore.get(context).upsert(installed("com.adhook.plugin.alpha", 12, true)))

        val root = JSONObject(BackupManager.createBackupJson(context))
        assertEquals(3, root.getInt("schema"))
        val plugins = root.getJSONObject("plugins")
        assertEquals(1, plugins.getJSONArray("sources").length())
        assertEquals(1, plugins.getJSONArray("installed").length())
        val installedEntry = plugins.getJSONArray("installed").getJSONObject(0)
        assertEquals("com.adhook.plugin.alpha", installedEntry.getString("id"))
        assertEquals(12, installedEntry.getInt("versionCode"))
        // No binaries, trust pins, fingerprints or host authorization grants are exportable.
        val raw = plugins.toString()
        assertFalse(raw.contains("publicKey"))
        assertFalse(raw.contains("fingerprint"))
        assertFalse(raw.contains("dexSha256"))
        assertFalse(raw.contains("hostAuthorization"))
        assertEquals(1, BackupManager.summarize(root.toString())!!.pluginSourceCount)
        assertEquals(1, BackupManager.summarize(root.toString())!!.installedPluginCount)
    }

    @Test fun restoreAddsSourcesButNeverInstallsMissingPlugins() {
        val source = PluginSourceStore.get(context)
        source.upsert(PluginMarketSource(7L, "社区镜像", "https://example.com/plugins/index.json", true, 0L, ""))
        PluginStore.get(context).upsert(installed("com.adhook.plugin.alpha", 12, true))
        val backup = BackupManager.createBackupJson(context)

        // Simulate a different device: new files directory, no installed plugin binaries.
        resetManagers()
        val other = newContext("files2")
        val restored = BackupManager.restore(other, backup).getOrThrow()
        assertEquals(1, restored.pluginSourceCount)
        assertEquals(0, restored.installedPluginCount)
        assertEquals(1, restored.pendingPluginReinstall)
        assertTrue(restored.warnings.any { it.contains("重新安装") })
        assertEquals(1, PluginSourceStore.get(other).count())
        assertEquals(0, PluginStore.get(other).count())
    }

    @Test fun restoreAppliesEnableFlagForLocallyInstalledPlugin() {
        PluginStore.get(context).upsert(installed("com.adhook.plugin.alpha", 12, true))
        val backup = JSONObject(BackupManager.createBackupJson(context))
        backup.getJSONObject("plugins").getJSONArray("installed")
            .getJSONObject(0).put("enabled", false)
        assertTrue(PluginStore.get(context).setEnabled("com.adhook.plugin.alpha", true))

        val restored = BackupManager.restore(context, backup.toString()).getOrThrow()
        assertEquals(1, restored.installedPluginCount)
        assertFalse(PluginStore.get(context).find("com.adhook.plugin.alpha")!!.isEnabled)
    }

    @Test fun invalidPluginMetadataRejectsWholeBackupBeforeRestore() {
        val valid = JSONObject().put("type", BackupManager.BACKUP_TYPE).put("schema", BackupManager.SCHEMA_VERSION)
            .put("config", JSONObject().put("global_sdk", GlobalConfig().toJson()).put("apps", JSONObject()))
            .put("subscriptions", JSONArray()).put("subscription_overrides", JSONObject())
            .put("subscription_meta", JSONObject())
            .put("plugins", JSONObject().put("schema", 1)
                .put("sources", JSONArray()).put("installed", JSONArray()))
        assertNotNull(BackupManager.summarize(valid.toString()))

        val unknownKey = JSONObject(valid.toString())
        unknownKey.getJSONObject("plugins").put("trust", JSONObject())
        assertNull(BackupManager.summarize(unknownKey.toString()))

        val badUrl = JSONObject(valid.toString())
        badUrl.getJSONObject("plugins").put("sources", JSONArray().put(
            JSONObject().put("id", 1).put("name", "x").put("url", "file:///tmp/index.json").put("enabled", true)))
        assertNull(BackupManager.summarize(badUrl.toString()))

        val duplicate = JSONObject(valid.toString())
        val entry = JSONObject().put("id", "com.adhook.plugin.alpha").put("versionCode", 1).put("enabled", true)
        duplicate.getJSONObject("plugins").put("installed", JSONArray().put(entry).put(entry))
        assertNull(BackupManager.summarize(duplicate.toString()))
    }

    @Test fun oldBackupWithoutPluginBlockRemainsValid() {
        val old = JSONObject().put("type", BackupManager.BACKUP_TYPE).put("schema", 2)
            .put("config", JSONObject().put("global_sdk", GlobalConfig().toJson()).put("apps", JSONObject()))
            .put("subscriptions", JSONArray()).put("subscription_overrides", JSONObject())
            .put("subscription_meta", JSONObject())
        val summary = BackupManager.summarize(old.toString())!!
        assertEquals(0, summary.pluginSourceCount)
        val restored = BackupManager.restore(context, old.toString()).getOrThrow()
        assertEquals(0, restored.installedPluginCount)
    }
}

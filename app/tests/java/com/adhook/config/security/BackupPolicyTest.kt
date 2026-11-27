package com.adhook.config.security

import com.adhook.config.backup.BackupManager
import com.adhook.config.model.AppConfig
import com.adhook.config.model.CustomRule
import com.adhook.config.model.GlobalConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class BackupPolicyTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()
    private lateinit var context: android.content.Context
    private lateinit var cm: com.adhook.config.ConfigManager
    @org.junit.Before fun setupPersistence() {
        resetManagers()
        val app: android.app.Application = org.robolectric.RuntimeEnvironment.getApplication()
        val files = temporary.newFolder("files")
        context = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = files
            override fun getDataDir(): java.io.File = temporary.root
            override fun createDeviceProtectedStorageContext(): android.content.Context = this
        }
        cm = com.adhook.config.ConfigManager.getInstance(context)
    }
    @org.junit.After fun stopPersistence() {
        if (::cm.isInitialized) {
            val executor = com.adhook.config.ConfigManager::class.java.getDeclaredField("ioExecutor").let {
                it.isAccessible = true; it.get(cm) as java.util.concurrent.ExecutorService
            }
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        resetManagers()
    }
    private fun resetManagers() {
        for ((type, field) in listOf(com.adhook.config.ConfigManager::class.java to "sInstance",
            com.adhook.config.subscription.SubscriptionManager::class.java to "instance"))
            type.getDeclaredField(field).apply { isAccessible = true }.set(null, null)
    }

    private fun backup(): JSONObject = JSONObject().put("type", BackupManager.BACKUP_TYPE).put("schema", 1)
        .put("config", JSONObject().put("global_sdk", GlobalConfig().toJson()).put("apps", JSONObject()))
        .put("subscriptions", JSONArray()).put("subscription_overrides", JSONObject()).put("subscription_meta", JSONObject())

    @Test fun schemaTwoBackupAndRestoreRoundTripConfiguration() {
        val app = AppConfig("com.host")
        app.addCustomRule(CustomRule("banner", "res_id", "com.host:id/banner", "collapse"))
        cm.getAppConfig("com.host")!!.addCustomRule(app.customRules.single())
        assertTrue(cm.save()); assertTrue(cm.awaitPendingWrites())
        val saved = BackupManager.createBackupJson(context)
        val root = JSONObject(saved)
        assertEquals(3, root.getInt("schema"))
        assertEquals("banner", root.getJSONObject("config").getJSONObject("apps")
            .getJSONObject("com.host").getJSONArray("customRules").getJSONObject(0).getString("name"))
        val summary = BackupManager.summarize(saved)!!
        assertEquals(1, summary.customRuleCount)
        val restored = BackupManager.restore(context, saved).getOrThrow()
        assertTrue(java.io.File(restored.preRestoreBackupPath!!).isFile)
        assertEquals("banner", cm.getAppConfigSnapshot("com.host")!!.customRules.single().name)
    }

    @Test fun oldSchemaAndUnknownTopLevelFieldsRemainReadable() {
        val old = backup().put("legacy_payload", JSONObject().put("value", 1))
        assertNotNull(BackupManager.summarize(old.toString()))
        val restored = BackupManager.restore(context, old.toString()).getOrThrow()
        assertEquals(0, restored.appCount)
        assertEquals(emptyList<String>(), restored.warnings)
    }

    @Test fun corruptConfigIsRejectedBeforeAnyProductionRestoreMutation() {
        val current = AppConfig("com.host")
        current.addCustomRule(CustomRule("keep", "text", "广告", "collapse"))
        cm.getAppConfig("com.host")!!.addCustomRule(current.customRules.single())
        assertTrue(cm.save()); assertTrue(cm.awaitPendingWrites())
        val incoming = JSONObject(BackupManager.createBackupJson(context))
        incoming.getJSONObject("config").getJSONObject("apps").getJSONObject("com.host")
            .getJSONArray("customRules").getJSONObject(0).put("trimText", "false")
        assertNull(BackupManager.summarize(incoming.toString()))
        assertTrue(BackupManager.restore(context, incoming.toString()).isFailure)
        assertEquals("keep", cm.getAppConfigSnapshot("com.host")!!.customRules.single().name)
        assertFalse(java.io.File(context.filesDir, "backup").exists())
    }

    @Test fun validatesCompleteSnapshotBeforeSummarizing() {
        val valid = backup()
        assertNotNull(BackupManager.summarize(valid.toString()))
        valid.remove("config")
        assertNull(BackupManager.summarize(valid.toString()))
        assertNull(BackupManager.summarize(backup().put("subscription_overrides", "not an object").toString()))
        assertNull(BackupManager.summarize(backup().put("subscriptions", JSONObject()).toString()))
        assertNull(BackupManager.summarize(backup().put("subscription_overrides", JSONObject().put("disabledRuleIds", "wrong")).toString()))
    }

    @Test fun rejectsInvalidPackageOrFeatureDataInsteadOfSilentlyDroppingIt() {
        val root = backup()
        root.getJSONObject("config").getJSONObject("apps").put("com..bad", JSONObject())
        assertNull(BackupManager.summarize(root.toString()))
        val config = AppConfig("com.host").toJson().put("nativeFeatures", JSONObject().put("key", "true"))
        assertFalse(ConfigSnapshotPolicy.isValid(JSONObject().put("global_sdk", GlobalConfig().toJson()).put("apps", JSONObject().put("com.host", config))))
    }

    @Test fun backupDoesNotApplyProviderOnlyNameAndActivityLengthLimits() {
        val config = AppConfig("com.host")
        config.addCustomRule(CustomRule("name".repeat(100), "text", "Skip", "collapse").apply {
            activityFilter = (1..40).joinToString(",") { "com.host.screen.Activity$it" }
        })
        val root = backup()
        root.getJSONObject("config").getJSONObject("apps").put("com.host", config.toJson())
        assertNotNull(BackupManager.summarize(root.toString()))
        root.getJSONObject("config").getJSONObject("apps").getJSONObject("com.host")
            .getJSONArray("customRules").getJSONObject(0).put("trimText", "false")
        assertNull(BackupManager.summarize(root.toString()))
    }

    @Test fun snapshotDefaultsRetainPreciseRuleConstraints() {
        val config = AppConfig("com.host")
        config.addCustomRule(CustomRule("skip", "text", "Skip", "collapse").apply {
            textProperty = "desc"; textMatchMode = "equals"; isTrimText = false; isExactActivityFilter = true
        })
        val root = backup()
        root.getJSONObject("config").getJSONObject("apps").put("com.host", config.toJson())
        assertEquals(1, BackupManager.summarize(root.toString())!!.customRuleCount)
        val copied = config.copy().customRules.single()
        assertEquals("desc", copied.textProperty); assertEquals("equals", copied.textMatchMode)
        assertFalse(copied.isTrimText); assertTrue(copied.isExactActivityFilter)
    }
}

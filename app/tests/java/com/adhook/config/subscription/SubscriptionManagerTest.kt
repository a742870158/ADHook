package com.adhook.config.subscription

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.adhook.config.ConfigManager
import com.adhook.config.model.CustomRule
import com.adhook.config.model.Subscription
import com.adhook.config.model.SubscriptionApp
import com.adhook.config.model.SubscriptionRuleItem
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
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Runs the actual Android persistence adapter as well as the pure reconciler; no network/device is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SubscriptionManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var cm: ConfigManager
    private lateinit var manager: SubscriptionManager
    private val pkg = "com.example"

    @Before fun setUp() {
        resetSingleton(ConfigManager::class.java, "sInstance")
        resetSingleton(SubscriptionManager::class.java, "instance")
        val application: Application = RuntimeEnvironment.getApplication()
        val files = temporary.newFolder("files")
        context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDataDir(): File = temporary.root
            override fun createDeviceProtectedStorageContext(): Context = this
        }
        cm = ConfigManager.getInstance(context)
        manager = SubscriptionManager.getInstance(context)
    }

    @After fun tearDown() {
        if (::cm.isInitialized) {
            val executor = ConfigManager::class.java.getDeclaredField("ioExecutor").let {
                it.isAccessible = true; it.get(cm) as ExecutorService
            }
            executor.shutdown()
            assertTrue("config writes should finish before removing test files", executor.awaitTermination(10, TimeUnit.SECONDS))
        }
        resetSingleton(SubscriptionManager::class.java, "instance")
        resetSingleton(ConfigManager::class.java, "sInstance")
    }

    @Test fun realApplyUpdatesAllFieldsAndAssignsRemoteSource() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val first = rules().single()
        assertEquals("remote", first.source)
        assertEquals("42", first.subscriptionId)
        assertEquals(pkg, first.subscriptionPackage)
        val changed = item("r", "Close").copy(name = "changed", type = "text", action = "collapse", parentDepth = 2,
            activityFilter = "com.example.Splash", maxTextLength = 8, textProperty = "desc", textMatchMode = "equals",
            trimText = false, exactActivityFilter = true, exactResourceId = true)
        assertTrue(manager.addSubscription(subscription(changed)))
        val latest = rules().single()
        assertEquals(first.id, latest.id)
        assertTrue(RuleReconciler.sameMatcher(changed.toCustomRule("remote"), latest))
        assertEquals("changed", latest.name)
        assertEquals(first.createTime, latest.createTime)
    }

    @Test fun withdrawalDisabledItemAndSubscriptionRemovalNeverRemoveLocalRules() {
        val local = CustomRule("same name", "res_id", "same", "collapse").also { it.id = "my-local" }
        cm.getAppConfig(pkg)!!.addCustomRule(local)
        assertTrue(manager.addSubscription(subscription(item("r", "same").copy(name = "same name"))))
        assertEquals(2, rules().size)
        assertTrue(manager.setSubscriptionEnabled(42, false))
        assertEquals(listOf("my-local"), rules().map { it.id })
        assertTrue(manager.setSubscriptionEnabled(42, true))
        assertTrue(manager.addSubscription(subscription(item("r", "same").copy(enabled = false))))
        assertEquals(listOf("my-local"), rules().map { it.id })
        assertTrue(manager.addSubscription(subscription()))
        assertEquals(listOf("my-local"), rules().map { it.id })
        assertTrue(manager.deleteSubscription(42))
        assertEquals(listOf("my-local"), rules().map { it.id })
    }

    @Test fun localMasterSwitchNeverRewritesIndividualChoicesOrExportsRemoteRules() {
        val disabled = CustomRule("off", "text", "local off", "collapse").also { it.isEnabled = false; it.id = "off" }
        val enabled = CustomRule("on", "text", "local on", "collapse").also { it.isEnabled = true; it.id = "on" }
        cm.getAppConfig(pkg)!!.addCustomRule(disabled)
        cm.getAppConfig(pkg)!!.addCustomRule(enabled)
        assertTrue(manager.addSubscription(subscription(item("remote", "remote.target"))))
        assertTrue(manager.setLocalRulesEnabled(false))
        assertTrue(rules().single { it.source == "remote" }.isEnabled)
        assertFalse(rules().first { it.id == "off" }.isEnabled)
        assertTrue(rules().first { it.id == "on" }.isEnabled)
        assertTrue(manager.setLocalRulesEnabled(true))
        assertFalse(rules().first { it.id == "off" }.isEnabled)
        assertTrue(rules().first { it.id == "on" }.isEnabled)
        val exported = manager.getLocalSubscription()
        assertEquals(setOf("off", "on"), exported.apps.single().rules.map { it.id }.toSet())
        assertFalse(exported.apps.single().rules.first { it.id == "off" }.enabled)
    }

    @Test fun userToggleAndDeletionRemainIndependentAcrossSourceReinstallAndRestart() {
        val source = subscription(item("off", "one"), item("gone", "two"))
        assertTrue(manager.addSubscription(source))
        val off = rules().first { it.externalRuleId == "off" }
        val gone = rules().first { it.externalRuleId == "gone" }
        assertTrue(manager.markRuleToggled(pkg, off, false))
        assertTrue(manager.markRuleRemoved(pkg, gone))
        assertTrue(manager.applyToConfigManager())
        assertEquals(1, rules().size)
        assertFalse(rules().single().isEnabled)
        assertTrue(manager.deleteSubscription(42))
        assertTrue(manager.addSubscription(source))
        assertEquals(off.id, rules().single().id)
        assertFalse(rules().single().isEnabled)
        assertTrue(manager.setLocalRulesEnabled(false))
        assertTrue(manager.setLocalRulesEnabled(true))
        assertFalse(rules().single().isEnabled)
        resetSingleton(SubscriptionManager::class.java, "instance")
        manager = SubscriptionManager.getInstance(context)
        assertEquals(off.id, rules().single().id)
        assertFalse(rules().single().isEnabled)
    }

    @Test fun oldBackupIdsAndPickerMislabelMigrateWithoutRewritingTheLocalRule() {
        val legacyItem = item("legacy", "old.target")
        val source = subscription(legacyItem)
        val old = legacyItem.toCustomRule("picker").also { it.isEnabled = false }
        val local = CustomRule("local", "res_id", "old.target", "collapse").also { it.id = "local" }
        cm.getAppConfig(pkg)!!.addCustomRule(old)
        cm.getAppConfig(pkg)!!.addCustomRule(local)
        val overrides = JSONObject().put("disabledRuleIds", JSONArray().put("legacy"))
        assertTrue(manager.restoreBackupJson(JSONArray().put(source.toJson()), overrides, null))
        // restoreBackupJson intentionally does not mutate the main-config projection itself.
        assertEquals(setOf("legacy", "local"), rules().map { it.id }.toSet())
        assertTrue(manager.applyToConfigManager())
        val migrated = rules().single { it.source == "remote" }
        assertFalse(migrated.isEnabled)
        assertEquals("legacy", migrated.externalRuleId)
        assertEquals("picker", rules().single { it.id == "local" }.source)
        val backup = manager.exportBackupJson()
        assertEquals(migrated.id, backup.getJSONObject("subscription_overrides").getJSONArray("disabledRuleIds").getString(0))
        assertTrue(backup.getJSONObject("subscription_overrides").getJSONArray("knownRules").length() > 0)
        assertTrue(manager.restoreBackupJson(backup.getJSONArray("subscriptions"), backup.getJSONObject("subscription_overrides"), backup.getJSONObject("subscription_meta")))
        assertTrue(manager.applyToConfigManager())
        assertFalse(rules().single { it.id == migrated.id }.isEnabled)
    }

    @Test fun malformedRestoreIsRejectedBeforeChangingFilesOrMemory() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val before = File(context.filesDir, "subscriptions.json").readText()
        assertFalse(manager.restoreBackupJson(JSONArray().put(JSONObject().put("name", "missing stable id")), null, null))
        assertEquals(before, File(context.filesDir, "subscriptions.json").readText())
        assertEquals(42L, manager.getSubscriptions().single().id)
        assertEquals("first", rules().single().targetValue)
    }

    @Test fun sameUrlWithDifferentPublisherIdentityIsNotSilentlyMerged() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        assertFalse(manager.addSubscription(subscription(item("r", "unrelated")).copy(id = 99)))
        assertEquals(42L, manager.getSubscriptions().single().id)
        assertEquals("first", rules().single().targetValue)
    }

    @Test fun firstUpgradeAmbiguityIsPersistedAndNeverReclaimedOrDuplicatedLater() {
        val oldLocal = item("legacy", "stale.target").toCustomRule("picker")
        cm.getAppConfig(pkg)!!.addCustomRule(oldLocal)
        assertTrue(manager.addSubscription(subscription(item("legacy", "current.target"))))
        assertEquals(2, rules().size)
        val remoteId = rules().single { it.source == "remote" }.id
        val warnings = manager.getReconcileWarnings()
        assertTrue(warnings.any { pkg in it })
        val message = manager.consumeLoadWarning()!!
        assertTrue(pkg in message && "详情页" in message && "保留" in message)
        assertTrue(manager.applyToConfigManager())
        assertEquals(2, rules().size)
        assertNull(manager.consumeLoadWarning())
        assertEquals(warnings, manager.getReconcileWarnings())
        // Even if a later source payload happens to equal the local one, the protection is stable.
        assertTrue(manager.addSubscription(subscription(item("legacy", "stale.target"))))
        assertEquals(2, rules().size)
        assertEquals(remoteId, rules().single { it.source == "remote" }.id)
        assertTrue(manager.markRuleRemoved(pkg, rules().single { it.id == "legacy" }))
        assertTrue(manager.applyToConfigManager())
        assertEquals(2, rules().size) // Local deletion API call did not tombstone the remote owner.
        val overrides = manager.exportBackupJson().getJSONObject("subscription_overrides")
        assertTrue(overrides.getJSONArray("protectedLocalRuleKeys").length() > 0)
        assertTrue(overrides.getJSONArray("migrationWarnings").length() > 0)
        resetSingleton(SubscriptionManager::class.java, "instance")
        manager = SubscriptionManager.getInstance(context)
        assertEquals(2, rules().size)
        assertEquals(warnings, manager.getReconcileWarnings())
        assertTrue(manager.consumeLoadWarning()!!.contains(pkg))
    }

    @Test fun corruptPreferenceFileDoesNotResurrectRulesOrOverwriteEvidence() {
        assertTrue(manager.addSubscription(subscription(item("r", "target"))))
        assertTrue(manager.markRuleRemoved(pkg, rules().single()))
        assertTrue(manager.applyToConfigManager())
        val good = manager.exportBackupJson()
        val overrideFile = File(context.filesDir, "subscription_overrides.json")
        overrideFile.writeText("broken preference json")
        resetSingleton(SubscriptionManager::class.java, "instance")
        manager = SubscriptionManager.getInstance(context)
        assertFalse(manager.applyToConfigManager())
        assertFalse(manager.addSubscription(subscription(item("r", "changed"))))
        assertTrue(rules().isEmpty())
        assertEquals("broken preference json", overrideFile.readText())
        assertTrue(manager.consumeLoadWarning()!!.contains("暂停"))
        assertThrows(IllegalStateException::class.java) { manager.exportBackupJson() }
        assertTrue(manager.restoreBackupJson(good.getJSONArray("subscriptions"), good.getJSONObject("subscription_overrides"), good.getJSONObject("subscription_meta")))
        assertTrue(manager.applyToConfigManager())
        assertTrue(rules().isEmpty())
    }

    @Test fun eachSourceFileFailureRestoresDefinitionsPreferencesMetadataAndDisk() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        for (name in listOf("subscriptions.json", "subscription_overrides.json", "subscription_meta.json")) {
            val before = manager.exportBackupJson().toString()
            val disk = diskSnapshot()
            failWriteOnce(name)
            assertFalse("must fail at $name", manager.addSubscription(subscription(item("r", "changed"))))
            assertEquals(before, manager.exportBackupJson().toString())
            assertEquals("first", rules().single().targetValue)
            assertEquals(disk, diskSnapshot())
            manager.beforeFileWrite = null
        }
        assertTrue(manager.addSubscription(subscription(item("r", "retry"))))
        assertEquals("retry", rules().single().targetValue)
    }

    @Test fun actualAtomicFileRenameFailureIsDetectedByReadbackAndRollsBack() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val before = manager.exportBackupJson().toString()
        val disk = diskSnapshot()
        var writes = 0
        manager.beforeFileWrite = { target ->
            if (target.name == "subscriptions.json") {
                writes++
                val blocker = File(target, "rename-blocker")
                if (writes == 1) {
                    // The actual AtomicFile write/finish still runs; no injected exception or fake result.
                    assertTrue(target.delete())
                    assertTrue(target.mkdir())
                    blocker.writeText("non-empty destination rejects rename")
                } else if (target.isDirectory) {
                    // Remove only this test's controlled obstacle so the production rollback can restore.
                    assertTrue(blocker.delete())
                    assertTrue(target.delete())
                }
            }
        }
        assertFalse(manager.addSubscription(subscription(item("r", "changed"))))
        assertTrue("production rollback must run after failed finish/readback", writes >= 2)
        assertEquals(before, manager.exportBackupJson().toString())
        assertEquals(disk, diskSnapshot())
        assertEquals("first", rules().single().targetValue)
        manager.beforeFileWrite = null
        assertTrue(manager.addSubscription(subscription(item("r", "retry"))))
    }

    @Test fun failureInReconcileAfterSuccessfulSourceSaveRollsBackTheWholeOperation() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val before = manager.exportBackupJson().toString()
        val disk = diskSnapshot()
        failWriteOnce("subscription_overrides.json", occurrence = 2)
        assertFalse(manager.addSubscription(subscription(item("r", "changed"))))
        assertEquals(before, manager.exportBackupJson().toString())
        assertEquals(disk, diskSnapshot())
        assertEquals("first", rules().single().targetValue)
    }

    @Test fun synchronousConfigDiskFailureNeverCommitsTheNewSourceOrProjectionAndCanRetry() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val before = manager.exportBackupJson().toString()
        val disk = diskSnapshot()
        val blocked = File(context.filesDir, "adhook_config.json.tmp")
        assertTrue(blocked.mkdir()) // Real production FileOutputStream failure, after source files were written.
        assertFalse(manager.addSubscription(subscription(item("r", "changed"))))
        assertEquals(before, manager.exportBackupJson().toString())
        assertEquals(disk, diskSnapshot())
        assertEquals("first", rules().single().targetValue)
        assertTrue(blocked.delete())
        assertTrue(manager.addSubscription(subscription(item("r", "retry"))))
        assertEquals("retry", rules().single().targetValue)
    }

    @Test fun deleteAndSourceSwitchFailureRestoreSourceAndUserRulePreferences() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        assertTrue(manager.markRuleToggled(pkg, rules().single(), false))
        assertTrue(manager.applyToConfigManager())
        val before = manager.exportBackupJson().toString()
        val disk = diskSnapshot()
        val blocked = File(context.filesDir, "adhook_config.json.tmp")
        assertTrue(blocked.mkdir())
        assertFalse(manager.setSubscriptionEnabled(42, false))
        assertFalse(manager.deleteSubscription(42))
        assertEquals(before, manager.exportBackupJson().toString())
        assertEquals(disk, diskSnapshot())
        assertTrue(manager.getSubscriptions().single().isEnabled)
        assertFalse(rules().single().isEnabled)
        assertTrue(blocked.delete())
    }

    @Test fun failedPreferenceWritesDoNotLeaveInMemoryDisableOrDeletionTombstones() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val rule = rules().single()
        val before = manager.exportBackupJson().toString()
        failWriteOnce("subscription_meta.json")
        assertFalse(manager.markRuleToggled(pkg, rule, false))
        assertEquals(before, manager.exportBackupJson().toString())
        failWriteOnce("subscription_overrides.json")
        assertFalse(manager.markRuleRemoved(pkg, rule))
        assertEquals(before, manager.exportBackupJson().toString())
        manager.beforeFileWrite = null
        assertTrue(manager.applyToConfigManager())
        assertTrue(rules().single().isEnabled)
    }

    @Test fun repositoryRuleEditsReturnFailureAndRestoreBothPreferenceAndProjection() = kotlinx.coroutines.runBlocking {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val repository = com.adhook.ui.data.ConsoleRepository(context)
        val id = rules().single().id
        val before = manager.exportBackupJson().toString()
        val disk = diskSnapshot()
        val blocked = File(context.filesDir, "adhook_config.json.tmp")
        assertTrue(blocked.mkdir())
        assertFalse(repository.setRuleEnabled(pkg, id, false))
        assertFalse(repository.removeRule(pkg, id))
        assertEquals(before, manager.exportBackupJson().toString())
        assertEquals(disk, diskSnapshot())
        assertTrue(rules().single().isEnabled)
        assertTrue(blocked.delete())
        assertTrue(repository.setRuleEnabled(pkg, id, false))
        assertFalse(rules().single().isEnabled)
        assertTrue(repository.removeRule(pkg, id))
        assertTrue(rules().isEmpty())
    }

    @Test fun downloadedFreshnessMetadataIsNotABestEffortTailWrite() {
        val url = "https://example.test/sub"
        fun payload(version: Int, updated: Long, target: String) = subscription(item("r", target))
            .copy(version = version).toJson().put("updatedAt", updated).toString()
        assertTrue(manager.applyDownloadedSubscription(url, payload(1, 100, "first")).isSuccess)
        val before = manager.exportBackupJson().toString()
        val disk = diskSnapshot()
        failWriteOnce("subscription_meta.json")
        assertTrue(manager.applyDownloadedSubscription(url, payload(2, 200, "changed")).isFailure)
        assertEquals(before, manager.exportBackupJson().toString())
        assertEquals(disk, diskSnapshot())
        manager.beforeFileWrite = null
        resetSingleton(SubscriptionManager::class.java, "instance")
        manager = SubscriptionManager.getInstance(context)
        // This response would be rejected if the failed update's v2/200 freshness had leaked to disk.
        assertTrue(manager.applyDownloadedSubscription(url, payload(1, 150, "valid retry")).isSuccess)
        assertEquals("valid retry", rules().single().targetValue)
    }

    @Test fun malformedDownloadedArraysAndScalarsNeverWithdrawOrBroadenExistingRules() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val before = manager.exportBackupJson().toString()
        val disk = diskSnapshot()
        val invalid = mutableListOf<JSONObject>()
        invalid += subscription().copy(version = 2).toJson().put("apps", JSONObject())
        invalid += subscription().copy(version = 2).toJson().put("apps", JSONObject.NULL)
        invalid += subscription(item("r", "changed")).copy(version = 2).toJson().also {
            it.getJSONArray("apps").getJSONObject(0).put("rules", "not an array")
        }
        for ((key, value) in listOf("enabled" to "invalid", "exactResourceId" to "invalid",
            "parentDepth" to "2", "maxTextLength" to 2.5, "textProperty" to JSONObject())) {
            invalid += subscription(item("r", "changed")).copy(version = 2).toJson().also {
                it.getJSONArray("apps").getJSONObject(0).getJSONArray("rules").getJSONObject(0).put(key, value)
            }
        }
        for (payload in invalid) {
            assertTrue(manager.applyDownloadedSubscription("https://example.test/sub", payload.toString()).isFailure)
            assertEquals(before, manager.exportBackupJson().toString())
            assertEquals(disk, diskSnapshot())
            assertEquals("first", rules().single().targetValue)
        }
    }

    @Test fun downloadedOversizedDeepAndTrailingPayloadsAreRejectedBeforeMutation() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val disk = diskSnapshot()
        val valid = subscription(item("r", "changed")).copy(version = 2).toJson().toString()
        val deep = "{\"id\":42,\"apps\":" + "[".repeat(65) + "]".repeat(65) + "}"
        for (payload in listOf(valid + "{}", deep, " ".repeat(16 * 1024 * 1024 + 1))) {
            assertTrue(manager.applyDownloadedSubscription("https://example.test/sub", payload).isFailure)
            assertEquals(disk, diskSnapshot())
            assertEquals("first", rules().single().targetValue)
        }
        // An explicit, well-formed empty source is still a legitimate withdrawal.
        assertTrue(manager.applyDownloadedSubscription("https://example.test/sub", subscription().copy(version = 2).toJson().toString()).isSuccess)
        assertTrue(rules().isEmpty())
    }

    @Test fun productionHttpEntryUsesStrictParsingAndNeverCommitsACancelledDownload() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val disk = diskSnapshot()
        val invalid = subscription().copy(version = 2).toJson().put("apps", JSONObject()).toString().toByteArray()
        fun connection(url: java.net.URL, stream: java.io.InputStream) = object : java.net.HttpURLConnection(url) {
            override fun connect() { }
            override fun disconnect() { }
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = stream
            override fun getContentLengthLong() = -1L
        }
        manager.downloader = SubscriptionDownloader { url -> connection(url, java.io.ByteArrayInputStream(invalid)) }
        assertTrue(kotlinx.coroutines.runBlocking { manager.updateFromUrl("https://example.test/sub") }.isFailure)
        assertEquals(disk, diskSnapshot())
        manager.downloader = SubscriptionDownloader { url -> connection(url, object : java.io.InputStream() {
            override fun read(): Int = throw java.util.concurrent.CancellationException("fixture cancelled")
        }) }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { manager.updateFromUrl("https://example.test/sub") }
        }
        assertEquals(disk, diskSnapshot())
        assertEquals("first", rules().single().targetValue)
    }

    @Test fun cancelledBeforeSourceTransactionStartsDoesNotEvenRewriteOldSourceFiles() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val disk = diskSnapshot()
        val memory = manager.exportBackupJson().toString()
        var checks = 0; var writes = 0
        manager.beforeFileWrite = { writes++ }
        val payload = subscription(item("r", "changed")).copy(version = 2).toJson().toString()
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            manager.applyDownloadedSubscription("https://example.test/sub", payload) {
                if (++checks == 3) throw java.util.concurrent.CancellationException("cancelled after queue barrier")
            }
        }
        assertEquals(0, writes)
        assertEquals(memory, manager.exportBackupJson().toString())
        assertEquals(disk, diskSnapshot())
    }

    @Test fun rollbackFailureKeepsOldMemoryAndPausesFurtherWritesUntilExplicitRecovery() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val before = manager.exportBackupJson()
        val oldSubscriptions = manager.getSubscriptions().single().toJson().toString()
        manager.beforeFileWrite = { file ->
            if (file.name == "subscription_overrides.json") throw java.io.IOException("persistent failure")
        }
        assertFalse(manager.addSubscription(subscription(item("r", "changed"))))
        assertEquals(oldSubscriptions, manager.getSubscriptions().single().toJson().toString())
        assertEquals("first", rules().single().targetValue)
        assertTrue(manager.consumeLoadWarning()!!.contains("回滚未完整"))
        assertThrows(IllegalStateException::class.java) { manager.exportBackupJson() }
        manager.beforeFileWrite = null
        assertFalse(manager.deleteSubscription(42))
        assertTrue(manager.restoreBackupJson(before.getJSONArray("subscriptions"),
            before.getJSONObject("subscription_overrides"), before.getJSONObject("subscription_meta")))
        assertTrue(manager.applyToConfigManager())
        assertTrue(manager.deleteSubscription(42))
    }

    @Test fun firstSaveFailureRestoresFileAbsenceInsteadOfCreatingEmptyEvidence() {
        failWriteOnce("subscription_meta.json")
        assertFalse(manager.addSubscription(subscription(item("r", "first"))))
        assertTrue(manager.getSubscriptions().isEmpty())
        for (name in sourceFileNames) {
            assertFalse(File(context.filesDir, name).exists())
            assertFalse(File(context.filesDir, "$name.bak").exists())
        }
        assertFalse(File(context.filesDir, "adhook_config.json").exists())
    }

    @Test fun localMasterSwitchReportsActualDiskFailureAndRestoresItsMemoryChoice() {
        assertTrue(manager.addSubscription(subscription(item("r", "first"))))
        val previous = cm.globalConfig.isLocalRulesEnabled
        val disk = diskSnapshot()
        val blocked = File(context.filesDir, "adhook_config.json.tmp")
        assertTrue(blocked.mkdir())
        assertFalse(manager.setLocalRulesEnabled(!previous))
        assertEquals(previous, cm.globalConfig.isLocalRulesEnabled)
        assertEquals(disk, diskSnapshot())
        assertTrue(blocked.delete())
        assertTrue(manager.setLocalRulesEnabled(!previous))
    }

    private val sourceFileNames = listOf("subscriptions.json", "subscription_overrides.json", "subscription_meta.json")
    private fun diskSnapshot(): Map<String, String?> = (sourceFileNames + "adhook_config.json").associateWith { name ->
        File(context.filesDir, name).let { if (it.isFile) it.readText() else null }
    }
    private fun failWriteOnce(name: String, occurrence: Int = 1) {
        var matches = 0
        manager.beforeFileWrite = { file ->
            if (file.name == name && ++matches == occurrence) throw java.io.IOException("injected $name failure")
        }
    }

    private fun rules(): List<CustomRule> = synchronized(cm) { cm.getAppConfig(pkg)!!.customRules.map { it.copy() } }
    private fun item(id: String, target: String) = SubscriptionRuleItem(id, id, "res_id", target, "collapse")
    private fun subscription(vararg items: SubscriptionRuleItem) = Subscription(42, "test", 1, "test", "", "https://example.test/sub",
        apps = listOf(SubscriptionApp(pkg, "test app", items.toList())))
    private fun resetSingleton(type: Class<*>, field: String) {
        type.getDeclaredField(field).apply { isAccessible = true }.set(null, null)
    }
}

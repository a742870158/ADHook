# 测试索引（自动生成）

> 由 `python tools/test_index.py` 生成，不要手改；`--check` 校验是否过期。
> 工作流见 [测试工作流](../docs/TESTING_WORKFLOW.md)：先按测试类定位并用 `--tests` 只跑相关类，收尾再按档位跑门禁，不要通读 `app/tests` 全部源码。

## 公共/核心层测试

| 生产包 | 测试类 |
| :--- | :--- |
| `com.adhook.config` | HookLaunchSnapshotTest、HookProviderCalibrationTest、HookRuntimeCacheTest、HookRuntimeCalibrationIntegrationTest、HookRuntimeContextInitTest、HookRuntimeSnapshotTest、RemoteConfigPublisherTest、RemotePublicationStateTest |
| `com.adhook.config.security` | BackupPolicyTest、CallerPolicyTest、ConfigCommitNotificationTest、ConfigImportSafetyTest、ConfigSecurityIntegrationTest、HostAuthorizationMinSdkTest、PluginBackupPolicyTest、RulePayloadPolicyTest、RuntimeStatsEnvelopeTest、RuntimeStatsMetadataTest、RuntimeStatsPacketTest、RuntimeStatsPolicyTest |
| `com.adhook.config.subscription` | RuleReconcilerTest、SubscriptionBackupCodecTest、SubscriptionDownloaderTest、SubscriptionManagerTest |
| `com.adhook.core` | ActivityUiCoordinatorTest、AdHookLogTest、FeatureGuardCallbackTest、FeatureGuardPolicyTest、FeatureGuardSingleSnapshotTest、HookDispatcherInstallRetryTest、HookPackagePolicyTest、HookRegistrationNativeTest、HookRegistrationTest、LatestStatsDeliveryTest、ObserverHookRegistryTest、PackerClassLoaderProbeTest、PluginBootstrapTest、RuleHitToastNotifierTest、ViewCollapserTest |
| `com.adhook.engine` | CustomRuleEngineTest、CustomRuleTraversalTest、ObserverInstallRetryTest、ObserverReadinessTest、RuleCandidateIndexTest |
| `com.adhook.inspector` | InspectorRuntimeGateTest |
| `com.adhook.plugin` | InstalledPluginRoundTripTest、PluginArtifactPublisherTest、PluginInstallerTest、PluginManifestTest、PluginMarketCacheTest、PluginMarketContractTest、PluginMarketTest、PluginRuntimeEndToEndTest、PluginRuntimeTableTest、PluginSignatureTest、PluginStoreTest、PluginTrustStoreTest、PluginZipTest |
| `com.adhook.runtime` | HookBridgeNativeAdapterTest、HookHelpersTest |
| `com.adhook.sdk` | PanglePublicContractTest、PublicWebViewMinSdkTest、SdkCallbackAccountingTest、SdkHookRegistryTest、SdkInfrastructureAccountingTest、SdkNetworkAccountingTest、SdkOwnershipTest、SdkRuntimeRegressionTest、SensorAdBlockerTest、WeakListenerCacheTest、WebViewHookRetryTest |
| `com.adhook.ui` | AppDetailStatsIntegrationTest、AppListRepositoryTest、ConsoleViewModelTest、DetailIntentPolicyTest、FeatureBadgeTest、ImportExportMinSdkTest、PublicResourceMinSdkTest |
| `com.adhook.util` | UpdateApkVerifierTest、UpdateCheckerFlowTest、UpdateDownloadSafetyTest |

## 公共 Hook 支撑测试

| 测试类 |
| :--- |
| ClassFingerprintProbeTest (`app/tests/java/com/adhook/hook/support/ClassFingerprintProbeTest.java`) |
| SplashActivityCompletionTest (`app/tests/java/com/adhook/hook/support/SplashActivityCompletionTest.java`) |
| ViewListenerRegistryTest (`app/tests/java/com/adhook/hook/support/ViewListenerRegistryTest.java`) |

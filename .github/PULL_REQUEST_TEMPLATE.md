## 改动说明

<!-- 改了什么、为什么改、影响哪些模块/文档/ABI -->

## 验证

<!-- 实际执行过的命令与结果；没有运行的检查请写明 -->

- [ ] `python tools/quality_checks.py --base-ref HEAD`
- [ ] `python -m unittest discover -s tools/tests -p 'test_*.py'`
- [ ] `./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyPluginAbiSurface`
- [ ] 涉及 UI 的改动已实际页面验证
- [ ] 涉及插件 ABI 的改动已提升 `PLUGIN_ABI` 并说明旧插件兼容性

## 风险与未验证项

<!-- 设备、宿主、网络、签名等无法在 CI 覆盖的部分 -->

## 检查清单

- [ ] 未提交密钥、口令、本机绝对路径、APK、dump 或 `.local/` 内容
- [ ] 未引入宿主专属 Hook、宿主包名清单或宿主文档
- [ ] 文档、测试索引与命令已同步
- [ ] 变更范围与 PR 描述一致，没有夹带无关重构

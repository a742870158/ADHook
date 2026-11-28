# ADHook

[![Quality Gates](https://github.com/a742870158/ADHook/actions/workflows/quality.yml/badge.svg)](https://github.com/a742870158/ADHook/actions/workflows/quality.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/a742870158/ADHook?display_name=tag&sort=semver)](https://github.com/a742870158/ADHook/releases)

基于现代 LSPosed / LibXposed API101 的 Android 广告拦截与规则管理底座，提供 SDK 断源、声明式折叠、可视化拾取、插件运行时与插件市场。

> ADHook 是**独立底座**：只维护通用 Hook、规则引擎、拾取器、插件运行时/市场和配置安全。宿主深度适配不进入本仓库，由独立维护的签名插件提供。LSPosed 作用域内 Hook 默认执行；插件安装即默认启用，但插件功能规则默认关闭，需在应用详情逐个手动开启。当前源码为 API101-only，最低 Android 12。

## 主要能力

- **规则中心**：Kotlin、Jetpack Compose、Material 3；全局、规则、应用、插件、关于五个入口；支持应用搜索、深浅色模式与按应用特性开关。
- **通用托管**：用户在 LSPosed 作用域中勾选应用后，默认挂载通用 SDK 拦截、规则引擎与拾取器；宿主数据按调用者自有包默认授权，不能访问其它包。
- **签名插件**：宿主深度适配以签名 zip 安装；插件清单声明 `targets`/`features`，底座校验 ABI、dex sha256、发布者签名与最小底座版本。插件由独立仓库或市场镜像分发，本仓库不内置任何宿主 Hook。
- **插件市场**：支持添加任意 HTTPS 镜像地址；安装/更新前校验索引 sha256 与 ECDSA P-256 发布者签名，首次信任展示完整指纹，发布者密钥变化阻断更新。
- **可视化拾取**：宿主内悬浮球、红框选择、父子层级切换、折叠预览、资源/文本/类名规则保存，以及运行时线索导出。
- **规则来源独立**：本地 picker 与远程订阅分开管理；总开关不覆写逐条偏好，远程规则按显式身份同步新增、修改、撤回和停用。
- **备份与导入导出**：本地规则可经系统文件选择器导入，导出到公共下载目录或应用私有目录；全量备份包含配置、订阅与逐条偏好，恢复先验证并保留恢复前快照，授权判定不随备份导入。
- **版本更新**：仅以官方 GitHub Release 和 SHA-256 启用受管下载；安装器打开不等于安装完成，签名轮换不自动接受。
- **真实动作统计**：详情区分已拦截、已注册未采集、未上报、挂载异常与旧版记录；注册 Hook 和 CSS 注入不算拦截。

## 兼容性

- Android 12（`minSdk=31`）及以上；`compileSdk=36`、`targetSdk=34` 为既有目标行为。
- LSPosed / LibXposed API101；不打包 API82 stub，不伪造传统入口。
- 生产字节码 Java 17；AGP 8.10.1、Gradle 8.13；依赖版本不整体升级。

## 构建与验证

需要 JDK 17+、Android SDK Platform 36、Build-Tools 36.0.0 与 Python 3.11。个人工具链路径通过 `JAVA_HOME`、`ANDROID_HOME`、`ADHOOK_PYTHON` 提供；需要长期保留的本机路径放在 `.local/`（已 gitignore），不要提交到仓库。

```bash
# 仓库级静态门禁与工具测试
python tools/quality_checks.py --base-ref HEAD
python -m unittest discover -s tools/tests -p 'test_*.py' -v

# 编译、JVM 回归、Lint 与插件 ABI surface
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyPluginAbiSurface --console=plain
```

Windows 使用 `.\gradlew.bat`。

**本地正式签名构建**：在仓库根创建未跟踪的 `signing.properties`（或使用同名环境变量），提供 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`、`certificateSha256`，然后执行：

```bash
./gradlew :app:assembleRelease :app:verifyPluginAbiMapping --console=plain
python tools/verify_apk_signature.py --apk app/build/outputs/apk/release/app-release.apk --expected-sha256 <指纹>
adb install -r app/build/outputs/apk/release/app-release.apk
```

本地构建必须复用既有正式证书；缺少签名材料时 `assembleRelease` 直接失败，不会回退 debug 证书。正式发布仍只由 GitHub Actions 在版本 Tag 上执行，流程与 Secrets 见[工程验证与安全发布](docs/QUALITY_AND_RELEASE.md#构建与发布)。

## 安装

1. 从 [Releases](https://github.com/a742870158/ADHook/releases) 下载 APK，或使用本地正式签名构建覆盖安装。
2. 在 LSPosed 管理器中启用 ADHook，并勾选目标宿主作用域。
3. 安装宿主插件（本地 zip 或市场镜像）；插件安装后默认启用，其功能规则默认关闭。
4. 在应用详情逐个打开需要的插件功能规则；强退并重开宿主，确认插件加载与目标功能。
5. 缺少注入或插件日志时，先检查作用域、插件文件/ABI/底座版本和框架 remote files 支持，不反复盲目探测。

编译成功、注册 `OK` 或计数增加都不是广告效果证据。

## 插件开发

底座发布 `adhook-plugin-api-<abi>.jar`、`adhook-test-classes-<version>.jar` 与稳定别名 `adhook-test-classes.jar` 供插件项目编译和测试。插件格式、ABI、签名、打包、索引与市场契约见：

- [插件格式（schema / ABI）](docs/PLUGIN_FORMAT.md)
- [插件开发与发布](docs/PLUGIN_DEVELOPMENT.md)
- [插件市场](docs/PLUGIN_MARKET.md)

插件发布顺序固定为：写 dex 并校验大小/哈希、提交 framework remote preferences、清理旧文件；任一步失败保留旧发布。插件作者负责自己的宿主文档与真机验收，不把“注册成功”当作实际拦截。

## 规则订阅

官方精选规则维护在 [规则 JSON](rules/rules.json) 与 [JSON5](rules/rules.json5)，可从 `updateUrl` 添加远程订阅。规则按显式身份同步，名称或简类名不作为规则身份；历史归属歧义保留本地并提示。

## 质量与发布

- [PR 质量门禁](.github/workflows/quality.yml)：仓库约束、离线工具测试、JVM 回归、Lint 与插件 ABI surface 校验，不读取生产 Secrets。
- [Release 工作流](.github/workflows/release.yml)：版本 Tag 通过同一质量门禁后，使用仓库 Secrets 签名构建、真实证书验签、R8 后校验插件 ABI mapping、上传草稿并验证附件，最后正式发布。
- 发布失败不覆盖既有 Tag 或附件；插件 SDK 资产与 APK 分别上传、分别报告。
- 开发与联调规则见 [AGENTS.md](AGENTS.md)，测试分层见[测试工作流](docs/TESTING_WORKFLOW.md)，配置与授权边界见[配置安全](docs/CONFIG_SECURITY.md)。

## 许可证

本项目以 [Apache-2.0](LICENSE) 许可证发布。第三方依赖与其许可证见各自发布物。

## 交流

- [Telegram 交流群](https://t.me/ADHook)
- 安全问题请按 [SECURITY.md](SECURITY.md) 私密报告，不要公开披露可利用细节。

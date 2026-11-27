# 贡献指南

ADHook 是独立的 LSPosed / LibXposed API101 底座，只维护通用 Hook、规则引擎、拾取器、插件运行时与插件市场。**宿主深度适配属于独立插件仓库，不进入本仓库**；本仓库不内置任何宿主专属 Hook、宿主文档或宿主包名。

## 开始之前

- 先读 [AGENTS.md](AGENTS.md)、[工程验证与安全发布](docs/QUALITY_AND_RELEASE.md) 与 [Hook 执行架构](docs/HOOK_RUNTIME.md)。
- 一个 Issue 只解决一个问题；涉及宿主行为、广告效果或设备日志的需求，请先提供可复现的运行时证据。
- 不要提交签名材料、生产口令、本机绝对路径、设备 dump、APK 或反编译产物。

## 本地环境

需要：

- JDK 17 或更高版本（推荐 21），设置 `JAVA_HOME`；
- Android SDK Platform 36 与 Build-Tools 36.0.0，设置 `ANDROID_HOME`；
- Python 3.11（可选：用 `ADHOOK_PYTHON` 指定解释器）。

个人机器上的工具链路径不要写进仓库。需要长期保留的本机配置放在 `.local/`（已 gitignore），例如 `.local/ENVIRONMENT.md`；仓库文档只使用环境变量和相对路径。

## 本地验证

```bash
python tools/quality_checks.py --base-ref HEAD
python -m unittest discover -s tools/tests -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyPluginAbiSurface --console=plain
```

Windows 使用 `.\gradlew.bat`。本仓库**不提供本地打包/发布脚本**：签名 APK 只由 GitHub Actions 在版本 Tag 上构建和发布，详见 [发布流程](docs/QUALITY_AND_RELEASE.md#构建与发布)。

## 插件 ABI

`tools/plugin_abi_seeds.txt` 定义插件可链接的底座类。修改这些类或它们的 public/protected 签名后：

```bash
./gradlew :app:compileDebugJavaWithJavac :app:compileDebugKotlin
python tools/plugin_abi.py generate \
  --classes-dir app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes \
  --classes-dir app/build/tmp/kotlin-classes/debug
```

不要手改 `app/plugin-abi-keep.pro` 或 `tools/plugin_abi_allowlist.txt`。破坏链接签名的变更必须提升 `PLUGIN_ABI`，让旧插件安全禁用。

## 提交与 PR

- 提交信息使用 `feat:`、`fix:`、`docs:`、`test:`、`chore:` 等前缀，正文说明改了什么、为什么改、跑了哪些验证。
- PR 描述里写清楚：影响范围、验证命令与结果、未验证项与风险。
- 不在 PR 中夹带无关重构；不顺手迁移历史命名或目录。
- 不提交 `app/build/`、`build/`、APK、dump、密钥或 `.local/` 内容。
- 提交即表示你同意以本仓库的 [Apache-2.0](LICENSE) 许可证授权你的贡献。

## 文档

文档与代码同源维护：

- 行为、路径、命令变化时同步修改对应文档；
- 本地文档链接必须可达（`tools/quality_checks.py` 会校验）；
- `app/tests/INDEX.md` 由 `python tools/test_index.py` 生成，不要手改。

## 行为准则

参与本项目即表示同意遵守 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

# 变更日志

本文件记录直接影响使用、构建、发布或插件 ABI 的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循仓库的日期版本约定。

## [Unreleased]

### Added

- Apache-2.0 许可证、`NOTICE`、贡献指南、安全策略、行为准则、Issue/PR 模板与变更日志。
- `.editorconfig`、`.gitattributes`，统一跨平台换行与缩进。
- `CHANGELOG.md` 与发布资产校验说明。

### Changed

- 底座与宿主插件仓库解耦：文档不再引用任何具体插件仓库、宿主清单或迁移批次；宿主适配只保留通用插件格式与 ABI 说明。
- 发布改为唯一的 GitHub Actions 流程：版本 Tag 触发质量门禁、签名构建、签名校验、草稿验证与正式发布；移除本地打包/发布脚本与本地签名回退。
- 本机工具链路径移出仓库，改为环境变量与 `.local/` 本地配置。
- 入口类 `ModernMainHook` 更名为 `AdHookModule`；`ConfigApplication` 更名为 `AdHookApplication`。
- Gradle 模块由 `module` 更名为 `app`，生产/测试源集统一为 `app/src`、`app/tests`；CI、文档、工具与 ABI 路径同步。
- UI 包与目录对齐：`com.adhook.ui.data`、`com.adhook.ui.screens`、`com.adhook.ui.components`，相关 import 与测试同步。
- 删除空的内置宿主目录/授权链路：`HookDispatcher` 查询、`HostAuthorizationStore.isBuiltIn`、`BuiltInApp`/`isBuiltIn`/`builtInCount`、空的 `arrays.xml` 与主题商店双包展示。
- 配置管理器归档到 `config/subscription`、`config/backup`；`AdHookApplication` 移到根包 `com.adhook`。
- 去掉不存在的 `tests/kotlin` 源集；Release 工作流显式运行 `:app:lintRelease` 与 JVM 回归。
- Release 额外发布稳定别名 `adhook-test-classes.jar` 与 `.sha256`，插件项目可用 `releases/latest/download/adhook-test-classes.jar` + `test-classes-sha256=auto`，底座升级不再手改版本化地址。
- 新增 adaptive icon（`mipmap-anydpi-v26`）与版本化启动图标回退。
- 更新检查与规则订阅指向本仓库。
- 清理无引用的宿主测试支撑、空的 `values-v28` 主题与迁移期空壳注册表。
- 插件 ABI 升级到 2：`IAppHook` → `HostHook`（`supports`/`install`）、`XLog` → `AdHookLog`、`JiaGuProbe` → `PackerClassLoaderProbe`；SDK 资产改为 `adhook-plugin-api-2.jar`。包含旧类的 ABI 1 插件会被安装与加载两侧安全拒绝，需按插件包 TODO 重新构建发布。

### Removed

- 本地 `build.ps1`、`release-local.*`、`publish-release.*`、`tools/release_common.ps1` 及其测试。
- README 宿主表格生成器 `tools/update_readme_table.py`。
- 宿主专用的 MITM 改写脚本。

### Fixed

- 修复 README 生成器与文档仓库名不一致导致的质量门禁失败。
- 修复关于页、应用规则页在“无内置宿主”后仍显示“内置/精调应用”的文案。
- 修复 `.gitignore` 中无效的负向规则与本地路径残留。

## [1.0.20261027]

首个公开的 API101-only 底座版本：通用 SDK 拦截、规则引擎、可视化拾取、插件运行时/市场、备份与更新校验。宿主深度适配以签名插件形式分发。

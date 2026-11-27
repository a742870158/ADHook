# 开发、运行时取证与目录约定

安全边界必须先读 [AGENTS](<../AGENTS.md>)；构建/发布唯一命令见 [工程说明](<QUALITY_AND_RELEASE.md>)，执行门控唯一解释见 [Hook 架构](<HOOK_RUNTIME.md>)。本页的设备/代理命令只是工作流说明，不构成安装、点击、修改系统设置或发布授权。

## 工具链与环境

不要递归全盘搜索 JDK/SDK/JADX。使用环境变量或 PATH 中的命令，缺失时报告，不自动安装或改环境。

| 工具 | 约定 |
| --- | --- |
| JDK | `JAVA_HOME` 指向 JDK 17+（推荐 21）；生产字节码 Java 17 |
| Android SDK | `ANDROID_HOME`；编译平台 `platforms/android-36/android.jar` |
| Build Tools | `build-tools/36.0.0` 中 aapt2、zipalign、D8/apksigner |
| ADB | PATH 中的 `adb`，或用 `ADB` 指定绝对路径 |
| JADX | PATH 中的 `jadx`/`jadx.bat`，或用 `JADX` 指定 |
| Python | PATH 中的 `python`，或用 `ADHOOK_PYTHON` 指定 |
| Hook 编译合约 | `app/libs/libxposed-api-101.jar`，compileOnly，不打包框架实现 |

个人机器上的固定路径只写在 `.local/`（已 gitignore），不要提交到仓库；文档、脚本和示例只使用环境变量与相对路径。

## 项目目录与命名

- `app/src/com/adhook` 是生产 Java/Kotlin 包根；Java/Kotlin 目标字节码 17。测试只在 `app/tests/java`，资源在 `app/tests/resources`；不能把测试塞入 `app/src/test`。
- `runtime` 承载 API101 原生后端；`core` 是门控、分发、指纹和视图公共基础设施；`sdk` 是共享联盟层；`plugin` 是插件安装/市场/可信发布与宿主加载；`hook/support` 只保留公共 Hook 支撑（如 `SemanticMembers`、`SplashActivityCompletion`），底座没有宿主专属适配目录。
- 宿主插件源码/测试/宿主文档属于独立插件仓库，不进入本仓库；插件 zip 只包含 `plugin.json`、`classes.dex`、可选 `assets/` 与 `META-INF/plugin.sig`。底座 `app/plugin-abi-keep.pro` 与 `tools/plugin_abi_allowlist.txt` 由工具生成，不得手改。
- Hook 目录中的 `com/…`、`gov/…`、`tv/…` 是历史宿主包镜像；当前底座已不再新增这类目录，新增宿主请在独立插件仓库实现。
- UI 目录分 screens/components/data/theme；现存 Kotlin 大多仍使用 `com.adhook.ui` package，与少量子 package 并存。不要在功能修复时顺手做无关命名迁移。
- feature ID 按宿主现有风格和语义命名，字符集/长度以 ConfigInputPolicy 为准；不重命名已持久化 ID 来追求格式统一，不把共享注册锁到一个子开关。
- [官方 JSON](<../rules/rules.json>) 与 [JSON5](<../rules/rules.json5>) 是相同订阅身份的两种公开格式。保持既有 URL/ID，变更时同时同步内容；native、picker、remote 是独立来源，不以相同名字推导规则身份。

## 四层漏斗：从现场到稳定身份

收到去广告需求，先用运行时拾取取得目标包、宿主版本、可见文案、资源名、itemViewType 和父链；不得先打开 JADX 盲搜。无明确设备授权时，只读取用户提供的拾取数据。

```bash
# 明确授权的目标设备上取证；这些命令不得在纯代码审计中执行。
python tools/ad_inspector.py top
python tools/ad_inspector.py dump-ui
python tools/ad_inspector.py dump-ui -k "广告"
# splash 会强退并冷启动宿主，须有该动作授权。
python tools/ad_inspector.py splash <pkg>
```

1. **标准 SDK/系统层**：优先公开 SDK 包、完整请求签名及可安全送达的 onError/onNoAD 回调。通用安装器成功 Method/Constructor 才去重，缺类/失败可补装。
2. **资源/视图层**：有已证实稳定的资源名/文本时做 owner 折叠或声明式规则。getIdentifier 后复验资源名/所属包；短资源名和编译期整数不是永久身份，未知结构放行。
3. **协议/数据层**：按服务端字段、枚举、序列化协议或可信 itemViewType 过滤；未知项保留，类型和成功提交后再计数。不要因形状相似清空任意列表。
4. **唯一指纹层**：前三层不足且有具体运行时证据时，用 ClassFingerprintProbe 的限定域、明确指纹 ID、预算及唯一性；多候选、反射不完整、预算耗尽放行。不回填短类名、单字母成员、字段位置或 first-match。

任何静态/反编译分析超过 30 秒无明确进展，应停止盲搜、返回现场；这里的秒数是止损预算，不是对宿主/UI/JADX 性能的保证。禁止对解包源码递归搜索 splash/ad/adv/skip/load 等泛词，禁止不带 `--single-class` 的全量 JADX。

```bash
# 仅在已有具体类/协议证据的第4层使用，绝不全量反编译。
python tools/ad_inspector.py dex-search "<APK>" "<具体特征>"
python tools/ad_inspector.py jadx-class "<APK>" "<完整类名>" -o "apk/jadx_out"
```

## 插件边界

底座最终只保留通用能力；新增宿主在独立插件仓库实现，不再向底座默认添加深度 Hook。插件格式/签名/索引契约见 [插件格式](<PLUGIN_FORMAT.md>)，构建与发布流程见 [插件开发](<PLUGIN_DEVELOPMENT.md>)。

1. 插件仓库按宿主包建立插件目录：`plugin.properties`（id/name/version/versionCode/entry/targets/author/minBaseVersionCode）+ `src/` + `tests/` + 宿主文档；入口实现 `HostHook`，行为、门控和注册契约与底座通用层一致。
2. 用 `tools/plugin_package.py build --sign` 生成签名 zip；清单 features 必须与入口 `getFeatures()` 一致（FeatureExtractor 校验，失败即构建失败）。构建只依赖底座发布的 `adhook-plugin-api-<abi>.jar` + `android.jar`，不打包框架实现。
3. 插件 ABI 改动只通过底座 `PLUGIN_ABI` 版本推进；底座 release 必须通过 `verifyPluginAbiSurface` / `verifyPluginAbiMapping`。插件仓库 CI 复用同一套源码守卫与测试。
4. 插件发布后先在目标设备完成安装/强退/重开/功能验收；底座不保存宿主清单，也不把“注册成功”当作效果证明。
5. 底座侧只有通用层、插件运行时/市场、ABI 或授权语义变化时才改 scope/授权契约；安装插件不改变 LSPosed 作用域，也不等于宿主授权或 Hook 启用，均由用户显式确认。
6. 插件入口类加载失败时 fail-open 到通用层：不回退已删除的内置适配，不重放第二个适配。没有目标包的插件加载日志时，先确认插件已启用、框架支持 remote files、dex 已发布且 ABI/底座版本匹配，再强退重开。

不短路 Activity super.onCreate；必要退出仅在正常生命周期后确认安全去向。未知任务根、异常或路由失败放行。

效果明确达到目标后立即收敛交付，不做多余 Tab 漫游、连环截图/点击或持续日志。只有失败、异常或用户要求进一步检查时继续诊断。本地测试、注册 OK、命中数和当前广告观感是不同证据层次。

## 工具维护边界

- [ad_inspector](<../tools/ad_inspector.py>) 是运行时定穴入口；ADB/JADX/JDK 路径通过 `ADB`、`JADX`、`JAVA_HOME` 覆盖，默认使用 PATH。
- 代理抓包与协议改写属于宿主现场工作，不随底座分发；需要时在已授权设备上单独审查、脱敏日志，不把固定局域网示例 IP 当当前环境。
- 代理证书/私有配置不读、不复制到文档；SSL Pinning 处置必须在已授权场景单独审查，不盲目全局放行。
- Python 嵌套改写标志用 nonlocal，不误用 global；仅处理已确认 JSON content-type，字符串编码保持 `ensure_ascii=False`。该编码经验不证明业务字段改写正确。

### 现役维护入口

[测试索引](<../app/tests/INDEX.md>)由[索引工具](<../tools/test_index.py>)生成，源码文本守卫在[静态源码守卫](<../tools/source_guards.py>)；质量/链接/守卫/索引检查走[仓库门禁](<../tools/quality_checks.py>)；发布器与验签器在 CI 中使用 [github_release.py](<../tools/github_release.py>) 与 [verify_apk_signature.py](<../tools/verify_apk_signature.py>)。不按旧 README 标题推断摘要，不按文件顺序猜主 Hook 入口。

本机 ignored 的退役研究脚本（历史 README 拆分器、仓库 README 抓取、模块搜索、单现场像素探针）不是生产构建或维护入口；它们只作历史线索，以 Git 历史为准，也不能据此对解包源码作泛词盲搜。清场必须先汇报，用户确认后才移除；不随之清理其它逆向脚本、APK、dump、密钥或 Git 历史。

历史功能回退与独有教训不再单独维护，以 Git 历史和本地 dump 材料为准。ignored dump/APK/调试样本不是维护代码，不能随文档整理删除唯一验收证据。

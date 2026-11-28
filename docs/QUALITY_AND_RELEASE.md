# 工程验证与安全发布

本页是验证命令、打包和发布失败语义的唯一说明。日常迭代的最小路径见[测试工作流](<TESTING_WORKFLOW.md>)，执行机制见 [Hook 架构](<HOOK_RUNTIME.md>)，设备取证与工具链见 [开发流程](<DEVELOPMENT.md>)。

<a id="validation-status"></a>

## 当前验证与产物状态

- 浏览器或命令行的“最近一次结果”以 [GitHub Actions](<https://github.com/a742870158/ADHook/actions>) 与本地实际输出为准；本文不固定测试计数，避免文档读数过期。
- 底座内置宿主始终为 **0**：不包含宿主 Hook、宿主包名清单、宿主文档或 `docs/apps`。宿主适配由独立插件提供，插件能力与验收状态由各插件仓库维护。
- 正式发布 APK 只由 GitHub Actions 在版本 Tag 上构建；本地可以用同一正式证书构建并安装到设备验证，但仓库不保存历史 APK，本地构建不是发布产物。
- 远端 CI、真机注入、真实广告效果属于不同证据层，不能互相替代。

## 支持环境与源集

- Android 下限 12（minSdk31），compileSdk36、targetSdk34；开发机 JDK17+（推荐 21），生产 Java/Kotlin 字节码 17，CI JDK17。
- AGP8.10.1、Gradle8.13；Kotlin/Compose 及业务依赖不整体升级。正式 service101 AAR 与既有 runtime 精确兼容，API101 jar 仅 compileOnly，测试也使用同一 jar，不把 API82 或自造 stub 塞进 APK。
- 生产包根 `app/src`；测试独立 `app/tests/java`，样本资源 `app/tests/resources`。不能重建 `app/src/test` 或不支持的 include/exclude DSL；包根不能设为 `src/com` 导致 Lint 定位失效。
- 生产源码文本契约在[静态源码守卫](<../tools/source_guards.py>)，由离线门禁运行；JVM 测试只覆盖可执行路径，不新增读取生产源码字符串的测试。[测试索引](<../app/tests/INDEX.md>)由[索引工具](<../tools/test_index.py>)生成，用于按生产包定位测试类。
- UI 屏幕/Repository/ViewModel 分离；包查询、标签/图标、订阅、备份和文件操作在 IO 层。纯策略和节点内存夹具优先 JVM，View/Activity 回归用 Robolectric；它们都不证明手机上 native Xposed Hook 已工作。

## 本地验证

在仓库根执行，首次运行可能需要下载依赖；离线缺依赖/限流/未进入断言不能算通过。工具链路径用 `JAVA_HOME`、`ANDROID_HOME`、`ADHOOK_PYTHON` 提供，个人固定路径放在 `.local/`（已 gitignore）。

```bash
python tools/quality_checks.py --base-ref HEAD
python -m unittest discover -s tools/tests -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyPluginAbiSurface --console=plain
```

Windows 使用 `.\gradlew.bat`。`verifyPluginAbiMapping` 依赖 `assembleRelease`；正式发布由 Release 工作流执行，本地设备验证可按下一节对正式签名包重复执行。

- [仓库门禁](<../tools/quality_checks.py>)校验私密材料是否 tracked（只查文件名）、目录/作用域、官方规则身份、测试源集、生命周期短路、API101 依赖/类型/元数据、CI 平台、R8 范围、本地文档链接、仓库卫生（本机绝对路径、个人账号、具体插件仓库名）、[静态源码守卫](<../tools/source_guards.py>)与测试索引是否同步。
- 抗混淆检测仍是启发式：指定修复文件零新增/全量约束，其余按 base-ref 增量；域名/包目录与只用于 View tag 的键依据上下文排除。框架栈名字串不是旧 API 依赖，不因过滤字符串移除安全保护。
- 对通过常量/数组传递的短资源名、弱业务指纹、未知字段/first-match 仍需人工审查；门禁没有匹配不证明全仓跨版本可靠。
- 插件 ABI 门禁：`tools/plugin_abi.py` 从 `tools/plugin_abi_seeds.txt` 计算 public/protected 签名闭包并生成 `app/plugin-abi-keep.pro` 与 `tools/plugin_abi_allowlist.txt`；`:app:verifyPluginAbiSurface` 校验生成文件与编译结果一致，`:app:verifyPluginAbiMapping` 校验 release R8 mapping 未改名/删除白名单类。禁止全包 keep、手改生成文件或把 private/package-private 类型算作插件 ABI。
- 插件包门禁：`tools/plugin_package.py verify` 重新校验 zip/manifest/sha256/ECDSA 签名；`python -m unittest discover -s tools/tests` 覆盖打包/签名/索引/ABI 校验工具。
- 插件 SDK 资产（`adhook-plugin-api-<abi>.jar`、`adhook-test-classes-<version>.jar`，并为 test-classes 提供稳定别名 `adhook-test-classes.jar`）由 `:app:pluginApiJar` / `:app:baseTestClassesJar` 产生，属于构建产物而非 APK 内容；发布时单独上传并记录 ABI/版本，不能拿旧 SDK 冒充当前 ABI。
- [离线工具测试](<../tools/tests/>) mock 网络、git/gh、Gradle、账号/签名配置；失败测试不得真发布或读取生产秘密。
- Lint 错误中止；保留具体 Xposed 场景的精确豁免，警告按反射资源、API 行为、性能/弃用分类。不能全局关 Lint，不能把 R8 当 Lint。

## 本地正式签名构建与设备验证

本地验证包必须与已安装应用使用同一正式证书，不能使用 debug 证书或新生成身份。签名材料只放在本机且不入库：

- 环境变量：`KEYSTORE_FILE`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`、`SIGNING_CERT_SHA256`。
- 或仓库根未跟踪的 `signing.properties`：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`、`certificateSha256`。

```bash
# 缺少正式签名或指纹不匹配时 assembleRelease 会直接失败
./gradlew :app:assembleRelease :app:verifyPluginAbiMapping --console=plain
python tools/verify_apk_signature.py --apk app/build/outputs/apk/release/app-release.apk \
  --expected-sha256 <与已安装应用一致的签名证书 SHA-256>
adb install -r app/build/outputs/apk/release/app-release.apk
```

本地构建只用于测试、Lint、ABI 与设备验证，不产出也不上传 Release 资产；发布仍只走 Tag 工作流。若设备已安装应用与本地证书不一致，停止并核对，不卸载应用、不清数据、不用 debug 包覆盖。

## 构建与发布

正式发布的唯一出口是 [Release 工作流](<../.github/workflows/release.yml>)：

1. 推送形如 `v1.0.20261127` 的不可变 Tag（或手动 `workflow_dispatch` 指定已存在 Tag）。
2. 复用 [Quality Gates](<../.github/workflows/quality.yml>)，对同一 Tag 跑仓库约束、离线工具测试、JVM 回归、Lint 与插件 ABI surface。
3. 在 `release` 环境读取 Secrets，构建 `:app:assembleRelease`、`:app:verifyPluginAbiMapping`、`:app:pluginApiJar`、`:app:baseTestClassesJar`。
4. 用 [verify_apk_signature.py](<../tools/verify_apk_signature.py>) 验证真实 APK 证书，生成 `dist/ADHook-<tag>.apk` 与 `.sha256`。
5. 上传插件 SDK 与 APK 作为工作流产物，并把 SDK jar/sha256 与 APK 一起上传为 Release 资产；用 [github_release.py](<../tools/github_release.py>) 先创建草稿、校验附件大小/状态，再正式发布。
6. 任一构建、验签、上传或附件校验失败都返回非零；草稿存在不是成功，也不覆盖既有 Tag/附件。

必需 Secrets：`SIGNING_KEYSTORE_BASE64`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`、`SIGNING_CERT_SHA256`。这些值只进入 runner 临时目录，工作流结束前删除；仓库、文档、脚本和日志中都不出现明文。

获授权安装前核对实际包名、递增版本及二进制 AndroidManifest 的 minSdk/targetSdk。aapt2 badging 未输出某一 SDK 行不代表字段被删除，应以 xmltree 清单字段核验，不修改 SDK 或跳过校验。覆盖安装后回读版本与实际 base.apk 散列；这些只证明所装产物一致，不证明注入或宿主效果。

## 实际执行与统计验收

- 业务注册与执行分离：run 的 OK 只表示同步注册返回；track 初始化零只声明采集。hit 仅在实际安全短路、成功数据提交、真实完成或首次 owner 折叠后记录，不计反射探测、注册、CSS 注入和放行。
- SDK 成功 Method/Constructor 才去重，缺类/失败可重试；解壳 callback/宿主注册部分失败不永远吞 loader/包。回归必须走实际入口，不测试已退役辅助类来冒充生产覆盖。
- 开关显式 canonical 优先，其次已登记旧别名；shared 安装组按子 feature 独立门控，兼容包按实际包使用配置/授权/统计。未实现功能保持未实现/偏好，不初始化假命中。
- 列表过滤按提交后真实移除量、同 View 同 owner 租约幂等计数；绑定/回收/规则编辑或停用只释放本 owner，不恢复其它规则仍控制的 View。
- 最近各进程会话快照不是终身累计；每包最多 8 个固定进程槽，各槽替换、跨槽饱和相加，受 256 应用、16Ki 输入、128 字段和 UID 频率预算。容量裁剪/旧裸快照/未采集/未上报在控制台分别显示。
- [LatestStatsDelivery](<../app/src/com/adhook/core/LatestStatsDelivery.java>) 尾批/ACK/退避只为有界可靠遥测，短命进程或 Provider 不可用仍可能丢数，不能退回无鉴权广播。

## 配置、订阅与恢复

详细合同见[配置安全](<CONFIG_SECURITY.md>)：

- 正式 API101 启动/完整快照同版本发布，启动版本不能冒充完整规则；后台鉴权校准，回调内存，主线程零 Provider 同步调用。保存、发布、消费与效果分开。
- 宿主数据默认授权但仅限调用者实际拥有的包；AppConfig 存在、列表/详情、开关和备份不改变授权判定，shared UID 不连带其它包，外部 Intent 只导航，授权判定不随备份导入。
- picker/remote 来源与逐条偏好独立；namespace/subscription/pkg/upstreamID 是身份，名称/简类名不是。旧同 ID 归属歧义保留并提示，不静默覆盖/删除。
- 订阅输入按原始类型预校验，非法项拒绝整份更新，不静默丢字段。
- 恢复先全量验证、保存恢复前快照；任一步失败反馈并尽力回滚，不静默清空。断电/多文件备份流程仍非跨文件耐久事务，保留人工恢复材料。

## 正式签名

**保留既有证书身份，不自动换钥匙。** 正式 keystore、口令、私配不进入 Git，也不写入仓库脚本。CI 通过 GitHub Actions Secrets 注入环境变量；本地可在未跟踪的 `signing.properties` 中提供同名键：

| 环境变量 | 本地 properties 键 | 含义 |
| --- | --- | --- |
| `KEYSTORE_FILE` | `storeFile` | keystore 路径 |
| `STORE_PASSWORD` | `storePassword` | keystore 口令 |
| `KEY_ALIAS` | `keyAlias` | 签名 alias |
| `KEY_PASSWORD` | `keyPassword` | key 口令 |
| `SIGNING_CERT_SHA256` | `certificateSha256` | 期望的证书 SHA-256 指纹 |

[app/build.gradle.kts](<../app/build.gradle.kts>) 优先读取环境变量，其次读取未跟踪的 `signing.properties`；`assembleRelease` 依赖证书指纹校验，缺少材料或指纹不匹配时失败，不会回退 debug 证书。纯编译/Lint/单测不要求生产签名。

停止 tracked 不删除 Git 历史/其它克隆的旧秘密。访问控制、历史重写、轮换及 Android 升级兼容由负责人独立授权；本轮不读秘密值、不改历史、不配远端 Secrets。

## 发布合同

- [Quality Gates](<../.github/workflows/quality.yml>) 不读生产 Secrets；[Release](<../.github/workflows/release.yml>) 针对同一不可变 Tag 在门禁成功后进入 `release` 环境，先验证 built APK 再复制 dist，上传草稿验证完成后才发布。两者显式安装 platform36/Build-Tools36。
- [发布器](<../tools/github_release.py>)不覆盖已有 Tag/附件，不强推失败版本。网络/git/gh/签名/附件失败非零，草稿存在不是成功。
- 授权 HTTP 仅标准 GitHub HTTPS authority，拒绝重定向、非标准端口/用户信息 URL；恢复失败发布现场需人工明确批准，不自动强推同名 Tag。
- 工作流引用 release 环境不证明远端保护已配置；负责人仍须配置审批/Secrets。本地通过不等于远端 CI/发布/安装/真机；交付保留报告、dump 和旧产物，不自动清场。

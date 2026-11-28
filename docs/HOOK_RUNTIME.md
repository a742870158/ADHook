# Hook 执行架构

## 实现与验证状态

现役后端为原生 API101，传统入口/API82 依赖及旧共享文件通道已退休。最近一次验证结果以 [GitHub Actions](<https://github.com/a742870158/ADHook/actions>) 和本地命令输出为准；迁移与整改过程以 Git 历史为准。本文只描述公共合同；宿主适配现状、边界与验证状态由各插件仓库自行维护，文档校准不算修复。

## 两个独立层次

1. **LSPosed 作用域**决定框架是否把模块装入宿主。作用域内提前注册业务方法和轻量观察器，不等待模块 App Provider 启动。
2. **Hook 在作用域内默认执行**；所属原生 feature 只控制断源、数据改写、折叠和增强。feature 关闭和 UNKNOWN 放行原方法；插件 feature 默认关闭，需在应用详情逐个开启。FeatureGuard 单次取得实际包的内存 AppConfig，用同一对象判断 feature，不组合后台刷新前后两个快照的许可。

不再提供每应用 Hook 开关；旧配置中的 `hookEnabled=false` 会被忽略并视为默认开启。不勾作用域的应用不会被注入，敏感应用建议不勾作用域。`scope.list` 为空，实际作用域由用户在 LSPosed 中勾选。

## 宿主插件层

底座不内置任何第三方宿主深度 Hook；宿主深度适配由独立维护的签名插件提供，用户本地导入或从市场镜像安装。插件加载失败一律 fail-open（只保留通用层），不回退已删除的内置适配。

- **发布链路**：主 App 在 IO 线程校验 zip/清单/ECDSA 签名/TOFU/ABI/底座版本/目标冲突后安装到私有目录；`PluginArtifactPublisher` 先把 dex 写入 LSPosed remote files 并校验大小，再把路由快照 commit 到独立 RemotePreferences 组 `adhook_plugins_v1`；最后才清理旧 dex。任一步失败保留旧发布。
- **宿主加载**：`AdHookModule` 初始化 `PluginBootstrap`；`HookDispatcher.performInjection` 在安装通用 SDK/规则/拾取器后按包名读取一次 remote 快照（≤64KB），匹配插件后用 `InMemoryDexClassLoader` 加载 entry，校验 dex sha256/ABI/minBaseVersion/`HostHook` 身份，再用清单 features（默认全部关闭）调 `FeatureGuard.configureHost` 并执行 `install`。
- **通道边界**：宿主侧只使用 framework remote preferences / remote files；不调用 ConfigProvider、不解析完整配置、不读宿主私有盘。读取失败、快照非法、哈希不符、缺类或 entry 异常均 fail-open，不得阻断底座通用 Hook，也不得把注册成功当实际拦截。
- **生效**：插件安装即默认启用，功能规则默认关闭，需在应用详情逐个开启；安装/更新/卸载不创建 AppConfig、不改变宿主授权判定。更新/卸载对已运行宿主不热生效，UI 必须提示强退重开。插件加载失败只 fail-open 到通用层，不回放、不补装第二个适配。
- **ABI**：插件只链接底座 ABI 名单中的 public/protected 类。白名单/keep 由 `tools/plugin_abi.py` 从 `tools/plugin_abi_seeds.txt` 生成，release 必须通过 `:app:verifyPluginAbiSurface` 与 `:app:verifyPluginAbiMapping`；破坏链接签名的变更必须提升 `PluginConstants.PLUGIN_ABI`，旧插件在安装与加载两侧都会被安全拒绝。

## API101 原生调用链与反射边界

- [AdHookModule](<../app/src/com/adhook/AdHookModule.java>) 是框架入口，由 `META-INF/xposed/java_init.list` 按名加载；先建立原生后端，再交 [HookDispatcher](<../app/src/com/adhook/core/HookDispatcher.java>) 分发 HostContext。不得用 Unsafe 构造传统 LoadPackageParam，不打包或自造 API82 stub。
- runtime 的 HookBridge/HookCallback/HookReplacement/HookHelpers/HostContext 承载原生 HookBuilder/Chain/Invoker 合约；before/after 优先级、可变参数、返回值、异常、原方法调用及 unhook 均有原生接口录制回归。安装 scope 以显式包/feature、原 callback 定义、优先级、Member 和注册源区分补装；门面转交原 callback 类型，内部栈过滤用类字面量名称而非混淆前字串。此身份只用于注册，不是宿主业务指纹。
- [HookRegistration](<../app/src/com/adhook/core/HookRegistration.java>) 保存/恢复包与 feature 归属，在 before/after 执行时分别查内存 gate，保留原参数和异常语义。共享多功能注册不锁在首个 owner。
- [R8](<../app/proguard-rules.pro>) 只精确保留框架入口、Android 组件及 CallbackMethods 反射查找的 beforeHookedMethod/afterHookedMethod 基类/子类成员名。配置模型使用显式 JSON key，不靠全包 keep 保持字段名；后端不按模块类名字串反射安装。仅保 com.adhook 及子包的 package 前缀以维持 Sensor 模块栈排除，类/成员仍可收缩混淆，不是全包 class keep 或全局 dontobfuscate。
- 当前仅保留精确 API101 合约：生产 compileOnly、测试使用同 jar，运行时实现由框架提供；API82 名字仅允许已标明历史或框架栈排除，不是现役兼容后端。

## 正式共享与完整性

- [AdHookApplication](<../app/src/com/adhook/AdHookApplication.java>) 注册正式 service101，框架提供的 XposedProvider 由 AAR 合并；不新增外部 Intent/广播写配置入口。
- [ConfigManager](<../app/src/com/adhook/config/ConfigManager.java>) 串行写盘并单调递增配置版本，[RemoteConfigPublisher](<../app/src/com/adhook/config/RemoteConfigPublisher.java>) 在工作线程同一次 Editor commit 发布完整 config_json 与启动 hook_launch_json。
- 启动 v2 包含总 Hook、原生偏好、拾取器与全局策略，只供加载早期使用；**启动快照不等于完整规则快照**。同版本 scoped/full 响应仍须升级完整性，不能因已有启动版本而跳过规则装载。
- [ConfigClient](<../app/src/com/adhook/config/ConfigClient.java>) 在入口读取框架受信启动偏好，回调仅查缓存。完整读取、版本刷新、按调用者自有包校验的 Provider 校准在工作线程；主线程不做 Provider IPC/完整 JSON/磁盘读取。
- legacy XSharedPreferences、私有文件直读和 world-readable CE/DE 镜像已退休；正式 API101 RemotePreferences 与按调用者自有包校验的后台 Provider 是现役通道。不开公共可写文件通道，不手写 daemon 目录或修改框架数据库。

### 保存、发布、消费、效果

本地保存不证明远程 commit 成功，远程确认不证明某个宿主消费或广告效果。控制台显示 desired/published/error/attempts 与宿主状态，失败有限重试，不回滚用户关闭选择。预算校准可以纠正旧远程快照；冻结 Binder/系统时不承诺“最迟 5 秒”或硬传播上界。

需要立即停止尚未确认的业务时，用户可取消 LSPosed 作用域并强退重开宿主。普通声明式/原生关闭仅精确释放明确 owner；已清洗数据、派生实例、旧 LEGACY owner 和历史磁盘预写原值不明，不静默恢复或覆盖用户数据。

## 注册、重试与 UI

- FeatureGuard.run 是注册归属与异常隔离，run/track/OK 不是命中。业务实际改写/短路/成功完成/首次 owner 折叠后才 hit。
- 解壳、ClassLoader、生命周期 readiness 和成对绑定前/回收清理是 raw 观察；观察不是无条件业务许可证。成功 Member 才去重，缺类/失败允许补装；callback 失败及部分注册失败须向 Dispatcher/探针上报，不以另一个成功项掩盖。独立组件均尝试后聚合失败；规则观察未完整时保留观察/自身 owner 回收，不新增业务折叠。
- [ObserverHookRegistry](<../app/src/com/adhook/core/ObserverHookRegistry.java>) 用于 raw 基础观察，弱 loader/Member/Unhook 按宿主 package/process、owner/feature、原 callback 来源、注册源和 priority 隔离；active 复用成功身份，unhook 后采用新 closure。动态无 scope 只能依据声明 loader；捕获其它 loader 的框架 Member 必须提供真实 HostContext，不猜注入身份。
- [ActivityUiCoordinator](<../app/src/com/adhook/core/ActivityUiCoordinator.java>) 是有限兜底，不重放生命周期、不点击、不无限轮询。任务弱引用、代次取消，每轮检查总 Hook/所属 feature；首页/底栏首次接入不代表所有宿主自动修复。
- CustomRuleEngine 以[候选索引](<../app/src/com/adhook/engine/RuleCandidateIndex.java>) 保持资源全名/历史尾名、类名后缀、文本属性/trim 语义与规则顺序；每节点最多 512 候选、文本/描述 8192 字符，超限拒绝整个节点且只释放自身规则 owner，不提交局部候选。树遍历使用[弱引用分帧任务](<../app/src/com/adhook/engine/RuleTreeTraversal.java>)：最多 32 待处理根、4096 节点/深 64，每片 64 步、从 8 步起复核 4ms 协作预算；同步 getter 不可抢占，不保证绝对墙钟或耗电收益。重复根请求合并，规则刷新按实际根合并已知来源；配置、包开关、挂载/父链/复用及暂停/销毁守门，生命周期恢复后重新准入。绑定前窗口尚未显示不是拒绝折叠的证据。
- 优先 SDK/协议/Adapter 在数据进界面前断源，其次挂载/绑定首次绘制前 owner 折叠；已经执行的请求或构造函数不能倒转。
- Activity 必须正常执行 super.onCreate。未进入生命周期的已知广告启动可在 execStartActivity 按 SDK/所属宿主 feature 阻断；已进入实例只能在原 onCreate 正常返回后确认安全去向再退出。公共 [SplashActivityCompletion](<../app/src/com/adhook/hook/support/SplashActivityCompletion.java>) 仅在已验证主路由被接受或确认非任务根时允许 finish，异常/未知根/路由失败放行，实际完成才计数，转场异常不掩盖已完成动作。不据此保证零等待或 SDK 奖励；普通 reward/award 路由保留既有豁免，显式宿主路由独立门控。

## 已精确退出的危险路径

底座不再保留任何宿主专属的危险旁路：静态广告常量清空、启动标记预写、Application/会员字段持久改写、自动点击、进程范围 TLS 证书/链强度绕过、原地删卡和分页短路均不属于底座能力。需要这些行为的宿主必须在插件中单独实现、单独授权、单独验收；未知卡片/路由放行，不宣称已移除。

## 平台与验收

支持下限 Android12（minSdk31），compileSdk36、targetSdk34，生产 Java17；AGP8.10.1、Gradle8.13、Kotlin/Compose 和既有签名不整体更换，不据警告擅自改变目标行为。

插件运行时依赖框架的 `PROP_CAP_REMOTE` 能力；框架不支持 remote files 时插件不可用，UI 必须显示原因，底座通用能力照常。源码、离线合约、JVM/Robolectric、Lint、R8 APK/证书、插件 ABI mapping、实际框架和手机是不同层次。Android15/16/17 分别验收，旧设备结果只代表明确版本/场景。门禁命令和发布失败边界见 [工程说明](<QUALITY_AND_RELEASE.md>)。

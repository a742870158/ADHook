# 插件开发与发布

本文说明如何用本仓库的打包/签名工具链开发、测试并发布 ADHook 宿主插件。格式契约见[插件包格式](<PLUGIN_FORMAT.md>)，市场索引与信任模型见[插件市场](<PLUGIN_MARKET.md>)，本机工具链见[开发与运行时取证](<DEVELOPMENT.md>)，发布门禁见[工程验证与发布](<QUALITY_AND_RELEASE.md>)。

宿主插件源码、测试与宿主文档由各插件仓库自行维护，不进入本仓库。本仓库只保留运行时、插件 ABI 工具与打包工具；`tools/plugin_package.py` + `tools/plugin-tools/*.java` 在底座与插件项目之间必须逐字节一致。

## 1. 工具链与前置条件

| 组件 | 要求 |
| --- | --- |
| JDK | 17+；`javac`/`java`/`jar` 均需可用（`JAVA_HOME` 优先，缺失时用 PATH） |
| Android SDK | `platforms/android-36/android.jar` + `build-tools/36.0.0/d8.bat`（Linux/macOS 为 `d8`） |
| Python | 3.11，仅标准库 |
| 插件 API | 底座 Release 资产中的 `adhook-plugin-api-<abi>.jar`（至少含 `com/adhook/config/model/FeatureMeta.class`） |

[plugin_package.py](<../tools/plugin_package.py>) 自动探测工具链：`java`/`javac` 优先 `JAVA_HOME`，Android SDK 取 `ANDROID_HOME`/`ANDROID_SDK_ROOT`；D8 优先 `build-tools/36.0.0`，缺失时取版本最高者。可用环境变量覆盖：`ADHOOK_PLUGIN_JAVA_HOME`、`ADHOOK_PLUGIN_ANDROID_SDK`、`ADHOOK_PLUGIN_ANDROID_JAR`、`ADHOOK_PLUGIN_D8`。个人固定路径只放在 `.local/`（已 gitignore）。

实现说明：

- 编译使用 `javac -source 17 -target 17 -classpath <plugin-api>:<android.jar>`；产物先打成临时 jar 再交给 D8（当前 D8 不接受 class 目录作为 program input），D8 参数为 `--min-api 31 --lib <android.jar> --classpath <plugin-api>`。
- 所有失败（缺工具、编译、D8、特性提取、验签、大小超限）都以非零退出并打印人类可读错误；不会静默产出空插件。
- `SOURCE_DATE_EPOCH`（秒）同时决定 `publisher.publishedAt`（毫秒）、`market/index.json.updatedAt` 与 zip 条目时间戳；签名每次随机 `k`，因此**签名 zip 的 sha256 不可复现**，但 `plugin.json` 与载荷字节可复现。
- 临时文件一律使用 `tempfile`；工具不在调用者未指定的位置写文件。

## 2. `plugin.properties`

`build` 的输入是宿主插件目录下的 `plugin.properties`（UTF-8、`key=value`、独占一行以 `#` 开头为注释；未知键、重复键、空值、非法值均报错）：

```properties
# 必填
id=com.adhook.plugin.xhs
name=小红书深度适配
version=1.4.0
versionCode=10400
entry=com.adhook.hook.com.xingin.xhs.XhsHook
targets=com.xingin.xhs:小红书:com.xingin.xhs
description=小红书去广告与体验增强插件
authorName=社区维护者

# 选填
authorUrl=https://example.com/maintainer
minBaseVersionCode=30000000
assets=assets/config.json;assets/rules
readme=README.md
summary=市场列表摘要（缺省取 description 前 200 字符）
changelog=本版本变更说明（进入市场索引）
```

- `targets` 分号分隔多个目标，`packageName:label:primary` 冒号分隔，`label`/`primary` 可空；禁止 `android`、`com.android.systemui`、`com.adhook`；上限 16 项。
- `assets` 分号分隔，路径相对 `--source` 源目录：文件条目入包为 `assets/<文件名>`，目录条目入包为 `assets/<相对路径>`；禁止 `..`、绝对路径、符号链接，单文件 ≤4 MiB。
- `readme` 可选，路径相对插件目录（如 `README.md`），必须是 UTF-8 Markdown；构建时统一入包为 `assets/README.md`，清单生成 `"readme": "README.md"`。控制台插件详情会展示它，最多 512 KiB；一仓多包时每个插件目录各放一份自己的 README，不要共用仓库根 README。
- 生成的 `plugin.json` 使用 camelCase 权威字段（`authorName`/`authorUrl`、`targets[].packageName`、`features[].defaultEnabled`、`files` 对象、`publisher.publishedAt`），不要手写清单。

## 3. 命令

以下命令在插件项目根执行，`<...>` 按实际路径替换：

```bash
# 1) 生成发布者密钥对：plugin-private.pem（PKCS#8）+ plugin-public.txt（单行 base64 SPKI）
python tools/plugin_package.py keygen --out-dir dist/keys

# 2) 编译 + D8 + 提取 features + 生成 plugin.json + 签名 + 打包 + 自检
python tools/plugin_package.py build --source plugins/xhs/src \
  --plugin-properties plugins/xhs/plugin.properties \
  --out dist --plugin-api sdk/adhook-plugin-api-2.jar \
  --feature-classpath build/test-classes.jar \
  --private-key dist/keys/plugin-private.pem --sign

# 3) 只读校验（布局/清单/哈希/签名，不联网）
python tools/plugin_package.py verify --zip dist/com.adhook.plugin.xhs-1.4.0.zip

# 4) 生成市场索引；zipUrl 相对索引 URL（部署时 index.json 与 plugins/ 同基路径）
python tools/plugin_package.py index --dir dist --base-url "https://<owner>.github.io/<repo>" \
  --name "ADHook Community Plugins" --out dist/market/index.json

# 5) 仅提取 features 目录（调试入口类用）
python tools/plugin_package.py features --plugin-api sdk/adhook-plugin-api-2.jar \
  --classes build/tmp/classes --entry com.adhook.hook.com.xingin.xhs.XhsHook
```

`build` 输出 `dist/<id>-<version>.zip` 与同名 `<name>.json` 发布元数据（市场索引的输入）；不带 `--sign` 只产出未签名包，`verify` 会拒绝它，不得发布。`--min-base-version-code` 可覆盖 `plugin.properties` 中的值。`index` 的 `--zip-url-base` 可选：给出后 `zipUrl` 生成该绝对基路径下的 URL（用于 GitHub Release 资产），缺省保持 `plugins/<file>` 相对路径。

**`--feature-classpath`（可重复，`build` 与 `features` 都支持）**：`plugin-api` jar 按 ABI 签名闭包裁剪，入口类执行 `getFeatures()` 时可能链接到未保留的底座实现/匿名/内部类（例如 `FeatureGuard` 调用的实现 helper），导致提取失败。该参数为提取器追加运行时类路径（jar 或目录，与 `plugin-api`、`android.jar`、编译产物一起），只影响构建期反射，不进入发布包或签名。可从底座 Release 下载稳定别名 `adhook-test-classes.jar`（或版本化 `adhook-test-classes-<version>.jar`）；不传时保持旧行为（仅 `plugin-api`）。

## 4. 密钥管理

- 私钥只存在于本机 / CI Secret。插件仓库 `.gitignore` 必须忽略 `dist/`、`out/`、`*.pem`、`*.jks`、`keystore/`、`local.properties`；禁止提交、打印或上传私钥内容。
- `keygen` 拒绝覆盖已存在的密钥文件；`plugin-private.pem` 在 POSIX 上写为 0600。
- CI 使用 Secret `PLUGIN_SIGNING_KEY_B64`（PEM 文件的 base64 单值），工作流解码到临时目录、`chmod 600`，构建后删除。
- 发布者身份 = `keyId`（`sha256(SPKI)` 前 16 字节）。同一 `keyId` 的任何发布都视为同一发布者；轮换密钥会产生新 `keyId`，市场侧按 TOFU 要求用户显式重信，且**旧密钥不能被静默替换**。
- `sign` 会先校验私钥派生公钥的 keyId 与入参一致，签名后立即用公钥验签；任何不一致都失败。

## 5. 插件 ABI 与底座版本

- 插件只依赖底座发布的插件 API（`plugin-api` jar）与 Android SDK，不依赖底座内部实现类。
- `plugin.json.abi` 当前为 `1`；`minBaseVersionCode` 声明所需底座最低版本，底座低于该值拒绝安装/加载。
- 底座通过 `tools/plugin_abi.py` 从种子类生成 ABI keep/allowlist，只统计 public/protected 成员；release 必须通过 `verifyPluginAbiSurface` 与 `verifyPluginAbiMapping`。禁止 `com.adhook.**` 全包 keep。破坏插件链接签名的变更必须提升 `PLUGIN_ABI`，让旧插件安全禁用而不是崩溃。
- 插件项目可用 `sdk.properties` 固定底座来源，CI 据此下载 `plugin-api`/测试类：

```properties
base.repo=https://github.com/<owner>/ADHook
abi=2
base-version=30000000
plugin-api-url=https://github.com/<owner>/ADHook/releases/download/<tag>/adhook-plugin-api-2.jar
plugin-api-sha256=
test-classes-url=https://github.com/<owner>/ADHook/releases/latest/download/adhook-test-classes.jar
test-classes-sha256=auto
market-base-url=https://example.com/<set-me>
```

URL 仍是占位（`example.com`、`<...>`、`SET-ME`、`TODO` 或空值）时，CI 必须失败并提示配置，不得伪造下载成功。`test-classes-sha256=auto` 表示 CI 下载 `adhook-test-classes.jar.sha256` 并校验；固定版本时也可直接填写 64 位哈希。

## 6. 插件项目结构

一个插件项目可以只放一个插件，也可以一仓多包；底座不限定目录名，推荐：

```text
<plugin-repo>/
  README.md  AGENTS.md  LICENSE  .gitignore
  sdk/adhook-plugin-api-2.jar  sdk/sdk.json
  tools/plugin_package.py  tools/plugin-tools/*.java
  scripts/check-plugins.py     # 离线预检：id/宿主包全仓唯一、入口类存在
  plugins/<plugin-id>/
    plugin.properties
    README.md                  # 可选但推荐；控制台插件详情展示
    src/**/*.java
    src/assets/**              # 可选
  tests/java/**  tests/libs/**  # 可选
  docs/apps/<宿主包名>.md       # 宿主文档由插件项目维护
  sdk.properties               # test-classes 来源与商店基路径（CI 前置配置）
  .github/workflows/plugin-ci.yml
```

1. **一仓多包**：全仓共用一份 `sdk/`、`tools/` 与发布者密钥；单包构建可用 `--plugin-properties` 指向目标插件；插件之间不能互相 import。
2. 每个插件独立 `version`/`versionCode`；新增插件只递增它自己的版本号，并同步宿主文档与项目内 catalog。
3. 分支/PR 修改后在本地 `build --sign` + `verify`，提交 `plugin.properties`、`src/`、`docs/`；不提交 `dist/`、密钥或 zip。
4. CI：静态门禁 → 插件清单预检 → SDK 摘要校验 → `sdk.properties` 配置校验 → JVM 测试 → 全量 `build --sign`（密钥来自 Secret，`--feature-classpath` 用 test-classes jar）→ 逐个 `verify` → `index` artifact（**不发布**）。
5. 发布由维护者显式执行：把 zip + 同名 json 放进商店站点/仓库并重建索引；发布工作流失败时不得上传旧包冒充新产物，不覆盖已发布版本/资产。

## 7. 测试

```bash
# 纯 Python 契约测试 + 本机有 JDK17/Android build-tools 时的端到端构建（否则显式 skip）
python -B -m unittest discover -s tools/tests -p 'test_*.py' -v

# 只跑工具链相关用例
python -B -m unittest discover -s tools/tests -p 'test_plugin_package.py' -v
```

端到端用例覆盖：keygen、最小插件 `build --sign`、`verify`、篡改载荷/清单必须失败、未签名包仅可 inspect 不可 verify、索引生成与元数据篡改拒绝；无工具链的环境跳过并给出原因，不能算通过。

## 8. 发布与托管

发布物：`dist/<id>-<version>.zip`、同名 `.json` 元数据、`market/index.json` 与全部 `plugins/<file>.zip`。`release.size`/`sha256` 是 zip 文件字节，安装器据此校验；`index.json` 建议 ≤2 MiB。

**GitHub Pages（推荐，相对 zipUrl）**

```text
<owner>.github.io/<repo>/
  index.json                 # index 输出
  plugins/<id>-<version>.zip # dist 中的 zip
```

`index.json` 必须与 `plugins/` 位于同一基路径（工具生成的 `zipUrl` 为 `plugins/<file>`，客户端按索引 URL 解析）。发布步骤：`build --sign` → `verify` → `index --base-url https://<owner>.github.io/<repo>` → 把 `index.json` 与 zip 同步到 Pages 分支/产物。

**GitHub Releases（绝对 zipUrl）**

把 zip 上传为 Release 资产后，用 `index --zip-url-base https://github.com/<owner>/<repo>/releases/download/<tag>` 生成索引，`zipUrl` 为绝对 URL；`index.json` 可托管在 Pages 或任意静态站点。发布后不要删除/替换同名资产（客户端以索引 sha256 校验，替换会导致旧客户端校验失败）。

**本地开发**

未签名包只用于本机开发者模式；远端市场/索引不得收录未签名包。

## 9. 外部开发者与 SDK

底座是 Apache-2.0 开源项目；外部开发者可从本仓库 Release 获取 SDK 资产，在独立仓库开发插件，不需要底座源码或内部实现类。

- **SDK**：`adhook-plugin-api-<abi>.jar` + 底座 Release 中的同名 `.sha256`；需要测试类时使用稳定别名 `adhook-test-classes.jar` 或版本化 `adhook-test-classes-<version>.jar`。不要自行编译底座冒充 SDK。
- **发布者身份**：一个插件项目多款插件共用一份发布者密钥（用户只需信任一个发布者）；插件之间不能互相 import，共享逻辑只能复制或上收成底座 ABI。
- **商店只收签名包**：`index` 会验 ECDSA P-256 签名并核对 zip 内清单与元数据；未签名包只能在开发者模式本地导入。
- **与运行时的边界**：安装即默认启用，但不等于作用域勾选；插件功能规则默认关闭，需在应用详情逐个开启。ABI 破坏性变更必须提升 `PLUGIN_ABI`。

**SDK 分发**：`adhook-plugin-api-<abi>.jar` 与 `adhook-test-classes.jar` / `adhook-test-classes-<version>.jar` 由 Release 工作流随 APK 一起上传为 Release 资产，并为每个 jar 生成同名 `.sha256`；插件项目可引用 `releases/latest/download/adhook-test-classes.jar` 稳定别名，或 `releases/download/<tag>/...` 固定版本，本地构建用下载后的副本 + sha256 校验。

## 10. 设备 spike 清单（需用户明确授权）

以下项目是插件运行时从“JVM/R8 可编译”进入“真实 LSPosed 可用”的阻塞门；未执行前不能宣称插件功能已在设备上可用。设备安装、强退、日志抓取和截图均需单独授权，命令不自动执行。

1. 确认目标设备已安装并启用 ADHook、LSPosed 版本支持 API101 且暴露 remote files/preferences 能力（console 插件页会显示服务状态；模块日志应出现 `PluginBootstrap` 相关诊断而不是静默跳过）。
2. 用本地测试发布者密钥构建一个试点插件（`build --sign` + `verify`），在 ADHook 插件页“本地导入”安装；首次信任需展示完整发布者指纹。
3. 强退并重开目标宿主，确认：模块日志出现 remote dex 发布成功、`PluginBootstrap` 匹配到插件、插件 entry 加载；底座通用拾取器/规则仍工作。
4. 在应用详情逐个打开对应插件功能，复测已有深度特性；确认功能关闭时原方法放行（不能用注册日志冒充命中）。
5. 卸载插件 → 强退重开 → 确认不再加载；再确认 AppConfig/用户规则保留、只删除插件目录。
6. 负向验证：未签名包在非开发者模式拒绝；篡改 zip 的 dex/签名拒绝；ABI 或 `minBaseVersionCode` 不符拒绝；发布者 key 变化阻断。
7. 用 release 签名底座 + 按 release plugin-api 资产构建的试点包重复 3–5，验证 R8 后 ABI 链接真实可用（JVM mapping 通过不等于设备链接通过）。
8. 记录设备型号/Android/LSPosed/底座版本、插件版本/签名指纹、成功与失败日志；真机结果与本地测试分开报告。只有这些证据通过，才能说该宿主插件在设备上可用。

设备结果由插件项目与用户维护，本仓库不保存宿主验证记录或迁移批次。

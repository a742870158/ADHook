# 插件包格式（schema 1 / ABI 2）

本源文件是插件 zip、`plugin.json`、签名与市场索引的**契约级**说明。权威实现分两部分：底座运行时解析器（宿主侧安装/加载）和打包工具 [plugin_package.py](<../tools/plugin_package.py>)。三者必须同步修改；运行时校验更严格时以运行时代码为准，任何字段变更都要同时提升契约版本并回归两边。

相关文档：[插件开发与发布](<PLUGIN_DEVELOPMENT.md>)、[插件市场](<PLUGIN_MARKET.md>)、[Hook 执行架构](<HOOK_RUNTIME.md>)、[工程验证与发布](<QUALITY_AND_RELEASE.md>)。

## 1. zip 布局

| 条目 | 必需 | 说明 |
| --- | --- | --- |
| `plugin.json` | 是 | 插件清单，**原始字节**原样入包（签名哈希的是该字节） |
| `classes.dex` | 是 | 插件字节码，D8 `--min-api 31` 输出 |
| `assets/**` | 否 | 插件静态资源，逐文件声明在 `files` |
| `META-INF/plugin.sig` | 签名包必需 | 发布者签名信封 |

- 条目名仅 ASCII，匹配 `^[A-Za-z0-9._\-/]+$`；使用 `/` 分隔，禁止目录项、绝对路径、反斜杠、`.`/`..` 段、重复项（含大小写不同）；禁止符号链接与加密条目；压缩方法只接受 stored/deflated。
- zip 文件 ≤16 MiB；DEX ≤12 MiB；解压总量 ≤48 MiB；条目 ≤256；`plugin.json` ≤64 KiB；单个 `assets/**` ≤4 MiB；`META-INF/plugin.sig` ≤4096 B。
- 构建固定 zip 条目时间戳为 `1980-01-01` 并固定顺序（`plugin.json` → 载荷按名升序 → `plugin.sig`），未签名包可逐字节复现；ECDSA 签名每次使用新的随机 `k`，因此**签名 zip 的 sha256 每次不同**，市场索引只记录当次发布的 zip 字节。

## 2. `plugin.json`

键名区分大小写、全部 camelCase；字符串 UTF-8；时间戳为 epoch **毫秒**。未知键、缺失必需键、JSON 重复键、UTF-8 BOM 一律拒绝。

```json
{
  "schema": 1,
  "id": "com.adhook.plugin.xhs",
  "name": "小红书深度适配",
  "version": "1.4.0",
  "versionCode": 10400,
  "abi": 2,
  "minBaseVersionCode": 30000000,
  "authorName": "社区维护者",
  "authorUrl": "https://example.com/maintainer",
  "description": "小红书去广告与体验增强插件",
  "entry": "com.adhook.hook.com.xingin.xhs.XhsHook",
  "targets": [
    {"packageName": "com.xingin.xhs", "label": "小红书", "primary": "com.xingin.xhs", "processes": []}
  ],
  "features": [
    {"id": "xhs_watermark_blocker", "name": "原生图片保存去水印", "description": "…", "defaultEnabled": true, "implemented": true}
  ],
  "files": {
    "assets/config.json": "<sha256 hex>",
    "classes.dex": "<sha256 hex>"
  },
  "publisher": {
    "keyId": "<sha256(SPKI DER) 前 16 字节小写 hex>",
    "publicKey": "<base64 X.509 SPKI DER 单行>",
    "publishedAt": 1700000000000
  }
}
```

字段约束：

| 字段 | 约束 |
| --- | --- |
| `schema` | 整数 `1` |
| `id` | `^[a-z0-9][a-z0-9._-]{2,63}$`，同一仓库/索引内唯一 |
| `name` | 1–64 字符 |
| `version` | `^[0-9][0-9A-Za-z._-]{0,63}$` |
| `versionCode` | 正整数（≤2^31-1），升级必须递增 |
| `abi` | 整数 `1`；插件 ABI 破坏性变更时提升 |
| `minBaseVersionCode` | ≥0；底座版本低于该值时拒绝安装/加载 |
| `authorName` | 1–128 字符，必填 |
| `authorUrl` | 可选；必须是 http/https、无凭据 URL |
| `description` | 1–4096 字符 |
| `entry` | Java 二进制类名，`getFeatures()` 反射入口 |
| `targets` | 1–16 项；`packageName` 为合法 Android 包名且**禁止** `android`、`com.android.systemui`、`com.adhook`；`label` 0–64；`primary` 可空、非空时须为合法包名；`processes` 0–16 个合法进程名（包名或 `包名:进程`，空数组表示所有进程） |
| `features` | 0–64 项；`id` 必须满足 `ConfigInputPolicy.isFeatureId`（`[A-Za-z0-9._-]{1,128}`）且唯一；`name` 1–128；`description` 0–2048；`defaultEnabled`/`implemented` 为布尔 |
| `files` | 对象：名字→小写 sha256 hex；**必须精确等于** zip 中 `classes.dex` 与全部 `assets/**` 的集合，不多不少；名字需满足 zip 条目规则；`classes.dex` 必须声明 |
| `publisher` | 签名包必需（未签名开发包可省略）：`keyId` 32 位小写 hex；`publicKey` 为单行 base64 P-256 SPKI DER；`publishedAt` 为 ≥0 的 epoch 毫秒；`keyId` 必须等于 `sha256(SPKI DER)` 前 16 字节（32 hex） |

未签名包（缺 `META-INF/plugin.sig`）只允许开发者模式显式放行；`publisher` 即使存在也视为未签名。空白字符串的 `publisher` 字段按缺失处理。

ABI 2 的宿主运行时只发布 `classes.dex` 与路由快照；`assets/**` 会随插件安装保存在模块私有目录，但不会自动发布到宿主进程。插件入口不得依赖读取 zip/assets 中的资源；需要随包数据时应在构建期嵌入 dex 或等后续 ABI 增加资源发布契约。

## 3. features 反射契约

`entry` 类必须可无参实例化，并公开 `java.util.List getFeatures()`；元素需提供以下 getter（与底座 `com.adhook.config.model.FeatureMeta` 一致）并映射为清单字段：

| getter | manifest 字段 |
| --- | --- |
| `getId()` | `id` |
| `getName()` | `name` |
| `getDescription()` | `description` |
| `isDefaultEnabled()` | `defaultEnabled` |
| `isImplemented()` | `implemented` |

提取器 [PluginFeatureExtractor.java](<../tools/plugin-tools/PluginFeatureExtractor.java>) 在独立 `URLClassLoader` 中加载入口类；任何加载/实例化/调用失败或 id 非法、重复，都以非零退出并打印错误，绝不静默输出空数组。

`plugin-api` jar 是按 ABI 签名闭包裁剪的：入口类执行 `getFeatures()` 时若调用 `FeatureGuard.registerAlias` 等底座实现，可能链接到未被裁剪保留的匿名/内部/实现类。因此提取器支持在 `plugin-api`、`android.jar`、编译产物之外追加**运行时类路径**（`build --feature-classpath JAR_OR_DIR` 可重复；`features` 子命令同名），缺省只使用 `plugin-api`。这是构建期工具参数，不进入 `plugin.json` 或签名范围；发布包只包含 `classes.dex`/`assets`。

## 4. 签名规范

算法固定 `ECDSA P-256 (secp256r1) + SHA-256`。`META-INF/plugin.sig` 是 UTF-8 JSON（单行紧凑或任意合法排版均可，不参与签名）：

```json
{"schema":1,"algorithm":"ECDSA_P256_SHA256","keyId":"<32 hex>","signature":"<base64 DER ECDSA 签名>"}
```

待签名字节（UTF-8，无 BOM、无额外空白；`plugin.json` 为 zip 内原始字节）：

```text
adhook-plugin-sig-v1\n
<hex(sha256(plugin.json 原始字节))>\n
<name> <hex(sha256(文件字节))>\n   # 按 name 的 ASCII 升序，每个 files 条目一行
```

示例（`files` 为 `assets/a.json`、`classes.dex`）：

```text
adhook-plugin-sig-v1
<64 hex>
assets/a.json <64 hex>
classes.dex <64 hex>
```

- `signature` 是 `SHA256withECDSA` 的 **DER** 签名（`SEQUENCE { INTEGER r, INTEGER s }`），不得输出 raw `r||s`。
- `keyId` = `sha256(SPKI DER)` 前 16 字节的小写 hex；`plugin.sig.keyId` 必须等于 `plugin.json.publisher.keyId`，`publisher.publicKey` 解码后重新计算的 keyId 也必须一致。
- 私钥为 PKCS#8 PEM；公钥为单行 base64 SPKI。密钥生成/签名/验签由 [PluginKeyTool.java](<../tools/plugin-tools/PluginKeyTool.java>) 完成（纯 JDK，P-256 曲线参数经 JCE 校验，私钥必须与声明的 keyId 匹配）。
- 私钥、口令、`.pem`、keystore 不进入任何 Git 仓库；密钥轮换会产生新的 `keyId`，市场按 TOFU 语义要求用户显式重信（见[插件市场](<PLUGIN_MARKET.md>)）。

## 5. 拒绝规则（必须 fail-closed）

1. zip 是坏包/加密/含目录项/符号链接，或条目名、数量、大小、解压总量超限；`plugin.json` 缺失或 >64 KiB。
2. `plugin.json` 非 UTF-8、含 BOM、JSON 非法、含重复键、缺必需键、含未知键，或任一字段违反第 2 节约束。
3. 载荷文件集合与 `files` 不一致（缺文件、未声明文件、大小/哈希不符）；`classes.dex` 缺失或超 12 MiB；`assets/**` 单文件超 4 MiB。
4. 签名包缺 `publisher`、`publisher.keyId/publicKey` 不一致、`plugin.sig` 缺失/超限/算法不是 `ECDSA_P256_SHA256`/schema 不是 1、keyId 与清单不一致、canonical 字节重算不一致、ECDSA 验签失败。
5. 市场索引与包不一致（见[插件市场](<PLUGIN_MARKET.md>)）。

`plugin_package.py verify --zip FILE` 覆盖 1–4 且只读、不联网；`build` 在写出后立即自检（签名包执行完整验签）。

## 6. 市场索引

`index.json` 把发布者、版本、zip 地址与 sha256 绑定在一起，供底座市场客户端拉取。字段与 URL 规则见[插件市场](<PLUGIN_MARKET.md>)。注意 `release` 内的发布者是**嵌套对象** `"publisher":{"keyId","publicKey"}`，`publishedAt` 在 `release` 顶层；平铺 `publisherKeyId/publisherPublicKeyBase64` 会被载体 `PluginMarketRelease.fromJson` 的 unknown-key 校验整体拒绝。

## 7. 与底座运行时 / remote 快照的关系（仅描述）

- 底座安装器把已启用插件的 `classes.dex` 以确定性文件名写入框架 **remote files**，并把路由快照（下一条，不含原始 manifest/签名/公钥）写入独立 **remote preferences** 组 `adhook_plugins_v1`；宿主进程每进程最多读取一次，按体积预算加载，任何加载/哈希/ABI/entry 失败都 fail-open，不阻断底座通用 Hook。
- 底座运行时自行生成快照 JSON（本工具不生成）：`{"schema":1,"abi":2,"generation":<long>,"updated_at":0,"packages":{"<pkg>":[{"id","name","versionCode","entry","dex","sha256","abi","minBaseVersionCode","primary","processes":[],"features":[{"id","name","description","defaultEnabled","implemented"}]}]}}`。其中 `dex` 文件名固定为 `p_<sha256(id + 十进制 versionCode) 前 16 hex>_<dexSha256 前 8 hex>.dex`；`primary`/`processes` 来自该包对应的 `targets` 项。
- 安装/启用插件不等于 LSPosed 作用域勾选、不等于宿主授权、不等于打开 Hook 开关；作用域与开关仍由用户显式操作。
- 发布顺序固定：先写 dex 并校验大小/哈希，再提交 remote prefs，最后清理旧文件；任一步失败保留旧发布。卸载不删除用户规则；禁用/卸载对已运行宿主只在下次进程启动生效。

## 8. 版本演进

- `schema` 是插件包/清单格式版本；`abi` 是底座插件 API 版本。新增可选字段可保持版本，删除/改名/语义变化必须提升 `schema` 或 `abi`，并让旧插件安全禁用。
- 插件 ABI surface/mapping 由底座插件 ABI 工具生成与校验；破坏链接签名的变化必须提升 `PLUGIN_ABI`，详见[插件开发](<PLUGIN_DEVELOPMENT.md>)。
- 签名信封与 canonical 字节一旦发布不得静默修改；需要变更时使用新的 `schema` 并保留旧验证路径。

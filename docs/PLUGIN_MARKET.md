# 插件市场契约

本文定义底座市场客户端与插件仓库之间的 `index.json` 契约、URL 规则、信任模型与更新语义。包格式见[插件包格式](<PLUGIN_FORMAT.md>)，开发/签章流程见[插件开发与发布](<PLUGIN_DEVELOPMENT.md>)，配置与备份边界见[配置安全](<CONFIG_SECURITY.md>)。

市场只解决「发现 + 校验 + 下载」。安装、启用、更新、卸载**都不得**授权宿主、创建 AppConfig、打开 Hook 开关或静默联网下载用户未确认的内容；安装插件不等于 LSPosed 作用域勾选或宿主授权。

## 1. `index.json`

键名区分大小写、全部 camelCase；字符串 UTF-8；时间戳为 epoch **毫秒**。未知/缺失键、重复 id、重复 JSON 键一律拒绝。整个索引 ≤2 MiB、条目 ≤256、`id` 唯一。

```json
{
  "schema": 1,
  "name": "ADHook Community Plugins",
  "updatedAt": 1700000000000,
  "entries": [
    {
      "id": "com.adhook.plugin.xhs",
      "name": "小红书深度适配",
      "summary": "去广告与体验增强",
      "author": "社区维护者",
      "targets": ["com.xingin.xhs"],
      "release": {
        "version": "1.4.0",
        "versionCode": 10400,
        "abi": 2,
        "minBaseVersionCode": 30000000,
        "zipUrl": "plugins/com.adhook.plugin.xhs-1.4.0.zip",
        "sha256": "<zip 文件 sha256>",
        "size": 123456,
        "publisher": {
          "keyId": "<32 hex>",
          "publicKey": "<base64 SPKI DER>"
        },
        "publishedAt": 1700000000000,
        "changelog": "…"
      }
    }
  ]
}
```

| 字段 | 约束 |
| --- | --- |
| `schema` | 整数 `1` |
| `name` | 1–64 字符，市场显示名（可来自 `--name`） |
| `updatedAt` | ≥0 的 epoch 毫秒；生成时取 max(zip mtime) 或 `SOURCE_DATE_EPOCH` |
| `entries[].id` | 插件清单 id；索引内唯一（每个插件只列一个当前版本） |
| `entries[].name` | 1–64，需与 `plugin.json.name` 一致 |
| `entries[].summary` | 0–200 字符，市场列表摘要 |
| `entries[].author` | 1–128，需与 `plugin.json.authorName` 一致 |
| `entries[].targets` | 1–16 个包名，集合需与 `plugin.json.targets[].packageName` 一致（顺序无关） |
| `release.version` | 需与清单一致 |
| `release.versionCode` | 需与清单一致；升级必须递增 |
| `release.abi` | 需与清单一致；运行时不支持时拒绝安装 |
| `release.minBaseVersionCode` | 需与清单一致；底座低于该值时拒绝安装 |
| `release.zipUrl` | 绝对 http/https URL 或相对索引 URL 的引用（见第 2 节） |
| `release.sha256` | zip 文件字节的 sha256，64 位小写 hex |
| `release.size` | zip 文件字节数，1–16 MiB |
| `release.publisher` | **嵌套对象** `{"keyId","publicKey"}`（与载体 `PluginMarketRelease.fromJson` 一致；不得使用平铺字段）：`keyId` 32 位小写 hex，需与清单 `publisher.keyId` 一致；`publicKey` 单行 base64 SPKI，DER 字节需与清单 `publisher.publicKey` **完全一致** |
| `release.publishedAt` | 需与清单 `publisher.publishedAt` 一致；仅展示，不参与版本比较 |
| `release.changelog` | 0–4096 字符，展示用 |

`updatedAt`/`publishedAt` 只用于展示与缓存，**不能**替代 sha256/签名校验。

## 2. URL 规则

- 索引与 zip 只允许 `http`/`https`；`http` 仅在用户显式接受非安全镜像时使用，不得从 https 自动降级到 http。
- URL 不得内嵌凭据（`user:pass@`）、空白、控制字符；索引 URL 不得含 query/fragment。
- `zipUrl` 解析规则：相对引用按**索引 URL**用标准 URL 解析（等价 `java.net.URL(base, ref)`）；相对引用不得以 `/`、`//` 开头，不得含空段、`.`、`..` 段或 fragment（与 `PluginConstants.isValidRelativeReference` 一致）。绝对 `zipUrl` 允许跨源（例如索引托管在 GitHub Pages、zip 放在 GitHub Release 资产域），但必须为 http/https、无用户信息与 fragment。无论是否同源，安装前都必须通过 sha256 + 发布者签名校验，且客户端不得向 zip 域发送用户凭据。
- 重定向：只接受同源 http/https 重定向（建议上限 3 次），跨源/非 http(s)/含用户信息的重定向一律拒绝；TLS 证书校验不可关闭。
- **不内置默认镜像**：市场镜像 base URL 只能由用户显式配置或由用户导入的索引提供；客户端不得硬编码第三方镜像，也不得在失败时静默切换镜像。
- 生成工具默认输出 `plugins/<file>` 相对 `zipUrl`（要求 `index.json` 与 `plugins/` 同基路径），可用 `index --zip-url-base <绝对基路径>` 生成绝对 URL。

## 3. 信任模型

安装一个插件版本前必须同时满足：

1. 下载 zip 字节的 `sha256 == release.sha256` 且 `size == release.size`。
2. zip 通过[格式契约](<PLUGIN_FORMAT.md>)的布局/清单/哈希校验；`plugin.json` 的 `id/version/versionCode/abi/minBaseVersionCode` 与索引条目一致。
3. 清单 `publisher.keyId` == `release.publisher.keyId`，且 `base64-decode(清单 publicKey)` 的 DER 字节 == `base64-decode(release.publisher.publicKey)`。
4. `META-INF/plugin.sig` 的 keyId 与清单一致，canonical 字节重算一致，ECDSA P-256/SHA256 验签通过。
5. 发布者信任（TOFU）：首次成功安装某 `id` 时记录并钉扎 `release.publisher.keyId`；后续任何版本的该字段必须与钉扎值相同，否则**阻断自动更新**，只有用户在明确展示新旧 keyId/指纹后执行“重新信任”才替换钉扎。
6. 运行时支持：`abi` 在底座支持范围内，`minBaseVersionCode ≤ 底座 versionCode`，`targets` 合法。

索引只是搬运 sha256 与发布者信息；**索引被篡改但 zip 签名不匹配时仍然拒绝**。未签名包只在开发者模式显式放行，绝不进入市场索引。发布者信任钉扎、插件二进制不进入配置备份，也不随备份恢复。

## 4. 更新 / 降级 / 发布者变更

- **更新**：同一 `id`、同一钉扎发布者、`versionCode` 严格递增、`abi`/`minBaseVersionCode` 满足当前底座。自动检查可以提示，但安装动作仍由用户确认。
- **同版本重发**：`versionCode` 相同而 zip sha256 不同视为冲突，必须提升 `versionCode` 重新发布；索引生成工具拒绝同一目录出现重复 id。
- **降级**：自动更新永不降级；用户手动安装更低 `versionCode` 时必须显示当前/目标版本并二次确认，且仍走完整校验。
- **发布者变更**：key 轮换 = 新 `keyId`。默认阻断；用户显式“重新信任”后才更新钉扎，UI 必须展示旧/新 keyId 与指纹并提示风险。旧 keyId 的版本不会因此被静默重签或替换。
- **ABI 提升 / 底座版本提高**：不满足条件的新版本不展示为可更新，也不可绕过安装；旧插件在本机保留，由运行时安全禁用。
- **撤回**：从索引移除条目只影响后续发现，不会卸载或停用用户已安装插件；安全事件需另行通知用户卸载。

## 5. 运维

- 索引生成是确定性的：条目按 `id` 升序，`updatedAt` 取 `SOURCE_DATE_EPOCH`（CI 用 commit 时间）或 max zip mtime；同一输入重复生成字节一致。
- 发布物三类必须同时保留并互相绑定：`index.json`、`plugins/<file>.zip`、zip 内 `plugin.json`/`plugin.sig`。更新索引后不得删除仍被索引引用的 zip。
- 镜像/缓存：客户端可缓存索引，但每次安装必须重新校验 zip sha256 与签名；索引拉取失败时保留上次已知内容并提示，不自动切换未配置镜像，不把失败显示为成功。
- 发布失败语义：任何步骤（下载、校验、写 remote、清理旧文件）失败都保留旧发布，如实报告；不得上传旧包冒充新版本，不覆盖已发布版本/资产。
- 安全事件（私钥泄露）：立即停止用该 key 发布新版本、发布安全通告；索引中标记/移除受影响条目；用户侧必须卸载受影响版本后重装；不得静默替换 `keyId` 或重签旧版本。旧密钥历史、索引快照与发布记录保留用于取证。
- 插件仓库 CI 只构建/校验/生成索引并上传 artifact，发布动作必须由维护者显式执行（见[插件开发](<PLUGIN_DEVELOPMENT.md>)）。

## 6. 与底座运行时的关系

- 市场安装器把 zip 内 `classes.dex`、`plugin.json`、`plugin.sig` 与发布者公钥写入框架 remote preferences / remote files；宿主进程每进程最多加载一次，失败 fail-open，不阻断底座通用 Hook（见[Hook 执行架构](<HOOK_RUNTIME.md>)）。
- 安装/启用/更新/卸载都不得授权宿主、创建 AppConfig、打开 Hook 开关或静默联网下载。禁用/卸载对已运行宿主只在下次进程启动生效，UI 必须提示强退重开。
- 快照 JSON 由底座运行时生成与消费，不属于市场索引契约；字段见[插件包格式](<PLUGIN_FORMAT.md>)第 7 节。

## 7. 已知限制与风险

- 索引 schema 1 没有吊销列表/过期时间；撤回依赖索引更新与用户动作，不保证已安装设备即时生效。
- ECDSA 签名随机化导致同源构建的签名 zip sha256 不同；每次发布都必须重新生成索引，不能复用旧 sha256。
- TOFU 是每设备/每安装状态；清除应用数据或重装会丢失钉扎，需要重新信任。
- 跨源 zip 托管（Pages 索引 + Release 资产）扩大下载域，客户端必须保持“不发送凭据 + 强制 sha256/签名校验”，且不得跟随跨源重定向。

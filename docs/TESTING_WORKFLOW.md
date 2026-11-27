# 测试与最小验证工作流

本页只解决一个问题：改一个需求时，如何用最小上下文找到并运行相关测试，收尾再收敛到完整门禁。测试源集、门禁命令与发布语义以[工程验证与安全发布](<QUALITY_AND_RELEASE.md>)为准，执行安全边界见 [AGENTS](../AGENTS.md)。

## 测试分层

| 层 | 位置 | 职责 |
| --- | --- | --- |
| 可执行回归 | `app/tests/java` | 真实入口、纯 JVM 策略、Robolectric 视图/Activity、插件安装/签名/市场/runtime table |
| 静态源码守卫 | [source_guards](../tools/source_guards.py) | 底座生产源码文本契约：官方规则退役类路径、资源与 minSdk |
| 离线仓库门禁 | [quality_checks](../tools/quality_checks.py) | 作用域/规则身份、生命周期短路、反混淆债务、插件安装不触碰配置/授权、文档链接、仓库卫生、测试索引 |
| 工具契约 | `tools/tests` | 插件打包/签名/索引/ABI 工具、发布器与验签离线回归 |

**不要再新增“读取生产源码字符串”的 JUnit 测试。** 这类断言属于静态源码守卫，加到 `tools/source_guards.py`；JVM 测试只覆盖可执行路径。这样重构源码时不需要改测试文件。宿主专属源码契约由各插件仓库自行维护。

插件运行时测试在 `app/tests/java/com/adhook/plugin/`，必须覆盖 zip 安全、清单/签名/TOFU、安装事务、市场索引绑定、runtime table 与发布器纯逻辑；真实 framework remote files/Binder/dex 加载属于设备层，不能由 JVM 测试冒充。插件适配测试与宿主文档属于独立插件仓库，其 CI 可下载底座 `adhook-test-classes` 资产后运行 Robolectric。

## 三档门禁：按阶段选档，不要每次都跑全量

| 档位 | 触发时机 | 跑什么 |
| --- | --- | --- |
| **迭代档** | 每改完一处，想确认没写坏 | `quality_checks.py` + 受影响测试类的 `--tests` |
| **提交档** | `git commit` / PR 前 | 全量 JVM + `lintDebug` + 全量 `tools/tests` + `verifyPluginAbiSurface` |
| **发布档** | 打 Tag（只由 CI） | 提交档全部 + `lintRelease` + `assembleRelease` + `verifyPluginAbiMapping` + 真实证书验签 |

- `lintRelease` 只在发布档跑：CI [quality.yml](../.github/workflows/quality.yml) 也只跑 `lintDebug`，Release 工作流负责 `assembleRelease` 与真实证书验签。
- [AGENTS](../AGENTS.md) 第 16 条约束的是发布门禁；它不要求每改一行就跑全量，但也不允许在发布前跳过全量。
- 全量结果、Lint、ABI 与真机效果必须分别报告，未运行不得写通过。

### 迭代档

```bash
python tools/quality_checks.py --base-ref HEAD
python tools/test_index.py --check

# 只跑受影响测试类；--tests 可重复，一次调用覆盖多个类比多次调用快
./gradlew :app:testDebugUnitTest --tests "com.adhook.plugin.*" --console=plain
./gradlew :app:testDebugUnitTest --tests "com.adhook.config.security.*" --console=plain
```

Gradle 启动与配置约数秒，是固定成本：能用一次 `--tests` 覆盖的，不要拆成多次调用。

### 提交档

```bash
python tools/quality_checks.py --base-ref HEAD
python -B -m unittest discover -s tools/tests -p 'test_*.py'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyPluginAbiSurface --console=plain
```

只改了某一个工具时，可先只跑对应文件，例如 `-p 'test_quality_checks.py'`；`tools/tests` 里最贵的是插件打包端到端，纯 Python 改动不必反复跑它。

### 发布档

在仓库根执行[工程验证与安全发布](<QUALITY_AND_RELEASE.md#构建与发布>)里的 GitHub Actions 流程：版本 Tag 通过质量门禁后，由 Release 工作流执行 `assembleRelease`、`verifyPluginAbiMapping`、真实证书验签与发布。本地不跑签名构建，也不产出 APK。

## 定位测试

1. 先看[测试索引](../app/tests/INDEX.md)的公共/核心层表找到对应测试类；有 CodeGraph 索引时优先用它定位符号。
2. 插件适配测试与宿主文档在独立插件仓库，不在本仓库。
3. 不要通读 `app/tests` 全部源码。

## 索引与守卫维护

- 测试索引 [INDEX.md](../app/tests/INDEX.md) 由 [test_index.py](../tools/test_index.py) 生成，不要手改；新增/删除 `*Test.java`、`*Test.kt` 后先运行 `python tools/test_index.py` 再重跑门禁。
- 底座源码文本契约（官方规则退役类路径、资源/minSdk）改 `tools/source_guards.py`；宿主源码文本契约由各插件仓库自行维护。

## 改测试与改文档的边界

- 行为预期变了才改测试；纯重构、改名、等价重写不改测试，也不该触发测试改动。
- 插件协议、限制或验证状态变化时同步对应插件仓库的宿主文档与市场摘要；纯内部重构不改宿主文档。
- 测试数量与门禁结果以 CI 运行输出为准，不在文档里复制固定读数。

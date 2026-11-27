#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ADHook 专属广告逆向与定位军刀 (ad_inspector.py)
用于解决新 App 去广告前期定位慢、JADX 全量反编译耗时过长、临时脚本散乱的问题。

功能特性：
1. top          : 毫秒级提取当前设备前台 Activity、Fragment 与进程信息。
2. dump-ui      : 抓取当前界面 UI 层级，自动筛选可疑广告/推广/弹窗节点与 Resource-ID。
3. jadx-class   : 靶向反编译单个类或单个包，1~2 秒完成，彻底告别全量反编译卡死。
4. dex-search   : 在 APK 或 dump 出的 Dex 目录中秒级搜索包含关键词的类名与符号。
5. log          : 快速捕获当前宿主的 ADHook 注入与拦截日志。
"""

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

# 外部工具通过环境变量覆盖；默认使用 PATH 中的命令。
ADB = os.environ.get("ADB") or "adb"
JAVA_HOME = os.environ.get("JAVA_HOME") or ""
JADX = os.environ.get("JADX") or ("jadx.bat" if os.name == "nt" else "jadx")
WORKSPACE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def run_cmd(cmd_list, capture=True, text=True, check=False, env=None):
    """安全执行外部子进程"""
    try:
        res = subprocess.run(
            cmd_list,
            stdout=subprocess.PIPE if capture else None,
            stderr=subprocess.PIPE if capture else None,
            text=text,
            check=check,
            encoding="utf-8",
            errors="ignore",
            env=env
        )
        return res.stdout if capture else ""
    except Exception as e:
        print(f"[Error] 执行命令失败: {' '.join(cmd_list)}\n原因: {e}")
        return ""


def cmd_top(args):
    """获取当前前台 Activity"""
    print("[*] 正在查询当前前台 Window 与 Activity...")
    out = run_cmd([ADB, "shell", "dumpsys", "window", "displays"])
    
    # 提取 mCurrentFocus 或 mFocusedApp
    current_focus = None
    focused_app = None
    for line in out.splitlines():
        line = line.strip()
        if "mCurrentFocus=" in line or "mCurrentFocus:" in line:
            current_focus = line
        elif "mFocusedApp=" in line or "mFocusedApp:" in line:
            focused_app = line

    if not current_focus and not focused_app:
        # 兼容性方案
        out2 = run_cmd([ADB, "shell", "dumpsys", "activity", "top"])
        for line in out2.splitlines()[:20]:
            if "ACTIVITY" in line:
                print(f"  -> {line.strip()}")
                return

    print("=" * 60)
    if current_focus:
        print(f"当前焦点窗口 (Focus): {current_focus}")
    if focused_app:
        print(f"当前焦点应用 (App)  : {focused_app}")
    print("=" * 60)


def cmd_dump_ui(args):
    """Dump 当前界面 UI 并智能提取可疑广告/推广节点"""
    remote_path = "/sdcard/adhook_ui_dump.xml"
    local_dir = os.path.join(WORKSPACE_DIR, "dump")
    os.makedirs(local_dir, exist_ok=True)
    local_path = os.path.join(local_dir, "ui_dump.xml")

    print("[*] 正在抓取设备当前 UI 层次结构...")
    run_cmd([ADB, "shell", "uiautomator", "dump", remote_path])
    run_cmd([ADB, "pull", remote_path, local_path])

    if not os.path.exists(local_path):
        print("[!] 抓取失败，未生成本地文件")
        return

    print(f"[+] UI Dump 成功保存至: {local_path}")
    print("[*] 正在扫描可疑广告、推广卡片及弹窗节点...")

    ad_keywords = [
        "ad", "adv", "splash", "banner", "sponsor", "popup", "dialog",
        "float", "operate", "market", "feed", "card", "recommend", "promo"
    ]
    if args.keyword:
        ad_keywords.append(args.keyword.lower())

    try:
        tree = ET.parse(local_path)
        root = tree.getroot()
        matched_nodes = []

        for elem in root.iter("node"):
            res_id = elem.attrib.get("resource-id", "")
            cls_name = elem.attrib.get("class", "")
            text = elem.attrib.get("text", "")
            desc = elem.attrib.get("content-desc", "")
            bounds = elem.attrib.get("bounds", "")

            combined = f"{res_id} {cls_name} {text} {desc}".lower()
            for kw in ad_keywords:
                if kw in combined:
                    matched_nodes.append((kw, elem.attrib))
                    break

        print(f"[+] 共发现 {len(matched_nodes)} 个可能的目标节点（已按四层抗混淆防御阶梯分析）：")
        print("=" * 80)
        for i, (kw, attr) in enumerate(matched_nodes[:args.limit], 1):
            res_id = attr.get('resource-id', '')
            cls_name = attr.get('class', '')
            text = attr.get('text', '')
            desc = attr.get('content-desc', '')
            bounds = attr.get('bounds', '')

            # 智能判定防御阶梯
            ladder_hint = ""
            code_snippet = ""
            if any(sdk in cls_name.lower() or sdk in res_id.lower() for sdk in ["bytedance", "pangle", "qq.e", "gdt", "kwad", "baidu.mobads"]):
                ladder_hint = "【第 1 阶梯：标准广告 SDK】（极度稳定，免疫混淆）"
                code_snippet = "UniversalSdkBlocker.install(classLoader); // 底层断源抛出 onNoAD"
            elif res_id and ":id/" in res_id:
                entry_name = res_id.split(":id/")[-1]
                ladder_hint = f"【第 2 阶梯：Resource-ID 零高度坍缩】（高稳定性，R8不混淆资源名）"
                code_snippet = (
                    f"int resId = act.getResources().getIdentifier(\"{entry_name}\", \"id\", act.getPackageName());\n"
                    f"        if (resId != 0) {{\n"
                    f"            View v = act.findViewById(resId);\n"
                    f"            if (v != null) {{ ViewCollapser.collapse(v); ViewCollapser.preventExpansion(v); }}\n"
                    f"        }}"
                )
            elif text in ["广告", "跳过", "赞助", "推广"]:
                ladder_hint = "【第 2 阶梯：文本特征规则】（永久稳定，界面文案不混淆）"
                code_snippet = f"// 可在 CustomRuleEngine 或 Activity 中匹配文本 \"{text}\" 进行点击或坍缩"
            else:
                ladder_hint = "【第 3/4 阶梯：数据源过滤 / 特征指纹探测】"
                code_snippet = "// 建议配合 extractRecyclerViewContext 检查 itemViewType，或用 ClassFingerprintProbe 探测"

            print(f"[{i}] 命中特征: [{kw}] -> {ladder_hint}")
            print(f"    Class       : {cls_name}")
            if res_id:
                print(f"    Resource-ID : {res_id}")
            if text:
                print(f"    Text        : {text}")
            if desc:
                print(f"    Content-Desc: {desc}")
            print(f"    Bounds      : {bounds}")
            if code_snippet:
                print(f"    >>> 推荐落地代码:")
                for line in code_snippet.splitlines():
                    print(f"        {line}")
            print("-" * 80)

        if len(matched_nodes) > args.limit:
            print(f"[*] 其余 {len(matched_nodes) - args.limit} 个节点已省略，完整树状结构请直接查看 {local_path}")

    except Exception as e:
        print(f"[!] 解析 UI XML 失败: {e}")


def cmd_splash(args):
    """一键冷启动捕获开屏广告现场与定位定穴 (耗时 < 5 秒)"""
    pkg = args.pkg
    delay = args.delay
    print(f"[*] 启动开屏广告专项定穴流程: 目标宿主 [{pkg}]")
    print("[1/4] 强制停止宿主进程...")
    run_cmd([ADB, "shell", "am", "force-stop", pkg])
    import time
    time.sleep(0.5)

    print(f"[2/4] 冷启动应用并等待开屏展现 (等待 {delay}s)...")
    run_cmd([ADB, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"])
    time.sleep(delay)

    print("[3/4] 正在抓取开屏瞬间的 Window 与 Activity...")
    out_win = run_cmd([ADB, "shell", "dumpsys", "window", "displays"])
    focus_win = ""
    for line in out_win.splitlines():
        line = line.strip()
        if "mCurrentFocus=" in line or "mCurrentFocus:" in line:
            focus_win = line
            break
    if not focus_win:
        out_act = run_cmd([ADB, "shell", "dumpsys", "activity", "top"])
        for line in out_act.splitlines()[:15]:
            if "ACTIVITY" in line:
                focus_win = line.strip()
                break

    print(f"  -> 当前开屏 Activity: {focus_win}")

    print("[4/4] 抓取开屏 UI 层次结构与可疑跳过/广告节点...")
    remote_path = "/sdcard/adhook_splash_dump.xml"
    local_dir = os.path.join(WORKSPACE_DIR, "dump")
    os.makedirs(local_dir, exist_ok=True)
    local_path = os.path.join(local_dir, "splash_ui_dump.xml")

    run_cmd([ADB, "shell", "uiautomator", "dump", remote_path])
    run_cmd([ADB, "pull", remote_path, local_path])

    splash_keywords = ["skip", "跳过", "close", "关闭", "splash", "ad", "adv", "count", "秒", "倒计时"]
    matched = []
    if os.path.exists(local_path):
        try:
            tree = ET.parse(local_path)
            root = tree.getroot()
            for elem in root.iter("node"):
                res_id = elem.attrib.get("resource-id", "")
                cls_name = elem.attrib.get("class", "")
                text = elem.attrib.get("text", "")
                desc = elem.attrib.get("content-desc", "")
                combined = f"{res_id} {cls_name} {text} {desc}".lower()
                for kw in splash_keywords:
                    if kw in combined:
                        matched.append((kw, elem.attrib))
                        break
        except Exception as e:
            print(f"[!] 解析 UI 失败: {e}")

    print("\n" + "=" * 70)
    print("🎯 开屏广告现场定穴诊断报告")
    print("=" * 70)
    print(f"【前台窗口】: {focus_win}")
    if matched:
        print(f"【命中关键节点】: 发现 {len(matched)} 处可疑跳过/广告控件")
        for i, (kw, attr) in enumerate(matched[:6], 1):
            print(f"  [{i}] 特征 [{kw}] | View: {attr.get('class')} | ID: {attr.get('resource-id')} | Text: {attr.get('text')} {attr.get('content-desc')}")
    else:
        print("【UI 节点】: 未检测到明显文字标识（可能为纯图片/联盟SDK渲染）")

    print("\n💡 推荐落地策略（严禁全量反编译！）：")
    print("  1. 优先挂载通用 SDK: UniversalSdkBlocker.install(cl)")
    print("  2. 若为独立 SplashActivity (如含有 Splash/Welcome/Ad):")
    print("     直接在 onCreate 中发送跳转主页 Intent 并执行 finish()")
    print("  3. 若为同页广告容器 (发现上述 View/ID):")
    print("     使用 ViewCollapser.collapse(view) 彻底折叠")
    print("=" * 70)


def cmd_jadx_class(args):
    """靶向反编译单个类"""
    target_apk = args.apk
    target_class = args.cls
    output_dir = args.out or os.path.join(WORKSPACE_DIR, "apk", "jadx_out")

    if not os.path.exists(target_apk):
        print(f"[!] 找不到目标 APK: {target_apk}")
        return

    print(f"[*] 启动 JADX 靶向单类反编译:")
    print(f"    目标 APK : {target_apk}")
    print(f"    目标类   : {target_class}")
    print(f"    输出目录 : {output_dir}")

    custom_env = os.environ.copy()
    if JAVA_HOME:
        custom_env["JAVA_HOME"] = JAVA_HOME
        custom_env["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + custom_env.get("PATH", "")

    cmd = [
        JADX,
        "--single-class", target_class,
        "-d", output_dir,
        "-r",
        target_apk
    ]
    out = run_cmd(cmd, env=custom_env)
    print(out)
    print(f"[+] 单类反编译完成，源码位于: {output_dir}")


def cmd_dex_search(args):
    """在 APK 或 Dex 文件夹中极速搜索包含关键词的类名/字符串（无需反编译）"""
    target = args.target
    kw = args.keyword.encode("utf-8")
    print(f"[*] 正在极速检索目标: {target} (关键词: {args.keyword})...")

    matched_classes = set()

    def search_in_dex_bytes(dex_bytes, source_label):
        # 简单高效的 Dex 字符串扫描
        if kw in dex_bytes:
            print(f"  [+] 在 {source_label} 中检测到匹配文本！")

    if os.path.isfile(target) and target.endswith(".apk"):
        with zipfile.ZipFile(target, "r") as z:
            dex_files = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
            print(f"[*] 发现 {len(dex_files)} 个 Dex 文件，开始逐个匹配...")
            for df in dex_files:
                data = z.read(df)
                if kw in data:
                    print(f"  -> 命中 Dex: {df} (大小: {len(data) // 1024} KB)")
    elif os.path.isdir(target):
        for root, _, files in os.walk(target):
            for f in files:
                if f.endswith(".dex"):
                    fpath = os.path.join(root, f)
                    with open(fpath, "rb") as fh:
                        data = fh.read()
                        if kw in data:
                            print(f"  -> 命中 Dex: {fpath}")
    else:
        print("[!] 目标必须是 .apk 文件或包含 .dex 的目录")


def cmd_log(args):
    """快速捕获模块与宿主日志"""
    pkg = args.pkg
    print(f"[*] 正在拉取日志 (过滤关键字: ADHook, {pkg})...")
    out = run_cmd([ADB, "logcat", "-d", "-s", "ADHook:*", f"{pkg}:*"])
    lines = out.strip().splitlines()
    for l in lines[-args.lines:]:
        print(l)


def cmd_pull_dump(args):
    """拉取拾取器在手机上 Dump 的逆向线索 JSON"""
    pkg = args.pkg
    remote_dir = f"/sdcard/ADHook/dump/{pkg}"
    local_dir = os.path.join(WORKSPACE_DIR, "dump", pkg)
    os.makedirs(local_dir, exist_ok=True)

    print(f"[*] 正在检查手机端 Dump 线索: {remote_dir}...")
    file_list = run_cmd([ADB, "shell", "ls", "-t", remote_dir]).strip().splitlines()
    valid_files = [f.strip() for f in file_list if f.strip().endswith(".json")]

    if not valid_files:
        print(f"[!] 手机端 {remote_dir} 暂无 dump 线索文件，请先在手机拾取器中点击 [Dump 逆向日志]")
        return

    latest_file = valid_files[0]
    remote_path = f"{remote_dir}/{latest_file}"
    local_path = os.path.join(local_dir, latest_file)

    print(f"[+] 发现最新线索文件: {latest_file}")
    run_cmd([ADB, "pull", remote_path, local_path])

    if os.path.exists(local_path):
        print(f"[+] 成功拉取到本地: {local_path}")
        print("=" * 60)
        try:
            import json
            with open(local_path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
                print(f"  宿主包名: {data.get('package')}")
                print(f"  当前界面: {data.get('activity')}")
                view_info = data.get("view", {})
                print(f"  选中 View: {view_info.get('class')} (ID: {view_info.get('id_entry')})")
                rv_ctx = data.get("recycler_context")
                if rv_ctx:
                    print(f"  ★ 命中 ViewHolder: {rv_ctx.get('view_holder_class')}")
                    print(f"  ★ ItemViewType: {rv_ctx.get('item_view_type')}")
        except Exception as e:
            print(f"  解析线索失败: {e}")
        print("=" * 60)
        print("💡 接下来可直接执行:")
        print(f'   python tools/ad_inspector.py jadx-class "<APK路径>" "<目标类名>"')


def main():
    parser = argparse.ArgumentParser(description="ADHook 专属广告逆向与定位军刀")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # top
    p_top = subparsers.add_parser("top", help="查看当前前台 Activity 与焦点窗口")
    p_top.set_defaults(func=cmd_top)

    # splash (开屏专项定穴)
    p_splash = subparsers.add_parser("splash", help="一键冷启动捕获开屏广告现场与定位定穴 (耗时 < 5 秒)")
    p_splash.add_argument("pkg", help="目标宿主包名，如 com.target.pkg")
    p_splash.add_argument("-d", "--delay", type=float, default=1.0, help="冷启动后等待开屏渲染的秒数 (默认 1.0s)")
    p_splash.set_defaults(func=cmd_splash)

    # dump-ui
    p_dump = subparsers.add_parser("dump-ui", help="抓取当前界面 UI 并智能提取可疑广告卡片与 Resource-ID")
    p_dump.add_argument("-k", "--keyword", help="附加自定义搜索关键字", default=None)
    p_dump.add_argument("-l", "--limit", type=int, default=15, help="展示匹配项数量上限 (默认 15)")
    p_dump.set_defaults(func=cmd_dump_ui)

    # jadx-class
    p_jadx = subparsers.add_parser("jadx-class", help="靶向反编译单个类（极速，告别全量卡死）")
    p_jadx.add_argument("apk", help="目标 APK 路径")
    p_jadx.add_argument("cls", help="类全限定名，如 com.example.ad.AdView")
    p_jadx.add_argument("-o", "--out", help="源码输出目录", default=None)
    p_jadx.set_defaults(func=cmd_jadx_class)

    # dex-search
    p_dex = subparsers.add_parser("dex-search", help="在 APK 或 Dex 目录中秒级搜索关键字匹配的 Dex 文件")
    p_dex.add_argument("target", help="APK 文件路径或解密 Dex 目录")
    p_dex.add_argument("keyword", help="要搜索的关键字（如类名、URL 或 JSON 字段）")
    p_dex.set_defaults(func=cmd_dex_search)

    # log
    p_log = subparsers.add_parser("log", help="查看 ADHook 注入与宿主拦截日志")
    p_log.add_argument("pkg", help="目标宿主包名")
    p_log.add_argument("-n", "--lines", type=int, default=40, help="展示最后 N 行日志 (默认 40)")
    p_log.set_defaults(func=cmd_log)

    # pull-dump
    p_pull = subparsers.add_parser("pull-dump", help="拉取手机端拾取器 Dump 的最新逆向线索 JSON")
    p_pull.add_argument("pkg", help="目标宿主包名，如 com.xingin.xhs")
    p_pull.set_defaults(func=cmd_pull_dump)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

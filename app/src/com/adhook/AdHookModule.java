package com.adhook;

import com.adhook.config.ConfigClient;
import com.adhook.core.HookDispatcher;
import com.adhook.core.PluginBootstrap;
import com.adhook.core.AdHookLog;
import com.adhook.runtime.HookBridge;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * ADHook 的 LibXposed API101 模块入口。
 * 入口类名由 META-INF/xposed/java_init.list 按名加载，改名必须同步该清单。
 */
public class AdHookModule extends XposedModule {

    private static volatile String currentProcessName = null;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        currentProcessName = param.getProcessName();
        HookBridge.initialize(this);
        ConfigClient.setXposedModule(this);
        PluginBootstrap.initialize(this);
        AdHookLog.i("AdHookModule (API 101) loaded in process: " + currentProcessName);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        String packageName = param.getPackageName();
        HookBridge.initialize(this);
        ConfigClient.setXposedModule(this);
        PluginBootstrap.initialize(this);
        ClassLoader classLoader = param.getClassLoader();
        String processName = currentProcessName != null ? currentProcessName : packageName;

        AdHookLog.i("AdHookModule (API 101) package ready: " + packageName + ", process=" + processName);

        HookDispatcher.dispatch(
                packageName,
                processName,
                classLoader,
                param.getApplicationInfo(),
                param.isFirstPackage()
        );
    }
}

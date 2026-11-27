package com.adhook.core;

import com.adhook.config.model.FeatureMeta;
import com.adhook.runtime.HostContext;

import java.util.Collections;
import java.util.List;

/**
 * 宿主插件入口接口（ABI 2）。
 * 每个插件实现此接口，由 PluginBootstrap 在目标进程内按清单 entry 反射实例化。
 */
public interface HostHook {

    /**
     * 判断当前进程的包名是否由当前插件负责。
     *
     * @param packageName 宿主包名
     * @return true 表示由当前处理器负责
     */
    boolean supports(String packageName);

    /**
     * 执行插件的 Hook 安装逻辑。
     *
     * @param host 载入包上下文
     * @throws Throwable 异常由外层捕获并记录日志
     */
    void install(HostContext host) throws Throwable;

    /**
     * 获取宿主包名。
     */
    default String getPackageName() {
        return null;
    }

    /**
     * 获取应用可读名称。
     */
    default String getAppName() {
        return null;
    }

    /**
     * 获取该应用支持的原生硬编码特性列表（用于在控制中心生成 Switch 选项）。
     */
    default List<FeatureMeta> getFeatures() {
        return Collections.emptyList();
    }
}

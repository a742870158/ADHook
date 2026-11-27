package com.adhook;

import android.app.Application;

import com.adhook.config.RemoteConfigPublisher;
import com.adhook.plugin.PluginArtifactPublisher;

/**
 * 进程级初始化入口：注册 app 侧配置发布服务与插件产物发布器。
 * 两者都在私有 IO 线程提交，不阻塞主线程。
 */
public final class AdHookApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        RemoteConfigPublisher.initialize(this);
        // Host-plugin dex/routing publication is independent from configuration publication.
        PluginArtifactPublisher.initialize(this);
    }
}

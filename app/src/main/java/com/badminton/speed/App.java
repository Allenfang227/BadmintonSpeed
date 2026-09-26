package com.badminton.speed;

import android.app.Application;
import android.util.Log;

/**
 * 应用入口：尽早初始化 OpenCV + 安装全局崩溃处理器。
 */
public class App extends Application {

    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        // 1) 先装崩溃处理器，确保任何初始化异常都能被记录
        CrashHandler.install(this);
        // 2) 初始化 OpenCV native 库
        OpenCVInit.ensureLoaded();
        Log.i(TAG, "App created, OpenCV loaded=" + OpenCVInit.isLoaded());
    }
}

package com.badminton.speed;

import android.util.Log;

/**
 * OpenCV 初始化工具。
 * OpenCV 4.x 依赖 libc++_shared.so，必须先加载它，再加载 opencv_java4。
 */
public final class OpenCVInit {

    private static final String TAG = "OpenCVInit";
    private static boolean loaded = false;
    private static Throwable loadError = null;

    public static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        try {
            // 必须先加载 C++ 运行时，否则 opencv_java4.so 加载失败
            System.loadLibrary("c++_shared");
            System.loadLibrary("opencv_java4");
            loaded = true;
            Log.i(TAG, "OpenCV native library loaded successfully");
        } catch (Throwable t) {
            loadError = t;
            Log.e(TAG, "Failed to load OpenCV native library", t);
        }
        return loaded;
    }

    public static boolean isLoaded() { return loaded; }
    public static Throwable getError() { return loadError; }

    private OpenCVInit() {}
}

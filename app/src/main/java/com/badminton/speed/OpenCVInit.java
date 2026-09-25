package com.badminton.speed;

/**
 * OpenCV 初始化工具。
 * OpenCV 4.x 推荐方式：直接 System.loadLibrary("opencv_java4")，
 * 不再使用 OpenCVLoader.initAsync()。
 */
public final class OpenCVInit {

    private static boolean loaded = false;
    private static Throwable loadError = null;

    public static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        try {
            System.loadLibrary("opencv_java4");
            // 快速探测核心类可用
            org.opencv.core.Core.NATIVE_LIBRARY_NAME.hashCode();
            loaded = true;
        } catch (Throwable t) {
            loadError = t;
        }
        return loaded;
    }

    public static boolean isLoaded() { return loaded; }
    public static Throwable getError() { return loadError; }

    private OpenCVInit() {}
}

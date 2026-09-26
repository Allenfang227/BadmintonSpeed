package com.badminton.speed;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 全局未捕获异常处理器：把闪退栈记录到 SharedPreferences，
 * 下次启动时读取并展示给用户，帮助定位 native/Java 崩溃。
 *
 * 写入 app 私有目录，无需外部存储权限，避免二次崩溃。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String PREFS = "badminton_crash_log";
    private static final String KEY_LOG = "crash_log";
    private static final String KEY_TIME = "crash_time";

    private final Thread.UncaughtExceptionHandler defaultHandler;
    private final Context appContext;

    public CrashHandler(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    /** 在 Application.onCreate 安装 */
    public static void install(Context ctx) {
        try {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(ctx));
            Log.i(TAG, "CrashHandler installed");
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            String stack = sw.toString();
            pw.close();

            StringBuilder sb = new StringBuilder();
            sb.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault()).format(new java.util.Date())).append('\n');
            sb.append("线程: ").append(t.getName()).append('\n');
            sb.append("异常: ").append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
            sb.append("堆栈:\n").append(stack);

            SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(KEY_LOG, sb.toString())
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .apply();
            Log.e(TAG, "Crash logged: " + sb.toString());
        } catch (Throwable ignore) {
            // 写日志失败也不能影响默认崩溃流程
        }

        // 交给系统默认处理器（弹出"已停止运行"或上报）
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e);
        }
    }

    /** 读取上次崩溃报告，没有则返回 null */
    public static String getLastCrash(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getString(KEY_LOG, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 清除崩溃记录 */
    public static void clearLastCrash(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ignored) {}
    }
}

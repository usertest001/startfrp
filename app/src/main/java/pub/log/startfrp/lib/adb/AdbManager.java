package pub.log.startfrp.lib.adb;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import pub.log.startfrp.LogManager;

/**
 * ADB管理器类
 * 用于管理ADB连接、执行命令、权限授权和状态监控
 * 提供同步和异步API，支持日志回调
 * @author BY YYX
 */
public class AdbManager {
    private static final String TAG = "AdbManager";
    private static final String SETTING_ADB_ENABLED = "adb_enabled";
    private static final String SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled";

    private static AdbManager instance;
    private final Context context;
    private final AdbConnector connection;

    private AdbManager(Context context) {
        this.context = context.getApplicationContext();
        this.connection = new AdbConnector(context);
    }

    public static synchronized AdbManager getInstance() {
        if (instance == null) {
            // 使用Application Context避免内存泄漏
            Context context = null;
            try {
                // 尝试通过反射获取Application Context
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
                Object application = activityThreadClass.getMethod("getApplication").invoke(activityThread);
                context = (Context) application;
            } catch (Exception e) {
                Log.e(TAG, "获取Application Context失败：" + e.getMessage(), e);
                throw new IllegalStateException("AdbManager not initialized. Call init(Context) first.");
            }
            instance = new AdbManager(context);
        }
        return instance;
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new AdbManager(context);
        }
    }

    public interface AdbCallback {
        void onSuccess(String result);
        void onError(String error);
    }
    
    public interface AdbLogCallback {
        void onLog(String logMessage);
    }
    
    private static AdbLogCallback adbLogCallback;
    
    public static void setAdbLogCallback(AdbLogCallback callback) {
        adbLogCallback = callback;
    }
    
    public static void clearAdbLogCallback() {
        adbLogCallback = null;
    }
    
    // 静态方法，供内部和AdbConnector调用
    public static void sendAdbLog(String logMessage) {
        if (adbLogCallback != null) {
            adbLogCallback.onLog(logMessage);
        }
    }

    public boolean enableAdb() {
        try {
            Log.i(TAG, "开始启用ADB调试");

            // 启用ADB调试
            boolean adbEnabled = setAdbEnabled(true);
            if (!adbEnabled) {
                Log.e(TAG, "启用ADB调试失败");
                return false;
            }

            // 启用无线ADB
            boolean wifiAdbEnabled = setWifiAdbEnabled(true);
            if (!wifiAdbEnabled) {
                Log.e(TAG, "启用无线ADB失败");
                return false;
            }

            Log.i(TAG, "ADB调试启用成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "启用ADB调试失败：" + e.getMessage(), e);
            return false;
        }
    }

    public boolean disableAdb() {
        try {
            Log.i(TAG, "开始禁用ADB调试");

            // 禁用无线ADB
            boolean wifiAdbEnabled = setWifiAdbEnabled(false);
            if (!wifiAdbEnabled) {
                Log.e(TAG, "禁用无线ADB失败");
                return false;
            }

            // 禁用ADB调试
            boolean adbEnabled = setAdbEnabled(false);
            if (!adbEnabled) {
                Log.e(TAG, "禁用ADB调试失败");
                return false;
            }

            Log.i(TAG, "ADB调试禁用成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "禁用ADB调试失败：" + e.getMessage(), e);
            return false;
        }
    }

    public boolean isAdbEnabled() {
        try {
            int value = Settings.Secure.getInt(context.getContentResolver(), SETTING_ADB_ENABLED, 0);
            return value == 1;
        } catch (Exception e) {
            Log.e(TAG, "获取ADB启用状态失败：" + e.getMessage(), e);
            return false;
        }
    }

    public boolean isWifiAdbEnabled() {
        try {
            int value = Settings.Secure.getInt(context.getContentResolver(), SETTING_ADB_WIFI_ENABLED, 0);
            return value == 1;
        } catch (Exception e) {
            Log.e(TAG, "获取无线ADB启用状态失败：" + e.getMessage(), e);
            return false;
        }
    }

    public boolean connect() {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logMessage = timestamp + " 开始连接ADB服务器...";
        Log.i(TAG, "开始连接ADB服务器");
        LogManager.getInstance(context).i(TAG, "开始连接ADB服务器");
        sendAdbLog(logMessage);
        
        // 由于这是同步方法，我们不能直接在主线程上执行网络操作
        // 但为了保持兼容性，我们仍然尝试执行
        try {
            // 检查是否在主线程
            if (Thread.currentThread().getName().equals("main")) {
                Log.w(TAG, "警告：在主线程上执行ADB连接操作，可能会导致NetworkOnMainThreadException");
                LogManager.getInstance(context).w(TAG, "警告：在主线程上执行ADB连接操作，可能会导致NetworkOnMainThreadException");
                // 尝试在后台线程上执行
                final boolean[] result = new boolean[1];
                final Object lock = new Object();
                
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (lock) {
                            result[0] = connection.connect();
                            lock.notify();
                        }
                    }
                }).start();
                
                synchronized (lock) {
                    lock.wait(5000); // 最多等待5秒
                }
                
                if (result[0]) {
                    timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    logMessage = timestamp + " ADB连接成功";
                    LogManager.getInstance(context).i(TAG, "ADB连接成功");
                    sendAdbLog(logMessage);
                } else {
                    timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    logMessage = timestamp + " ADB连接失败，请确保无线ADB已开启";
                    LogManager.getInstance(context).e(TAG, "ADB连接失败，请确保无线ADB已开启");
                    sendAdbLog(logMessage);
                }
                return result[0];
            } else {
                // 在非主线程上，直接执行
                boolean success = connection.connect();
                if (success) {
                    timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    logMessage = timestamp + " ADB连接成功";
                    LogManager.getInstance(context).i(TAG, "ADB连接成功");
                    sendAdbLog(logMessage);
                } else {
                    timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    logMessage = timestamp + " ADB连接失败，请确保无线ADB已开启";
                    LogManager.getInstance(context).e(TAG, "ADB连接失败，请确保无线ADB已开启");
                    sendAdbLog(logMessage);
                }
                return success;
            }
        } catch (Exception e) {
            timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            logMessage = timestamp + " ADB连接异常：" + e.getMessage();
            Log.e(TAG, "ADB连接异常：" + e.getMessage(), e);
            LogManager.getInstance(context).e(TAG, "ADB连接异常：" + e.getMessage());
            sendAdbLog(logMessage);
            return false;
        }
    }

    public void connect(final AdbCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String logMessage = timestamp + " 开始连接ADB服务器...";
                    Log.i(TAG, "开始连接ADB服务器");
                    LogManager.getInstance(context).i(TAG, "开始连接ADB服务器");
                    sendAdbLog(logMessage);
                    
                    boolean success = connection.connect();
                    if (success) {
                        timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                        logMessage = timestamp + " ADB连接成功";
                        LogManager.getInstance(context).i(TAG, "ADB连接成功");
                        sendAdbLog(logMessage);
                        callback.onSuccess("ADB连接成功");
                    } else {
                        timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                        logMessage = timestamp + " ADB连接失败，请确保无线ADB已开启";
                        LogManager.getInstance(context).e(TAG, "ADB连接失败，请确保无线ADB已开启");
                        sendAdbLog(logMessage);
                        callback.onError("ADB连接失败，请确保无线ADB已开启");
                    }
                } catch (Exception e) {
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String logMessage = timestamp + " ADB连接异常：" + e.getMessage();
                    Log.e(TAG, "ADB连接异常：" + e.getMessage(), e);
                    LogManager.getInstance(context).e(TAG, "ADB连接异常：" + e.getMessage());
                    sendAdbLog(logMessage);
                    callback.onError("ADB连接失败：" + e.getMessage());
                }
            }
        }).start();
    }

    public String executeCommand(String command) {
        Log.i(TAG, "执行ADB命令：" + command);
        return connection.executeCommandWithPooling(command);
    }

    public void executeCommand(final String command, final AdbCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "执行ADB命令：" + command);
                    String result = connection.executeCommandWithPooling(command);
                    if (result != null && !result.isEmpty()) {
                        Log.d(TAG, "命令 " + command + " 执行结果: " + result);
                        callback.onSuccess(result);
                    } else {
                        Log.d(TAG, "命令 " + command + " 执行无结果");
                        callback.onError("命令执行失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "命令执行异常：" + e.getMessage(), e);
                    callback.onError("命令执行失败：" + e.getMessage());
                }
            }
        }).start();
    }

    public void grantPermission(final String packageName, final String permission, final AdbCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String command = "pm grant " + packageName + " " + permission;
                    Log.i(TAG, "执行授权命令：" + command);
                    String result = connection.executeCommandWithPooling(command);
                    if (result != null) {
                        Log.d(TAG, "授权命令执行结果: " + result);
                        callback.onSuccess("权限授权成功");
                    } else {
                        callback.onError("权限授权失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "授权异常：" + e.getMessage(), e);
                    callback.onError("授权失败：" + e.getMessage());
                }
            }
        }).start();
    }

    public void disconnect() {
        Log.i(TAG, "断开ADB连接");
        connection.disconnect();
    }

    public boolean isConnected() {
        return connection.isConnected();
    }

    private boolean setAdbEnabled(boolean enabled) {
        try {
            int value = enabled ? 1 : 0;
            Settings.Secure.putInt(context.getContentResolver(), SETTING_ADB_ENABLED, value);
            Log.d(TAG, "设置ADB启用状态：" + enabled);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "设置ADB启用状态失败：" + e.getMessage(), e);
            return false;
        }
    }

    private boolean setWifiAdbEnabled(boolean enabled) {
        try {
            int value = enabled ? 1 : 0;
            Settings.Secure.putInt(context.getContentResolver(), SETTING_ADB_WIFI_ENABLED, value);
            Log.d(TAG, "设置无线ADB启用状态：" + enabled);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "设置无线ADB启用状态失败：" + e.getMessage(), e);
            return false;
        }
    }
}

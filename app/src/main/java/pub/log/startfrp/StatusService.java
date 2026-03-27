package pub.log.startfrp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import pub.log.startfrp.lib.adb.AdbManager;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;
import pub.log.startfrp.ShizukuHelper;

/**
 * 状态服务
 * 用于监控FRP进程的运行状态并显示前台通知，支持Shizuku和ADB模式
 * @author BY YYX
 */
public class StatusService extends Service {
    private static final String TAG = "StatusService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "frpc_service_channel";
    private static final long STATUS_CHECK_INTERVAL = 30000; // 30秒检查一次
    private static final long DETAILS_UPDATE_INTERVAL = 300000; // 5分钟更新一次详细信息
    private static final long STATUS_DEBOUNCE_INTERVAL = 1000; // 1秒状态防抖

    public static boolean isRunning = false;
    private Handler handler;
    private Runnable statusCheckRunnable;
    private Runnable detailsUpdateRunnable;
    private SharedPreferences sharedPreferences;
    private NotificationManager notificationManager;
    private boolean lastFrpRunningState = false;
    private long lastStatusChangeTime = 0;
    private long lastDetailsUpdateTime = 0;
    private Notification.Builder notificationBuilder;
    private Notification currentNotification;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        LogManager.getInstance(this).d(TAG, "StatusService created");
        
        // 立即初始化通知管理器并显示常驻通知
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        
        // 显示初始通知
        showInitialNotification();
        
        // 第四步：在通知显示后，再执行其他初始化操作
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 初始化SharedPreferences
                sharedPreferences = getSharedPreferences("frp_config", Context.MODE_PRIVATE);
                
                // 初始化Handler和定期检查逻辑
                initStatusCheck();
                
                LogManager.getInstance(StatusService.this).d(TAG, "后台初始化操作完成");
            }
        }).start();
    }
    
    /**
     * 显示初始常驻通知
     */
    private void showInitialNotification() {
        // 立即尝试显示通知，不延迟
        try {
            // 初始化通知构建器
            if (notificationBuilder == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notificationBuilder = new Notification.Builder(this, CHANNEL_ID);
                } else {
                    notificationBuilder = new Notification.Builder(this);
                }
                
                // 创建点击通知时的跳转意图
                Intent notificationIntent = new Intent(this, MainActivity.class);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        notificationIntent,
                        PendingIntent.FLAG_IMMUTABLE
                );
                
                // 设置通用属性
                notificationBuilder.setContentTitle(getString(R.string.app_name))
                        .setContentText("检测状态中")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentIntent(pendingIntent)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .setOngoing(true);
            }
            
            // 构建并显示通知
            currentNotification = notificationBuilder.build();
            startForeground(NOTIFICATION_ID, currentNotification);
            LogManager.getInstance(this).d(TAG, "startForeground调用成功 - 常驻通知已显示");
        } catch (SecurityException e) {
            LogManager.getInstance(this).e(TAG, "startForeground失败，可能是因为缺少通知权限: " + e.getMessage());
            // 权限问题：立即在主线程尝试再次显示，不延迟
            if (handler == null) {
                handler = new Handler(Looper.getMainLooper());
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (notificationBuilder == null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                notificationBuilder = new Notification.Builder(StatusService.this, CHANNEL_ID);
                            } else {
                                notificationBuilder = new Notification.Builder(StatusService.this);
                            }
                            
                            // 创建点击通知时的跳转意图
                            Intent notificationIntent = new Intent(StatusService.this, MainActivity.class);
                            PendingIntent pendingIntent = PendingIntent.getActivity(
                                    StatusService.this,
                                    0,
                                    notificationIntent,
                                    PendingIntent.FLAG_IMMUTABLE
                            );
                            
                            // 设置通用属性
                            notificationBuilder.setContentTitle(getString(R.string.app_name))
                                    .setContentText("检测状态中")
                                    .setSmallIcon(R.mipmap.ic_launcher)
                                    .setContentIntent(pendingIntent)
                                    .setPriority(Notification.PRIORITY_HIGH)
                                    .setOngoing(true);
                        }
                        
                        currentNotification = notificationBuilder.build();
                        startForeground(NOTIFICATION_ID, currentNotification);
                        LogManager.getInstance(StatusService.this).d(TAG, "权限授予后，startForeground调用成功");
                    } catch (SecurityException ex) {
                        LogManager.getInstance(StatusService.this).e(TAG, "再次尝试startForeground失败: " + ex.getMessage());
                    }
                }
            });
        }
    }
    
    /**
     * 创建通知渠道（Android 8.0+ 必需）
     */
    private void createNotificationChannel() {
        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            String name = "FRP Status Channel";
            String description = "FRP Client运行状态通知";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.enableLights(true);
            channel.setLightColor(android.graphics.Color.RED);
            channel.setShowBadge(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            
            notificationManager.createNotificationChannel(channel);
            LogManager.getInstance(this).d(TAG, "创建通知渠道，重要性: IMPORTANCE_HIGH");
        }
    }

    /**
     * 初始化定期检查逻辑
     */
    private void initStatusCheck() {
        handler = new Handler(Looper.getMainLooper());
        
        // 状态检查 Runnable
        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                // 检查FRP进程状态
                checkFrpProcessStatus();
                // 继续调度下一次检查
                handler.postDelayed(this, STATUS_CHECK_INTERVAL);
            }
        };
        
        // 详细信息更新 Runnable
        detailsUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                // 检查是否需要更新详细信息
                if (System.currentTimeMillis() - lastDetailsUpdateTime >= DETAILS_UPDATE_INTERVAL) {
                    updateNotificationDetails();
                }
                // 继续调度下一次检查
                handler.postDelayed(this, DETAILS_UPDATE_INTERVAL);
            }
        };
        
        // 延迟3秒后再进行第一次检查，给用户时间看到"检测状态中"的通知
        handler.postDelayed(statusCheckRunnable, 3000);
        // 启动详细信息更新
        handler.postDelayed(detailsUpdateRunnable, DETAILS_UPDATE_INTERVAL);
        
        LogManager.getInstance(this).d(TAG, "初始化定期检查逻辑完成，延迟3秒后开始第一次检查");
    }

    /**
     * 检查FRP进程状态
     */
    private void checkFrpProcessStatus() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean frpRunning = false;
                try {
                    // 获取用户选择的模式
                    boolean useShizuku = sharedPreferences.getBoolean("use_shizuku", false);
                    boolean useAdb = sharedPreferences.getBoolean("use_adb", false);
                    
                    LogManager.getInstance(StatusService.this).d(TAG, "开始检测实际进程状态 - Shizuku: " + useShizuku + ", ADB: " + useAdb);
                    
                    // 1. 如果用户选择了Shizuku模式，使用Shizuku检测进程
                    if (useShizuku && Shizuku.pingBinder()) {
                        String checkCommand = "ps -A | grep libfrpc | grep -v grep";
                        String[] command = {"sh", "-c", checkCommand};
                        
                        LogManager.getInstance(StatusService.this).d(TAG, "使用Shizuku执行进程检测命令: " + checkCommand);
                        
                        ShizukuRemoteProcess process = ShizukuHelper.newProcess(command, null, null);
                        InputStream inputStream = process.getInputStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                        
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String trimmedLine = line.trim();
                            // 过滤空行和命令行
                            if (trimmedLine.isEmpty() || trimmedLine.contains("ps -A") || trimmedLine.contains("grep libfrpc")) {
                                continue;
                            }
                            // 检查是否包含进程信息
                            if (trimmedLine.contains("libfrpc") && trimmedLine.matches(".*\\s+\\d+\\s+.*")) {
                                LogManager.getInstance(StatusService.this).d(TAG, "Shizuku检测到frpc进程: " + trimmedLine);
                                frpRunning = true;
                                break;
                            }
                        }
                        
                        reader.close();
                        inputStream.close();
                        process.waitFor();
                    }
                    // 2. 如果用户选择了ADB模式，使用ADB检测进程
                    else if (useAdb) {
                        LogManager.getInstance(StatusService.this).d(TAG, "使用ADB执行进程检测");
                        AdbManager adbManager = AdbManager.getInstance();
                        boolean connected = adbManager.connect();
                        if (connected) {
                            String result = adbManager.executeCommand("ps -A | grep libfrpc | grep -v grep");
                            if (result != null && parseAdbProcessOutput(result)) {
                                LogManager.getInstance(StatusService.this).d(TAG, "ADB检测到frpc进程");
                                frpRunning = true;
                            }
                        }
                    }
                    // 3. 默认使用普通方式检测进程
                    else {
                        LogManager.getInstance(StatusService.this).d(TAG, "使用普通方式执行进程检测");
                        String checkCommand = "ps -A | grep libfrpc | grep -v grep";
                        Process process = Runtime.getRuntime().exec("sh -c " + checkCommand);
                        InputStream inputStream = process.getInputStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                        
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String trimmedLine = line.trim();
                            // 过滤空行和命令行
                            if (trimmedLine.isEmpty() || trimmedLine.contains("ps -A") || trimmedLine.contains("grep libfrpc")) {
                                continue;
                            }
                            // 检查是否包含进程信息
                            if (trimmedLine.contains("libfrpc") && trimmedLine.matches(".*\\s+\\d+\\s+.*")) {
                                LogManager.getInstance(StatusService.this).d(TAG, "普通方式检测到frpc进程: " + trimmedLine);
                                frpRunning = true;
                                break;
                            }
                        }
                        
                        reader.close();
                        inputStream.close();
                        process.waitFor();
                    }
                    
                    // 检查FrpcService的运行状态，与实际检测结果同步
                    LogManager.getInstance(StatusService.this).d(TAG, "检查FrpcService.isRunning状态: " + FrpcService.isRunning + ", 实际检测结果: " + frpRunning);
                    
                    // 如果实际检测到进程运行，但FrpcService显示未运行，同步状态
                    if (frpRunning && !FrpcService.isRunning) {
                        FrpcService.isRunning = true;
                        LogManager.getInstance(StatusService.this).d(TAG, "同步FrpcService.isRunning为true");
                    }
                    // 如果实际检测到进程未运行，但FrpcService显示运行中，同步状态
                    else if (!frpRunning && FrpcService.isRunning) {
                        FrpcService.isRunning = false;
                        LogManager.getInstance(StatusService.this).d(TAG, "同步FrpcService.isRunning为false");
                    }
                } catch (Exception e) {
                    LogManager.getInstance(StatusService.this).e(TAG, "检查进程状态时发生错误：" + e.getMessage(), e);
                }
                
                // 检查状态是否发生变化，以及是否超过防抖间隔
                if (frpRunning != lastFrpRunningState) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastStatusChangeTime >= STATUS_DEBOUNCE_INTERVAL) {
                        // 状态变化且超过防抖间隔，更新通知
                        lastFrpRunningState = frpRunning;
                        lastStatusChangeTime = currentTime;
                        updateNotification(frpRunning);
                    }
                }
            }
        }).start();
    }

    /**
     * 解析ADB进程输出，过滤误判
     * @param output ADB命令输出
     * @return 是否检测到真实进程
     */
    private boolean parseAdbProcessOutput(String output) {
        if (output == null || output.trim().isEmpty()) {
            return false;
        }
        
        // 分割输出为行
        String[] lines = output.split("\\r?\\n");
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            
            // 过滤空行
            if (trimmedLine.isEmpty()) {
                continue;
            }
            
            // 过滤命令行
            if (trimmedLine.contains("ps -A") || trimmedLine.contains("grep libfrpc")) {
                continue;
            }
            
            // 过滤提示符
            if (trimmedLine.contains("$") && trimmedLine.trim().endsWith("$")) {
                continue;
            }
            if (trimmedLine.contains("#") && trimmedLine.trim().endsWith("#")) {
                continue;
            }
            
            // 检查是否包含进程信息
            if (trimmedLine.contains("libfrpc") && trimmedLine.matches(".*\\s+\\d+\\s+.*")) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 更新通知内容
     * @param frpRunning FRP进程是否运行
     */
    private void updateNotification(final boolean frpRunning) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                // 延迟初始化通知管理器
                if (notificationManager == null) {
                    notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    createNotificationChannel();
                }
                
                // 重用通知构建器
                if (notificationBuilder == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        notificationBuilder = new Notification.Builder(StatusService.this, CHANNEL_ID);
                    } else {
                        notificationBuilder = new Notification.Builder(StatusService.this);
                    }
                    
                    // 创建点击通知时的跳转意图
                    Intent notificationIntent = new Intent(StatusService.this, MainActivity.class);
                    PendingIntent pendingIntent = PendingIntent.getActivity(
                            StatusService.this,
                            0,
                            notificationIntent,
                            PendingIntent.FLAG_IMMUTABLE
                    );
                    
                    // 设置通用属性
                    notificationBuilder.setContentTitle(getString(R.string.app_name))
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentIntent(pendingIntent)
                            .setPriority(Notification.PRIORITY_HIGH)
                            .setOngoing(true);
                }
                
                // 更新状态相关内容
                String contentText = frpRunning ? "FRP运行中" : "FRP已停止";
                notificationBuilder.setContentText(contentText);
                
                // 构建并发送通知
                currentNotification = notificationBuilder.build();
                
                // 首次启动时使用startForeground
                if (!isRunning) {
                    try {
                        startForeground(NOTIFICATION_ID, currentNotification);
                        LogManager.getInstance(StatusService.this).d(TAG, "startForeground调用成功 - 通知已显示");
                    } catch (SecurityException e) {
                        LogManager.getInstance(StatusService.this).e(TAG, "startForeground失败，可能是因为缺少通知权限: " + e.getMessage());
                    }
                } else {
                    // 后续更新使用notify
                    notificationManager.notify(NOTIFICATION_ID, currentNotification);
                }
                
                LogManager.getInstance(StatusService.this).d(TAG, "通知已更新，FRP状态: " + (frpRunning ? "运行中" : "已停止"));
            }
        });
    }

    /**
     * 更新通知详细信息
     */
    private void updateNotificationDetails() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                // 只有在通知已创建的情况下才更新详细信息
                if (notificationBuilder != null && currentNotification != null) {
                    // 这里可以添加运行时间等详细信息
                    // 例如：String details = "已运行 " + getRunningTime() + " 分钟";
                    // notificationBuilder.setSubText(details);
                    
                    // 更新通知
                    currentNotification = notificationBuilder.build();
                    notificationManager.notify(NOTIFICATION_ID, currentNotification);
                    
                    lastDetailsUpdateTime = System.currentTimeMillis();
                    LogManager.getInstance(StatusService.this).d(TAG, "通知详细信息已更新");
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogManager.getInstance(this).d(TAG, "StatusService started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        // 停止定期检查
        if (handler != null) {
            if (statusCheckRunnable != null) {
                handler.removeCallbacks(statusCheckRunnable);
            }
            if (detailsUpdateRunnable != null) {
                handler.removeCallbacks(detailsUpdateRunnable);
            }
        }
        LogManager.getInstance(this).d(TAG, "StatusService destroyed");
    }

    /**
     * 启动前台服务的静态方法
     */
    public static void startService(MainActivity activity) {
        LogManager.getInstance(activity).d(TAG, "startService静态方法被调用，当前isRunning状态: " + isRunning);
        Intent intent = new Intent(activity, StatusService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LogManager.getInstance(activity).d(TAG, "使用startForegroundService启动StatusService");
            activity.startForegroundService(intent);
        } else {
            LogManager.getInstance(activity).d(TAG, "使用startService启动StatusService");
            activity.startService(intent);
        }
        LogManager.getInstance(activity).d(TAG, "StatusService启动命令已发送");
    }
    
    /**
     * 启动前台服务的静态方法（通用版本）
     */
    public static void startService(Context context) {
        LogManager.getInstance(context).d(TAG, "startService(Context)静态方法被调用，当前isRunning状态: " + isRunning);
        Intent intent = new Intent(context, StatusService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LogManager.getInstance(context).d(TAG, "使用startForegroundService启动StatusService");
            context.startForegroundService(intent);
        } else {
            LogManager.getInstance(context).d(TAG, "使用startService启动StatusService");
            context.startService(intent);
        }
        LogManager.getInstance(context).d(TAG, "StatusService启动命令已发送");
    }

    /**
     * 停止前台服务的静态方法
     */
    public static void stopService(MainActivity activity) {
        if (isRunning) {
            Intent intent = new Intent(activity, StatusService.class);
            activity.stopService(intent);
        }
    }
    
    /**
     * 停止前台服务的静态方法（通用版本）
     */
    public static void stopService(Context context) {
        if (isRunning) {
            Intent intent = new Intent(context, StatusService.class);
            context.stopService(intent);
        }
    }
}

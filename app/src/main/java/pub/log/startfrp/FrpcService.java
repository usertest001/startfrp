package pub.log.startfrp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Locale;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

import java.io.FileNotFoundException;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

import pub.log.startfrp.lib.adb.AdbManager;

/**
 * FRP服务类
 * 用于管理FRP进程的启动、停止和状态监控，支持Shizuku和ADB执行模式
 * @author BY YYX
 */
public class FrpcService extends Service implements Shizuku.OnBinderReceivedListener, Shizuku.OnBinderDeadListener {

    // 广播相关常量
    public static final String ACTION_LOG_UPDATE = "pub.log.startfrp.LOG_UPDATE";
    public static final String ACTION_STATUS_UPDATE = "pub.log.startfrp.STATUS_UPDATE";
    public static final String ACTION_START = "pub.log.startfrp.START";
    public static final String ACTION_STOP = "pub.log.startfrp.STOP";
    
    // 广播额外参数常量
    public static final String EXTRA_LOG_MESSAGE = "log";
    public static final String EXTRA_STATUS = "status";
    
    private static final String CHANNEL_ID = "frpc_service_channel";
private static final int NOTIFICATION_ID = 1;
public static boolean isRunning = false;
private Process frpcProcess;
private Handler handler;
private boolean useShizuku = false;
    private boolean useAdb = false;
    private PowerManager.WakeLock wakeLock;
    // 定时检查FRP进程运行状态的Handler和Runnable
    private Handler checkHandler;
    private Runnable checkRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
        
        // 从SharedPreferences读取配置
        SharedPreferences prefs = getSharedPreferences("frp_config", MODE_PRIVATE);
        useShizuku = prefs.getBoolean("use_shizuku", false);
        useAdb = prefs.getBoolean("use_adb", false);
        Log.d("StartFRP", "从SharedPreferences读取配置: useShizuku=" + useShizuku + ", useAdb=" + useAdb);
        
        // 初始化AdbManager
        AdbManager.init(this);
        
        // 注册Shizuku监听器
        Shizuku.addBinderReceivedListenerSticky(this);
        Shizuku.addBinderDeadListener(this);
        
        // 检查Shizuku是否已经初始化
        if (Shizuku.pingBinder()) {
            Log.d("StartFRP", "Shizuku在FrpcService创建时已初始化");
        } else {
            Log.d("StartFRP", "Shizuku在FrpcService创建时未初始化，等待binder接收");
        }
        
        // 服务创建时立即检查并启动FRP进程
        checkAndStartFRP();
        
        // 初始化定时检查机制，每30秒检查一次
        checkHandler = new Handler(Looper.getMainLooper());
        checkRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d("StartFRP", "定时检查FRP进程运行状态");
                checkAndStartFRP();
                // 30秒后再次检查
                checkHandler.postDelayed(this, 30000);
                Log.d("StartFRP", "===== FRP进程启动流程完成 =====");
            }
        };
        // 启动定时检查
        checkHandler.postDelayed(checkRunnable, 30000);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "FRPC Service";
            String description = "FRP Client Service";
            // 使用IMPORTANCE_HIGH确保通知能够显示
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // 允许通知在锁屏上显示
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            // 不显示通知的角标
            channel.setShowBadge(false);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
            Log.d("StartFRP", "创建通知渠道，重要性: IMPORTANCE_HIGH");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("StartFRP", "onStartCommand被调用，intent: " + (intent != null ? intent.toString() : "null"));
        
        // 必须在startForegroundService后5秒内调用startForeground，否则会抛出ForegroundServiceDidNotStartInTimeException
        showForegroundNotification();
        
        if (intent != null) {
            Log.d("StartFRP", "onStartCommand action: " + intent.getAction());
            // 获取Shizuku状态
            useShizuku = intent.getBooleanExtra("use_shizuku", false);
            // 获取ADB执行状态
            useAdb = intent.getBooleanExtra("use_adb", false);
            Log.d("StartFRP", "使用Shizuku: " + useShizuku);
            Log.d("StartFRP", "使用ADB执行: " + useAdb);
        }
        
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.d("StartFRP", "收到停止命令，准备停止FRP服务");
            stopFRP();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            // 只在收到START命令时启动FRP，避免主线程网络操作异常
            // 在新线程中执行checkAndStartFRP，避免主线程网络操作
            new Thread(() -> {
                try {
                    Log.d("StartFRP", "在新线程中执行checkAndStartFRP");
                    checkAndStartFRP();
                } catch (Exception e) {
                    Log.e("StartFRP", "执行checkAndStartFRP失败: " + e.getMessage(), e);
                }
            }).start();
        } else if (intent == null) {
            // 系统重启服务时，intent为null，此时也需要检查并启动FRP
            // 避免服务被杀死后重启但FRP未启动的问题
            new Thread(() -> {
                try {
                    Log.d("StartFRP", "系统重启服务，intent为null，执行checkAndStartFRP");
                    checkAndStartFRP();
                } catch (Exception e) {
                    Log.e("StartFRP", "执行checkAndStartFRP失败: " + e.getMessage(), e);
                }
            }).start();
        }

        // 返回START_STICKY，确保系统在服务被杀死后自动重启它
        // 当服务被杀死时，系统会重新创建服务并调用onStartCommand，intent为null
        return START_STICKY;
    }
    
    /**
     * 显示前台服务通知
     * 必须在startForegroundService后5秒内调用startForeground，否则会抛出ForegroundServiceDidNotStartInTimeException
     */
    private void showForegroundNotification() {
        try {
            // 检查通知权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Log.w("StartFRP", "缺少通知权限，尝试显示通知");
                }
            }
            
            // 创建打开应用的意图
            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
            
            // 创建停止服务的意图
            Intent stopIntent = new Intent(this, FrpcService.class);
            stopIntent.setAction(ACTION_STOP);
            PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);
            
            // 构建通知，确保在下拉菜单中正确显示
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("FRP Client Starting")
                    .setContentText("FRP Client is initializing...")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true) // 标记为持续通知，用户无法滑动删除
                    .setPriority(NotificationCompat.PRIORITY_HIGH) // 提高优先级，确保显示
                    .setCategory(NotificationCompat.CATEGORY_SERVICE) // 设置通知类别
                    // 添加停止按钮
                    .addAction(R.mipmap.ic_launcher, "Stop", stopPendingIntent)
                    // 设置样式，确保在下拉菜单中显示完整信息
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("FRP Client is initializing...\nTap to open app or use Stop button to terminate"));
            Log.d("StartFRP", "构建前台服务通知，优先级: PRIORITY_HIGH");
            
            Notification notification = builder.build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED) {
                    startForeground(NOTIFICATION_ID, notification);
                    Log.d("StartFRP", "使用startForeground显示前台服务通知");
                } else {
                    Log.w("StartFRP", "缺少前台服务权限，以后台服务运行");
                    // 发送普通通知
                    NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    notificationManager.notify(NOTIFICATION_ID, notification);
                    Log.d("StartFRP", "使用notify显示普通通知");
                }
            } else {
                startForeground(NOTIFICATION_ID, notification);
                Log.d("StartFRP", "使用startForeground显示前台服务通知（旧版Android）");
            }
            Log.d("StartFRP", "已立即显示前台服务通知，避免ForegroundServiceDidNotStartInTimeException");
        } catch (Exception e) {
            Log.e("StartFRP", "立即显示前台服务通知失败: " + e.getMessage(), e);
            // 尝试作为普通通知显示
            try {
                // 创建打开应用的意图
                Intent notificationIntent = new Intent(this, MainActivity.class);
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                
                // 创建停止服务的意图
                Intent stopIntent = new Intent(this, FrpcService.class);
                stopIntent.setAction(ACTION_STOP);
                PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);
                
                // 构建通知
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("FRP Client Starting")
                        .setContentText("FRP Client is initializing...")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentIntent(pendingIntent)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_SERVICE)
                        .addAction(R.mipmap.ic_launcher, "Stop", stopPendingIntent)
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText("FRP Client is initializing...\nTap to open app or use Stop button to terminate"))
                        .build();

                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.notify(NOTIFICATION_ID, notification);
                Log.d("StartFRP", "已显示通知（备用方案）");
            } catch (Exception ex) {
                Log.e("StartFRP", "备用方案显示通知失败: " + ex.getMessage(), ex);
            }
        }
    }

    // 添加一个状态变量来标记FRP正在启动中
    private boolean isStarting = false;
    
    /**
     * 检查FRP进程是否真的在运行，如果没有运行则启动它
     * 解决isRunning状态与实际进程状态不一致的问题
     */
    private synchronized void checkAndStartFRP() {
        Log.d("StartFRP", "checkAndStartFRP方法被调用，当前isRunning状态: " + isRunning);
        Log.d("StartFRP", "当前isStarting状态: " + isStarting);
        
        // 先检查系统中是否真的有FRP进程在运行
        boolean systemRunning = isFRPRunningInSystem();
        Log.d("StartFRP", "系统中FRP进程实际运行状态: " + systemRunning);
        
        // 如果已经在启动中，直接返回
        if (isStarting) {
            Log.d("StartFRP", "FRP正在启动中，不需要重复启动");
            return;
        }
        
        // 无论isRunning状态如何，以系统实际状态为准
        if (!systemRunning) {
            Log.d("StartFRP", "系统中没有FRP进程在运行，准备启动FRP");
            startFRP();
            return;
        }
        
        // 如果系统中进程在运行，但内部状态标记为未运行，更新状态
        if (systemRunning && !isRunning) {
            Log.d("StartFRP", "系统中FRP进程在运行，但内部状态标记为未运行，更新状态");
            isRunning = true;
        }
        
        Log.d("StartFRP", "FRP进程仍然在运行，不需要重新启动");
    }
    
    /**
     * 检查系统中是否有libfrpc.so进程在运行
     * 使用ShizukuHelper执行命令来避免SELinux限制
     */
    private boolean isFRPRunningInSystem() {
        boolean found = false;
        
        // 注意：不先检查内部状态，而是直接检测系统中是否真的有进程在运行
        // 这样可以确保即使内部状态与实际状态不一致，也能正确检测
        
        // 尝试使用Shizuku执行命令检测进程
        if (useShizuku && Shizuku.pingBinder()) {
            try {
                String checkCommand = "ps -A | grep libfrpc | grep -v grep";
                String[] command = {"sh", "-c", checkCommand};
                
                Log.d("StartFRP", "使用ShizukuHelper执行进程检测命令: " + checkCommand);
                
                // 使用ShizukuHelper调用Shizuku的newProcess方法
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
                        Log.d("StartFRP", "Shizuku检测到frpc进程: " + trimmedLine);
                        found = true;
                        break;
                    }
                }
                
                reader.close();
                inputStream.close();
                
                int exitCode = process.waitFor();
                Log.d("StartFRP", "Shizuku命令执行完成，退出码: " + exitCode);
                
            } catch (Exception e) {
                Log.e("StartFRP", "Shizuku检测进程失败: " + e.getMessage(), e);
            }
        } 
        // 当使用ADB时，通过ADB执行命令检测进程
        else if (useAdb) {
            try {
                AdbManager adbManager = AdbManager.getInstance();
                String checkCommand = "ps -A | grep libfrpc | grep -v grep";
                Log.d("StartFRP", "使用ADB执行进程检测命令: " + checkCommand);
                
                String result = adbManager.executeCommand(checkCommand);
                Log.d("StartFRP", "ADB检测进程结果: " + (result != null ? result : "无结果"));
                
                // 检查结果中是否包含libfrpc
                if (result != null && parseAdbProcessOutput(result)) {
                    Log.d("StartFRP", "ADB方式检测到frpc进程");
                    found = true;
                } else {
                    Log.d("StartFRP", "ADB方式未检测到frpc进程");
                }
            } catch (Exception e) {
                Log.e("StartFRP", "ADB检测进程失败: " + e.getMessage(), e);
            }
        }
        // 默认方式：使用Runtime.exec()执行命令检测进程
        else {
            try {
                String checkCommand = "ps -A | grep libfrpc | grep -v grep";
                Log.d("StartFRP", "使用默认方式执行进程检测命令: " + checkCommand);
                
                // 使用Runtime.exec执行命令
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
                        Log.d("StartFRP", "默认方式检测到frpc进程: " + trimmedLine);
                        found = true;
                        break;
                    }
                }
                
                reader.close();
                inputStream.close();
                
                int exitCode = process.waitFor();
                Log.d("StartFRP", "默认方式命令执行完成，退出码: " + exitCode);
                
            } catch (Exception e) {
                Log.e("StartFRP", "默认方式检测进程失败: " + e.getMessage(), e);
            }
        }
        
        // 如果检测到进程在运行，更新内部状态
        if (found) {
            isRunning = true;
            // 发送状态更新广播
            sendStatusUpdateBroadcast(true);
            Log.d("StartFRP", "成功检测到frpc进程正在运行");
        } else {
            Log.d("StartFRP", "未发现运行中的frpc进程");
        }
        
        return found;
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
     * 发送状态更新广播
     */
    private void sendStatusUpdateBroadcast(boolean running) {
        Intent statusIntent = new Intent();
        statusIntent.setAction(ACTION_STATUS_UPDATE);
        statusIntent.putExtra(EXTRA_STATUS, running);
        sendBroadcast(statusIntent);
        Log.d("StartFRP", "发送状态更新广播，运行状态: " + running);
    }

    public synchronized void startFRP() {
        Log.d("StartFRP", "startFRP方法被调用");
        Log.d("StartFRP", "当前isRunning状态: " + isRunning);
        Log.d("StartFRP", "当前isStarting状态: " + isStarting);
        Log.d("StartFRP", "当前frpcProcess状态: " + (frpcProcess != null ? "非空" : "空"));
        Log.d("StartFRP", "系统中是否已有FRP进程: " + isFRPRunningInSystem());
        
        if (isRunning || isStarting || isFRPRunningInSystem()) {
            Log.d("StartFRP", "FRP已经在运行中、正在启动或系统中存在残留进程，直接返回");
            return;
        }

        // 设置启动中状态
        isStarting = true;
        Log.d("StartFRP", "设置isStarting为true，启动新线程准备启动FRP进程");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d("StartFRP", "新线程开始执行");
                // 确保在任何情况下都能正确更新状态
                try {
                    Log.d("StartFRP", "步骤1: 启动FRP进程");
                    // 再次检查系统中是否已有FRP进程（防止在启动线程期间有其他进程启动）
                    if (isFRPRunningInSystem()) {
                        Log.e("StartFRP", "发现系统中已存在FRP进程，取消本次启动");
                        return;
                    }
                    startFRPProcess();
                } catch (FileNotFoundException e) {
                    Log.e("StartFRP", "启动FRP失败：找不到必要的文件: " + e.getMessage());
                    e.printStackTrace();
                    // 发送详细的错误日志
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String logMsg = timestamp + " 启动失败：找不到文件 - " + e.getMessage();
                    Intent logIntent = new Intent(ACTION_LOG_UPDATE);
                    logIntent.putExtra(EXTRA_LOG_MESSAGE, logMsg);
                    sendBroadcast(logIntent);
                } catch (IOException e) {
                    Log.e("StartFRP", "启动FRP失败：I/O错误: " + e.getMessage());
                    e.printStackTrace();
                    // 发送详细的错误日志
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String logMsg = timestamp + " 启动失败：I/O错误 - " + e.getMessage();
                    Intent logIntent = new Intent(ACTION_LOG_UPDATE);
                    logIntent.putExtra(EXTRA_LOG_MESSAGE, logMsg);
                    sendBroadcast(logIntent);
                } catch (Exception e) {
                    Log.e("StartFRP", "启动FRP失败：未知错误: " + e.getMessage());
                    e.printStackTrace();
                    // 发送详细的错误日志
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String logMsg = timestamp + " 启动失败：未知错误 - " + e.getMessage();
                    Intent logIntent = new Intent(ACTION_LOG_UPDATE);
                    logIntent.putExtra(EXTRA_LOG_MESSAGE, logMsg);
                    sendBroadcast(logIntent);
                } finally {
                    // 不管成功还是失败，都更新isStarting状态为false
                    synchronized (FrpcService.this) {
                        isStarting = false;
                        Log.d("StartFRP", "启动过程结束，设置isStarting为false");
                    }
                    
                    // 如果isRunning仍为false，说明启动失败
                    if (!isRunning) {
                        Log.d("StartFRP", "FRP启动失败，清理资源");
                        // 确保进程引用为null
                        frpcProcess = null;
                        // 发送广播通知MainActivity服务启动失败
                        Intent broadcastIntent = new Intent(ACTION_STATUS_UPDATE);
                        broadcastIntent.putExtra(EXTRA_STATUS, false);
                        sendBroadcast(broadcastIntent);
                    }
                }
            }
        }).start();
    }

    private void startFRPProcess() throws Exception {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String startMsg = timestamp + " ===== 开始启动FRP进程 =====";
        Log.d("StartFRP", startMsg);
        sendLogUpdate(startMsg);
        
        // 检查系统中是否已经有libfrpc.so进程在运行
        if (isFRPRunningInSystem()) {
            String cleanMsg = timestamp + " 发现系统中已存在FRP进程，先清理残留进程";
            Log.d("StartFRP", cleanMsg);
            sendLogUpdate(cleanMsg);
            // 清理残留进程
            try {
                // 使用pgrep查找所有libfrpc.so相关进程
                Process pgrepProcess = Runtime.getRuntime().exec("/system/bin/pgrep -f libfrpc.so");
                pgrepProcess.waitFor(500, TimeUnit.MILLISECONDS);
                
                // 读取pgrep的输出，获取所有匹配的进程ID
                InputStream inputStream = pgrepProcess.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                List<String> pids = new ArrayList<>();
                
                while ((line = reader.readLine()) != null) {
                    pids.add(line.trim());
                }
                reader.close();
                inputStream.close();
                
                // 终止所有找到的进程
                for (String pid : pids) {
                    try {
                        // 先尝试优雅终止
                        Runtime.getRuntime().exec("/system/bin/kill " + pid).waitFor(1000, TimeUnit.MILLISECONDS);
                        // 再检查进程是否还存在，如果存在则强制终止
                        Process psProcess = Runtime.getRuntime().exec("/system/bin/ps -p " + pid);
                        psProcess.waitFor();
                        if (psProcess.exitValue() == 0) {
                            Runtime.getRuntime().exec("/system/bin/kill -9 " + pid).waitFor(1000, TimeUnit.MILLISECONDS);
                        }
                        String cleanPidMsg = timestamp + " 已清理残留进程: " + pid;
                        Log.d("StartFRP", cleanPidMsg);
                        sendLogUpdate(cleanPidMsg);
                    } catch (Exception e) {
                        String errorMsg = timestamp + " 清理残留进程 " + pid + " 时发生错误: " + e.getMessage();
                        Log.e("StartFRP", errorMsg);
                        sendLogUpdate(errorMsg);
                    }
                }
                
                if (pids.isEmpty()) {
                    String noPidMsg = timestamp + " 未发现需要清理的残留FRP进程";
                    Log.d("StartFRP", noPidMsg);
                    sendLogUpdate(noPidMsg);
                } else {
                    String allCleanMsg = timestamp + " 已清理所有残留的FRP进程";
                    Log.d("StartFRP", allCleanMsg);
                    sendLogUpdate(allCleanMsg);
                }
            } catch (Exception e) {
                String errorMsg = timestamp + " 清理残留进程时发生错误: " + e.getMessage();
                Log.e("StartFRP", errorMsg);
                sendLogUpdate(errorMsg);
            }
        } else {
            String noProcessMsg = timestamp + " 系统中没有FRP进程在运行，直接启动";
            Log.d("StartFRP", noProcessMsg);
            sendLogUpdate(noProcessMsg);
        }
        
        // 获取FRP目录，与MainActivity保持一致
        String frpDir;
        try {
            // 使用应用的数据目录
            File dataDir = this.getDataDir();
            if (dataDir == null) {
                String errorMsg = timestamp + " 无法获取应用数据目录，使用备用路径";
                Log.e("StartFRP", errorMsg);
                sendLogUpdate(errorMsg);
                // 备用路径
                frpDir = this.getFilesDir().getAbsolutePath() + File.separator + "frp";
                String pathMsg = timestamp + " 使用备用路径: " + frpDir;
                Log.d("StartFRP", pathMsg);
                sendLogUpdate(pathMsg);
            } else {
                File frpDirFile = new File(dataDir, "frp");
                frpDir = frpDirFile.getAbsolutePath();
                String pathMsg = timestamp + " FRP目录路径: " + frpDir;
                Log.d("StartFRP", pathMsg);
                sendLogUpdate(pathMsg);
            }
        } catch (Exception e) {
            String errorMsg = timestamp + " 获取应用数据目录失败: " + e.getMessage();
            Log.e("StartFRP", errorMsg);
            sendLogUpdate(errorMsg);
            // 备用路径
            frpDir = this.getFilesDir().getAbsolutePath() + File.separator + "frp";
            String pathMsg = timestamp + " 使用备用路径: " + frpDir;
            Log.d("StartFRP", pathMsg);
            sendLogUpdate(pathMsg);
        }

        // 确保FRP目录存在
        File frpDirFile = new File(frpDir);
        if (!frpDirFile.exists()) {
            String createMsg = timestamp + " FRP目录不存在，正在创建: " + frpDir;
            Log.d("StartFRP", createMsg);
            sendLogUpdate(createMsg);
            if (frpDirFile.mkdirs()) {
                String successMsg = timestamp + " FRP目录创建成功";
                Log.d("StartFRP", successMsg);
                sendLogUpdate(successMsg);
            } else {
                String errorMsg = timestamp + " FRP目录创建失败";
                Log.e("StartFRP", errorMsg);
                sendLogUpdate(errorMsg);
                // 尝试使用应用的files目录作为工作目录
                frpDir = this.getFilesDir().getAbsolutePath();
                String pathMsg = timestamp + " 使用files目录作为工作目录: " + frpDir;
                Log.d("StartFRP", pathMsg);
                sendLogUpdate(pathMsg);
            }
        } else {
            String existMsg = timestamp + " FRP目录已存在: " + frpDir;
            Log.d("StartFRP", existMsg);
            sendLogUpdate(existMsg);
        }

        // 配置文件路径
        File configFile = new File(frpDir + File.separator + "frpc.toml");
        String configPathMsg = timestamp + " 配置文件路径: " + configFile.getAbsolutePath();
        Log.d("StartFRP", configPathMsg);
        sendLogUpdate(configPathMsg);
        String configExistMsg = timestamp + " 配置文件存在: " + configFile.exists();
        Log.d("StartFRP", configExistMsg);
        sendLogUpdate(configExistMsg);

        if (!configFile.exists()) {
            String copyMsg = timestamp + " 配置文件不存在，尝试从assets目录复制";
            Log.d("StartFRP", copyMsg);
            sendLogUpdate(copyMsg);
            try {
                // 从assets目录复制配置文件
                InputStream inputStream = getAssets().open("frpc.toml");
                OutputStream outputStream = new FileOutputStream(configFile);
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
                outputStream.flush();
                outputStream.close();
                inputStream.close();
                String successMsg = timestamp + " 配置文件从assets目录复制成功";
                Log.d("StartFRP", successMsg);
                sendLogUpdate(successMsg);
            } catch (IOException e) {
                String errorMsg = timestamp + " 从assets目录复制配置文件失败: " + e.getMessage();
                Log.e("StartFRP", errorMsg);
                sendLogUpdate(errorMsg);
                // 配置文件不存在会导致FRP启动失败，抛出异常让上层处理
                throw new IOException("配置文件复制失败: " + e.getMessage(), e);
            }
        }

        // 获取native library目录
        ApplicationInfo appInfo = getApplicationInfo();
        String nativeLibDir = appInfo.nativeLibraryDir;
        String nativeDirMsg = timestamp + " 使用nativeLibraryDir: " + nativeLibDir;
        Log.d("StartFRP", nativeDirMsg);
        sendLogUpdate(nativeDirMsg);

        // 使用native库frpc
        String frpcPath = nativeLibDir + "/libfrpc.so";
        File frpcFile = new File(frpcPath);
        String frpcPathMsg = timestamp + " 使用native库frpc: " + frpcPath;
        Log.d("StartFRP", frpcPathMsg);
        sendLogUpdate(frpcPathMsg);
        String frpcExistMsg = timestamp + " libfrpc.so文件存在: " + frpcFile.exists();
        Log.d("StartFRP", frpcExistMsg);
        sendLogUpdate(frpcExistMsg);
        
        // 检查libfrpc.so文件是否存在
        if (!frpcFile.exists()) {
            String errorMsg = timestamp + " libfrpc.so文件不存在于native library目录: " + frpcPath;
            Log.e("StartFRP", errorMsg);
            sendLogUpdate(errorMsg);
            // 抛出异常让上层处理
            throw new FileNotFoundException("libfrpc.so文件不存在: " + frpcPath);
        }
        
        // 设置文件执行权限（使用chmod命令确保可靠设置）
        try {
            Runtime.getRuntime().exec("chmod +x " + frpcPath).waitFor();
            String permMsg = timestamp + " 已设置libfrpc.so执行权限";
            Log.d("StartFRP", permMsg);
            sendLogUpdate(permMsg);
        } catch (Exception e) {
            String errorMsg = timestamp + " 设置libfrpc.so执行权限失败: " + e.getMessage();
            Log.e("StartFRP", errorMsg);
            sendLogUpdate(errorMsg);
        }

        // 执行frpc命令
        String prepareMsg = timestamp + " 准备执行frpc命令";
        Log.d("StartFRP", prepareMsg);
        sendLogUpdate(prepareMsg);
        String command = frpcPath + " -c " + configFile.getAbsolutePath();
        String commandMsg = timestamp + " 执行命令: " + command;
        Log.d("StartFRP", commandMsg);
        sendLogUpdate(commandMsg);
        String dirMsg = timestamp + " 工作目录: " + frpDir;
        Log.d("StartFRP", dirMsg);
        sendLogUpdate(dirMsg);

        // 根据Shizuku和ADB状态选择执行方式
        try {
            if (useShizuku) {
                // 使用Shizuku执行FRP
                startFRPWithShizuku(frpcPath, configFile.getAbsolutePath(), frpDir, nativeLibDir);
            } else if (useAdb) {
                // 使用ADB执行FRP
                startFRPWithAdb(frpcPath, configFile.getAbsolutePath(), frpDir, nativeLibDir);
            } else {
                // 直接使用ProcessBuilder启动frpc进程，不通过shell
                ProcessBuilder processBuilder = new ProcessBuilder();
                processBuilder.command(frpcPath, "-c", configFile.getAbsolutePath());
                processBuilder.directory(new File(frpDir));
                // 设置环境变量，确保FRP能正常运行
                processBuilder.environment().put("LD_LIBRARY_PATH", nativeLibDir);
                String envMsg = timestamp + " 设置环境变量LD_LIBRARY_PATH: " + nativeLibDir;
                Log.d("StartFRP", envMsg);
                sendLogUpdate(envMsg);
                
                frpcProcess = processBuilder.start();
                isRunning = true;
                String frpcStartMsg = timestamp + " frpc进程启动成功";
                Log.d("StartFRP", frpcStartMsg);
                sendLogUpdate(frpcStartMsg);
                String statusMsg = timestamp + " 设置运行状态为: 已启动";
                Log.d("StartFRP", statusMsg);
                sendLogUpdate(statusMsg);
                
                // 获取WakeLock防止设备进入深度休眠
                acquireWakeLock();
                
                // 发送广播通知MainActivity服务已启动
                Intent broadcastIntent = new Intent(ACTION_STATUS_UPDATE);
                broadcastIntent.putExtra(EXTRA_STATUS, true);
                sendBroadcast(broadcastIntent);
                String broadcastMsg = timestamp + " 已发送服务启动广播";
                Log.d("StartFRP", broadcastMsg);
                sendLogUpdate(broadcastMsg);

                // 读取输出
                Thread outputThread = new ShellThread(frpcProcess.getInputStream());
                outputThread.start();

                // 读取错误输出
                Thread errorThread = new ShellThread(frpcProcess.getErrorStream());
                errorThread.start();

                // 监听进程结束
                Thread exitThread = new Thread(() -> {
                    try {
                        int exitCode = frpcProcess.waitFor();
                        String endTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                        String endMsg = endTimestamp + " FRP进程已结束，退出码: " + exitCode;
                        Log.d("StartFRP", endMsg);
                        sendLogUpdate(endMsg);
                        isRunning = false;
                        frpcProcess = null;
                        
                        // 发送广播通知MainActivity服务已停止
                        Intent stopBroadcastIntent = new Intent(ACTION_STATUS_UPDATE);
                        stopBroadcastIntent.putExtra(EXTRA_STATUS, false);
                        sendBroadcast(stopBroadcastIntent);
                        String endBroadcastMsg = endTimestamp + " 已发送服务停止广播";
                        Log.d("StartFRP", endBroadcastMsg);
                        sendLogUpdate(endBroadcastMsg);
                        
                        // 通知由StatusService统一管理，不再需要本地停止通知
                        Log.d("StartFRP", "通知由StatusService统一管理");
                        stopSelf();
                    } catch (InterruptedException e) {
                        Log.e("StartFRP", "进程等待被中断: " + e.getMessage());
                        e.printStackTrace();
                        isRunning = false;
                        frpcProcess = null;
                        
                        // 发送广播通知MainActivity服务已停止
                        Intent stopBroadcastIntent = new Intent(ACTION_STATUS_UPDATE);
                        stopBroadcastIntent.putExtra(EXTRA_STATUS, false);
                        sendBroadcast(stopBroadcastIntent);
                        Log.d("StartFRP", "已发送服务停止广播");
                    }
                });
                exitThread.start();
            }
        } catch (IOException e) {
            String errorTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String errorMsg = errorTimestamp + " 错误: FRP进程执行错误: " + e.getMessage();
            Log.e("StartFRP", errorMsg);
            sendLogUpdate(errorMsg);
            e.printStackTrace();
            isRunning = false;
            frpcProcess = null;
            throw new IOException("启动FRP进程失败: " + e.getMessage(), e);
        }
        
        // 通知由StatusService统一管理，不再需要本地启动通知
        Log.d("StartFRP", "通知由StatusService统一管理");
    }

    // 不再需要文件复制和权限设置
    // 依赖应用的nativeLibraryDir目录，该目录下的文件通常具备执行权限

    public synchronized void stopFRP() {
        Log.d("StartFRP", "stopFRP方法被调用，当前isRunning状态: " + isRunning);
        // 无论当前状态如何，都尝试终止进程
        Log.d("StartFRP", "开始处理进程终止逻辑，忽略当前isRunning状态");

        // 直接停止进程
        if (frpcProcess != null) {
            Log.d("StartFRP", "开始停止frpc进程");
            try {
                // 先尝试优雅停止
                frpcProcess.destroy();
                Log.d("StartFRP", "调用destroy()尝试优雅停止进程");
                
                // 使用try-catch块处理ShizukuRemoteProcess的waitFor()方法可能抛出的IllegalArgumentException
                boolean terminated = false;
                try {
                    terminated = frpcProcess.waitFor(2000, TimeUnit.MILLISECONDS);
                    Log.d("StartFRP", "进程是否在2秒内正常停止: " + terminated);
                } catch (IllegalArgumentException e) {
                    // 捕获"process hasn't exited"异常，这是ShizukuRemoteProcess的waitFor()方法的已知问题
                    Log.d("StartFRP", "waitFor()抛出异常，进程可能仍在运行: " + e.getMessage());
                    terminated = false;
                }
                
                if (!terminated) {
                    Log.e("StartFRP", "进程未在2秒内正常停止，准备强制终止");
                    frpcProcess.destroyForcibly();
                    Log.d("StartFRP", "调用destroyForcibly()强制终止进程");
                    
                    boolean forceTerminated = false;
                    try {
                        forceTerminated = frpcProcess.waitFor(1000, TimeUnit.MILLISECONDS);
                        Log.d("StartFRP", "强制终止是否成功: " + forceTerminated);
                    } catch (IllegalArgumentException e) {
                        // 再次捕获可能的异常
                        Log.d("StartFRP", "强制终止后waitFor()抛出异常: " + e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Log.e("StartFRP", "等待进程停止被中断: " + e.getMessage());
                e.printStackTrace();
                Log.d("StartFRP", "中断时强制终止进程");
                frpcProcess.destroyForcibly();
            } catch (Exception e) {
                Log.e("StartFRP", "停止进程时发生错误: " + e.getMessage());
                e.printStackTrace();
            } finally {
                Log.d("StartFRP", "进程引用设置为null");
                frpcProcess = null;
            }
        } else {
            Log.d("StartFRP", "frpcProcess为null，尝试通过其他方式停止进程");
        }
        
        // 不再使用pkill命令，避免终止服务进程本身
        Log.d("StartFRP", "进程已通过Process对象管理，不需要额外的pkill命令");
        
        // 检查并清理可能存在的残留FRP进程
        try {
            // 使用多种方式查找FRP进程，确保能够找到实际运行的进程
            String[] searchCommands = {
                "/system/bin/pgrep -f libfrpc.so",  // 原始命令
                "/system/bin/pgrep -f frpc.toml",   // 匹配配置文件
                "/system/bin/ps aux | grep libfrpc | grep -v grep | awk '{print $2}'",  // 使用ps命令更精确匹配
                "/system/bin/ps aux | grep -E 'libfrpc|frpc.toml' | grep -v grep | awk '{print $2}'"  // 匹配两种模式
            };
            
            List<String> allPids = new ArrayList<>();
            
            // 尝试所有查找命令
            for (String searchCmd : searchCommands) {
                try {
                    Log.d("StartFRP", "使用命令查找FRP进程: " + searchCmd);
                    Process pgrepProcess = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", searchCmd});
                    pgrepProcess.waitFor(1000, TimeUnit.MILLISECONDS);
                    
                    // 读取pgrep的输出，获取所有匹配的进程ID
                    InputStream inputStream = pgrepProcess.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    String line;
                    
                    while ((line = reader.readLine()) != null) {
                        String pid = line.trim();
                        if (!pid.isEmpty() && !allPids.contains(pid)) {
                            allPids.add(pid);
                            Log.d("StartFRP", "找到FRP进程ID: " + pid);
                        }
                    }
                    reader.close();
                    inputStream.close();
                } catch (Exception e) {
                    Log.e("StartFRP", "执行查找命令 " + searchCmd + " 时发生错误: " + e.getMessage());
                }
            }
            
            // 去重并终止所有找到的进程
            if (!allPids.isEmpty()) {
                Log.d("StartFRP", "总共找到 " + allPids.size() + " 个FRP相关进程");
                
                for (String pid : allPids) {
                    try {
                        // 直接使用kill -9强制终止进程，确保进程能够被终止
                        Log.d("StartFRP", "尝试强制终止进程: " + pid);
                        Process killProcess = Runtime.getRuntime().exec("/system/bin/kill -9 " + pid);
                        boolean success = killProcess.waitFor(2000, TimeUnit.MILLISECONDS);
                        
                        // 检查进程是否真的被终止
                        Process psProcess = Runtime.getRuntime().exec("/system/bin/ps -p " + pid);
                        psProcess.waitFor();
                        if (psProcess.exitValue() != 0) {
                            Log.d("StartFRP", "进程 " + pid + " 已成功终止");
                        } else {
                            Log.e("StartFRP", "进程 " + pid + " 仍然存在");
                        }
                    } catch (Exception e) {
                        Log.e("StartFRP", "终止进程 " + pid + " 时发生错误: " + e.getMessage());
                    }
                }
                Log.d("StartFRP", "已尝试清理所有残留的FRP进程");
            } else {
                Log.d("StartFRP", "未发现残留的FRP进程");
            }
        } catch (Exception e) {
            Log.e("StartFRP", "清理残留进程时发生错误: " + e.getMessage());
        }
        
        // 尝试使用Shizuku执行命令停止FRP进程（无论当前useShizuku配置如何，都尝试一次）
        // 因为之前可能通过Shizuku启动了进程，但当前配置可能已改变
        if (Shizuku.pingBinder()) {
            try {
                Log.d("StartFRP", "尝试使用Shizuku执行命令停止FRP进程");
                
                // 使用Shizuku查找libfrpc.so进程
                String[] findCommand = {"sh", "-c", "ps -A | grep libfrpc | awk '{print $2}'"};
                ShizukuRemoteProcess findProcess = ShizukuHelper.newProcess(findCommand, null, null);
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(findProcess.getInputStream()));
                String pidStr;
                boolean foundProcess = false;
                
                while ((pidStr = reader.readLine()) != null) {
                    pidStr = pidStr.trim();
                    if (!pidStr.isEmpty()) {
                        try {
                            int pid = Integer.parseInt(pidStr);
                            foundProcess = true;
                            
                            // 使用Shizuku执行kill命令终止进程
                            String[] killCommand = {"sh", "-c", "kill -9 " + pid};
                            Log.d("StartFRP", "使用Shizuku执行kill命令终止进程，PID: " + pid);
                            
                            ShizukuRemoteProcess killProcess = ShizukuHelper.newProcess(killCommand, null, null);
                            int exitCode = killProcess.waitFor();
                            Log.d("StartFRP", "Shizuku kill命令执行完成，退出码: " + exitCode);
                            
                        } catch (NumberFormatException e) {
                            Log.e("StartFRP", "无效的PID: " + pidStr);
                        }
                    }
                }
                
                reader.close();
                findProcess.waitFor();
                
                if (!foundProcess) {
                    Log.d("StartFRP", "通过Shizuku未找到运行中的libfrpc.so进程");
                }
                
            } catch (Exception e) {
                Log.e("StartFRP", "使用Shizuku停止进程时发生错误: " + e.getMessage(), e);
            }
        }
        
        // 尝试使用AdbManager执行命令停止FRP进程（无论当前useAdb配置如何，都尝试一次）
        // 因为之前可能通过ADB启动了进程，但当前配置可能已改变
        try {
            Log.d("StartFRP", "尝试使用AdbManager执行命令停止FRP进程");
            
            AdbManager adbManager = AdbManager.getInstance();
            // 使用ADB查找并终止libfrpc.so进程
            String killCommand = "ps -A | grep libfrpc | awk '{print $2}' | xargs -r kill -9";
            String result = adbManager.executeCommand(killCommand);
            Log.d("StartFRP", "AdbManager kill命令执行结果: " + (result != null ? result : "无结果"));
            
        } catch (Exception e) {
            Log.e("StartFRP", "使用AdbManager停止进程时发生错误: " + e.getMessage(), e);
        }
        
        // 更新状态
        Log.d("StartFRP", "更新服务状态为停止");
        isRunning = false;
        
        // 停止前台服务
        stopForeground(true);
        Log.d("StartFRP", "已停止前台服务通知");
        
        // 释放WakeLock，允许设备进入休眠
        releaseWakeLock();
        
        // 通知由StatusService统一管理
        Log.d("StartFRP", "通知由StatusService统一管理");
        
        // 发送广播通知MainActivity服务已停止
        Log.d("StartFRP", "发送广播通知MainActivity服务已停止");
        Intent broadcastIntent = new Intent("pub.log.startfrp.FRP_SERVICE_STOPPED");
        // 去掉ComponentName，让系统根据IntentFilter自动匹配接收器，确保跨进程通信正常
        sendBroadcast(broadcastIntent);
        
        // 同时发送标准的状态更新广播
        Intent statusIntent = new Intent(ACTION_STATUS_UPDATE);
        statusIntent.putExtra(EXTRA_STATUS, false);
        // 去掉ComponentName，让系统根据IntentFilter自动匹配接收器，确保跨进程通信正常
        sendBroadcast(statusIntent);
        
        // 所有清理和广播发送完成后，停止服务
        Log.d("StartFRP", "所有清理和广播发送完成，调用stopSelf()停止服务");
        stopSelf();
    }

    public boolean isFRPRunning() {
        return isRunning;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 不要在这里调用stopFRP()，这样即使服务被销毁，FRP进程仍能继续运行
        // 当服务重启时，会重新检查并控制FRP进程
        
        // 停止定时检查任务
        if (checkHandler != null && checkRunnable != null) {
            checkHandler.removeCallbacks(checkRunnable);
        }
        
        // 注销Shizuku监听器
        Shizuku.removeBinderReceivedListener(this);
        Shizuku.removeBinderDeadListener(this);
        
        // 释放WakeLock，确保服务销毁时资源被完全释放
        releaseWakeLock();
    }

    private void sendLogUpdate(String logMsg) {
        // 发送广播到界面显示
        Intent intent = new Intent(ACTION_LOG_UPDATE);
        intent.putExtra(EXTRA_LOG_MESSAGE, logMsg);
        sendBroadcast(intent);
        
        // 同时写入日志到文件
        LogManager logManager = LogManager.getInstance(this);
        logManager.d("StartFRP", logMsg);
    }

    private void showToast(String message) {
        final String msg = message;
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(FrpcService.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 使用Shizuku执行FRP
     */
    @Override
    public void onBinderReceived() {
        Log.d("StartFRP", "Shizuku Binder已接收");
        // 当Shizuku的binder就绪时，如果需要使用Shizuku且FRP未运行，尝试启动FRP
        if (useShizuku && !isRunning && !isStarting) {
            Log.d("StartFRP", "Shizuku Binder就绪，正在尝试启动FRP");
            checkAndStartFRP();
        }
    }

    @Override
    public void onBinderDead() {
        Log.d("StartFRP", "Shizuku Binder已断开");
        // 当Shizuku的binder断开时，更新状态
        if (useShizuku) {
            Log.d("StartFRP", "Shizuku Binder断开，将isRunning设为false");
            isRunning = false;
            // 发送广播通知MainActivity服务已停止
            Intent broadcastIntent = new Intent(ACTION_STATUS_UPDATE);
            broadcastIntent.putExtra(EXTRA_STATUS, false);
            sendBroadcast(broadcastIntent);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FRP:WakeLockTag");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.d("StartFRP", "已获取WakeLock，防止设备进入深度休眠");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d("StartFRP", "已释放WakeLock");
        }
    }

    private void startFRPWithShizuku(String frpcPath, String configPath, String workingDir, String nativeLibDir) throws IOException {
        try {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String shizukuStartMsg = timestamp + " 使用Shizuku执行FRP";
            Log.d("StartFRP", shizukuStartMsg);
            sendLogUpdate(shizukuStartMsg);
            
            Log.d("StartFRP", "传入的frpcPath: " + frpcPath);
            Log.d("StartFRP", "传入的configPath: " + configPath);
            Log.d("StartFRP", "传入的workingDir: " + workingDir);
            Log.d("StartFRP", "传入的nativeLibDir: " + nativeLibDir);
            
            // 检查Shizuku是否已经初始化
            String checkStatusMsg = timestamp + " 检查Shizuku状态...";
            Log.d("StartFRP", checkStatusMsg);
            sendLogUpdate(checkStatusMsg);
            
            if (!Shizuku.pingBinder()) {
                String notReadyMsg = timestamp + " Shizuku尚未初始化，正在等待...";
                Log.e("StartFRP", notReadyMsg);
                sendLogUpdate(notReadyMsg);
                
                // 尝试等待一段时间后重试
                int retryCount = 0;
                while (!Shizuku.pingBinder() && retryCount < 5) {
                    String retryMsg = timestamp + " 等待Shizuku初始化，重试次数: " + (retryCount + 1);
                    Log.d("StartFRP", retryMsg);
                    sendLogUpdate(retryMsg);
                    try {
                        Thread.sleep(1000); // 等待1秒
                        retryCount++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
                // 如果重试后仍然未初始化，抛出异常
                if (!Shizuku.pingBinder()) {
                    String timeoutMsg = timestamp + " Shizuku初始化超时，无法执行命令";
                    Log.e("StartFRP", timeoutMsg);
                    sendLogUpdate(timeoutMsg);
                    throw new IOException("Shizuku初始化超时，无法执行命令");
                }
                String successMsg = timestamp + " Shizuku初始化成功";
                Log.d("StartFRP", successMsg);
                sendLogUpdate(successMsg);
            } else {
                String readyMsg = timestamp + " Shizuku已就绪";
                Log.d("StartFRP", readyMsg);
                sendLogUpdate(readyMsg);
            }
            
            // 使用/data/local/tmp/startfrp/目录作为工作目录
            String targetDir = "/data/local/tmp/startfrp/";
            
            // 使用目标目录中的文件
            String targetFrpcPath = targetDir + "libfrpc.so";
            String targetConfigPath = targetDir + "frpc.toml";
            
            // 检查源文件是否存在
            File sourceFrpcFile = new File(targetFrpcPath);
            File sourceConfigFile = new File(targetConfigPath);
            
            String checkSourceMsg = timestamp + " 检查源文件: libfrpc.so=" + sourceFrpcFile.exists() + ", frpc.toml=" + sourceConfigFile.exists();
            Log.d("StartFRP", checkSourceMsg);
            sendLogUpdate(checkSourceMsg);
            
            if (!sourceFrpcFile.exists() || !sourceConfigFile.exists()) {
                String errorMsg = timestamp + " 错误: 必要文件不存在，请手动点击'安装Shizuku'按钮进行安装";
                Log.e("StartFRP", errorMsg);
                sendLogUpdate(errorMsg);
                throw new FileNotFoundException("必要文件不存在，请手动点击'安装Shizuku'按钮进行安装");
            }
            
            
            
            // 使用shell执行命令并添加nohup，确保进程在后台运行
            String shellCommand = "nohup " + targetFrpcPath + " -c " + targetConfigPath + " >>/dev/null 2>&1 &";
            String[] command = {
                "sh",
                "-c",
                shellCommand
            };
            
            // 设置环境变量，包含目标目录和native library目录
            String[] env = {
                "LD_LIBRARY_PATH=" + targetDir + ":" + nativeLibDir
            };
            
            String commandMsg = timestamp + " Shizuku执行命令: " + shellCommand;
            Log.d("StartFRP", commandMsg);
            sendLogUpdate(commandMsg);
            
            String dirMsg = timestamp + " 工作目录: " + targetDir;
            Log.d("StartFRP", dirMsg);
            sendLogUpdate(dirMsg);
            
            String envMsg = timestamp + " 环境变量: LD_LIBRARY_PATH=" + targetDir + ":" + nativeLibDir;
            Log.d("StartFRP", envMsg);
            sendLogUpdate(envMsg);
            
            // 使用Shizuku执行命令
            String executeMsg = timestamp + " 正在执行FRP命令...";
            Log.d("StartFRP", executeMsg);
            sendLogUpdate(executeMsg);
            
            ShizukuRemoteProcess shizukuProcess = ShizukuHelper.newProcess(command, env, targetDir);
            frpcProcess = shizukuProcess;
            
            isRunning = true;
            String successMsg = timestamp + " frpc进程通过Shizuku启动成功";
            Log.d("StartFRP", successMsg);
            sendLogUpdate(successMsg);
            
            String statusMsg = timestamp + " 设置运行状态为: 已启动";
            Log.d("StartFRP", statusMsg);
            sendLogUpdate(statusMsg);
            
            // 获取WakeLock防止设备进入深度休眠
            acquireWakeLock();
            
            // 发送广播通知MainActivity服务已启动
            Intent broadcastIntent = new Intent(ACTION_STATUS_UPDATE);
            broadcastIntent.putExtra(EXTRA_STATUS, true);
            sendBroadcast(broadcastIntent);
            Log.d("StartFRP", "已发送服务启动广播");

            // 使用nohup启动后，shell会立即返回，所以不需要读取输出或等待进程结束
            // 关闭输入流，因为我们不需要读取输出
            shizukuProcess.getInputStream().close();
            shizukuProcess.getErrorStream().close();
            shizukuProcess.getOutputStream().close();
            
            // 重置frpcProcess引用，因为shell进程已经结束，我们将依靠定期检查来监控frpc进程
            frpcProcess = null;
            
            // 由于使用nohup，shell进程会立即返回，所以我们需要重置exitThread的逻辑
            // 改为依靠checkAndStartFRP()方法定期检查frpc进程是否真的在运行
            Log.d("StartFRP", "使用nohup启动FRP，将依靠定期检查机制监控进程状态");
            
            // 立即检查一次frpc进程是否真的启动成功
            new Thread(() -> {
                try {
                    // 等待1秒，让frpc进程有时间启动
                    Thread.sleep(1000);
                    boolean systemRunning = isFRPRunningInSystem();
                    Log.d("StartFRP", "nohup启动后检查frpc进程状态: " + systemRunning);
                    if (!systemRunning) {
                        Log.e("StartFRP", "使用nohup启动FRP失败，系统中没有检测到frpc进程");
                        isRunning = false;
                        
                        // 发送广播通知MainActivity服务启动失败
                        Intent statusIntent = new Intent(ACTION_STATUS_UPDATE);
                        statusIntent.putExtra(EXTRA_STATUS, false);
                        sendBroadcast(statusIntent);
                    }
                } catch (InterruptedException e) {
                    Log.e("StartFRP", "检查nohup启动状态时被中断: " + e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            String errorTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String errorMsg = errorTimestamp + " 错误: 使用Shizuku执行FRP失败: " + e.getMessage();
            Log.e("StartFRP", errorMsg);
            sendLogUpdate(errorMsg);
            e.printStackTrace();
            throw new IOException("通过Shizuku启动FRP进程失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 使用ADB执行FRP
     */
    private void startFRPWithAdb(String frpcPath, String configPath, String workingDir, String nativeLibDir) throws IOException {
        try {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            Log.d("StartFRP", "使用ADB执行FRP");
            sendLogUpdate(timestamp + " 使用ADB执行FRP");
            
            Log.d("StartFRP", "传入的frpcPath: " + frpcPath);
            Log.d("StartFRP", "传入的configPath: " + configPath);
            Log.d("StartFRP", "传入的workingDir: " + workingDir);
            Log.d("StartFRP", "传入的nativeLibDir: " + nativeLibDir);
            
            // 使用/data/local/tmp/startfrp/目录作为工作目录（与Shizuku模式保持一致）
           // 使用/data/local/tmp/startfrp/目录作为工作目录
            String targetDir = "/data/local/tmp/startfrp/";
            
            // 使用目标目录中的文件
            String targetFrpcPath = targetDir + "libfrpc.so";
            String targetConfigPath = targetDir + "frpc.toml";
            
            // 检查源文件是否存在
            File sourceFrpcFile = new File(targetFrpcPath);
            File sourceConfigFile = new File(targetConfigPath);
            
            String checkSourceMsg = timestamp + " 检查源文件: libfrpc.so=" + sourceFrpcFile.exists() + ", frpc.toml=" + sourceConfigFile.exists();
            Log.d("StartFRP", checkSourceMsg);
            sendLogUpdate(checkSourceMsg);
            
            if (!sourceFrpcFile.exists() || !sourceConfigFile.exists()) {
                String errorMsg = timestamp + " 错误: 必要文件不存在，请手动点击'安装Shizuku'按钮进行安装";
                Log.e("StartFRP", errorMsg);
                sendLogUpdate(errorMsg);
                throw new FileNotFoundException("必要文件不存在，请手动点击'安装Shizuku'按钮进行安装");
            }
            
            // 确保目标目录存在
            AdbManager adbManager = AdbManager.getInstance();
            
            // 显式检查ADB连接状态
            sendLogUpdate(timestamp + " 检查ADB连接状态...");
            sendLogUpdate(timestamp + " 开始连接ADB服务器...");
            
            // 直接实现连接尝试和日志显示，确保详细日志能够正确显示
            boolean connected = false;
            String ADB_HOST = "localhost";
            int ADB_PORT = 5555;
            
            // 尝试连接3次
            for (int i = 0; i < 3; i++) {
                String attemptTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                String logMsg = attemptTimestamp + " 尝试连接ADB服务器，第" + (i + 1) + "次: " + ADB_HOST + ":" + ADB_PORT;
                Log.d("StartFRP", logMsg);
                sendLogUpdate(logMsg);
                
                try {
                    // 直接尝试连接
                    Log.d("StartFRP", "调用adbManager.connect()");
                    connected = adbManager.connect();
                    Log.d("StartFRP", "adbManager.connect()返回: " + connected);
                    
                    if (connected) {
                        String successTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                        String successMsg = successTimestamp + " ADB连接成功";
                        Log.d("StartFRP", successMsg);
                        sendLogUpdate(successMsg);
                        break;
                    } else {
                        String errorTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                        String errorMsg = errorTimestamp + " ADB连接失败，第" + (i + 1) + "次：Connection refused";
                        Log.d("StartFRP", errorMsg);
                        sendLogUpdate(errorMsg);
                        // 等待1秒后重试
                        Log.d("StartFRP", "等待1秒后重试");
                        Thread.sleep(1000);
                    }
                } catch (Exception e) {
                    String errorTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String errorMsg = errorTimestamp + " ADB连接失败，第" + (i + 1) + "次：" + e.getMessage();
                    Log.e("StartFRP", errorMsg, e);
                    sendLogUpdate(errorMsg);
                    try {
                        Log.d("StartFRP", "等待1秒后重试");
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            
            // 显示最终连接结果
            if (!connected) {
                String finalTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                String failMsg1 = finalTimestamp + " ADB连接失败，已尝试3次";
                String failMsg2 = finalTimestamp + " ADB连接失败，请确保无线ADB已开启";
                Log.d("StartFRP", failMsg1);
                Log.d("StartFRP", failMsg2);
                sendLogUpdate(failMsg1);
                sendLogUpdate(failMsg2);
                throw new IOException("ADB连接失败，请确保无线ADB已开启");
            }
            
      
            
            // 先确保libfrpc.so文件有执行权限
            String chmodCommand = "chmod +x " + targetFrpcPath;
            Log.d("StartFRP", "设置执行权限命令: " + chmodCommand);
            sendLogUpdate(timestamp + " 设置执行权限命令: " + chmodCommand);
            String chmodResult = adbManager.executeCommand(chmodCommand);
            Log.d("StartFRP", "设置执行权限结果: " + (chmodResult != null ? chmodResult : "无结果"));
            sendLogUpdate(timestamp + " 设置执行权限结果: " + (chmodResult != null ? chmodResult : "无结果"));
            
            // 设置环境变量，与Shizuku模式保持一致
            String envCommand = "export LD_LIBRARY_PATH=" + targetDir + ":" + nativeLibDir + ";";
            Log.d("StartFRP", "设置环境变量命令: " + envCommand);
            sendLogUpdate(timestamp + " 设置环境变量命令: " + envCommand);
            
            // 使用shell执行命令并添加nohup，确保进程在后台运行
            // 与Shizuku模式保持一致的命令格式，但指定工作目录
            String shellCommand = "cd " + targetDir + "; " + envCommand + " nohup " + targetFrpcPath + " -c " + targetConfigPath + " >>/dev/null 2>&1 &";
            
            Log.d("StartFRP", "ADB执行命令: " + shellCommand);
            sendLogUpdate(timestamp + " ADB执行命令: " + shellCommand);
            Log.d("StartFRP", "工作目录: " + targetDir);
            sendLogUpdate(timestamp + " 工作目录: " + targetDir);
            
            // 使用AdbManager执行命令
            String result = adbManager.executeCommand(shellCommand);
            Log.d("StartFRP", "ADB命令执行结果: " + (result != null ? result : "无结果"));
            sendLogUpdate(timestamp + " ADB命令执行结果: " + (result != null ? result : "无结果"));
            
            // 立即设置isRunning为true，与Shizuku模式保持一致
            // 这样当方法返回后，startFRP方法执行到前台服务通知代码时，isRunning就是true
            isRunning = true;
            Log.d("StartFRP", "设置isRunning为true（ADB模式）");
            
            // 检查FRP日志文件
            String checkLogCommand = "ls -la /data/local/tmp/startfrp/frpc.log";
            Log.d("StartFRP", "检查FRP日志文件: " + checkLogCommand);
            sendLogUpdate(timestamp + " 检查FRP日志文件: " + checkLogCommand);
            String checkLogResult = adbManager.executeCommand(checkLogCommand);
            Log.d("StartFRP", "检查FRP日志文件结果: " + (checkLogResult != null ? checkLogResult : "无结果"));
            sendLogUpdate(timestamp + " 检查FRP日志文件结果: " + (checkLogResult != null ? checkLogResult : "无结果"));
            
            // 由于使用nohup，shell进程会立即返回，所以我们需要重置exitThread的逻辑
            // 改为依靠checkAndStartFRP()方法定期检查frpc进程是否真的在运行
            Log.d("StartFRP", "使用nohup启动FRP，将依靠定期检查机制监控进程状态");
            sendLogUpdate(timestamp + " 使用nohup启动FRP，将依靠定期检查机制监控进程状态");
            
            // 立即检查一次frpc进程是否真的启动成功
            new Thread(() -> {
                try {
                    // 等待3秒，让frpc进程有时间启动
                    Thread.sleep(3000);
                    
                    // 多次检查进程状态
                    for (int i = 0; i < 3; i++) {
                        boolean systemRunning = isFRPRunningInSystem();
                        String checkTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                        Log.d("StartFRP", "ADB启动后检查frpc进程状态（尝试" + (i+1) + "/3）: " + systemRunning);
                        sendLogUpdate(checkTimestamp + " ADB启动后检查frpc进程状态（尝试" + (i+1) + "/3）: " + systemRunning);
                        
                        if (systemRunning) {
                            Log.d("StartFRP", "frpc进程通过ADB启动成功");
                            sendLogUpdate(checkTimestamp + " frpc进程通过ADB启动成功");
                            isRunning = true;
                            Log.d("StartFRP", "设置isRunning为true");
                            
                            // 获取WakeLock防止设备进入深度休眠
                            acquireWakeLock();
                            
                            // 发送广播通知MainActivity服务已启动
                            Intent broadcastIntent = new Intent(ACTION_STATUS_UPDATE);
                            broadcastIntent.putExtra(EXTRA_STATUS, true);
                            sendBroadcast(broadcastIntent);
                            Log.d("StartFRP", "已发送服务启动广播");
                            
                            // 检查FRP日志内容
                            String checkLogContentCommand = "cat /data/local/tmp/startfrp/frpc.log | head -20";
                            Log.d("StartFRP", "检查FRP日志内容: " + checkLogContentCommand);
                            sendLogUpdate(checkTimestamp + " 检查FRP日志内容");
                            String logContent = adbManager.executeCommand(checkLogContentCommand);
                            Log.d("StartFRP", "FRP日志内容: " + (logContent != null ? logContent : "无内容"));
                            sendLogUpdate(checkTimestamp + " FRP日志内容: " + (logContent != null ? logContent : "无内容"));
                            
                            // 不要return，让代码继续执行到startFRP方法末尾的前台服务通知实现
                        } else {
                            // 检查是否有任何FRP相关进程
                            String checkAllProcessesCommand = "ps -A | grep frp";
                            String allProcessesResult = adbManager.executeCommand(checkAllProcessesCommand);
                            Log.d("StartFRP", "检查所有FRP相关进程: " + (allProcessesResult != null ? allProcessesResult : "无结果"));
                            sendLogUpdate(checkTimestamp + " 检查所有FRP相关进程: " + (allProcessesResult != null ? allProcessesResult : "无结果"));
                            
                            // 检查FRP日志内容
                            String checkLogContentCommand = "cat /data/local/tmp/startfrp/frpc.log";
                            String logContent = adbManager.executeCommand(checkLogContentCommand);
                            Log.d("StartFRP", "FRP日志内容: " + (logContent != null ? logContent : "无内容"));
                            sendLogUpdate(checkTimestamp + " FRP日志内容: " + (logContent != null ? logContent : "无内容"));
                            
                            // 等待1秒后再次检查
                            Thread.sleep(1000);
                        }
                    }
                    
                    // 所有尝试都失败
                    String finalCheckTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    Log.e("StartFRP", "使用ADB启动FRP失败，系统中没有检测到frpc进程");
                    sendLogUpdate(finalCheckTimestamp + " 错误: 使用ADB启动FRP失败，系统中没有检测到frpc进程");
                    isRunning = false;
                    
                    // 发送广播通知MainActivity服务启动失败
                    Intent statusIntent = new Intent(ACTION_STATUS_UPDATE);
                    statusIntent.putExtra(EXTRA_STATUS, false);
                    sendBroadcast(statusIntent);
                    
                } catch (InterruptedException e) {
                    Log.e("StartFRP", "检查ADB启动状态时被中断: " + e.getMessage());
                    String errorTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    sendLogUpdate(errorTimestamp + " 错误: 检查ADB启动状态时被中断: " + e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            Log.e("StartFRP", "使用ADB执行FRP失败: " + e.getMessage());
            e.printStackTrace();
            String errorTimestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            sendLogUpdate(errorTimestamp + " 错误: 使用ADB执行FRP失败: " + e.getMessage());
            
            // 发送广播通知MainActivity服务启动失败
            Intent statusIntent = new Intent(ACTION_STATUS_UPDATE);
            statusIntent.putExtra(EXTRA_STATUS, false);
            sendBroadcast(statusIntent);
            
            throw new IOException("通过ADB启动FRP进程失败: " + e.getMessage(), e);
        }
    }
    
    private class ShellThread extends Thread {
        private InputStream inputStream;

        public ShellThread(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            int length;
            try {
                while ((length = inputStream.read(buffer)) > 0) {
                    final String line = new String(buffer, 0, length);
                    Log.d("StartFRP", line);
                    sendLogUpdate(line);
                }
            } catch (IOException e) {
                Log.e("StartFRP", "ShellThread错误: " + e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
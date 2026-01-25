package pub.log.startfrp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class FrpcDaemonService extends Service {
    
    // 服务相关常量
    public static final String ACTION_START = "pub.log.startfrp.DAEMON_START";
    public static final String ACTION_STOP = "pub.log.startfrp.DAEMON_STOP";
    public static boolean isRunning = false;
    
    // 前台服务相关常量
    private static final String CHANNEL_ID = "frpc_daemon_channel";
    private static final int NOTIFICATION_ID = 2;
    
    // 定时检查相关常量
    private Handler checkHandler;
    private Runnable checkRunnable;
    private static final long CHECK_INTERVAL = 10000; // 10秒检查一次
    
    // 权限控制相关
    private static final String VALID_PACKAGE = "pub.log.startfrp";
    
    // FRP进程相关路径
    private static final String TARGET_DIR = "/data/local/tmp/startfrp/";
    private static final String FRPC_PATH = TARGET_DIR + "libfrpc.so";
    private static final String CONFIG_PATH = TARGET_DIR + "frpc.toml";
    
    // 日志管理器
    private LogManager logManager;
    
    @Override
    public void onCreate() {
        super.onCreate();
        logManager = LogManager.getInstance(this);
        logManager.d("FrpcDaemonService", "守护进程服务已创建");
        
        // 初始化通知渠道
        createNotificationChannel();
        
        // 初始化定时检查机制，每10秒检查一次
        checkHandler = new Handler(Looper.getMainLooper());
        checkRunnable = new Runnable() {
            @Override
            public void run() {
                logManager.d("FrpcDaemonService", "定时检查libfrpc进程运行状态");
                checkAndManageFRPProcess();
                // 10秒后再次检查
                checkHandler.postDelayed(this, CHECK_INTERVAL);
            }
        };
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 严格的权限控制：只允许来自本应用的启动命令
        if (intent == null) {
            logManager.e("FrpcDaemonService", "收到空的Intent，拒绝执行");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // 检查Intent的Action是否合法（只有本应用知道合法的Action）
        String action = intent.getAction();
        if (!ACTION_START.equals(action) && !ACTION_STOP.equals(action)) {
            logManager.e("FrpcDaemonService", "收到非法的Action: " + action + ", 拒绝执行");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // 由于我们在Manifest中已经设置了exported="false"，
        // 只有本应用内的组件可以访问该服务，因此不需要额外的调用者验证
        // 这里可以添加额外的安全检查，如验证Intent中的特殊参数
        String securityToken = intent.getStringExtra("security_token");
        if (securityToken == null || !securityToken.equals("frpc_daemon_secure_token_2024")) {
            logManager.e("FrpcDaemonService", "安全令牌验证失败，拒绝执行");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // 处理停止命令
        if (ACTION_STOP.equals(intent.getAction())) {
            logManager.d("FrpcDaemonService", "收到停止命令");
            stopDaemonService();
            return START_NOT_STICKY;
        }
        
        // 处理启动命令
        if (!isRunning) {
            logManager.d("FrpcDaemonService", "启动守护进程服务");
            isRunning = true;
            
            // 立即执行一次检查
            checkHandler.post(checkRunnable);
            
            // 启动前台服务，确保在主进程退出后仍然运行
            startForegroundService();
        }
        
        // 返回START_STICKY确保系统在服务被杀死后重启它
        return START_STICKY;
    }
    
    /**
     * 启动前台服务，确保服务在后台持续运行
     */
    private void startForegroundService() {
        try {
            // 创建打开应用的意图
            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
            
            // 创建停止服务的意图
            Intent stopIntent = new Intent(this, FrpcDaemonService.class);
            stopIntent.setAction(ACTION_STOP);
            PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);
            
            // 构建通知
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("FRP守护进程")
                    .setContentText("正在监控libfrpc进程")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .addAction(R.mipmap.ic_launcher, "停止", stopPendingIntent)
                    .build();
            
            // 启动前台服务
            startForeground(NOTIFICATION_ID, notification);
            logManager.d("FrpcDaemonService", "守护进程已作为前台服务启动");
        } catch (Exception e) {
            logManager.e("FrpcDaemonService", "启动前台服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查libfrpc进程是否运行，如果没有运行则直接使用Shizuku和nohup启动它
     */
    private void checkAndManageFRPProcess() {
        if (!Shizuku.pingBinder()) {
            logManager.e("FrpcDaemonService", "Shizuku服务不可用，无法检查和管理进程");
            return;
        }
        
        try {
            // 使用Shizuku检查libfrpc进程是否存在
            String[] checkCommand = {"sh", "-c", "ps -A | grep libfrpc | grep -v grep"};
            ShizukuRemoteProcess checkProcess = ShizukuHelper.newProcess(checkCommand, null, null);
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream()));
            String result = reader.readLine();
            reader.close();
            checkProcess.waitFor();
            
            boolean processRunning = result != null && !result.trim().isEmpty();
            
            if (!processRunning) {
                logManager.d("FrpcDaemonService", "未检测到libfrpc进程，尝试直接启动libfrpc.so");
                
                // 确保目标目录存在
                String[] mkdirCommand = {"mkdir", "-p", TARGET_DIR};
                ShizukuRemoteProcess mkdirProcess = ShizukuHelper.newProcess(mkdirCommand, null, null);
                mkdirProcess.waitFor();
                
                // 检查文件是否存在
                String[] checkFilesCommand = {"sh", "-c", "ls -la " + TARGET_DIR};
                ShizukuRemoteProcess checkFilesProcess = ShizukuHelper.newProcess(checkFilesCommand, null, null);
                
                BufferedReader filesReader = new BufferedReader(new InputStreamReader(checkFilesProcess.getInputStream()));
                String filesList;
                StringBuilder filesBuilder = new StringBuilder();
                while ((filesList = filesReader.readLine()) != null) {
                    filesBuilder.append(filesList).append("\n");
                }
                filesReader.close();
                checkFilesProcess.waitFor();
                
                logManager.d("FrpcDaemonService", "目标目录文件列表: \n" + filesBuilder.toString());
                
                // 检查libfrpc.so和frpc.toml文件是否存在
                boolean libfrpcExists = new File(FRPC_PATH).exists();
                boolean configExists = new File(CONFIG_PATH).exists();
                
                logManager.d("FrpcDaemonService", "libfrpc.so存在: " + libfrpcExists + ", frpc.toml存在: " + configExists);
                
                if (libfrpcExists && configExists) {
                    // 使用shell执行命令并添加nohup，确保进程在后台运行
                    String shellCommand = "nohup " + FRPC_PATH + " -c " + CONFIG_PATH + " >>/dev/null 2>&1 &";
                    String[] command = {
                        "sh",
                        "-c",
                        shellCommand
                    };
                    
                    // 设置环境变量，包含目标目录
                    String[] env = {
                        "LD_LIBRARY_PATH=" + TARGET_DIR
                    };
                    
                    logManager.d("FrpcDaemonService", "使用nohup启动libfrpc.so: " + shellCommand);
                    
                    // 使用Shizuku执行命令
                    ShizukuRemoteProcess shizukuProcess = ShizukuHelper.newProcess(command, env, TARGET_DIR);
                    
                    // 关闭输入输出流
                    shizukuProcess.getInputStream().close();
                    shizukuProcess.getErrorStream().close();
                    shizukuProcess.getOutputStream().close();
                    
                    // 等待命令执行完成
                    int exitCode = shizukuProcess.waitFor();
                    
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    logManager.d("FrpcDaemonService", timestamp + " libfrpc.so已通过nohup启动");
                    logManager.d("FrpcDaemonService", "启动命令退出码: " + exitCode);
                    
                    // 检查进程是否真的启动成功
                    new Thread(() -> {
                        try {
                            // 等待2秒，让frpc进程有时间启动
                            Thread.sleep(2000);
                            String[] checkNewProcessCommand = {"sh", "-c", "ps -A | grep libfrpc | grep -v grep"};
                            ShizukuRemoteProcess newCheckProcess = ShizukuHelper.newProcess(checkNewProcessCommand, null, null);
                            
                            BufferedReader newReader = new BufferedReader(new InputStreamReader(newCheckProcess.getInputStream()));
                            String newResult = newReader.readLine();
                            newReader.close();
                            newCheckProcess.waitFor();
                            
                            boolean newProcessRunning = newResult != null && !newResult.trim().isEmpty();
                            logManager.d("FrpcDaemonService", "nohup启动后检查libfrpc进程状态: " + newProcessRunning);
                            if (newProcessRunning) {
                                // 获取PID（简单分割，取第二个字段）
                                String pid = "未知";
                                if (newResult != null) {
                                    String[] parts = newResult.split(" ");
                                    for (String part : parts) {
                                        if (!part.isEmpty()) {
                                            pid = part;
                                            break;
                                        }
                                    }
                                }
                                logManager.d("FrpcDaemonService", "libfrpc进程启动成功，PID: " + pid);
                            } else {
                                logManager.e("FrpcDaemonService", "使用nohup启动libfrpc.so失败，系统中没有检测到进程");
                            }
                        } catch (Exception e) {
                            logManager.e("FrpcDaemonService", "检查nohup启动状态时发生错误: " + e.getMessage(), e);
                        }
                    }).start();
                } else {
                    logManager.e("FrpcDaemonService", "libfrpc.so或frpc.toml文件不存在，无法启动进程");
                }
            } else {
                // 获取PID（简单分割，取第一个非空字段）
                String pid = "未知";
                String[] parts = result.split(" ");
                for (String part : parts) {
                    if (!part.isEmpty()) {
                        pid = part;
                        break;
                    }
                }
                logManager.d("FrpcDaemonService", "libfrpc进程正在运行，PID: " + pid);
            }
            
        } catch (Exception e) {
            logManager.e("FrpcDaemonService", "检查或管理FRP进程时发生错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 停止守护进程服务
     */
    private void stopDaemonService() {
        logManager.d("FrpcDaemonService", "停止守护进程服务");
        
        // 移除定时检查
        checkHandler.removeCallbacks(checkRunnable);
        isRunning = false;
        
        // 停止前台服务
        stopForeground(true);
        
        // 停止服务
        stopSelf();
    }
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            CharSequence name = "FRP守护进程";
            String description = "持续监控libfrpc进程，确保其正常运行";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.setShowBadge(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        logManager.d("FrpcDaemonService", "守护进程服务已销毁");
        isRunning = false;
        checkHandler.removeCallbacks(checkRunnable);
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // 不支持绑定
        return null;
    }
}
package pub.log.startfrp;

import android.app.ActivityManager;

import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import android.provider.Settings;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // UI组件
    private Button btnControl;
    private Button btnTools;
    private TextView tvStatus;
    private TextView tvLogView;
    private Button btnInstallShizuku;
    private CheckBox cbAutoStart;
    private Button btnBackgroundConfig;
    private Button btnBatteryOptimization;
    private CheckBox cbExcludeFromRecents;
    private CheckBox cbAccessibilityService;
    private CheckBox cbUseShizuku;
    private Button btnStartDaemon;
    private Button btnStopDaemon;
    
    // Shizuku监听器
    private Shizuku.OnBinderReceivedListener binderReceivedListener;
    private Shizuku.OnBinderDeadListener binderDeadListener;

    // 状态变量
    private boolean isRunning = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable logUpdateRunnable;

    // 权限相关
    private static final int FOREGROUND_SERVICE_PERMISSION_CODE = 1002;
    private static final int NOTIFICATION_PERMISSION_CODE = 1003;
    private static final int SHIZUKU_PERMISSION_CODE = 1004;
    
    // 配置相关
    private SharedPreferences sharedPreferences;
    
    // 日志管理器
    private LogManager logManager;
    
    // FRP相关常量
    private static final String FRP_SUBDIR = "frp";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences("frp_config", Context.MODE_PRIVATE);
        
        // 初始化日志管理器
        logManager = LogManager.getInstance(this);
        
        // 初始化Shizuku监听器
        binderReceivedListener = () -> {
            logManager.d("MainActivity", "Shizuku Binder received");
            // 检查Shizuku服务版本和应用注册状态
            checkShizukuStatus();
        };
        
        binderDeadListener = () -> {
            logManager.d("MainActivity", "Shizuku Binder dead");
        };

        // 设置边缘到边缘
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        
        // 应用excludeFromRecents设置
        boolean excludeFromRecents = sharedPreferences.getBoolean("exclude_from_recents", false);
        if (excludeFromRecents) {
            // 设置Activity排除在最近任务之外
            // 使用Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS标志位
            // 这个标志位需要在Activity启动时设置
        } else {
            // 确保Activity不排除在最近任务之外
        }

        // 初始化UI
        initViews();
        
        // 创建通知渠道（Android O及以上版本需要）
        createNotificationChannel();
        
        // 请求通知权限（Android 13+需要）
        requestNotificationPermission();

        // 注册广播接收器
        registerLogReceiver();
        
        // 注册Shizuku监听器
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);

        // 检查文件和状态
        checkFilesAndStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 检查系统中无障碍服务的实际状态
        boolean isAccessibilityEnabled = isAccessibilityServiceEnabled();
        
        // 更新开关状态为系统实际状态
        cbAccessibilityService.setChecked(isAccessibilityEnabled);
        
        // 更新用户意图为当前系统状态
        sharedPreferences.edit().putBoolean("accessibility_wanted", isAccessibilityEnabled).apply();
        
        // 检查前台服务状态
        checkForegroundServiceStatus();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销广播接收器
        unregisterLogReceiver();
        // 停止日志更新
        stopLogUpdate();
        // 注销Shizuku监听器
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
    }
    
    /**
     * 检查Shizuku状态并输出调试信息
     */
    private void checkShizukuStatus() {
        try {
            logManager.d("MainActivity", "Shizuku版本: " + Shizuku.getVersion());
            logManager.d("MainActivity", "Shizuku是否可用: " + (Shizuku.pingBinder() ? "是" : "否"));
            
            // 检查权限
            if (Shizuku.isPreV11()) {
                logManager.d("MainActivity", "Shizuku版本低于11，不支持新的权限模型");
            } else {
                int permission = Shizuku.checkSelfPermission();
                logManager.d("MainActivity", "Shizuku权限状态: " + permission);
                boolean shouldShowRationale = Shizuku.shouldShowRequestPermissionRationale();
                logManager.d("MainActivity", "是否需要显示权限理由: " + shouldShowRationale);
            }
        } catch (Throwable e) {
            logManager.e("MainActivity", "检查Shizuku状态时出错: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            // 检查通知权限是否被授予
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logManager.d("MainActivity", "通知权限已授予");
                // 如果FRP服务正在运行，重新启动前台服务以显示通知
                if (isRunning) {
                    StatusService.startService(this);
                }
            } else {
                logManager.d("MainActivity", "通知权限被拒绝");
                showToast("通知权限被拒绝，将无法显示运行状态通知");
            }
        }
    }
    
    /**
     * 注册广播接收器
     */
    private void registerLogReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(FrpcService.ACTION_LOG_UPDATE);
        filter.addAction(FrpcService.ACTION_STATUS_UPDATE);
        filter.addAction("pub.log.startfrp.FRP_SERVICE_STOPPED");
        // 使用RECEIVER_EXPORTED标志，确保能够接收来自同一应用不同进程的广播
        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        logManager.d("MainActivity", "广播接收器已注册");
    }
    
    /**
     * 注销广播接收器
     */
    private void unregisterLogReceiver() {
        try {
            unregisterReceiver(logReceiver);
            logManager.d("MainActivity", "广播接收器已注销");
        } catch (IllegalArgumentException e) {
            // 如果接收器未注册，忽略异常
            logManager.d("MainActivity", "广播接收器未注册，无需注销");
        }
    }

    private void initViews() {
        logManager.d("MainActivity", "初始化UI组件");

        // 获取UI组件
        btnControl = findViewById(R.id.button);
        btnTools = findViewById(R.id.btnTools);
        Button btnAbout = findViewById(R.id.btnAbout);
        tvStatus = findViewById(R.id.tvStatus);
        tvLogView = findViewById(R.id.tvLogView);
        Button btnCopyLog = findViewById(R.id.btnCopyLog);
        cbAutoStart = findViewById(R.id.cbAutoStart);
        btnBackgroundConfig = findViewById(R.id.btnBackgroundConfig);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        cbExcludeFromRecents = findViewById(R.id.cbExcludeFromRecents);
        cbAccessibilityService = findViewById(R.id.cbAccessibilityService);
        
        // 设置版本号
        TextView tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText(getResources().getString(R.string.kernel_version));

        // 设置日志TextView可以滚动
        tvLogView.setMovementMethod(new ScrollingMovementMethod());
        
        // 设置日志TextView支持复制功能
        tvLogView.setTextIsSelectable(true);

        // 初始化安装Shizuku按钮
        btnInstallShizuku = findViewById(R.id.btnInstallShizuku);
        btnInstallShizuku.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installShizukuFiles();
            }
        });

        // 设置按钮文本
        btnControl.setText("启动FRP");
        btnControl.setTextSize(16);

        // 设置控制按钮点击事件
        btnControl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logManager.d("MainActivity", "控制按钮被点击，当前状态: " + (isRunning ? "运行中" : "已停止"));
                if (isRunning) {
                    stopFRPService();
                } else {
                    startFRPService();
                }
            }
        });

        // 长按控制按钮查看详细状态
        btnControl.setOnLongClickListener(v -> {
            showDetailedStatus();
            return true;
        });
        
        // 设置复制日志按钮点击事件
        btnCopyLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logManager.d("MainActivity", "复制日志按钮被点击");
                copyLogToClipboard();
            }
        });

        // 设置配置按钮点击事件
        btnTools.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logManager.d("MainActivity", "配置按钮被点击");
                Intent intent = new Intent(MainActivity.this, ConfigEditorActivity.class);
                intent.putExtra("isRunning", isRunning);
                startActivity(intent);
            }
        });

        // 设置关于按钮点击事件
        findViewById(R.id.btnAbout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logManager.d("MainActivity", "关于按钮被点击");
                Intent intent = new Intent(MainActivity.this, AboutActivity.class);
                startActivity(intent);
            }
        });
        
        // 设置开机自启动CheckBox
        cbAutoStart.setChecked(sharedPreferences.getBoolean("auto_start", false));
        cbAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            logManager.d("MainActivity", "开机自启动设置变更: " + (isChecked ? "开启" : "关闭"));
            sharedPreferences.edit().putBoolean("auto_start", isChecked).apply();
            showToast(isChecked ? "已开启开机自启动" : "已关闭开机自启动");
            
            // 当开启开机自启动时，引导用户到系统设置界面开启自启动权限
            if (isChecked) {
                showAutostartGuideDialog();
            }
        });
        
        // 设置后台配置按钮
        btnBackgroundConfig.setOnClickListener((view) -> {
            logManager.d("MainActivity", "点击后台配置按钮");
            // 直接跳转到电池优化设置界面
            requestIgnoreBatteryOptimizations();
        });
        
        // 设置电池优化按钮
        btnBatteryOptimization.setOnClickListener((view) -> {
            logManager.d("MainActivity", "点击电池优化按钮");
            // 跳转到电池优化应用列表界面
            openBatteryOptimizationList();
        });
        
        // 初始化守护进程按钮
        btnStartDaemon = findViewById(R.id.btnStartDaemon);
        btnStopDaemon = findViewById(R.id.btnStopDaemon);
        
        // 设置守护进程按钮点击事件
        btnStartDaemon.setOnClickListener((view) -> {
            logManager.d("MainActivity", "点击启动守护进程按钮");
            startDaemonService();
        });
        
        btnStopDaemon.setOnClickListener((view) -> {
            logManager.d("MainActivity", "点击停止守护进程按钮");
            stopDaemonService();
        });
        
        // 设置"不在最近任务中显示"CheckBox
        cbExcludeFromRecents.setChecked(sharedPreferences.getBoolean("exclude_from_recents", false));
        cbExcludeFromRecents.setOnCheckedChangeListener((buttonView, isChecked) -> {
            logManager.d("MainActivity", "不在最近任务中显示设置变更: " + (isChecked ? "开启" : "关闭"));
            sharedPreferences.edit().putBoolean("exclude_from_recents", isChecked).apply();
            
            // 应用excludeFromRecents设置
            applyExcludeFromRecents(isChecked);
        });
        
        // 应用已保存的excludeFromRecents设置
        applyExcludeFromRecents(sharedPreferences.getBoolean("exclude_from_recents", false));
        
        // 初始化无障碍服务开关
        initAccessibilityService();
        
        // 初始化Shizuku复选框
        cbUseShizuku = findViewById(R.id.cbUseShizuku);
        cbUseShizuku.setChecked(sharedPreferences.getBoolean("use_shizuku", false));
        cbUseShizuku.setOnCheckedChangeListener((buttonView, isChecked) -> {
            logManager.d("MainActivity", "使用Shizuku设置变更: " + (isChecked ? "开启" : "关闭"));
            sharedPreferences.edit().putBoolean("use_shizuku", isChecked).apply();
            
            if (isChecked) {
                // 检查Shizuku是否可用
                if (!Shizuku.pingBinder()) {
                    showToast("Shizuku服务不可用，请先启动Shizuku应用");
                    // 取消勾选
                    buttonView.setChecked(false);
                    return;
                }
                
                // 检查Shizuku权限
                if (!Shizuku.isPreV11()) {
                    int permission = Shizuku.checkSelfPermission();
                    if (permission != PackageManager.PERMISSION_GRANTED) {
                        showToast("正在请求Shizuku权限");
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
                    }
                }
                
                showToast("已开启Shizuku运行");
            } else {
                showToast("已关闭Shizuku运行");
            }
        });
    }

    private void startFRPService() {
        logManager.d("MainActivity", "启动FRP服务");

        // 检查并复制frp配置文件
        String frpDir = getFrpDirPath();
        File configFile = new File(frpDir, "frpc.toml");

        if (!configFile.exists()) {
            showToast("frpc.toml文件不存在，正在复制...");
            boolean copied = copyAssetsToFrpDir();
            if (!copied) {
                showToast("文件复制失败");
                return;
            }
        }

        Intent serviceIntent = new Intent(this, FrpcService.class);
        serviceIntent.setAction(FrpcService.ACTION_START);
        serviceIntent.putExtra("use_shizuku", cbUseShizuku.isChecked());

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            // 同时启动前台保活服务，确保显示常驻通知
            StatusService.startService(this);

            isRunning = true;
            updateUI();
            startLogUpdate();
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            updateLogView(timestamp + " FRP正在启动...");
        } catch (SecurityException e) {
            logManager.e("MainActivity", "启动服务安全异常: " + e.getMessage());
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            updateLogView(timestamp + " 启动失败：缺少必要权限");
        } catch (Exception e) {
            logManager.e("MainActivity", "启动服务失败: " + e.getMessage());
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            updateLogView(timestamp + " 启动失败: " + e.getMessage());
        }
    }

    /**
     * 初始化无障碍服务开关
     */
    private void initAccessibilityService() {
        // 检查系统中无障碍服务的实际状态
        boolean isAccessibilityEnabled = isAccessibilityServiceEnabled();
        
        // 设置开关状态为系统实际状态
        cbAccessibilityService.setChecked(isAccessibilityEnabled);
        
        // 保存当前系统状态作为用户意图
        sharedPreferences.edit().putBoolean("accessibility_wanted", isAccessibilityEnabled).apply();
        
        // 设置点击事件监听器
        cbAccessibilityService.setOnClickListener(view -> {
            // 获取用户点击后的新状态（点击前的相反状态）
            boolean wantEnable = !isAccessibilityServiceEnabled();
            // 保存用户的勾选意图
            sharedPreferences.edit().putBoolean("accessibility_wanted", wantEnable).apply();
            
            // 引导用户到系统设置页面
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            
            // 显示提示信息
            if (wantEnable) {
                showToast(getString(R.string.accessibility_service_tip));
            } else {
                showToast("请在系统设置中禁用FRP Client无障碍服务");
            }
        });
        
        // 禁用自动状态改变
        cbAccessibilityService.setOnCheckedChangeListener(null);
    }
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        // Android O及以上版本需要创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 创建通知渠道
            NotificationChannelCompat channel = new NotificationChannelCompat.Builder(
                    "frp_client_channel",  // 渠道ID
                    NotificationManagerCompat.IMPORTANCE_HIGH  // 重要性
            )
                    .setName("FRP Client通知")  // 渠道名称
                    .setDescription("FRP Client运行状态通知")  // 渠道描述
                    .build();

            // 注册通知渠道
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 请求通知权限（Android 13+需要）
     */
    private void requestNotificationPermission() {
        // Android 13+需要明确请求POST_NOTIFICATIONS权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // 请求通知权限
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    /**
     * 检查前台服务状态
     */
    private void checkForegroundServiceStatus() {
        // 如果无障碍服务已启用，但前台服务未运行，则启动前台服务
        boolean isAccessibilityEnabled = isAccessibilityServiceEnabled();
        if (isAccessibilityEnabled && !StatusService.isRunning) {
            logManager.d("MainActivity", "无障碍服务已启用，但前台服务未运行，尝试启动前台服务");
            StatusService.startService(this);
        }
    }

    /**
     * 检查无障碍服务是否已启用
     * @return 无障碍服务是否已启用
     */
    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/" + AccessibilityKeepAliveService.class.getName();
        
        try {
            accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            TextUtils.SimpleStringSplitter mStringColonSplitter = new TextUtils.SimpleStringSplitter(':');
            
            if (accessibilityEnabled == 1) {
                String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (settingValue != null) {
                    mStringColonSplitter.setString(settingValue);
                    while (mStringColonSplitter.hasNext()) {
                        String accessibilityService = mStringColonSplitter.next();
                        if (accessibilityService.equalsIgnoreCase(service)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Settings.SettingNotFoundException e) {
            logManager.e("MainActivity", "Error checking accessibility service status: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * 应用excludeFromRecents设置
     * @param exclude 是否排除在最近任务列表之外
     */
    private void applyExcludeFromRecents(boolean exclude) {
        // 使用ActivityManager设置任务的excludeFromRecents属性
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            List<ActivityManager.AppTask> tasks = activityManager.getAppTasks();
            if (tasks != null && !tasks.isEmpty()) {
                // 设置当前任务的excludeFromRecents属性
                tasks.get(0).setExcludeFromRecents(exclude);
            }
        }
    }
    
    private void stopFRPService() {
        logManager.d("MainActivity", "停止FRP服务");

        // 发送停止命令给服务，让服务自己管理停止过程
        Intent serviceIntent = new Intent(this, FrpcService.class);
        serviceIntent.setAction(FrpcService.ACTION_STOP);
        startService(serviceIntent);

        // 不直接调用stopService，避免在服务执行完停止逻辑之前就终止服务
        // 等待服务通过广播通知停止完成
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        updateLogView(timestamp + " FRP正在停止...");
        
        // 添加超时机制，确保即使广播没有被接收，UI也能正确更新
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 检查服务是否真的已经停止
                boolean serviceRunning = FrpcService.isRunning;
                if (!serviceRunning) {
                    // 如果服务已经停止，但UI仍然显示运行中，则更新UI
                    if (isRunning) {
                        logManager.d("MainActivity", "超时检查：服务已停止，但UI显示运行中，强制更新UI");
                        isRunning = false;
                        updateUI();
                    }
                } else {
                    // 如果服务仍然在运行，尝试直接检查进程
                    logManager.d("MainActivity", "超时检查：服务仍显示运行中，检查实际进程状态");
                    checkFRPProcessStatus();
                }
            }
        }, 3000); // 3秒超时
    }
    
    /**
     * 启动守护进程服务
     */
    private void startDaemonService() {
        logManager.d("MainActivity", "启动守护进程服务");
        
        // 检查Shizuku是否可用
        if (!Shizuku.pingBinder()) {
            showToast("Shizuku服务不可用，无法启动守护进程");
            logManager.e("MainActivity", "Shizuku服务不可用，无法启动守护进程");
            return;
        }
        
        // 启动守护进程服务
        Intent daemonIntent = new Intent(this, FrpcDaemonService.class);
        daemonIntent.setAction(FrpcDaemonService.ACTION_START);
        daemonIntent.putExtra("security_token", "frpc_daemon_secure_token_2024");
        startForegroundService(daemonIntent);
        
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        updateLogView(timestamp + " FRP守护进程已启动");
    }
    
    /**
     * 停止守护进程服务
     */
    private void stopDaemonService() {
        logManager.d("MainActivity", "停止守护进程服务");
        
        // 发送停止命令给守护进程服务
        Intent daemonIntent = new Intent(this, FrpcDaemonService.class);
        daemonIntent.setAction(FrpcDaemonService.ACTION_STOP);
        daemonIntent.putExtra("security_token", "frpc_daemon_secure_token_2024");
        startService(daemonIntent);
        
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        updateLogView(timestamp + " FRP守护进程已停止");
    }
    
    /**
     * 安装Shizuku文件
     */
    private void installShizukuFiles() {
        if (!Shizuku.pingBinder()) {
            updateLogView("[错误] Shizuku服务不可用");
            return;
        }

        // 检查Shizuku权限
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            updateLogView("[提示] 正在请求Shizuku权限");
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
            return;
        }

        updateLogView("[信息] 开始安装Shizuku文件");
        new Thread(() -> {
            try {
                // 创建目标目录
                String targetDir = "/data/local/tmp/startfrp/";
                String[] mkdirCmd = {"mkdir", "-p", targetDir};
                ShizukuRemoteProcess mkdirProcess = ShizukuHelper.newProcess(mkdirCmd, null, null);
                int mkdirExitCode = mkdirProcess.waitFor();
                if (mkdirExitCode != 0) {
                    updateLogView("[错误] 创建目录失败: " + targetDir);
                    return;
                }

                // 获取应用内部文件路径
                String nativeLibPath = getApplicationInfo().nativeLibraryDir;
                File nativeLibDir = new File(nativeLibPath);
                String frpDirPath = getFrpDirPath();
                File frpDir = new File(frpDirPath);

                // 复制libfrpc.so文件
                File libFile = new File(nativeLibDir, "libfrpc.so");
                if (libFile.exists()) {
                    if (copyFileWithShizuku(libFile.getAbsolutePath(), targetDir + "libfrpc.so")) {
                        updateLogView("[成功] 复制libfrpc.so到 " + targetDir);
                    } else {
                        updateLogView("[错误] 复制libfrpc.so失败");
                    }
                } else {
                    updateLogView("[错误] libfrpc.so文件不存在: " + libFile.getAbsolutePath());
                }

                // 复制frpc.toml文件
                File configFile = new File(frpDir, "frpc.toml");
                if (configFile.exists()) {
                    if (copyFileWithShizuku(configFile.getAbsolutePath(), targetDir + "frpc.toml")) {
                        updateLogView("[成功] 复制frpc.toml到 " + targetDir);
                    } else {
                        updateLogView("[错误] 复制frpc.toml失败");
                    }
                } else {
                    updateLogView("[错误] frpc.toml文件不存在: " + configFile.getAbsolutePath());
                }

                // 复制证书和密钥文件
                File[] crtFiles = frpDir.listFiles((dir, name) -> name.endsWith(".crt"));
                if (crtFiles != null && crtFiles.length > 0) {
                    for (File crtFile : crtFiles) {
                        if (copyFileWithShizuku(crtFile.getAbsolutePath(), targetDir + crtFile.getName())) {
                            updateLogView("[成功] 复制 " + crtFile.getName() + " 到 " + targetDir);
                        } else {
                            updateLogView("[错误] 复制 " + crtFile.getName() + " 失败");
                        }
                    }
                }

                File[] keyFiles = frpDir.listFiles((dir, name) -> name.endsWith(".key"));
                if (keyFiles != null && keyFiles.length > 0) {
                    for (File keyFile : keyFiles) {
                        if (copyFileWithShizuku(keyFile.getAbsolutePath(), targetDir + keyFile.getName())) {
                            updateLogView("[成功] 复制 " + keyFile.getName() + " 到 " + targetDir);
                        } else {
                            updateLogView("[错误] 复制 " + keyFile.getName() + " 失败");
                        }
                    }
                }

                // 设置文件权限
                String[] chmodCmd = {"chmod", "755", targetDir + "libfrpc.so"};
                ShizukuRemoteProcess chmodProcess = ShizukuHelper.newProcess(chmodCmd, null, null);
                chmodProcess.waitFor();

                updateLogView("[完成] Shizuku文件安装完成");
            } catch (Exception e) {
                updateLogView("[错误] 安装Shizuku文件时出错: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * 使用Shizuku复制文件
     */
    private boolean copyFileWithShizuku(String sourcePath, String targetPath) {
        try {
            // 检查源文件是否存在
            File sourceFile = new File(sourcePath);
            if (!sourceFile.exists()) {
                updateLogView("[错误] 源文件不存在: " + sourcePath);
                return false;
            }
            updateLogView("[信息] 源文件存在: " + sourcePath);
            updateLogView("[信息] 源文件大小: " + sourceFile.length() + " 字节");
            updateLogView("[信息] 源文件权限: " + (sourceFile.canRead() ? "可读" : "不可读"));

            // 方法1: 尝试直接复制（适用于可访问的文件，如libfrpc.so）
            try {
                String[] cpCmd = {"cp", sourcePath, targetPath};
                updateLogView("[信息] 执行命令: cp " + sourcePath + " " + targetPath);
                ShizukuRemoteProcess cpProcess = ShizukuHelper.newProcess(cpCmd, null, null);
                
                // 读取命令输出
                InputStream inputStream = cpProcess.getInputStream();
                InputStream errorStream = cpProcess.getErrorStream();
                String output = readStream(inputStream);
                String error = readStream(errorStream);
                
                int exitCode = cpProcess.waitFor();
                
                if (exitCode == 0) {
                    updateLogView("[信息] 命令执行成功");
                    if (!output.isEmpty()) {
                        updateLogView("[信息] 命令输出: " + output);
                    }
                    return true;
                } else {
                    updateLogView("[警告] 直接复制失败，尝试使用Java API复制: " + error);
                }
            } catch (Exception e) {
                updateLogView("[警告] 直接复制失败，尝试使用Java API复制: " + e.getMessage());
            }

            // 方法2: 使用Java API读取源文件，然后使用Shizuku写入目标文件
            // 这样可以绕过权限问题，因为Java API可以访问应用自己的私有目录
            updateLogView("[信息] 使用Java API复制文件...");
            
            // 读取源文件内容
            byte[] fileContent = new byte[(int) sourceFile.length()];
            try (FileInputStream fis = new FileInputStream(sourceFile)) {
                fis.read(fileContent);
            }
            updateLogView("[信息] 成功读取源文件内容，大小: " + fileContent.length + " 字节");

            // 使用Shizuku将内容写入目标文件
            String[] writeCmd = {"sh", "-c", "cat > '" + targetPath + "'"};
            updateLogView("[信息] 执行命令: sh -c cat > '" + targetPath + "'");
            ShizukuRemoteProcess writeProcess = ShizukuHelper.newProcess(writeCmd, null, null);
            
            // 获取输出流并写入内容
            OutputStream os = writeProcess.getOutputStream();
            os.write(fileContent);
            os.close();
            
            // 读取命令输出
            InputStream inputStream = writeProcess.getInputStream();
            InputStream errorStream = writeProcess.getErrorStream();
            String output = readStream(inputStream);
            String error = readStream(errorStream);
            
            int exitCode = writeProcess.waitFor();
            
            if (exitCode != 0) {
                updateLogView("[错误] 写入目标文件失败，退出码: " + exitCode);
                if (!output.isEmpty()) {
                    updateLogView("[信息] 命令输出: " + output);
                }
                if (!error.isEmpty()) {
                    updateLogView("[错误] 命令错误: " + error);
                }
                return false;
            } else {
                updateLogView("[信息] 成功写入目标文件");
                if (!output.isEmpty()) {
                    updateLogView("[信息] 命令输出: " + output);
                }
                return true;
            }
        } catch (Exception e) {
            updateLogView("[错误] 复制文件时异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 读取输入流内容
     */
    private String readStream(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, len));
        }
        inputStream.close();
        return sb.toString();
    }

    /**
     * 检查FRP进程状态
     */
    private void checkFRPProcessStatus() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 尝试通过命令行检查frpc进程是否存在
                    Process process = Runtime.getRuntime().exec("/system/bin/ps -ef | grep libfrpc.so");
                    InputStream inputStream = process.getInputStream();
                    byte[] buffer = new byte[1024];
                    int length = inputStream.read(buffer);
                    String output = new String(buffer, 0, length);
                    
                    final boolean hasFrpcProcess = output.contains("libfrpc.so") && !output.contains("grep");
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!hasFrpcProcess && isRunning) {
                                logManager.d("MainActivity", "进程检查：frpc进程不存在，但UI显示运行中，强制更新UI");
                                isRunning = false;
                                updateUI();
                                String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                                updateLogView(timestamp + " FRP服务已停止");
                            }
                        }
                    });
                } catch (Exception e) {
                    logManager.e("MainActivity", "检查进程状态时发生错误：" + e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void updateUI() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    tvStatus.setText("FRP运行中");
                    tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                    btnControl.setText("停止FRP");
                    btnTools.setEnabled(false); // FRP运行时禁用配置按钮
                } else {
                    tvStatus.setText("FRP已停止");
                    tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    btnControl.setText("启动FRP");
                    btnTools.setEnabled(true); // FRP停止时启用配置按钮
                }
            }
        });
    }

    private void checkFilesAndStatus() {
        String logMsg = "开始检查文件和状态";
        logManager.d("MainActivity", logMsg);
        updateLogView(logMsg);

        // 检查文件是否存在
        boolean filesExist = checkFrpFilesExist();
        logMsg = "FRP文件存在: " + filesExist;
        Log.d("MainActivity", logMsg);
        updateLogView(logMsg);

        if (!filesExist) {
            // 复制文件到应用内部存储
            logMsg = "正在复制FRP文件...";
            showToast(logMsg);
            updateLogView(logMsg);
            
            boolean copied = copyAssetsToFrpDir();
            if (copied) {
                logMsg = "FRP文件复制成功";
                showToast(logMsg);
                updateLogView(logMsg);
                
                String frpDir = getFrpDirPath();
                logMsg = "FRP目录: " + frpDir;
                logManager.d("MainActivity", logMsg);
                updateLogView(logMsg);
            } else {
                logMsg = "FRP文件复制失败，请检查应用权限";
                showToast(logMsg);
                updateLogView(logMsg);
            }
        } else {
            logMsg = "FRP文件已存在，跳过复制";
            logManager.d("MainActivity", logMsg);
            updateLogView(logMsg);
        }

        // 检查运行状态
        checkFRPStatus();
    }

    private void checkFRPStatus() {
        // 先检查静态变量状态
        isRunning = FrpcService.isRunning;
        logManager.d("MainActivity", "FRP服务静态变量状态: " + isRunning);
        
        // 无论静态变量状态如何，都实际检测系统中是否有FRP进程在运行
        // 这样可以解决应用清理内存后重启时状态不同步的问题
        checkFRPProcessInSystem();
        
        logManager.d("MainActivity", "FRP服务最终运行状态: " + isRunning);
        updateUI();

        if (isRunning) {
            startLogUpdate();
        }
    }
    
    /**
     * 检测系统中是否有FRP进程在运行
     * 使用Shizuku执行命令来避免SELinux限制
     */
    private void checkFRPProcessInSystem() {
        try {
            // 尝试使用Shizuku执行命令检测进程
            if (Shizuku.pingBinder() && sharedPreferences.getBoolean("use_shizuku", false)) {
                String checkCommand = "ps -A | grep libfrpc";
                String[] command = {"sh", "-c", checkCommand};
                
                logManager.d("MainActivity", "使用ShizukuHelper执行进程检测命令: " + checkCommand);
                
                // 使用ShizukuHelper调用Shizuku的newProcess方法
                ShizukuRemoteProcess process = ShizukuHelper.newProcess(command, null, null);
                InputStream inputStream = process.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                
                String line;
                boolean foundProcess = false;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("libfrpc")) {
                        logManager.d("MainActivity", "Shizuku检测到frpc进程: " + line.trim());
                        foundProcess = true;
                        break;
                    }
                }
                
                reader.close();
                inputStream.close();
                
                int exitCode = process.waitFor();
                logManager.d("MainActivity", "Shizuku命令执行完成，退出码: " + exitCode);
                
                if (foundProcess && !isRunning) {
                    // 更新状态为运行中
                    isRunning = true;
                    FrpcService.isRunning = true;
                    logManager.d("MainActivity", "检测到FRP进程实际在运行，更新状态为运行中");
                } else if (!foundProcess && isRunning) {
                    // 更新状态为停止
                    isRunning = false;
                    FrpcService.isRunning = false;
                    logManager.d("MainActivity", "未检测到FRP进程，更新状态为停止");
                }
                
            } else {
                logManager.d("MainActivity", "Shizuku不可用或未启用，跳过进程检测");
            }
        } catch (Exception e) {
            logManager.e("MainActivity", "检测进程失败: " + e.getMessage(), e);
        }
    }

    private void showDetailedStatus() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("FRP详细状态")
                .setMessage(
                        "运行状态: " + (isRunning ? "运行中" : "已停止") + "\n"
                                + "应用版本: 1.0.0\n"
                                + "FRP目录: " + getFrpDirPath()
                )
                .setPositiveButton("确定", null)
                .show();
    }

    private void startLogUpdate() {
        logManager.d("MainActivity", "开始更新日志");
        // 日志更新通过广播接收器实现，不需要定期轮询
    }

    private void stopLogUpdate() {
        logManager.d("MainActivity", "停止更新日志");
        // 日志更新通过广播接收器实现，不需要停止定期轮询
    }



    private void showToast(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }



    /**
     * 请求用户将应用排除在电池优化之外
     */
    private void requestIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }
    
    /**
     * 打开电池优化应用列表界面
     */
    private void openBatteryOptimizationList() {
        try {
            // 使用指定的包名和活动名打开电池优化界面
            Intent intent = new Intent();
            intent.setClassName("com.android.settings", "com.android.settings.Settings$HighPowerApplicationsActivity");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            logManager.d("MainActivity", "成功打开电池优化应用列表界面");
        } catch (Exception e) {
            // 如果指定的活动无法打开，尝试打开系统设置的电池优化界面
            try {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
                logManager.d("MainActivity", "使用备用方式打开电池优化界面");
            } catch (Exception ex) {
                logManager.e("MainActivity", "打开电池优化界面失败: " + ex.getMessage());
                showToast("无法打开电池优化界面");
            }
        }
    }

    /**
     * 显示引导用户开启系统自启动权限的对话框
     */
    private void showAutostartGuideDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("提示")
                .setMessage("为了确保开机自启动功能正常工作，请在系统设置中开启本应用的自启动权限。\n\n操作步骤：\n1. 点击\"前往设置\"\n2. 找到\"自启动\"选项\n3. 开启自启动开关")
                .setPositiveButton("前往设置", (dialog, which) -> {
                    // 打开系统设置界面
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        // 如果打开应用详情设置失败，尝试打开其他可能的设置界面
                        try {
                            Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                            startActivity(intent);
                            showToast("请手动找到应用信息并开启自启动权限");
                        } catch (Exception ex) {
                            showToast("无法打开设置界面，请手动开启自启动权限");
                        }
                    }
                })
                .setNegativeButton("暂不设置", (dialog, which) -> {
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }
    
    /**
     * 日志和状态更新的广播接收器
     */
    private final LogReceiver logReceiver = new LogReceiver();
    
    public class LogReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (FrpcService.ACTION_LOG_UPDATE.equals(action)) {
                // 处理日志更新
                String logMessage = intent.getStringExtra(FrpcService.EXTRA_LOG_MESSAGE);
                if (logMessage != null) {
                    updateLogView(logMessage);
                }
            } else if (FrpcService.ACTION_STATUS_UPDATE.equals(action)) {
                // 处理状态更新
                boolean status = intent.getBooleanExtra(FrpcService.EXTRA_STATUS, false);
                isRunning = status;
                updateUI();
            } else if ("pub.log.startfrp.FRP_SERVICE_STOPPED".equals(action)) {
                // 处理服务停止完成的广播
                logManager.d("MainActivity", "收到服务停止完成的广播");
                isRunning = false;
                updateUI();
                stopLogUpdate();
                
                // 停止前台保活服务，移除常驻通知
                StatusService.stopService(MainActivity.this);
                
                String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                updateLogView(timestamp + " FRP服务已停止");
            }
        }
    }
    
    /**
     * 更新日志视图
     * @param logMessage 日志消息
     */
    private void updateLogView(String logMessage) {
        runOnUiThread(() -> {
            if (tvLogView != null) {
                // 获取当前日志内容
                String currentLog = tvLogView.getText().toString();
                // 限制日志长度，避免内存占用过大
                if (currentLog.length() > 10000) {
                    // 截取最新的日志
                    currentLog = currentLog.substring(currentLog.length() - 5000);
                }
                // 添加新日志
                String newLog = currentLog + (currentLog.isEmpty() ? "" : "\n") + logMessage;
                tvLogView.setText(newLog);
                // 滚动到日志底部
                tvLogView.scrollTo(0, tvLogView.getLineCount() * tvLogView.getLineHeight());
            }
        });
    }
    
    /**
     * 复制日志到剪贴板
     */
    private void copyLogToClipboard() {
        String logContent = tvLogView.getText().toString();
        
        if (logContent.isEmpty()) {
            showToast("日志内容为空");
            return;
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10及以上版本
                ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("FRP日志", logContent);
                clipboardManager.setPrimaryClip(clip);
            } else {
                // Android 10以下版本
                ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboardManager.setText(logContent);
            }
            
            showToast("日志已复制到剪贴板");
            logManager.d("MainActivity", "日志已成功复制到剪贴板，长度: " + logContent.length() + " 字符");
            
        } catch (Exception e) {
            logManager.e("MainActivity", "复制日志失败: " + e.getMessage());
            showToast("复制失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // 移除旧的updateLogView方法，使用带参数的版本
    
    /**
     * 检查FRP文件是否存在
     * @return FRP文件是否存在
     */
    private boolean checkFrpFilesExist() {
        logManager.d("MainActivity", "开始检查FRP文件是否存在");

        try {
            String frpDirPath = getFrpDirPath();
            File frpDir = new File(frpDirPath);

            if (!frpDir.exists()) {
                logManager.d("MainActivity", "FRP目录不存在");
                return false;
            }

            // 需要检查的文件列表，只检查frpc.toml配置文件
            // 证书文件(ca.crt, client.crt, client.key)不是必须的，启动时不检查
            String[] requiredFiles = {"frpc.toml"};

            for (String fileName : requiredFiles) {
                File file = new File(frpDir, fileName);
                if (!file.exists()) {
                    logManager.d("MainActivity", "文件不存在: " + fileName);
                    return false;
                }
                logManager.d("MainActivity", "文件存在: " + fileName);
            }

            return true;

        } catch (Exception e) {
            logManager.e("MainActivity", "检查FRP文件存在失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 获取应用数据目录下的frp目录路径
     */
    private String getFrpDirPath() {
        String logMsg = "获取FRP目录路径...";
        Log.d("MainActivity", logMsg);
        updateLogView(logMsg);
        
        try {
            // 使用应用的数据目录
            File dataDir = this.getDataDir();
            if (dataDir == null) {
                logMsg = "无法获取应用数据目录，使用备用路径";
                logManager.e("MainActivity", logMsg);
                updateLogView("[警告] " + logMsg);
                // 备用路径
                String backupPath = this.getFilesDir().getAbsolutePath() + "/frp";
                updateLogView("使用备用路径: " + backupPath);
                return backupPath;
            }

            File frpDir = new File(dataDir, FRP_SUBDIR);
            String path = frpDir.getAbsolutePath();
            logMsg = "FRP目录路径: " + path;
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);
            return path;

        } catch (Exception e) {
            logMsg = "获取应用数据目录失败: " + e.getMessage();
            logManager.e("MainActivity", logMsg);
            updateLogView("[错误] " + logMsg);
            // 备用路径
            String backupPath = this.getFilesDir().getAbsolutePath() + "/frp";
            updateLogView("使用备用路径: " + backupPath);
            return backupPath;
        }
    }
    
    /**
     * 确保应用数据目录下的frp目录存在且可写
     */
    private boolean ensureFrpDir() {
        try {
            String frpDirPath = getFrpDirPath();
            File frpDir = new File(frpDirPath);

            // 记录目录信息
            String logMsg = "检查FRP目录: " + frpDirPath;
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);
            
            logMsg = "目录存在: " + frpDir.exists();
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);

            if (!frpDir.exists()) {
                logMsg = "创建FRP目录...";
                logManager.d("MainActivity", logMsg);
                updateLogView(logMsg);
                
                boolean created = frpDir.mkdirs();
                logMsg = "创建目录结果: " + created;
                Log.d("MainActivity", logMsg);
                updateLogView(logMsg);

                if (!created) {
                    logMsg = "创建目录失败，尝试备用方法";
                    logManager.e("MainActivity", logMsg);
                    updateLogView("[警告] " + logMsg);

                    // 方法1: 尝试逐级创建目录
                    try {
                        logMsg = "尝试逐级创建目录...";
                        logManager.d("MainActivity", logMsg);
                        updateLogView(logMsg);
                        
                        String[] parts = frpDirPath.split("/");
                        StringBuilder currentPath = new StringBuilder();
                        for (String part : parts) {
                            if (!part.isEmpty()) {
                                currentPath.append("/").append(part);
                                File currentDir = new File(currentPath.toString());
                                if (!currentDir.exists()) {
                                    boolean dirCreated = currentDir.mkdir();
                                    logMsg = "创建目录 " + currentPath + ": " + dirCreated;
                                    logManager.d("MainActivity", logMsg);
                                    updateLogView(logMsg);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logMsg = "逐级创建目录失败: " + e.getMessage();
                        logManager.e("MainActivity", logMsg);
                        updateLogView("[错误] " + logMsg);
                    }

                    // 重新检查目录是否存在
                    if (!frpDir.exists()) {
                        logMsg = "最终目录创建失败";
                        logManager.e("MainActivity", logMsg);
                        updateLogView("[错误] " + logMsg);
                        return false;
                    } else {
                        logMsg = "最终目录创建成功";
                        logManager.d("MainActivity", logMsg);
                        updateLogView(logMsg);
                    }
                }
            }

            // 检查目录权限
            boolean canRead = frpDir.canRead();
            boolean canWrite = frpDir.canWrite();
            boolean canExecute = frpDir.canExecute();

            logMsg = "目录权限 - 读: " + canRead + ", 写: " + canWrite + ", 执行: " + canExecute;
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);

            // 尝试设置权限
            boolean setReadable = frpDir.setReadable(true, false);
            boolean setWritable = frpDir.setWritable(true, false);
            boolean setExecutable = frpDir.setExecutable(true, false);

            logMsg = "设置权限结果 - 读: " + setReadable + ", 写: " + setWritable + ", 执行: " + setExecutable;
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);

            // 创建测试文件验证写入权限
            logMsg = "创建测试文件验证写入权限...";
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);
            
            File testFile = new File(frpDir, "test_permission.tmp");
            try {
                OutputStream os = new FileOutputStream(testFile);
                os.write("test".getBytes());
                os.close();
                boolean deleted = testFile.delete();
                logMsg = "写入测试文件成功，删除: " + deleted;
                Log.d("MainActivity", logMsg);
                updateLogView("✓ " + logMsg);
            } catch (Exception e) {
                logMsg = "写入测试文件失败: " + e.getMessage();
                Log.e("MainActivity", logMsg);
                updateLogView("[错误] " + logMsg);
                return false;
            }

            return true;

        } catch (Exception e) {
            logManager.e("MainActivity", "确保FRP目录存在失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 复制assets文件到应用数据目录下的frp目录
     */
    private boolean copyAssetsToFrpDir() {
        String logMsg = "开始复制assets文件到应用数据目录";
        Log.d("MainActivity", logMsg);
        updateLogView(logMsg);

        try {
            // 确保目录存在
            if (!ensureFrpDir()) {
                logMsg = "无法确保FRP目录存在";
                Log.e("MainActivity", logMsg);
                updateLogView("[错误] " + logMsg);
                return false;
            }

            String frpDirPath = getFrpDirPath();
            File frpDir = new File(frpDirPath);

            logMsg = "目标FRP目录: " + frpDir.getAbsolutePath();
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);
            
            logMsg = "目录可写: " + frpDir.canWrite();
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);

            // 检查assets中是否有文件
            logMsg = "检查assets目录中的文件...";
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);
            
            String[] assetFiles = this.getAssets().list("");
            if (assetFiles != null) {
                logMsg = "Assets中的文件: " + String.join(", ", assetFiles);
                Log.d("MainActivity", logMsg);
                updateLogView(logMsg);
            } else {
                logMsg = "Assets中没有文件或无法读取";
                Log.d("MainActivity", logMsg);
                updateLogView("[警告] " + logMsg);
                return false;
            }

            boolean allCopied = true;
            
            // 首先复制必须的frpc.toml文件
            String requiredFile = "frpc.toml";
            logMsg = "正在复制必要文件: " + requiredFile;
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);
            
            try {
                File destFile = new File(frpDir, requiredFile);
                
                // 检查assets中是否存在该文件
                boolean assetExists = false;
                for (String assetFile : assetFiles) {
                    if (assetFile.equals(requiredFile)) {
                        assetExists = true;
                        break;
                    }
                }
                
                if (!assetExists) {
                    logMsg = "文件在assets中不存在: " + requiredFile;
                    logManager.e("MainActivity", logMsg);
                    updateLogView("[错误] " + logMsg);
                    allCopied = false;
                } else {
                    // 复制frpc.toml文件
                    InputStream in = this.getAssets().open(requiredFile);
                    OutputStream out = new FileOutputStream(destFile);
                    byte[] buffer = new byte[1024];
                    int read;
                    int totalRead = 0;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        totalRead += read;
                    }
                    in.close();
                    out.close();
                    
                    logMsg = "✓ 复制成功: " + requiredFile + " (" + totalRead + " bytes)";
                    logManager.d("MainActivity", logMsg);
                    updateLogView(logMsg);
                }
            } catch (Exception e) {
                logMsg = "复制失败: " + requiredFile + ", 错误: " + e.getMessage();
                logManager.e("MainActivity", logMsg, e);
                updateLogView("[错误] " + logMsg);
                allCopied = false;
            }
            
            // 然后复制所有证书和密钥文件
            logMsg = "开始复制证书和密钥文件...";
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);
            
            for (String assetFile : assetFiles) {
                try {
                    // 复制所有.crt和.key文件
                    if (assetFile.endsWith(".crt") || assetFile.endsWith(".key")) {
                        logMsg = "正在复制: " + assetFile;
                        logManager.d("MainActivity", logMsg);
                        updateLogView(logMsg);
                        
                        File destFile = new File(frpDir, assetFile);
                        
                        // 复制文件
                        InputStream in = this.getAssets().open(assetFile);
                        OutputStream out = new FileOutputStream(destFile);
                        byte[] buffer = new byte[1024];
                        int read;
                        int totalRead = 0;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            totalRead += read;
                        }
                        in.close();
                        out.close();
                        
                        logMsg = "✓ 复制成功: " + assetFile + " (" + totalRead + " bytes)";
                        logManager.d("MainActivity", logMsg);
                        updateLogView(logMsg);
                    }
                } catch (Exception e) {
                    logMsg = "复制失败: " + assetFile + ", 错误: " + e.getMessage();
                    logManager.e("MainActivity", logMsg, e);
                    updateLogView("[错误] " + logMsg);
                    // 证书文件不是必须的，复制失败不影响整体结果
                }
                    

            }

            // 列出FRP目录中的所有文件
            logMsg = "\n复制完成后FRP目录文件列表:";
            Log.d("MainActivity", logMsg);
            updateLogView(logMsg);
            
            File[] files = frpDir.listFiles();
            if (files != null && files.length > 0) {
                for (File file : files) {
                    logMsg = "  " + file.getName() +
                            " (" + file.length() + " bytes)" +
                            " [R:" + file.canRead() +
                            " W:" + file.canWrite() +
                            " X:" + file.canExecute() + "]";
                    logManager.d("MainActivity", logMsg);
                    updateLogView(logMsg);
                }
            } else {
                logMsg = "  目录为空或无法读取";
                Log.d("MainActivity", logMsg);
                updateLogView("[警告] " + logMsg);
                allCopied = false;
            }

            return allCopied;

        } catch (Exception e) {
            logMsg = "复制文件到应用数据目录失败: " + e.getMessage();
            Log.e("MainActivity", logMsg);
            updateLogView("[错误] " + logMsg);
            e.printStackTrace();
            return false;
        }
    }


}
package pub.log.startfrp;

import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 关于页面活动
 * 显示应用版本信息、项目地址，提供复制和清空调试日志的功能
 * @author BY YYX
 */
public class AboutActivity extends AppCompatActivity {

    private static final String TAG = "AboutActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 100;
    private TextView tvAppVersion;
    private TextView tvProjectUrl;
    private Button btnBack;
    private Button btnCopyDebugLog;
    private Button btnClearDebugLog;
    private Button btnBydStartupManager;
    private Button btnCustomCommand;
    private LogManager logManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);


        // 初始化日志管理器
        logManager = LogManager.getInstance(this);
        
        // 初始化视图
        initViews();
        // 设置按钮点击事件
        setupButtonListeners();
        // 更新应用版本号
        updateAppVersion();
    }

    /**
     * 初始化视图
     */
    private void initViews() {
        tvAppVersion = findViewById(R.id.tvAppVersion);
        tvProjectUrl = findViewById(R.id.tvProjectUrl);
        btnBack = findViewById(R.id.btnBack);
        btnCopyDebugLog = findViewById(R.id.btnCopyDebugLog);
        btnClearDebugLog = findViewById(R.id.btnClearDebugLog);
        btnBydStartupManager = findViewById(R.id.btnBydStartupManager);
        btnCustomCommand = findViewById(R.id.btnCustomCommand);
    }

    /**
     * 设置按钮点击事件
     */
    private void setupButtonListeners() {
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 关闭当前Activity，返回上一级
                finish();
            }
        });
        
        // 设置项目地址点击事件
        tvProjectUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logManager.d(TAG, "项目地址被点击");
                openProjectUrl();
            }
        });
        
        // 设置复制调试日志按钮点击事件
        btnCopyDebugLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logManager.d(TAG, "复制调试日志按钮被点击");
                copyDebugLog();
            }
        });
        
        // 设置清空调试日志按钮点击事件
        btnClearDebugLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logManager.d(TAG, "清空调试日志按钮被点击");
                clearDebugLog();
            }
        });
        
        // 设置BYD后台启动项管理按钮点击事件
        btnBydStartupManager.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logManager.d(TAG, "BYD后台启动项管理按钮被点击");
                showBydStartupManager();
            }
        });
        
        // 设置自定义命令按钮点击事件
        btnCustomCommand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logManager.d(TAG, "自定义命令按钮被点击");
                // 跳转到自定义设置页
                Intent intent = new Intent(AboutActivity.this, CustomCommandActivity.class);
                startActivity(intent);
            }
        });
    }
    
    /**
     * 打开项目地址
     */
    private void openProjectUrl() {
        try {
            String url = "https://github.com/usertest001/startfrp";
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(url));
            startActivity(intent);
            logManager.d(TAG, "已打开项目地址: " + url);
        } catch (Exception e) {
            logManager.e(TAG, "打开项目地址时出错: " + e.getMessage(), e);
            showToast("打开项目地址失败: " + e.getMessage());
        }
    }
    
    /**
     * 复制调试日志到下载目录
     */
    private void copyDebugLog() {
        new Thread(() -> {
            try {
                // 获取日志文件路径
                String logFilePath = logManager.getLogFilePath();
                File logFile = new File(logFilePath);
                
                // 检查日志文件是否存在
                if (!logFile.exists()) {
                    runOnUiThread(() -> {
                        showToast("调试日志文件不存在");
                    });
                    return;
                }
                
                // 使用现代存储API保存到下载目录
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 使用 MediaStore
                    if (saveToDownloadsWithMediaStore(logFile)) {
                        runOnUiThread(() -> {
                            showToast("调试日志已复制到下载目录");
                            logManager.d(TAG, "调试日志已复制到下载目录");
                        });
                    } else {
                        runOnUiThread(() -> {
                            showToast("复制调试日志失败");
                        });
                    }
                } else {
                    // Android 9及以下使用旧方法
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            runOnUiThread(() -> {
                                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
                            });
                            return;
                        }
                    }
                    
                    // 获取下载目录路径
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File targetFile = new File(downloadsDir, "startfrp_debug_log.txt");
                    
                    // 复制文件
                    if (copyFile(logFile, targetFile)) {
                        runOnUiThread(() -> {
                            showToast("调试日志已复制到下载目录");
                            logManager.d(TAG, "调试日志已复制到: " + targetFile.getAbsolutePath());
                        });
                    } else {
                        runOnUiThread(() -> {
                            showToast("复制调试日志失败");
                        });
                    }
                }
            } catch (Exception e) {
                logManager.e(TAG, "复制调试日志时出错: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    showToast("复制调试日志时出错: " + e.getMessage());
                });
            }
        }).start();
    }
    
    /**
     * 使用 MediaStore 保存文件到下载目录（Android 10+）
     */
    private boolean saveToDownloadsWithMediaStore(File sourceFile) {
        try {
            // 创建 ContentValues
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "startfrp_debug_log.txt");
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
            
            // 插入到 MediaStore
            android.content.ContentResolver resolver = getContentResolver();
            android.net.Uri uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            
            if (uri != null) {
                // 打开输出流并复制文件
                java.io.OutputStream outputStream = resolver.openOutputStream(uri);
                if (outputStream != null) {
                    java.io.FileInputStream inputStream = new java.io.FileInputStream(sourceFile);
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                    inputStream.close();
                    outputStream.close();
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            logManager.e(TAG, "使用MediaStore保存文件时出错: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 复制文件
     */
    private boolean copyFile(File source, File destination) {
        try {
            // 创建目标文件
            if (destination.exists()) {
                destination.delete();
            }
            destination.createNewFile();
            
            // 复制文件内容
            java.io.FileInputStream fis = new java.io.FileInputStream(source);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(destination);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            
            // 关闭流
            fis.close();
            fos.close();
            
            return true;
        } catch (Exception e) {
            logManager.e(TAG, "复制文件时出错: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 清空调试日志
     */
    private void clearDebugLog() {
        try {
            // 调用LogManager的clearLogs方法清空调试日志
            logManager.clearLogs();
            logManager.d(TAG, "调试日志已清空");
            showToast("调试日志已清空");
        } catch (Exception e) {
            logManager.e(TAG, "清空调试日志时出错: " + e.getMessage(), e);
            showToast("清空调试日志失败: " + e.getMessage());
        }
    }
    
    /**
     * 显示Toast消息
     */
    private void showToast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 处理权限请求结果
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予，继续复制日志
                copyDebugLog();
            } else {
                // 权限被拒绝
                logManager.e(TAG, "存储权限被拒绝");
                showToast("需要存储权限才能复制日志文件");
            }
        }
    }

    /**
     * 更新应用版本号
     */
    private void updateAppVersion() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String versionName = packageInfo.versionName;
            int versionCode = packageInfo.versionCode;
            String versionText = "版本: " + versionName + " (" + versionCode + ")";
            tvAppVersion.setText(versionText);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            tvAppVersion.setText("版本: 未知");
        }
    }
    
    /**
     * 显示BYD后台启动项管理
     */
    private void showBydStartupManager() {
        try {
            logManager.d(TAG, "开始启动BYD后台启动项管理");
            
            // 使用Java Intent启动BYD应用启动管理
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.byd.appstartmanagement", 
                "com.byd.appstartmanagement.frame.AppStartManagement"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            
            logManager.d(TAG, "已启动BYD后台启动项管理");
            showToast("已启动BYD后台启动项管理");
        } catch (Exception e) {
            logManager.e(TAG, "BYD启动异常", e);
            showToast("启动失败: " + e.getMessage());
        }
    }
}

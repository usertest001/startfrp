package pub.log.startfrp;

import android.content.Context;
import android.os.Environment;
import android.os.PowerManager;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志管理工具类
 * 用于将日志保存到/data/local/tmp/startfrp/目录下的txt文件中
 */
public class LogManager {

    private static final String TAG = "LogManager";
    private static final String LOG_FILE = "startfrp_log.txt";
    private static final int MAX_LOG_SIZE = 1024 * 1024; // 1MB
    private static final int MAX_BACKUP_FILES = 3;
    
    private static LogManager instance;
    private Context context;
    private SimpleDateFormat dateFormat;
    private PowerManager.WakeLock wakeLock;
    private String logDir;
    
    private LogManager(Context context) {
        this.context = context.getApplicationContext();
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        
        // 使用应用的内部存储目录，确保有写入权限
        this.logDir = context.getFilesDir().getAbsolutePath() + File.separator + "logs";
        
        ensureLogDirectoryExists();
        initializeWakeLock();
    }
    
    /**
     * 初始化WakeLock，用于防止设备进入深度休眠
     */
    private void initializeWakeLock() {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "startfrp:LogManagerWakeLock");
            wakeLock.setReferenceCounted(true);
            // 直接使用系统日志，避免递归调用
            Log.i(TAG, "WakeLock已初始化");
        }
    }
    
    public static synchronized LogManager getInstance(Context context) {
        if (instance == null) {
            instance = new LogManager(context);
        }
        return instance;
    }
    
    /**
     * 确保日志目录存在
     */
    private void ensureLogDirectoryExists() {
        File dir = new File(logDir);
        Log.d(TAG, "检查日志目录是否存在: " + dir.getAbsolutePath());
        Log.d(TAG, "目录是否存在: " + dir.exists());
        Log.d(TAG, "目录是否可写: " + dir.canWrite());
        
        if (!dir.exists()) {
            Log.d(TAG, "尝试创建日志目录: " + dir.getAbsolutePath());
            boolean created = dir.mkdirs();
            Log.d(TAG, "目录创建结果: " + created);
            if (created) {
                Log.i(TAG, "日志目录创建成功: " + logDir);
                // 避免递归调用
                try {
                    // 直接写入日志文件，不使用writeLog方法避免递归
                    String timestamp = dateFormat.format(new Date());
                    String logEntry = String.format("%s INFO %s: 日志目录创建成功: %s\n", timestamp, TAG, logDir);
                    File logFile = new File(logDir, LOG_FILE);
                    FileWriter fileWriter = new FileWriter(logFile, true);
                    fileWriter.write(logEntry);
                    fileWriter.close();
                } catch (IOException e) {
                    Log.e(TAG, "写入日志文件失败: " + e.getMessage(), e);
                }
            } else {
                Log.e(TAG, "日志目录创建失败: " + logDir);
                // 检查父目录权限
                File parentDir = dir.getParentFile();
                if (parentDir != null) {
                    Log.d(TAG, "父目录是否存在: " + parentDir.exists());
                    Log.d(TAG, "父目录是否可写: " + parentDir.canWrite());
                }
            }
        } else {
            Log.i(TAG, "日志目录已存在: " + logDir);
        }
    }
    
    /**
     * 获取WakeLock，防止设备进入深度休眠
     */
    public void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(60 * 1000); // 保持1分钟唤醒
            Log.i(TAG, "WakeLock已获取");
        }
    }
    
    /**
     * 释放WakeLock
     */
    public void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock已释放");
        }
    }
    
    /**
     * 写入日志到文件
     * @param level 日志级别
     * @param tag 日志标签
     * @param message 日志消息
     */
    public void writeLog(String level, String tag, String message) {
        try {
            // 获取当前时间戳
            String timestamp = dateFormat.format(new Date());
            
            // 检查并轮换日志文件
            checkLogRotation();
            
            // 格式化日志内容
            String logEntry = String.format("%s %s %s: %s\n", timestamp, level, tag, message);
            
            // 写入日志文件
            File logFile = new File(logDir, LOG_FILE);
            FileWriter fileWriter = new FileWriter(logFile, true);
            fileWriter.write(logEntry);
            fileWriter.close();
            
            // 同时输出到Android系统日志
            switch (level) {
                case "DEBUG":
                    Log.d(tag, message);
                    break;
                case "INFO":
                    Log.i(tag, message);
                    break;
                case "WARN":
                    Log.w(tag, message);
                    break;
                case "ERROR":
                    Log.e(tag, message);
                    break;
                case "VERBOSE":
                    Log.v(tag, message);
                    break;
            }
            
        } catch (IOException e) {
            Log.e(TAG, "写入日志文件失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 写入DEBUG级别的日志
     */
    public void d(String tag, String message) {
        writeLog("DEBUG", tag, message);
    }
    
    /**
     * 写入INFO级别的日志
     */
    public void i(String tag, String message) {
        writeLog("INFO", tag, message);
    }
    
    /**
     * 写入WARN级别的日志
     */
    public void w(String tag, String message) {
        writeLog("WARN", tag, message);
    }
    
    /**
     * 写入ERROR级别的日志
     */
    public void e(String tag, String message, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();
        writeLog("ERROR", tag, message + "\n" + stackTrace);
    }
    
    /**
     * 写入ERROR级别的日志
     */
    public void e(String tag, String message) {
        writeLog("ERROR", tag, message);
    }
    
    /**
     * 检查并轮换日志文件
     */
    private void checkLogRotation() {
        try {
            File logFile = new File(logDir, LOG_FILE);
            if (logFile.exists()) {
                long fileSize = logFile.length();
                Log.d(TAG, "当前日志文件大小: " + fileSize + " 字节，最大允许大小: " + MAX_LOG_SIZE + " 字节");
                
                if (fileSize > MAX_LOG_SIZE) {
                    Log.i(TAG, "日志文件大小超过限制，开始轮换日志文件");
                    
                    // 轮换日志文件
                    for (int i = MAX_BACKUP_FILES - 1; i > 0; i--) {
                        File oldFile = new File(logDir, LOG_FILE + "." + i);
                        File newFile = new File(logDir, LOG_FILE + "." + (i + 1));
                        
                        if (oldFile.exists()) {
                            Log.d(TAG, "将日志文件 " + oldFile.getName() + " 重命名为 " + newFile.getName());
                            if (newFile.exists()) {
                                Log.d(TAG, "删除已存在的日志文件 " + newFile.getName());
                                newFile.delete();
                            }
                            oldFile.renameTo(newFile);
                        }
                    }
                    
                    // 重命名当前日志文件为备份文件
                    File backupFile = new File(logDir, LOG_FILE + ".1");
                    if (backupFile.exists()) {
                        Log.d(TAG, "删除已存在的备份日志文件 " + backupFile.getName());
                        backupFile.delete();
                    }
                    
                    Log.d(TAG, "将当前日志文件重命名为 " + backupFile.getName());
                    logFile.renameTo(backupFile);
                    
                    Log.i(TAG, "日志文件轮换完成");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "日志文件轮换失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取日志文件路径
     */
    public String getLogFilePath() {
        return new File(logDir, LOG_FILE).getAbsolutePath();
    }
    
    /**
     * 获取日志目录路径
     */
    public String getLogDirectory() {
        return logDir;
    }
    
    /**
     * 清除所有日志文件
     */
    public void clearLogs() {
        try {
            // 删除主日志文件
            File logFile = new File(logDir, LOG_FILE);
            if (logFile.exists()) {
                logFile.delete();
            }
            
            // 删除备份日志文件
            for (int i = 1; i <= MAX_BACKUP_FILES; i++) {
                File backupFile = new File(logDir, LOG_FILE + "." + i);
                if (backupFile.exists()) {
                    backupFile.delete();
                }
            }
            
            Log.d(TAG, "所有日志文件已清除");
        } catch (Exception e) {
            Log.e(TAG, "清除日志文件失败: " + e.getMessage(), e);
        }
    }
}
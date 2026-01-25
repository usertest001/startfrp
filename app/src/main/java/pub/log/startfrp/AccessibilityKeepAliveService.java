package pub.log.startfrp;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍保活服务
 * 用于增强应用在后台的存活能力
 */
public class AccessibilityKeepAliveService extends AccessibilityService {
    private static final String TAG = "AccessibilityKeepAliveService";
    
    // 无障碍服务运行状态标记
    public static boolean isRunning = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        Log.d(TAG, "无障碍保活服务已创建");
        
        // 启动前台保活服务
        startForegroundService();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "无障碍保活服务已启动");
        
        // 确保前台服务在运行
        if (!StatusService.isRunning) {
            startForegroundService();
        }
        
        return START_STICKY;
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要处理复杂的无障碍事件，仅用于保活
        // 可以添加简单的状态检查，确保前台服务在运行
        if (!StatusService.isRunning) {
            startForegroundService();
        }
    }
    
    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍保活服务被中断");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        Log.d(TAG, "无障碍保活服务已销毁");
        
        // 停止前台保活服务
        stopForegroundService();
    }
    
    /**
     * 启动前台保活服务
     */
    private void startForegroundService() {
        Log.d(TAG, "尝试启动前台保活服务");
        try {
            Intent intent = new Intent(this, StatusService.class);
            // 根据Android版本选择启动方式
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 停止前台保活服务
     */
    private void stopForegroundService() {
        Log.d(TAG, "尝试停止前台保活服务");
        try {
            Intent intent = new Intent(this, StatusService.class);
            stopService(intent);
        } catch (Exception e) {
            Log.e(TAG, "停止前台服务失败: " + e.getMessage());
        }
    }
}
package pub.log.startfrp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class StatusService extends Service {
    private static final String TAG = "StatusService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "frp_client_channel";

    public static boolean isRunning = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        Log.d(TAG, "StatusService created");
        // 先创建通知渠道
        createNotificationChannel();
        // 创建并显示前台服务通知
        startForeground(NOTIFICATION_ID, createNotification());
    }
    
    /**
     * 创建通知渠道（Android 8.0+ 必需）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String name = "FRP Status Channel";
            String description = "FRP Client运行状态通知";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.setShowBadge(false);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "StatusService started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        Log.d(TAG, "StatusService destroyed");
        // 尝试重启服务
        restartService();
    }

    /**
     * 创建前台服务通知
     */
    private Notification createNotification() {
        // 创建点击通知时的跳转意图
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        // 构建通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)  // 应用图标
                .setContentTitle(getString(R.string.app_name))  // 应用名称
                .setContentText("FRP Client正在运行")  // 通知内容
                .setContentIntent(pendingIntent)  // 点击跳转
                .setOngoing(true)  // 设置为不可移除的通知
                .setPriority(NotificationCompat.PRIORITY_HIGH);  // 高优先级

        return builder.build();
    }

    /**
     * 重启服务
     */
    private void restartService() {
        // 如果无障碍服务仍在运行，则尝试重启前台服务和FRP服务
        if (AccessibilityKeepAliveService.isRunning) {
            Log.d(TAG, "尝试重启StatusService和FrpcService");
            
            // 重启StatusService
            Intent intent = new Intent(this, StatusService.class);
            startService(intent);
            
            // 检查并启动FrpcService
            Intent frpcIntent = new Intent(this, FrpcService.class);
            frpcIntent.setAction(FrpcService.ACTION_START);
            
            // 从SharedPreferences读取Shizuku配置
            SharedPreferences prefs = getSharedPreferences("frp_config", MODE_PRIVATE);
            boolean useShizuku = prefs.getBoolean("use_shizuku", false);
            frpcIntent.putExtra("use_shizuku", useShizuku);
            
            startService(frpcIntent);
        }
    }

    /**
     * 启动前台服务的静态方法
     */
    public static void startService(MainActivity activity) {
        if (!isRunning) {
            Intent intent = new Intent(activity, StatusService.class);
            activity.startForegroundService(intent);
        }
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
}

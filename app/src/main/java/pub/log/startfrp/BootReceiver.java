package pub.log.startfrp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                Intent.ACTION_REBOOT.equals(action)) {

            SharedPreferences prefs = context.getSharedPreferences("frp_config", Context.MODE_PRIVATE);
            boolean autoStart = prefs.getBoolean("auto_start", true);
            boolean useShizuku = prefs.getBoolean("use_shizuku", false);

            if (autoStart) {
                // 启动FRP服务
                Intent serviceIntent = new Intent(context, FrpcService.class);
                serviceIntent.setAction(FrpcService.ACTION_START);
                serviceIntent.putExtra("use_shizuku", useShizuku);

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                
                // 同时启动前台保活服务，确保显示常驻通知
                Intent statusIntent = new Intent(context, StatusService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(statusIntent);
                } else {
                    context.startService(statusIntent);
                }
            }
        }
    }
}

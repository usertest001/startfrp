package pub.log.startfrp;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public class MyApplication extends Application {
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        Log.d("StartFRP", "MyApplication onCreate");
        
        // 启用Shizuku多进程支持
        ShizukuProvider.enableMultiProcessSupport(true);
        
        // 检查Shizuku是否可用
        if (Shizuku.pingBinder()) {
            Log.d("StartFRP", "Shizuku在Application初始化时已可用");
        } else {
            Log.d("StartFRP", "Shizuku在Application初始化时不可用");
        }
    }
    
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        
        Log.d("StartFRP", "MyApplication attachBaseContext");
    }
}
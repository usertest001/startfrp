package pub.log.startfrp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;
import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.File;

// 比亚迪车机专用API，使用反射调用
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/**
 * 车辆熄火检测广播接收器
 * 用于检测车辆熄火事件，并执行相应的Shizuku命令
 */
public class EngineOffReceiver extends BroadcastReceiver {

    private static final String TAG = "EngineOffReceiver";
    private LogManager logManager;
    private Object engineListener; // 用于保存发动机监听器实例
    
    // 比亚迪等车机可能的自定义广播事件
    private static final String BYD_ACTION_ENGINE_OFF = "com.byd.action.ENGINE_OFF";
    private static final String BYD_ACTION_POWER_MODE_CHANGE = "com.byd.action.POWER_MODE_CHANGE";
    private static final String POWER_MODE_OFF = "OFF";
    
    // 其他可能的比亚迪车机自定义广播
    private static final String BYD_ACTION_VEHICLE_STATUS_CHANGE = "com.byd.action.VEHICLE_STATUS_CHANGE";
    private static final String BYD_ACTION_IGNITION_OFF = "com.byd.action.IGNITION_OFF";
    
    // 比亚迪其他可能的自定义广播（不同命名空间）
    private static final String BYD_CAR_ACTION_ENGINE_OFF = "com.byd.car.action.ENGINE_OFF";
    private static final String BYD_CAR_ACTION_POWER_OFF = "com.byd.car.action.POWER_OFF";
    private static final String BYD_CAR_ACTION_IGNITION_OFF = "com.byd.car.action.IGNITION_OFF";
    private static final String BYD_ADAS_ACTION_ENGINE_OFF = "com.byd.adas.action.ENGINE_OFF";
    
    // 其他可能的车机自定义广播
    private static final String CUSTOM_ACTION_ENGINE_OFF = "com.custom.action.ENGINE_OFF";
    private static final String CUSTOM_ACTION_POWER_OFF = "com.custom.action.POWER_OFF";
    private static final String CUSTOM_ACTION_IGNITION_OFF = "com.custom.action.IGNITION_OFF";
    private static final String CUSTOM_ACTION_KEY_OFF = "com.custom.action.KEY_OFF";
    
    // 通用车辆熄火广播
    private static final String ANDROID_CAR_ACTION_ENGINE_OFF = "com.android.car.action.ENGINE_OFF";
    private static final String ANDROID_CAR_ACTION_POWER_OFF = "com.android.car.action.POWER_OFF";
    
    // 比亚迪待机模式相关广播
    private static final String BYD_ACTION_STANDBY_MODE = "com.byd.action.STANDBY_MODE";
    private static final String BYD_ACTION_SLEEP_MODE = "com.byd.action.SLEEP_MODE";
    private static final String BYD_ACTION_LOW_POWER_MODE = "com.byd.action.LOW_POWER_MODE";
    private static final String BYD_CAR_ACTION_STANDBY = "com.byd.car.action.STANDBY";
    private static final String BYD_CAR_ACTION_SLEEP = "com.byd.car.action.SLEEP";
    private static final String BYD_CAR_ACTION_LOW_POWER = "com.byd.car.action.LOW_POWER";
    
    // 其他车机待机模式相关广播
    private static final String CUSTOM_ACTION_STANDBY = "com.custom.action.STANDBY";
    private static final String CUSTOM_ACTION_SLEEP = "com.custom.action.SLEEP";
    private static final String CUSTOM_ACTION_LOW_POWER = "com.custom.action.LOW_POWER";
    
    // Android Car待机模式广播
    private static final String ANDROID_CAR_ACTION_STANDBY = "com.android.car.action.STANDBY";
    private static final String ANDROID_CAR_ACTION_SLEEP = "com.android.car.action.SLEEP";
    
    // 系统待机相关广播
    private static final String SYSTEM_ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";
    private static final String SYSTEM_ACTION_DEVICE_IDLE_MODE_CHANGED = "android.intent.action.DEVICE_IDLE_MODE_CHANGED";
    private static final String SYSTEM_ACTION_LOW_MEMORY = "android.intent.action.LOW_MEMORY";
    private static final String SYSTEM_ACTION_DEVICE_STORAGE_LOW = "android.intent.action.DEVICE_STORAGE_LOW";
    
    // 比亚迪车机专用API相关常量
    private static final String BYD_POWER_DEVICE_CLASS = "android.hardware.bydauto.power.BYDAutoPowerDevice";
    private static final String BYD_POWER_DEVICE_METHOD_GET_INSTANCE = "getInstance";
    private static final String BYD_POWER_DEVICE_METHOD_GET_ENGINE_STATUS = "getEngineStatus";
    private static final String BYD_POWER_DEVICE_METHOD_WAKE_UP_MCU = "wakeUpMcu";
    private static final String BYD_POWER_DEVICE_METHOD_GET_POWER_MODE = "getPowerMode";
    private static final int ENGINE_STATUS_OFF = 0;
    private static final int ENGINE_STATUS_ON = 1;
    private static final int POWER_MODE_OFF_INT = 0;
    private static final int POWER_MODE_IGNITION_ON_INT = 1;
    private static final int POWER_MODE_ACCESSORY_INT = 2;
    private static final int POWER_MODE_STANDBY_INT = 3;
    private static final int POWER_MODE_SLEEP_INT = 4;
    private static final int POWER_MODE_LOW_POWER_INT = 5;
    
    // 比亚迪发动机设备类相关常量
    private static final String BYD_ENGINE_DEVICE_CLASS = "android.hardware.bydauto.engine.BYDAutoEngineDevice";
    private static final String BYD_ENGINE_DEVICE_METHOD_GET_INSTANCE = "getInstance";
    private static final String BYD_ENGINE_DEVICE_METHOD_GET_ENGINE_SPEED = "getEngineSpeed";
    private static final String BYD_ENGINE_DEVICE_METHOD_REGISTER_LISTENER = "registerListener";
    private static final String BYD_ENGINE_DEVICE_METHOD_UNREGISTER_LISTENER = "unregisterListener";
    private static final int ENGINE_SPEED_OFF = 0; // 发动机速度为0表示熄火
    private static final int ENGINE_SPEED_MIN = 0;
    private static final int ENGINE_SPEED_MAX = 8000;
    
    // 比亚迪发动机监听器类
    private static final String BYD_ENGINE_LISTENER_CLASS = "android.hardware.bydauto.engine.AbsBYDAutoEngineListener";

    // 比亚迪网络设备类
    private static final String BYD_NETWORK_DEVICE_CLASS = "android.hardware.bydauto.network.BYDNetworkDevice";
    private static final String BYD_NETWORK_DEVICE_METHOD_GET_INSTANCE = "getInstance";
    private static final String BYD_NETWORK_DEVICE_METHOD_SET_KEEP_ALIVE = "setKeepAlive";
    private static final String BYD_NETWORK_DEVICE_METHOD_ENABLE_DATA = "enableData";
    private static final String BYD_NETWORK_DEVICE_METHOD_ENABLE_WIFI = "enableWifi";
    private static final String BYD_NETWORK_DEVICE_METHOD_SET_AIRPLANE_MODE = "setAirplaneMode";

    // 比亚迪系统管理类
    private static final String BYD_SYSTEM_MANAGER_CLASS = "android.hardware.bydauto.system.BYDSystemManager";
    private static final String BYD_SYSTEM_MANAGER_METHOD_GET_INSTANCE = "getInstance";
    private static final String BYD_SYSTEM_MANAGER_METHOD_SET_BACKGROUND_RUN = "setBackgroundRun";
    private static final String BYD_SYSTEM_MANAGER_METHOD_DISABLE_POWER_OPTIMIZATION = "disablePowerOptimization";

    /**
     * 检测设备是否为比亚迪车机
     */
    private boolean isBydVehicle(Context context) {
        try {
            // 检查设备型号
            String deviceModel = android.os.Build.MODEL;
            String deviceManufacturer = android.os.Build.MANUFACTURER;
            String deviceBrand = android.os.Build.BRAND;
            
            logManager.d(TAG, "设备信息: 厂商=" + deviceManufacturer + ", 品牌=" + deviceBrand + ", 型号=" + deviceModel);
            
            // 检查是否包含比亚迪相关标识
            if (deviceModel.contains("BYD") || 
                deviceManufacturer.contains("BYD") || 
                deviceBrand.contains("BYD") ||
                deviceModel.contains("比亚迪") ||
                deviceBrand.contains("比亚迪")) {
                logManager.d(TAG, "检测到比亚迪设备");
                return true;
            }
            
            // 检查是否安装了比亚迪相关应用
            PackageManager pm = context.getPackageManager();
            String[] bydPackages = {
                "com.byd.adas",
                "com.byd.car",
                "com.byd.media",
                "com.byd.vehicle",
                "com.byd.telematics"
            };
            
            for (String pkg : bydPackages) {
                try {
                    pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                    logManager.d(TAG, "检测到比亚迪应用: " + pkg);
                    return true;
                } catch (PackageManager.NameNotFoundException e) {
                    // 应用不存在，继续检查
                }
            }
            
        } catch (Exception e) {
            logManager.e(TAG, "检测是否为比亚迪车机时出错: " + e.getMessage(), e);
        }
        
        logManager.d(TAG, "未检测到比亚迪设备标识");
        return false;
    }
    
    /**
     * 使用反射调用比亚迪车机专用API检测引擎状态
     * @param context 上下文
     * @return true表示引擎熄火，false表示引擎运行或无法检测
     */
    private boolean checkBydEngineStatus(Context context) {
        logManager.d(TAG, "开始检测比亚迪车机引擎状态");
        
        // 首先检查是否为比亚迪设备
        if (!isBydVehicle(context)) {
            logManager.d(TAG, "不是比亚迪设备，跳过比亚迪车机API检测");
            return false;
        }
        
        try {
            // 尝试通过反射调用比亚迪车机电源管理服务
            logManager.d(TAG, "尝试通过PowerManager检测比亚迪车机状态");
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            
            // 检查是否有比亚迪车机特定的电源模式
            if (pm != null) {
                try {
                    Method isVehicleModeOnMethod = PowerManager.class.getDeclaredMethod("isVehicleModeOn");
                    Boolean isVehicleModeOn = (Boolean) isVehicleModeOnMethod.invoke(pm);
                    logManager.d(TAG, "比亚迪车辆模式状态: " + isVehicleModeOn);
                    if (isVehicleModeOn != null && !isVehicleModeOn) {
                        return true;
                    }
                } catch (Exception e) {
                    logManager.w(TAG, "PowerManager.isVehicleModeOn方法不可用: " + e.getMessage());
                }
                
                try {
                    Method isIgnitionOnMethod = PowerManager.class.getDeclaredMethod("isIgnitionOn");
                    Boolean isIgnitionOn = (Boolean) isIgnitionOnMethod.invoke(pm);
                    logManager.d(TAG, "比亚迪点火状态: " + isIgnitionOn);
                    if (isIgnitionOn != null && !isIgnitionOn) {
                        return true;
                    }
                } catch (Exception e) {
                    logManager.w(TAG, "PowerManager.isIgnitionOn方法不可用: " + e.getMessage());
                }
            }
            
            // 加载比亚迪电源设备类
            logManager.d(TAG, "尝试加载比亚迪车机API类: " + BYD_POWER_DEVICE_CLASS);
            Class<?> bydPowerDeviceClass = Class.forName(BYD_POWER_DEVICE_CLASS);
            logManager.d(TAG, "成功加载比亚迪车机API类");
            
            // 获取单例实例
            logManager.d(TAG, "尝试获取比亚迪车机API实例");
            Method getInstanceMethod = bydPowerDeviceClass.getDeclaredMethod(BYD_POWER_DEVICE_METHOD_GET_INSTANCE, Context.class);
            Object bydPowerDevice = getInstanceMethod.invoke(null, context);
            logManager.d(TAG, "成功获取比亚迪车机API实例");
            
            // 唤醒MCU（可选，确保设备响应）
            try {
                Method wakeUpMcuMethod = bydPowerDeviceClass.getDeclaredMethod(BYD_POWER_DEVICE_METHOD_WAKE_UP_MCU);
                wakeUpMcuMethod.invoke(bydPowerDevice);
                logManager.d(TAG, "已唤醒比亚迪车机MCU");
            } catch (Exception e) {
                logManager.w(TAG, "调用wakeUpMcu方法失败: " + e.getMessage());
            }
            
            // 获取引擎状态
            logManager.d(TAG, "尝试获取比亚迪车机引擎状态");
            Method getEngineStatusMethod = bydPowerDeviceClass.getDeclaredMethod(BYD_POWER_DEVICE_METHOD_GET_ENGINE_STATUS);
            int engineStatus = (int) getEngineStatusMethod.invoke(bydPowerDevice);
            
            logManager.d(TAG, "比亚迪车机引擎状态: " + engineStatus + " (0=OFF, 1=ON)");
            
            // 如果引擎状态为OFF，返回true
            boolean isEngineOff = engineStatus == ENGINE_STATUS_OFF;
            logManager.d(TAG, "通过比亚迪车机API检测到引擎状态: " + (isEngineOff ? "OFF" : "ON"));
            
            // 如果引擎状态不是OFF，尝试检测其他可能的待机模式
            if (!isEngineOff) {
                logManager.d(TAG, "引擎状态为ON，尝试检测其他待机相关状态");
                
                // 尝试检测电源模式
                try {
                    Method getPowerModeMethod = bydPowerDeviceClass.getDeclaredMethod(BYD_POWER_DEVICE_METHOD_GET_POWER_MODE);
                    int powerMode = (int) getPowerModeMethod.invoke(bydPowerDevice);
                    logManager.d(TAG, "比亚迪车机电源模式: " + powerMode);
                    
                    // 待机、睡眠、低电量模式都视为熄火状态
                    if (powerMode == POWER_MODE_STANDBY_INT || 
                        powerMode == POWER_MODE_SLEEP_INT || 
                        powerMode == POWER_MODE_LOW_POWER_INT || 
                        powerMode == POWER_MODE_OFF_INT) {
                        isEngineOff = true;
                        logManager.d(TAG, "检测到比亚迪车机处于电源模式: " + powerMode + " (视为熄火/待机)");
                    }
                } catch (Exception e) {
                    logManager.w(TAG, "获取比亚迪车机电源模式失败: " + e.getMessage());
                }
                
                // 尝试检测待机模式状态
                try {
                    Method getStandbyStatusMethod = bydPowerDeviceClass.getDeclaredMethod("getStandbyStatus");
                    int standbyStatus = (int) getStandbyStatusMethod.invoke(bydPowerDevice);
                    logManager.d(TAG, "比亚迪车机待机状态: " + standbyStatus);
                    if (standbyStatus > 0) {
                        isEngineOff = true;
                        logManager.d(TAG, "检测到比亚迪车机处于待机状态");
                    }
                } catch (Exception e) {
                    logManager.w(TAG, "获取比亚迪车机待机状态失败: " + e.getMessage());
                }
                
                // 尝试检测睡眠模式状态
                if (!isEngineOff) {
                    try {
                        Method getSleepStatusMethod = bydPowerDeviceClass.getDeclaredMethod("getSleepStatus");
                        boolean sleepStatus = (boolean) getSleepStatusMethod.invoke(bydPowerDevice);
                        logManager.d(TAG, "比亚迪车机睡眠状态: " + sleepStatus);
                        if (sleepStatus) {
                            isEngineOff = true;
                            logManager.d(TAG, "检测到比亚迪车机处于睡眠状态");
                        }
                    } catch (Exception e) {
                        logManager.w(TAG, "获取比亚迪车机睡眠状态失败: " + e.getMessage());
                    }
                }
                
                // 尝试检测低电量模式状态
                if (!isEngineOff) {
                    try {
                        Method getLowPowerStatusMethod = bydPowerDeviceClass.getDeclaredMethod("getLowPowerStatus");
                        boolean lowPowerStatus = (boolean) getLowPowerStatusMethod.invoke(bydPowerDevice);
                        logManager.d(TAG, "比亚迪车机低电量状态: " + lowPowerStatus);
                        if (lowPowerStatus) {
                            isEngineOff = true;
                            logManager.d(TAG, "检测到比亚迪车机处于低电量状态");
                        }
                    } catch (Exception e) {
                        logManager.w(TAG, "获取比亚迪车机低电量状态失败: " + e.getMessage());
                    }
                }
                
                // 尝试检测车辆电源状态
                if (!isEngineOff) {
                    try {
                        Method getPowerStatusMethod = bydPowerDeviceClass.getDeclaredMethod("getPowerStatus");
                        String powerStatus = (String) getPowerStatusMethod.invoke(bydPowerDevice);
                        logManager.d(TAG, "比亚迪车机电源状态: " + powerStatus);
                        if ("STANDBY".equals(powerStatus) || "SLEEP".equals(powerStatus) || "LOW_POWER".equals(powerStatus)) {
                            isEngineOff = true;
                            logManager.d(TAG, "检测到比亚迪车机处于" + powerStatus + "状态");
                        }
                    } catch (Exception e) {
                        logManager.w(TAG, "获取比亚迪车机电源状态失败: " + e.getMessage());
                    }
                }
            }
            
            // 5. 使用比亚迪发动机设备类检测发动机速度
            if (!isEngineOff) {
                try {
                    logManager.d(TAG, "尝试加载比亚迪发动机设备类: " + BYD_ENGINE_DEVICE_CLASS);
                    Class<?> bydEngineDeviceClass = Class.forName(BYD_ENGINE_DEVICE_CLASS);
                    logManager.d(TAG, "成功加载比亚迪发动机设备类");
                    
                    // 获取发动机设备实例
                    Method getEngineInstanceMethod = bydEngineDeviceClass.getDeclaredMethod(BYD_ENGINE_DEVICE_METHOD_GET_INSTANCE, Context.class);
                    Object bydEngineDevice = getEngineInstanceMethod.invoke(null, context);
                    logManager.d(TAG, "成功获取比亚迪发动机设备实例");
                    
                    // 获取发动机速度
                    Method getEngineSpeedMethod = bydEngineDeviceClass.getDeclaredMethod(BYD_ENGINE_DEVICE_METHOD_GET_ENGINE_SPEED);
                    int engineSpeed = (int) getEngineSpeedMethod.invoke(bydEngineDevice);
                    logManager.d(TAG, "比亚迪发动机当前速度: " + engineSpeed + " r/min");
                    
                    // 如果发动机速度为0，视为熄火
                    if (engineSpeed == ENGINE_SPEED_OFF) {
                        isEngineOff = true;
                        logManager.d(TAG, "通过发动机速度检测到引擎熄火");
                    }
                    
                } catch (ClassNotFoundException e) {
                    logManager.w(TAG, "未找到比亚迪发动机设备类: " + BYD_ENGINE_DEVICE_CLASS);
                    logManager.w(TAG, "可能不是比亚迪车机或API版本不匹配");
                } catch (Exception e) {
                    logManager.e(TAG, "使用比亚迪发动机设备API时发生错误: " + e.getMessage(), e);
                }
            }
            
            logManager.d(TAG, "通过比亚迪车机API综合检测结果: " + (isEngineOff ? "熄火/待机" : "运行中"));
            return isEngineOff;
            
        } catch (ClassNotFoundException e) {
            logManager.w(TAG, "未找到比亚迪车机专用API类: " + BYD_POWER_DEVICE_CLASS);
            logManager.w(TAG, "可能不是比亚迪车机或API版本不匹配");
        } catch (NoSuchMethodException e) {
            logManager.w(TAG, "未找到比亚迪车机API方法: " + e.getMessage());
            logManager.w(TAG, "可能是API版本不匹配或方法签名已更改");
        } catch (InvocationTargetException e) {
            logManager.e(TAG, "调用比亚迪车机API方法失败: " + e.getMessage(), e);
            logManager.e(TAG, "异常原因: " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            logManager.e(TAG, "访问比亚迪车机API方法失败: " + e.getMessage(), e);
            logManager.e(TAG, "可能是API方法的访问权限问题");
        } catch (Exception e) {
            logManager.e(TAG, "检测比亚迪车机引擎状态时发生未知错误: " + e.getMessage(), e);
        }
        
        // 如果无法检测，返回false
        logManager.d(TAG, "无法通过比亚迪车机API检测引擎状态，将使用传统方式检测");
        return false;
    }
    
    /**
     * 使用比亚迪网络设备API保持网络连接
     */
    private void keepNetworkConnection(Context context) {
        logManager.d(TAG, "尝试使用比亚迪网络设备API保持网络连接");
        
        try {
            // 加载比亚迪网络设备类
            Class<?> bydNetworkDeviceClass = Class.forName(BYD_NETWORK_DEVICE_CLASS);
            logManager.d(TAG, "成功加载比亚迪网络设备类");
            
            // 获取单例实例
            Method getInstanceMethod = bydNetworkDeviceClass.getDeclaredMethod(BYD_NETWORK_DEVICE_METHOD_GET_INSTANCE, Context.class);
            Object bydNetworkDevice = getInstanceMethod.invoke(null, context);
            logManager.d(TAG, "成功获取比亚迪网络设备实例");
            
            // 启用网络保持功能
            try {
                Method setKeepAliveMethod = bydNetworkDeviceClass.getDeclaredMethod(BYD_NETWORK_DEVICE_METHOD_SET_KEEP_ALIVE, boolean.class);
                setKeepAliveMethod.invoke(bydNetworkDevice, true);
                logManager.d(TAG, "成功启用比亚迪网络保持功能");
            } catch (Exception e) {
                logManager.w(TAG, "调用setKeepAlive方法失败: " + e.getMessage());
            }
            
            // 确保数据连接已启用
            try {
                Method enableDataMethod = bydNetworkDeviceClass.getDeclaredMethod(BYD_NETWORK_DEVICE_METHOD_ENABLE_DATA, boolean.class);
                enableDataMethod.invoke(bydNetworkDevice, true);
                logManager.d(TAG, "成功启用比亚迪数据连接");
            } catch (Exception e) {
                logManager.w(TAG, "调用enableData方法失败: " + e.getMessage());
            }
            
            // 确保WiFi已启用（如果可用）
            try {
                Method enableWifiMethod = bydNetworkDeviceClass.getDeclaredMethod(BYD_NETWORK_DEVICE_METHOD_ENABLE_WIFI, boolean.class);
                enableWifiMethod.invoke(bydNetworkDevice, true);
                logManager.d(TAG, "成功启用比亚迪WiFi连接");
            } catch (Exception e) {
                logManager.w(TAG, "调用enableWifi方法失败: " + e.getMessage());
            }
            
            // 确保飞行模式已关闭
            try {
                Method setAirplaneModeMethod = bydNetworkDeviceClass.getDeclaredMethod(BYD_NETWORK_DEVICE_METHOD_SET_AIRPLANE_MODE, boolean.class);
                setAirplaneModeMethod.invoke(bydNetworkDevice, false);
                logManager.d(TAG, "成功关闭比亚迪飞行模式");
            } catch (Exception e) {
                logManager.w(TAG, "调用setAirplaneMode方法失败: " + e.getMessage());
            }
            
        } catch (ClassNotFoundException e) {
            logManager.w(TAG, "未找到比亚迪网络设备类: " + BYD_NETWORK_DEVICE_CLASS);
            logManager.w(TAG, "可能不是比亚迪车机或API版本不匹配");
        } catch (Exception e) {
            logManager.e(TAG, "使用比亚迪网络设备API时发生错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 注册比亚迪发动机监听器
     */
    private void registerEngineListener(Context context) {
        logManager.d(TAG, "尝试注册比亚迪发动机监听器");
        
        try {
            // 检查是否是比亚迪车辆
            if (!isBydVehicle(context)) {
                logManager.d(TAG, "不是比亚迪车辆，跳过注册发动机监听器");
                return;
            }
            
            // 加载比亚迪发动机设备类
            Class<?> bydEngineDeviceClass = Class.forName(BYD_ENGINE_DEVICE_CLASS);
            Class<?> bydEngineListenerClass = Class.forName(BYD_ENGINE_LISTENER_CLASS);
            
            // 获取发动机设备实例
            Method getInstanceMethod = bydEngineDeviceClass.getDeclaredMethod(BYD_ENGINE_DEVICE_METHOD_GET_INSTANCE, Context.class);
            Object bydEngineDevice = getInstanceMethod.invoke(null, context);
            
            // 使用动态代理创建监听器实例
            engineListener = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{bydEngineListenerClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getName().equals("onEngineSpeedChanged") && args != null && args.length > 0) {
                            int engineSpeed = (int) args[0];
                            logManager.d(TAG, "收到发动机速度变化通知: " + engineSpeed + " r/min");
                            
                            // 如果发动机速度变为0，表示熄火
                            if (engineSpeed == ENGINE_SPEED_OFF) {
                                logManager.d(TAG, "通过发动机监听器检测到引擎熄火");
                                // 执行Shizuku命令
                                executeShizukuCommands(context);
                            }
                        } else if (method.getName().equals("onEngineCoolantLevelChanged")) {
                            // 冷却液位变化，暂时不处理
                            logManager.d(TAG, "收到发动机冷却液位变化通知");
                        } else if (method.getName().equals("onOilLevelChanged")) {
                            // 机油液位变化，暂时不处理
                            logManager.d(TAG, "收到发动机机油液位变化通知");
                        }
                        return null;
                    }
                }
            );
            
            // 注册监听器
            Method registerListenerMethod = bydEngineDeviceClass.getDeclaredMethod(BYD_ENGINE_DEVICE_METHOD_REGISTER_LISTENER, bydEngineListenerClass);
            registerListenerMethod.invoke(bydEngineDevice, engineListener);
            
            logManager.d(TAG, "成功注册比亚迪发动机监听器");
            
        } catch (ClassNotFoundException e) {
            logManager.w(TAG, "未找到比亚迪发动机相关类: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            logManager.w(TAG, "未找到比亚迪发动机API方法: " + e.getMessage());
        } catch (InvocationTargetException e) {
            logManager.e(TAG, "调用比亚迪发动机API失败: " + e.getMessage(), e);
        } catch (IllegalAccessException e) {
            logManager.e(TAG, "访问比亚迪发动机API失败: " + e.getMessage(), e);
        } catch (Exception e) {
            logManager.e(TAG, "注册比亚迪发动机监听器失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 使用比亚迪系统管理API禁用电源优化
     */
    private void disablePowerOptimization(Context context) {
        logManager.d(TAG, "尝试使用比亚迪系统管理API禁用电源优化");
        
        try {
            // 加载比亚迪系统管理类
            Class<?> bydSystemManagerClass = Class.forName(BYD_SYSTEM_MANAGER_CLASS);
            logManager.d(TAG, "成功加载比亚迪系统管理类");
            
            // 获取单例实例
            Method getInstanceMethod = bydSystemManagerClass.getDeclaredMethod(BYD_SYSTEM_MANAGER_METHOD_GET_INSTANCE, Context.class);
            Object bydSystemManager = getInstanceMethod.invoke(null, context);
            logManager.d(TAG, "成功获取比亚迪系统管理实例");
            
            // 启用后台运行
            try {
                Method setBackgroundRunMethod = bydSystemManagerClass.getDeclaredMethod(BYD_SYSTEM_MANAGER_METHOD_SET_BACKGROUND_RUN, boolean.class);
                setBackgroundRunMethod.invoke(bydSystemManager, true);
                logManager.d(TAG, "成功启用比亚迪后台运行模式");
            } catch (Exception e) {
                logManager.w(TAG, "调用setBackgroundRun方法失败: " + e.getMessage());
            }
            
            // 禁用电源优化
            try {
                Method disablePowerOptimizationMethod = bydSystemManagerClass.getDeclaredMethod(BYD_SYSTEM_MANAGER_METHOD_DISABLE_POWER_OPTIMIZATION, String.class);
                disablePowerOptimizationMethod.invoke(bydSystemManager, context.getPackageName());
                logManager.d(TAG, "成功禁用比亚迪电源优化");
            } catch (Exception e) {
                logManager.w(TAG, "调用disablePowerOptimization方法失败: " + e.getMessage());
            }
            
        } catch (ClassNotFoundException e) {
            logManager.w(TAG, "未找到比亚迪系统管理类: " + BYD_SYSTEM_MANAGER_CLASS);
            logManager.w(TAG, "可能不是比亚迪车机或API版本不匹配");
        } catch (Exception e) {
            logManager.e(TAG, "使用比亚迪系统管理API时发生错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 通过检查系统属性检测车辆状态
     */
    private boolean checkVehicleStatusBySystemProperty() {
        try {
            // 检查系统属性 - 扩展了更多比亚迪相关属性
            String[] propertiesToCheck = {
                // 基本车辆信息
                "ro.product.vehicle.manufacturer",
                "ro.product.vehicle.model",
                "ro.product.model",
                "ro.product.brand",
                "ro.product.device",
                "ro.build.product",
                
                // 通用车辆状态属性
                "sys.vehicle.engine.status",
                "sys.vehicle.ignition.status",
                "sys.vehicle.power.mode",
                "sys.vehicle.power.status",
                "sys.vehicle.standby.status",
                
                // 比亚迪专用车辆状态属性
                "sys.byd.engine.status",
                "sys.byd.ignition.status",
                "sys.byd.power.mode",
                "sys.byd.power.status",
                "sys.byd.vehicle.status",
                "sys.byd.vehicle.power_status",
                
                // 其他可能的比亚迪属性
                "sys.byd.autoengine.status",
                "sys.byd.car.engine",
                "sys.byd.car.ignition",
                "sys.byd.car.power",
                "sys.byd.car.state",
                "sys.byd.car.status",
                
                // 通用系统电源属性
                "sys.powerctl",
                "sys.shutdown.requested",
                "sys.power.state",
                "sys.power.mode",
                "sys.power.standby",
                "sys.power.status",
                "sys.standby.requested",
                "sys.standby.status",
                "sys.systemui.standby",
                "sys.display.standby",
                "sys.suspend.requested",
                "sys.suspend",
                "sys.suspend.status",
                "sys.disp.power",
                "sys.disp.standby",
                
                // 比亚迪待机模式相关属性
                "sys.byd.standby",
                "sys.byd.power.standby",
                "sys.byd.vehicle.standby",
                "sys.byd.car.standby",
                "sys.byd.sleep.mode",
                "sys.byd.sleep.status",
                "sys.byd.low.power",
                "sys.byd.power.save",
                "sys.byd.powermode",
                "sys.byd.powermode.state",
                "sys.byd.dormant",
                "sys.byd.dormant.status"
            };
            
            boolean isBydVehicle = false;
            boolean isEngineOff = false;
            
            for (String propName : propertiesToCheck) {
                String propValue = getSystemProperty(propName);
                if (propValue != null) {
                    logManager.d(TAG, "系统属性: " + propName + " = " + propValue);
                    
                    // 检测是否是比亚迪车辆
                    if ((propName.equals("ro.product.vehicle.manufacturer") || 
                         propName.equals("ro.product.brand")) && 
                         propValue.toLowerCase().contains("byd")) {
                        isBydVehicle = true;
                    }
                    
                    // 检测引擎或点火状态是否为熄火
                    if (propName.contains("engine") || propName.contains("ignition") || 
                        propName.contains("power") || propName.contains("status") ||
                        propName.contains("standby") || propName.contains("sleep") ||
                        propName.contains("suspend") || propName.contains("low") ||
                        propName.contains("dormant") || propName.contains("state") ||
                        propName.equals("sys.powerctl") || propName.equals("sys.shutdown.requested")) {
                        
                        // 转换为大写以进行不区分大小写的比较
                        String upperPropValue = propValue.toUpperCase();
                        
                        // 常见的熄火状态值
                        if ("OFF".equals(upperPropValue) || "0".equals(upperPropValue) || 
                            "STOPPED".equals(upperPropValue) || "SHUTDOWN".equals(upperPropValue) ||
                            "REQUESTED".equals(upperPropValue) || "IGNITION_OFF".equals(upperPropValue) ||
                            // 待机模式相关状态值
                            "STANDBY".equals(upperPropValue) || "SUSPEND".equals(upperPropValue) ||
                            "SLEEP".equals(upperPropValue) || "HIBERNATE".equals(upperPropValue) ||
                            "DEEP_SLEEP".equals(upperPropValue) || "LOW_POWER".equals(upperPropValue) ||
                            "SLEEP_MODE".equals(upperPropValue) || "STANDBY_MODE".equals(upperPropValue) ||
                            "POWER_SAVE".equals(upperPropValue) || "POWER_OFF".equals(upperPropValue) ||
                            "DEEP_POWER_SAVE".equals(upperPropValue) || "DOZE".equals(upperPropValue) ||
                            "DOZE_SNOOZE".equals(upperPropValue) || "SUSPENDED".equals(upperPropValue) ||
                            // 比亚迪待机模式相关值
                            "BYD_STANDBY".equals(upperPropValue) || "BYD_SLEEP".equals(upperPropValue) ||
                            "CAR_STANDBY".equals(upperPropValue) || "VEHICLE_STANDBY".equals(upperPropValue) ||
                            "BYD_DORMANT".equals(upperPropValue) || "DORMANT".equals(upperPropValue) ||
                            "BYD_POWER_SAVE".equals(upperPropValue) || "BYD_LOW_POWER".equals(upperPropValue)) {
                            isEngineOff = true;
                            logManager.d(TAG, "通过系统属性检测到熄火/待机状态: " + propName + " = " + propValue);
                            break;
                        }
                    }
                }
            }
            
            // 如果是比亚迪车辆且检测到熄火状态，返回true
            if (isBydVehicle && isEngineOff) {
                logManager.d(TAG, "确认比亚迪车辆，且检测到熄火状态");
                return true;
            }
            
        } catch (Exception e) {
            logManager.e(TAG, "检查系统属性时出错: " + e.getMessage(), e);
        }
        
        return false;
    }
    
    /**
     * 获取系统属性
     */
    private String getSystemProperty(String propName) {
        try {
            // 使用反射调用SystemProperties
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method getMethod = clazz.getDeclaredMethod("get", String.class);
            String value = (String) getMethod.invoke(null, propName);
            return value;
        } catch (Exception e) {
            logManager.w(TAG, "获取系统属性" + propName + "失败: " + e.getMessage());
            return null;
        }
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        // 初始化LogManager
        if (logManager == null) {
            logManager = LogManager.getInstance(context);
        }
        
        if (intent == null) {
            logManager.e(TAG, "接收到空的广播Intent");
            return;
        }
        
        String action = intent.getAction();
        if (action == null) {
            logManager.e(TAG, "接收到空的广播Action");
            return;
        }
        
        logManager.d(TAG, "=== EngineOffReceiver 接收到广播事件 ===");
        logManager.d(TAG, "广播Action: " + action);
        
        // 记录广播的所有额外信息
        Bundle extras = intent.getExtras();
        if (extras != null) {
            logManager.d(TAG, "广播额外信息列表:");
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                logManager.d(TAG, "  " + key + " = " + value);
            }
        } else {
            logManager.d(TAG, "广播没有额外信息");
        }

        // 检查是否是比亚迪车辆
        boolean isBydVehicle = isBydVehicle(context);
        logManager.d(TAG, "是否是比亚迪车辆: " + isBydVehicle);
        
        // 如果是比亚迪车辆且监听器尚未注册，注册发动机监听器
        if (isBydVehicle && engineListener == null) {
            registerEngineListener(context);
        }

        // 检查是否是熄火相关的广播事件
        boolean isEngineOff = false;
        
        // 1. 优先处理已知的熄火和待机相关广播事件
        logManager.d(TAG, "1. 开始处理广播事件: " + action);
        logManager.d(TAG, "1. 当前广播Intent: " + intent.toString());
        
        // 重用已有的extras变量记录Intent中的所有额外数据
        if (extras != null) {
            logManager.d(TAG, "1. Intent额外数据详情:");
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                logManager.d(TAG, "1. Intent额外数据 - " + key + ": " + value);
            }
        } else {
            logManager.d(TAG, "1. Intent没有额外数据");
        }
        
        // 检查是否是任何可能的熄火或待机相关广播
        String[]熄火AndStandbyActions = {
            // 系统关机和重启广播
            Intent.ACTION_SHUTDOWN, "android.intent.action.ACTION_SHUTDOWN",
            
            // 比亚迪熄火相关广播
            BYD_ACTION_ENGINE_OFF, BYD_CAR_ACTION_ENGINE_OFF, BYD_CAR_ACTION_POWER_OFF,
            BYD_CAR_ACTION_IGNITION_OFF, BYD_ADAS_ACTION_ENGINE_OFF, BYD_ACTION_IGNITION_OFF,
            
            // 比亚迪待机模式相关广播
            BYD_ACTION_STANDBY_MODE, BYD_ACTION_SLEEP_MODE, BYD_ACTION_LOW_POWER_MODE,
            BYD_CAR_ACTION_STANDBY, BYD_CAR_ACTION_SLEEP, BYD_CAR_ACTION_LOW_POWER,
            
            // 其他熄火相关广播
            ANDROID_CAR_ACTION_ENGINE_OFF, ANDROID_CAR_ACTION_POWER_OFF,
            CUSTOM_ACTION_ENGINE_OFF, CUSTOM_ACTION_POWER_OFF, CUSTOM_ACTION_IGNITION_OFF,
            CUSTOM_ACTION_KEY_OFF,
            
            // 其他待机模式相关广播
            ANDROID_CAR_ACTION_STANDBY, ANDROID_CAR_ACTION_SLEEP,
            CUSTOM_ACTION_STANDBY, CUSTOM_ACTION_SLEEP, CUSTOM_ACTION_LOW_POWER,
            
            // 系统待机相关广播
            SYSTEM_ACTION_SCREEN_OFF, SYSTEM_ACTION_DEVICE_IDLE_MODE_CHANGED,
            SYSTEM_ACTION_LOW_MEMORY, SYSTEM_ACTION_DEVICE_STORAGE_LOW,
            
            // 新增可能的比亚迪待机广播
            "com.byd.action.POWER_SAVE_MODE", "com.byd.action.DEEP_SLEEP_MODE",
            "com.byd.car.action.POWER_SAVE", "com.byd.car.action.DEEP_SLEEP",
            "com.byd.adas.action.STANDBY",
            
            // 新增系统相关广播
            Intent.ACTION_SCREEN_OFF, "android.intent.action.SCREEN_OFF",
            Intent.ACTION_USER_PRESENT, "android.intent.action.USER_PRESENT",
            "android.intent.action.SCREEN_ON", "android.intent.action.SCREEN_ON",
            Intent.ACTION_BATTERY_CHANGED, "android.intent.action.BATTERY_CHANGED",
            Intent.ACTION_POWER_DISCONNECTED, "android.intent.action.ACTION_POWER_DISCONNECTED",
            // 应用状态变化相关广播
            Intent.ACTION_CLOSE_SYSTEM_DIALOGS, "android.intent.action.ACTION_CLOSE_SYSTEM_DIALOGS",
            Intent.ACTION_PACKAGE_RESTARTED, "android.intent.action.PACKAGE_RESTARTED",
            Intent.ACTION_PACKAGE_CHANGED, "android.intent.action.PACKAGE_CHANGED",
            // 任务相关广播
            "android.intent.action.TASK_REMOVED", "android.intent.action.RECENT_APPS_CLEARED",
            // 进程状态相关广播
            "android.intent.action.PROCESS_KILL", "android.intent.action.ACTION_SHUTDOWN"
        };
        
        // 检查当前广播是否在熄火和待机相关广播列表中
        for (String standbyAction : 熄火AndStandbyActions) {
            if (standbyAction.equals(action)) {
                isEngineOff = true;
                logManager.d(TAG, "1. 检测到熄火/待机相关广播: " + action + "，判断为熄火");
                break;
            }
        }
        
        // 2. 特殊处理比亚迪车辆的电源模式变化广播
        if (!isEngineOff && BYD_ACTION_POWER_MODE_CHANGE.equals(action)) {
            logManager.d(TAG, "2. 特殊处理比亚迪电源模式变化广播");
            String powerMode = intent.getStringExtra("power_mode");
            logManager.d(TAG, "2. 比亚迪电源模式: " + powerMode);
            
            // 如果电源模式为OFF、STANDBY、SLEEP等，表示车辆进入待机模式
            if (powerMode != null && ("OFF".equals(powerMode) || "STANDBY".equals(powerMode) || 
                                     "SLEEP".equals(powerMode) || "LOW_POWER".equals(powerMode) ||
                                     "POWER_SAVE".equals(powerMode) || "DEEP_SLEEP".equals(powerMode))) {
                isEngineOff = true;
                logManager.d(TAG, "2. 检测到比亚迪车机电源模式变为" + powerMode + "，判断为熄火");
            }
        }
        
        // 3. 特殊处理比亚迪车辆状态变化广播
        if (!isEngineOff && BYD_ACTION_VEHICLE_STATUS_CHANGE.equals(action)) {
            logManager.d(TAG, "3. 特殊处理比亚迪车辆状态变化广播");
            // 检查车辆状态变化事件
            String vehicleStatus = intent.getStringExtra("vehicle_status");
            String engineStatus = intent.getStringExtra("engine_status");
            String powerMode = intent.getStringExtra("power_mode");
            
            logManager.d(TAG, "3. 比亚迪车辆状态: " + vehicleStatus);
            logManager.d(TAG, "3. 比亚迪引擎状态: " + engineStatus);
            logManager.d(TAG, "3. 比亚迪电源模式: " + powerMode);
            
            // 如果车辆状态、引擎状态或电源模式指示熄火，则判断为熄火
            if ((vehicleStatus != null && ("OFF".equals(vehicleStatus) || "STANDBY".equals(vehicleStatus) || "SLEEP".equals(vehicleStatus))) ||
                (engineStatus != null && ("OFF".equals(engineStatus) || "STOPPED".equals(engineStatus))) ||
                (powerMode != null && ("OFF".equals(powerMode) || "STANDBY".equals(powerMode) || "SLEEP".equals(powerMode)))) {
                isEngineOff = true;
                logManager.d(TAG, "3. 根据比亚迪车辆状态变化判断为熄火");
            }
        }
        
        // 4. 如果是比亚迪车辆，优先使用比亚迪车机专用API检测引擎状态
        if (!isEngineOff && isBydVehicle) {
            logManager.d(TAG, "4. 尝试使用比亚迪车机专用API检测引擎状态");
            isEngineOff = checkBydEngineStatus(context);
            if (isEngineOff) {
                logManager.d(TAG, "4. 通过比亚迪车机专用API检测到引擎熄火");
            } else {
                logManager.d(TAG, "4. 比亚迪车机专用API未检测到引擎熄火");
            }
        }
        
        // 5. 通过系统属性检测车辆状态
        if (!isEngineOff) {
            logManager.d(TAG, "5. 尝试通过系统属性检测车辆状态");
            isEngineOff = checkVehicleStatusBySystemProperty();
            if (isEngineOff) {
                logManager.d(TAG, "5. 通过系统属性检测到引擎熄火");
            } else {
                logManager.d(TAG, "5. 系统属性未检测到引擎熄火");
            }
        }
        
        // 6. 检查电源连接状态变化
        if (!isEngineOff && Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            logManager.d(TAG, "6. 检测到电源断开，判断为熄火");
            isEngineOff = true;
        }
        
        // 7. 检查电池状态变化（从充电变为放电，可能表示熄火）
        if (!isEngineOff && Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            logManager.d(TAG, "7. 尝试通过电池状态变化检测车辆状态");
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;
            
            // 如果之前是充电状态，现在变为非充电状态，可能表示熄火
            SharedPreferences prefs = context.getSharedPreferences("engine_status", Context.MODE_PRIVATE);
            boolean wasCharging = prefs.getBoolean("was_charging", false);
            
            logManager.d(TAG, "7. 当前充电状态: " + isCharging + ", 之前充电状态: " + wasCharging);
            
            if (wasCharging && !isCharging) {
                logManager.d(TAG, "7. 检测到从充电状态变为放电状态，判断为熄火");
                isEngineOff = true;
            }
            
            // 更新充电状态
            prefs.edit().putBoolean("was_charging", isCharging).apply();
        }
        
        // 8. 检查电池电量低状态
        if (!isEngineOff && Intent.ACTION_BATTERY_LOW.equals(action)) {
            logManager.d(TAG, "8. 检测到电池电量低，判断为熄火");
            isEngineOff = true;
        }
        
        // 9. 检查系统重启广播（可能表示车辆重新启动）
        if (!isEngineOff && Intent.ACTION_REBOOT.equals(action)) {
            logManager.d(TAG, "9. 检测到系统重启广播，判断为熄火");
            isEngineOff = true;
        }
        
        // 10. 检查屏幕关闭事件
        if (!isEngineOff && Intent.ACTION_SCREEN_OFF.equals(action)) {
            logManager.d(TAG, "10. 检测到屏幕关闭事件，判断为熄火");
            isEngineOff = true;
        }
        
        // 如果仍然无法确定状态，尝试使用其他方法
        if (!isEngineOff) {
            logManager.d(TAG, "当前所有检测方法都未检测到熄火状态");
        }
        // 传统检测逻辑结束
        
        // 如果检测到熄火，执行Shizuku命令
        if (isEngineOff) {
            executeShizukuCommands(context);
        }
    }
    
    /**
     * 执行Shizuku命令
     */
    private void executeShizukuCommands(Context context) {
        logManager.d(TAG, "开始执行Shizuku命令");
        
        // 首先使用比亚迪API保持网络连接和禁用电源优化
        keepNetworkConnection(context);
        disablePowerOptimization(context);
        
        // 检查Shizuku是否可用
        if (!Shizuku.pingBinder()) {
            logManager.e(TAG, "Shizuku服务不可用，无法执行命令");
            return;
        }
        
        // 要执行的命令列表
        String[][] commands = {
            {"am", "start", "-n", "com.termux/com.termux.app.TermuxActivity"},
            {"svc", "data", "enable"},
            {"svc", "wifi", "enable"},
            // 保持网络连接的额外命令
            {"settings", "put", "global", "stay_on_while_plugged_in", "3"},
            {"settings", "put", "global", "wifi_sleep_policy", "0"},
            {"settings", "put", "global", "mobile_data_alway_on", "1"},
            // 禁用电源优化
            {"dumpsys", "deviceidle", "disable"},
            {"dumpsys", "deviceidle", "whitelist", "add", "pub.log.startfrp"},
            {"pm", "grant", "pub.log.startfrp", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"}
        };
        
        // 执行每个命令
        for (String[] command : commands) {
            executeCommand(command);
        }
    }
    
    /**
     * 执行单个Shizuku命令
     */
    private void executeCommand(String[] command) {
        try {
            logManager.d(TAG, "执行Shizuku命令: " + arrayToString(command));
            
            // 使用ShizukuHelper执行命令
            ShizukuRemoteProcess process = ShizukuHelper.newProcess(command, null, null);
            
            // 获取命令输出
            InputStream inputStream = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            
            String line;
            while ((line = reader.readLine()) != null) {
                logManager.d(TAG, "命令输出: " + line);
            }
            
            reader.close();
            inputStream.close();
            
            int exitCode = process.waitFor();
            logManager.d(TAG, "命令执行完成，退出码: " + exitCode);
            
        } catch (Exception e) {
            logManager.e(TAG, "执行Shizuku命令失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 将字符串数组转换为字符串
     */
    private String arrayToString(String[] array) {
        StringBuilder sb = new StringBuilder();
        for (String s : array) {
            sb.append(s).append(" ");
        }
        return sb.toString().trim();
    }
}
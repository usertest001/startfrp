package pub.log.startfrp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import pub.log.startfrp.lib.adb.AdbManager;

/**
 * 开机广播接收器类
 * 用于处理设备启动完成、屏幕唤醒等系统广播，自动启动FRP服务和激活Shizuku
 * @author BY YYX
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final String SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api";
    
    /**
     * 清理ADB命令输出，移除命令提示符、命令本身和多余的空白字符
     */
    private String cleanAdbOutput(String output) {
        if (output == null) {
            return null;
        }
        // 按行处理输出
        String[] lines = output.split("\\n");
        StringBuilder cleaned = new StringBuilder();
        for (String line : lines) {
            // 移除命令提示符（以$或#结尾的部分）
            line = line.replaceAll(".*[\\$#]\\s*", "");
            // 移除空白字符
            line = line.trim();
            // 只添加非空行，且不包含命令本身
            if (!line.isEmpty() && !line.startsWith("pm path") && !line.startsWith("find ") && !line.startsWith("ls ")) {
                cleaned.append(line).append("\n");
            }
        }
        // 修剪首尾空白字符
        return cleaned.toString().trim();
    }
    
    /**
     * 动态获取Shizuku库文件路径
     */
    private String getShizukuLibraryPath(Context context) {
        try {
            // 获取Shizuku应用信息
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(SHIZUKU_PACKAGE_NAME, 0);
            // 获取应用的native库目录
            String libDir = appInfo.nativeLibraryDir;
            // 构建完整的libshizuku.so路径
            return libDir + "/libshizuku.so";
        } catch (PackageManager.NameNotFoundException e) {
            LogManager.getInstance(context).e(TAG, "未找到Shizuku应用", e);
            return null;
        }
    }
    
    /**
     * 检查是否需要执行自定义命令（避免进程重复）
     */
    private boolean shouldExecuteCustomCommand(String customCommand, Context context) {
        try {
            // 解析自定义命令，提取进程名称
            String processName = extractProcessName(customCommand);
            if (processName.isEmpty()) {
                // 如果无法提取进程名称，默认执行命令
                LogManager.getInstance(context).d(TAG, "无法提取进程名称，默认执行自定义命令");
                return true;
            }
            
            LogManager.getInstance(context).d(TAG, "检查进程是否存在: " + processName);
            
            // 检查Shizuku是否可用
            if (rikka.shizuku.Shizuku.pingBinder()) {
                // 使用Shizuku执行ps命令检查进程
                String checkCommand = "ps -A | grep " + processName + " | grep -v grep";
                String[] command = {"sh", "-c", checkCommand};
                rikka.shizuku.ShizukuRemoteProcess process = ShizukuHelper.newProcess(command, null, null);
                
                // 读取命令输出
                java.io.InputStream inputStream = process.getInputStream();
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                
                // 关闭流
                reader.close();
                inputStream.close();
                process.waitFor();
                
                // 检查输出是否包含进程信息
                String outputStr = output.toString().trim();
                boolean processExists = !outputStr.isEmpty();
                LogManager.getInstance(context).d(TAG, "进程检查结果: " + (processExists ? "存在" : "不存在"));
                
                // 如果进程不存在，返回true表示需要执行命令
                return !processExists;
            } else {
                LogManager.getInstance(context).e(TAG, "Shizuku不可用，无法检查进程状态，默认执行命令");
                return true;
            }
        } catch (Exception e) {
            LogManager.getInstance(context).e(TAG, "检查进程状态时发生错误: " + e.getMessage(), e);
            // 发生错误时，默认执行命令
            return true;
        }
    }
    
    /**
     * 从自定义命令中提取进程名称
     */
    private String extractProcessName(String customCommand) {
        // 处理复杂的命令格式，包括 nohup 等包装命令
        
        // 移除命令中的重定向和后台运行符号
        String cleanedCommand = customCommand.replaceAll("\\s*>>.*", "").replaceAll("\\s*>.*", "").replaceAll("\\s*2>&1.*", "").replaceAll("\\s*&\\s*$", "");
        
        // 分割命令为部分
        String[] parts = cleanedCommand.split("\\s+");
        if (parts.length == 0) {
            return "";
        }
        
        // 跳过 nohup 等包装命令
        int executableIndex = 0;
        while (executableIndex < parts.length) {
            String part = parts[executableIndex];
            if (!part.equals("nohup") && !part.equals("sh") && !part.equals("bash") && !part.equals("-c")) {
                break;
            }
            executableIndex++;
        }
        
        // 如果没有找到可执行文件，返回空
        if (executableIndex >= parts.length) {
            return "";
        }
        
        String executablePath = parts[executableIndex];
        // 提取可执行文件名
        int lastSlashIndex = executablePath.lastIndexOf('/');
        String processName;
        if (lastSlashIndex != -1 && lastSlashIndex < executablePath.length() - 1) {
            processName = executablePath.substring(lastSlashIndex + 1);
        } else {
            processName = executablePath;
        }
        
        // 移除可能的扩展名
        if (processName.endsWith(".sh")) {
            processName = processName.substring(0, processName.length() - 3);
        } else if (processName.endsWith(".so")) {
            processName = processName.substring(0, processName.length() - 3);
        } else if (processName.endsWith(".bin")) {
            processName = processName.substring(0, processName.length() - 4);
        } else if (processName.endsWith(".arm64")) {
            processName = processName.substring(0, processName.length() - 6);
        }
        
        return processName;
    }
    
    /**
     * 启动FRP服务
     */
    private void startFrpcService(Context context, boolean useShizuku, boolean useAdb) {
        LogManager.getInstance(context).d(TAG, "启动FrpcService");
        Intent serviceIntent = new Intent(context, FrpcService.class);
        serviceIntent.setAction(FrpcService.ACTION_START);
        serviceIntent.putExtra("use_shizuku", useShizuku);
        serviceIntent.putExtra("use_adb", useAdb);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            LogManager.getInstance(context).d(TAG, "使用startForegroundService启动FrpcService");
            context.startForegroundService(serviceIntent);
        } else {
            LogManager.getInstance(context).d(TAG, "使用startService启动FrpcService");
            context.startService(serviceIntent);
        }
        LogManager.getInstance(context).d(TAG, "FrpcService启动请求已发送");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            LogManager.getInstance(context).d(TAG, "接收到广播: " + action);

            if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                    "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                    Intent.ACTION_REBOOT.equals(action)) {
                
                LogManager.getInstance(context).d(TAG, "设备启动完成，准备启动应用服务...");
                
                SharedPreferences prefs = context.getSharedPreferences("frp_config", Context.MODE_PRIVATE);
                boolean autoStart = prefs.getBoolean("auto_start", true);
                boolean useShizuku = prefs.getBoolean("use_shizuku", false);
                boolean useAdb = prefs.getBoolean("use_adb", false);

                LogManager.getInstance(context).d(TAG, "配置信息 - autoStart: " + autoStart + ", useShizuku: " + useShizuku + ", useAdb: " + useAdb);

                // 无论是否自动启动，都启动StatusService显示通知
                LogManager.getInstance(context).d(TAG, "启动StatusService（前台服务，确保通知显示）");
                StatusService.startService(context);
                LogManager.getInstance(context).d(TAG, "StatusService启动请求已发送");
                
                // 延迟1秒后再次尝试启动，确保服务能够正常启动
                new android.os.Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        LogManager.getInstance(context).d(TAG, "延迟再次启动StatusService");
                        StatusService.startService(context);
                    }
                }, 1000);
                
                if (autoStart) {
                    LogManager.getInstance(context).d(TAG, "开始启动FRP相关服务");
                    
                    // 先启动FRP服务
                    startFrpcService(context, useShizuku, useAdb);
                    
                    // 检查是否需要在开机时激活Shizuku
                    boolean activateShizukuOnBoot = prefs.getBoolean("activate_shizuku_on_boot", false);
                    LogManager.getInstance(context).d(TAG, "激活Shizuku配置: " + activateShizukuOnBoot);
                    
                    // 如果需要激活Shizuku且使用ADB模式，在FRP服务启动后激活Shizuku
                    if (activateShizukuOnBoot && useAdb) {
                        LogManager.getInstance(context).d(TAG, "开始使用ADB激活Shizuku");
                        new Thread(() -> {
                            try {
                                // 移除冗余初始化，避免与FrpcService冲突
                                AdbManager adbManager = AdbManager.getInstance();
                                
                                // 检查ADB连接状态
                                if (!adbManager.connect()) {
                                    LogManager.getInstance(context).e(TAG, "ADB连接失败，无法激活Shizuku");
                                    return;
                                }
                                
                                // 只使用pm path命令获取Shizuku路径（最可靠的方式）
                                String foundPath = null;
                                
                                // 通过pm path命令获取Shizuku应用路径
                                LogManager.getInstance(context).d(TAG, "通过pm path命令获取Shizuku应用路径");
                                String pmPathResult = adbManager.executeCommand("pm path moe.shizuku.privileged.api");
                                if (pmPathResult != null && !pmPathResult.isEmpty()) {
                                    LogManager.getInstance(context).d(TAG, "pm path原始结果: " + pmPathResult);
                                    
                                    // 清理ADB命令输出
                                    String[] lines = pmPathResult.split("\\n");
                                    StringBuilder cleaned = new StringBuilder();
                                    for (String line : lines) {
                                        // 移除命令提示符
                                        line = line.replaceAll(".*[\\$#]\\s*", "");
                                        // 移除空白字符
                                        line = line.trim();
                                        // 只添加非空行，且不包含命令本身
                                        if (!line.isEmpty() && !line.startsWith("pm path")) {
                                            cleaned.append(line).append("\n");
                                        }
                                    }
                                    String cleanPmPath = cleaned.toString().trim();
                                    LogManager.getInstance(context).d(TAG, "pm path清理结果: " + cleanPmPath);
                                    
                                    // 处理清理后的结果
                                    if (cleanPmPath != null && !cleanPmPath.isEmpty() && cleanPmPath.startsWith("package:")) {
                                        String apkPath = cleanPmPath.substring(8);
                                        LogManager.getInstance(context).d(TAG, "提取的APK路径: " + apkPath);
                                        
                                        // 检查APK路径是否有效
                                        if (apkPath.endsWith(".apk")) {
                                            // 替换/base.apk为空白，得到应用目录路径
                                            String appDirPath = apkPath.replace("/base.apk", "");
                                            LogManager.getInstance(context).d(TAG, "应用目录路径: " + appDirPath);
                                            
                                            // 构建完整的libshizuku.so路径
                                            String libPath = appDirPath + "/lib/arm64/libshizuku.so";
                                            LogManager.getInstance(context).d(TAG, "计算出的Shizuku路径: " + libPath);
                                            foundPath = libPath;
                                        }
                                    }
                                }
                                
                                // 如果找到路径，执行激活命令
                                if (foundPath != null && !foundPath.isEmpty()) {
                                    LogManager.getInstance(context).d(TAG, "执行激活命令: " + foundPath);
                                    String result = adbManager.executeCommand(foundPath);
                                    LogManager.getInstance(context).d(TAG, "激活Shizuku结果: " + (result != null ? result : "无结果"));
                                } else {
                                    LogManager.getInstance(context).e(TAG, "无法找到Shizuku路径，跳过激活");
                                }
                                
                                // 延迟2秒确保Shizuku完全激活
                                Thread.sleep(2000);
                            } catch (Exception e) {
                                LogManager.getInstance(context).e(TAG, "激活Shizuku失败: " + e.getMessage(), e);
                                // 即使激活失败也继续执行，不影响FRP服务
                            }
                        }).start();
                    }
                } else {
                    LogManager.getInstance(context).d(TAG, "自动启动已禁用，跳过FrpcService启动");
                }
                
                // 不再启动MainActivity，只在后台运行
                LogManager.getInstance(context).d(TAG, "服务启动完成，应用在后台运行");
                
                // 延迟20秒后执行自定义命令
                new android.os.Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        LogManager.getInstance(context).d(TAG, "开始执行自定义命令");
                        // 检查是否启用了自定义命令
                        boolean enableCustomCommand = CustomCommandActivity.isCustomCommandEnabled(context);
                        if (enableCustomCommand) {
                            // 读取自定义命令
                            String customCommand = CustomCommandActivity.getCustomCommand(context);
                            if (!customCommand.isEmpty()) {
                                LogManager.getInstance(context).d(TAG, "执行自定义命令: " + customCommand);
                                
                                // 检查是否需要执行自定义命令（避免进程重复）
                                if (shouldExecuteCustomCommand(customCommand, context)) {
                                    // 使用Shizuku执行自定义命令
                                    try {
                                        // 检查Shizuku是否可用
                                        if (rikka.shizuku.Shizuku.pingBinder()) {
                                            // 执行自定义命令
                                            String[] command = {"sh", "-c", customCommand};
                                            rikka.shizuku.ShizukuRemoteProcess process = ShizukuHelper.newProcess(command, null, null);
                                            int exitCode = process.waitFor();
                                            LogManager.getInstance(context).d(TAG, "自定义命令执行完成，退出码: " + exitCode);
                                        } else {
                                            LogManager.getInstance(context).e(TAG, "Shizuku不可用，无法执行自定义命令");
                                        }
                                    } catch (Exception e) {
                                        LogManager.getInstance(context).e(TAG, "执行自定义命令失败: " + e.getMessage(), e);
                                    }
                                } else {
                                    LogManager.getInstance(context).d(TAG, "自定义命令对应的进程已在运行，跳过执行");
                                }
                            } else {
                                LogManager.getInstance(context).d(TAG, "自定义命令为空，跳过执行");
                            }
                        } else {
                            LogManager.getInstance(context).d(TAG, "自定义命令已禁用，跳过执行");
                        }
                    }
                }, 20000); // 延迟20秒
            } else if (Intent.ACTION_USER_PRESENT.equals(action) ||
                       Intent.ACTION_SCREEN_ON.equals(action)) {
                
                LogManager.getInstance(context).d(TAG, "设备唤醒，准备启动StatusService显示通知...");
                
                // 只启动StatusService显示通知，不需要启动MainActivity
                LogManager.getInstance(context).d(TAG, "启动StatusService（前台服务，确保通知显示）");
                StatusService.startService(context);
                LogManager.getInstance(context).d(TAG, "StatusService启动请求已发送");
                
                // 延迟1秒后再次尝试启动，确保服务能够正常启动
                new android.os.Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        LogManager.getInstance(context).d(TAG, "延迟再次启动StatusService");
                        StatusService.startService(context);
                    }
                }, 1000);
                LogManager.getInstance(context).d(TAG, "唤醒处理完成");
            } else {
                LogManager.getInstance(context).d(TAG, "忽略非启动广播: " + action);
            }
        }
    }
}

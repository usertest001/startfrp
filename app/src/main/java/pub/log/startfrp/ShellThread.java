package pub.log.startfrp;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/**
 * 用于执行shell命令的线程类
 * 封装了进程执行逻辑，支持命令参数、工作目录和环境变量
 * 实现了输出流处理和错误流合并
 */
public class ShellThread extends Thread {
    private static final String TAG = "ShellThread";
    
    private List<String> command;
    private File workingDir;
    private Map<String, String> environment;
    private Process process;
    private boolean isRunning = false;
    private ShellListener listener;
    
    /**
     * 构造函数
     * @param command 命令参数列表
     * @param workingDir 工作目录
     * @param environment 环境变量
     * @param listener 监听器
     */
    public ShellThread(List<String> command, File workingDir, Map<String, String> environment, ShellListener listener) {
        this.command = command;
        this.workingDir = workingDir;
        this.environment = environment;
        this.listener = listener;
    }
    
    /**
     * 重写run方法，执行进程并处理输出
     */
    @Override
    public void run() {
        Log.d(TAG, "ShellThread开始执行");
        isRunning = true;
        
        try {
            // 创建进程构建器
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            
            // 设置工作目录
            if (workingDir != null) {
                processBuilder.directory(workingDir);
                Log.d(TAG, "工作目录: " + workingDir.getAbsolutePath());
            }
            
            // 设置环境变量
            if (environment != null) {
                processBuilder.environment().putAll(environment);
            }
            
            // 合并错误流到标准输出
            processBuilder.redirectErrorStream(true);
            
            // 记录执行的命令
            StringBuilder commandStr = new StringBuilder();
            for (String cmd : command) {
                commandStr.append(cmd).append(" ");
            }
            Log.d(TAG, "执行命令: " + commandStr.toString().trim());
            
            // 启动进程
            process = processBuilder.start();
            Log.d(TAG, "进程启动成功");
            
            // 通知监听器进程已启动
            if (listener != null) {
                listener.onProcessStarted(process);
            }
            
            // 读取进程输出
            readProcessOutput(process.getInputStream());
            
            // 等待进程结束
            int exitCode = process.waitFor();
            Log.d(TAG, "进程已结束，退出码: " + exitCode);
            
            // 通知监听器进程已结束
            if (listener != null) {
                listener.onProcessExited(exitCode);
            }
            
        } catch (IOException e) {
            Log.e(TAG, "执行命令异常: " + e.getMessage());
            e.printStackTrace();
            if (listener != null) {
                listener.onError(e);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "线程被中断: " + e.getMessage());
            e.printStackTrace();
            if (listener != null) {
                listener.onError(e);
            }
        } finally {
            isRunning = false;
            cleanup();
        }
    }
    
    /**
     * 读取进程输出
     * @param inputStream 输入流
     */
    private void readProcessOutput(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        Log.d(TAG, "开始读取进程输出");
        
        while ((line = reader.readLine()) != null && isRunning) {
            Log.d(TAG, "进程输出: " + line);
            if (listener != null) {
                listener.onOutput(line);
            }
        }
        
        Log.d(TAG, "进程输出读取完毕");
        reader.close();
    }
    
    /**
     * 停止进程
     */
    public void stopProcess() {
        Log.d(TAG, "停止进程");
        isRunning = false;
        
        if (process != null) {
            try {
                // 尝试终止进程
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    process.destroyForcibly();
                } else {
                    process.destroy();
                }
                
                // 等待进程终止
                process.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                Log.d(TAG, "进程已终止");
            } catch (Exception e) {
                Log.e(TAG, "停止进程异常: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        Log.d(TAG, "清理资源");
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
    
    /**
     * 检查线程是否正在运行
     * @return 是否正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * 获取当前进程
     * @return 进程
     */
    public Process getProcess() {
        return process;
    }
    
    /**
     * 监听器接口，用于处理进程输出和状态变化
     */
    public interface ShellListener {
        /**
         * 进程已启动
         * @param process 进程
         */
        void onProcessStarted(Process process);
        
        /**
         * 进程输出
         * @param line 输出行
         */
        void onOutput(String line);
        
        /**
         * 进程已结束
         * @param exitCode 退出码
         */
        void onProcessExited(int exitCode);
        
        /**
         * 发生错误
         * @param e 异常
         */
        void onError(Exception e);
    }
}

package pub.log.startfrp;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Shizuku API辅助类，用于访问最新版本中被改为私有方法的newProcess
 * @author BY YYX
 */
public class ShizukuHelper {

    /**
     * 启动一个新的远程进程
     * @param cmd 命令数组
     * @param env 环境变量数组
     * @param dir 工作目录
     * @return ShizukuRemoteProcess实例
     */
    public static ShizukuRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        try {
            // 使用反射直接调用Shizuku.newProcess私有方法
            java.lang.reflect.Method newProcessMethod = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            newProcessMethod.setAccessible(true);
            return (ShizukuRemoteProcess) newProcessMethod.invoke(null, cmd, env, dir);
        } catch (Exception e) {
            throw new RuntimeException("调用Shizuku.newProcess失败", e);
        }
    }

    /**
     * 执行自定义命令
     * @param command 要执行的命令字符串
     * @return 命令执行结果
     * @throws Exception 执行过程中的异常
     */
    public static String executeCustomCommand(String command) throws Exception {
        // 检查Shizuku是否可用
        if (!Shizuku.pingBinder()) {
            throw new RuntimeException("Shizuku不可用");
        }

        // 构建命令数组
        String[] cmd = {"sh", "-c", command};
        // 执行命令
        ShizukuRemoteProcess process = newProcess(cmd, null, null);

        // 读取命令输出
        java.io.InputStream inputStream = process.getInputStream();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        // 读取错误输出
        java.io.InputStream errorStream = process.getErrorStream();
        java.io.BufferedReader errorReader = new java.io.BufferedReader(new java.io.InputStreamReader(errorStream));
        StringBuilder errorOutput = new StringBuilder();
        while ((line = errorReader.readLine()) != null) {
            errorOutput.append(line).append("\n");
        }

        // 等待命令执行完成
        int exitCode = process.waitFor();

        // 关闭流
        reader.close();
        errorReader.close();
        inputStream.close();
        errorStream.close();

        // 构建完整的执行结果
        StringBuilder result = new StringBuilder();
        if (output.length() > 0) {
            result.append("标准输出:\n").append(output);
        }
        if (errorOutput.length() > 0) {
            result.append("错误输出:\n").append(errorOutput);
        }
        result.append("退出码: ").append(exitCode);

        return result.toString();
    }
}
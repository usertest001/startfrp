package pub.log.startfrp;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Shizuku API辅助类，用于访问最新版本中被改为私有方法的newProcess
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
}
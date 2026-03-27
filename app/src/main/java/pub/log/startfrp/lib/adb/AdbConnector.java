package pub.log.startfrp.lib.adb;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import pub.log.startfrp.LogManager;
import pub.log.startfrp.adblib.AdbBase64;
import pub.log.startfrp.adblib.AdbConnection;
import pub.log.startfrp.adblib.AdbCrypto;
import pub.log.startfrp.adblib.AdbStream;

/**
 * ADB连接器类
 * 用于与ADB服务器建立连接，执行shell命令，并管理连接池
 * 支持自动重连和连接状态检查
 * @author BY YYX
 */
public class AdbConnector {
    private static final String TAG = "AdbConnector";
    private static final String ADB_HOST = "localhost";
    private static final int ADB_PORT = 5555;
    private static final String PREF_NAME = "adb_connector_prefs";
    private static final String KEY_PRIVATE_KEY = "private_key";
    private static final String KEY_PUBLIC_KEY = "public_key";
    private static final int MAX_POOL_SIZE = 3; // 连接池最大大小

    private final Context context;
    private AdbConnection connection;
    private boolean connected = false;
    private AdbCrypto adbCrypto;
    private final java.util.Queue<AdbConnection> connectionPool = new java.util.LinkedList<>();
    private final java.util.concurrent.Semaphore poolSemaphore = new java.util.concurrent.Semaphore(MAX_POOL_SIZE);
    private java.util.Timer connectionCheckTimer;
    private static final long CONNECTION_CHECK_INTERVAL = 60 * 1000; // 60秒检查一次

    public AdbConnector(Context context) {
        this.context = context.getApplicationContext();
        this.connection = null;
        this.connected = false;
        this.adbCrypto = null;
    }
    
    private void sendAdbLog(String logMessage) {
        // 通过AdbManager发送日志
        AdbManager.sendAdbLog(logMessage);
    }

    public synchronized boolean connect() {
        try {
            // 检查是否已经连接
            if (connected && connection != null) {
                Log.d(TAG, "ADB已经连接，无需重新连接");
                LogManager.getInstance(context).d(TAG, "ADB已经连接，无需重新连接");
                return true;
            }

            // 尝试从连接池获取连接
            if (!connectionPool.isEmpty()) {
                connection = connectionPool.poll();
                if (connection != null) {
                    Log.d(TAG, "从连接池获取ADB连接");
                    LogManager.getInstance(context).d(TAG, "从连接池获取ADB连接");
                    connected = true;
                    startConnectionChecks();
                    return true;
                }
            }

            // 检查连接池是否已满
            if (!poolSemaphore.tryAcquire()) {
                Log.d(TAG, "ADB连接池已满，等待可用连接");
                LogManager.getInstance(context).d(TAG, "ADB连接池已满，等待可用连接");
                // 连接池已满，使用现有连接
                if (connection != null) {
                    connected = true;
                    startConnectionChecks();
                    return true;
                }
                return false;
            }

            Log.d(TAG, "开始连接ADB服务器");
            LogManager.getInstance(context).d(TAG, "开始连接ADB服务器");

            // 先断开现有连接
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    Log.d(TAG, "关闭现有连接时出错：" + e.getMessage());
                    LogManager.getInstance(context).d(TAG, "关闭现有连接时出错：" + e.getMessage());
                }
                connection = null;
            }

            // 加载或生成RSA密钥
            if (adbCrypto == null) {
                adbCrypto = loadOrGenerateCrypto();
            }

            // 尝试连接，最多尝试3次
            for (int i = 0; i < 3; i++) {
                try {
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String logMessage = timestamp + " 尝试连接ADB服务器，第" + (i + 1) + "次: " + ADB_HOST + ":" + ADB_PORT;
                    Log.d(TAG, "尝试连接ADB服务器，第" + (i + 1) + "次: " + ADB_HOST + ":" + ADB_PORT);
                    LogManager.getInstance(context).d(TAG, "尝试连接ADB服务器，第" + (i + 1) + "次: " + ADB_HOST + ":" + ADB_PORT);
                    sendAdbLog(logMessage);

                    // 创建Socket
                    Socket socket = new Socket(ADB_HOST, ADB_PORT);
                    socket.setSoTimeout(5000); // 设置Socket超时

                    // 创建AdbConnection
                    connection = AdbConnection.create(socket, adbCrypto);

                    // 检查连接是否创建成功
                    if (connection == null) {
                        timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                        logMessage = timestamp + " 创建AdbConnection失败，返回null";
                        Log.e(TAG, "创建AdbConnection失败，返回null");
                        LogManager.getInstance(context).e(TAG, "创建AdbConnection失败，返回null");
                        sendAdbLog(logMessage);
                        continue;
                    }

                    // 连接，设置5秒超时
                    connection.connect(5000);

                    connected = true;
                    timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    logMessage = timestamp + " ADB连接成功";
                    Log.d(TAG, "ADB连接成功");
                    LogManager.getInstance(context).d(TAG, "ADB连接成功");
                    sendAdbLog(logMessage);
                    startConnectionChecks();
                    return true;
                } catch (IOException e) {
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String logMessage = timestamp + " ADB连接失败，第" + (i + 1) + "次：" + e.getMessage();
                    Log.e(TAG, "ADB连接失败，第" + (i + 1) + "次：" + e.getMessage(), e);
                    LogManager.getInstance(context).e(TAG, "ADB连接失败，第" + (i + 1) + "次：" + e.getMessage());
                    sendAdbLog(logMessage);
                    // 等待1秒后重试
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                } catch (InterruptedException e) {
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String logMessage = timestamp + " ADB连接被中断，第" + (i + 1) + "次：" + e.getMessage();
                    Log.e(TAG, "ADB连接被中断，第" + (i + 1) + "次：" + e.getMessage(), e);
                    LogManager.getInstance(context).e(TAG, "ADB连接被中断，第" + (i + 1) + "次：" + e.getMessage());
                    sendAdbLog(logMessage);
                    Thread.currentThread().interrupt();
                    poolSemaphore.release();
                    return false;
                } catch (Exception e) {
                    String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    String logMessage = timestamp + " ADB连接异常，第" + (i + 1) + "次：" + e.getMessage();
                    Log.e(TAG, "ADB连接异常，第" + (i + 1) + "次：" + e.getMessage(), e);
                    LogManager.getInstance(context).e(TAG, "ADB连接异常，第" + (i + 1) + "次：" + e.getMessage());
                    sendAdbLog(logMessage);
                    continue;
                }
            }

            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String logMessage = timestamp + " ADB连接失败，已尝试3次";
            Log.e(TAG, "ADB连接失败，已尝试3次");
            LogManager.getInstance(context).e(TAG, "ADB连接失败，已尝试3次");
            sendAdbLog(logMessage);
            disconnect();
            poolSemaphore.release();
            return false;
        } catch (Exception e) {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String logMessage = timestamp + " ADB连接异常：" + e.getMessage();
            Log.e(TAG, "ADB连接异常：" + e.getMessage(), e);
            LogManager.getInstance(context).e(TAG, "ADB连接异常：" + e.getMessage());
            sendAdbLog(logMessage);
            disconnect();
            poolSemaphore.release();
            return false;
        }
    }

    /**
     * 开始定期检查连接状态
     */
    private synchronized void startConnectionChecks() {
        // 先停止现有的定时器
        stopConnectionChecks();
        
        // 创建新的定时器
        connectionCheckTimer = new java.util.Timer();
        connectionCheckTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                synchronized (AdbConnector.this) {
                    // 检查当前连接
                    if (connected && connection != null) {
                        if (!isConnectionValid()) {
                            Log.d(TAG, "当前ADB连接无效，尝试重新连接");
                            LogManager.getInstance(context).d(TAG, "当前ADB连接无效，尝试重新连接");
                            connect();
                        }
                    }
                    
                    // 检查连接池中的连接
                    if (!connectionPool.isEmpty()) {
                        java.util.Queue<AdbConnection> validConnections = new java.util.LinkedList<>();
                        while (!connectionPool.isEmpty()) {
                            AdbConnection pooledConnection = connectionPool.poll();
                            try {
                                // 验证连接是否有效
                                AdbStream stream = pooledConnection.open("shell:");
                                byte[] commandBytes = ("echo test\n").getBytes("UTF-8");
                                stream.write(commandBytes);
                                Thread.sleep(200);
                                InputStream inputStream = stream.getInputStream();
                                byte[] buffer = new byte[1024];
                                int bytesRead = inputStream.read(buffer);
                                stream.close();
                                
                                if (bytesRead > 0) {
                                    validConnections.offer(pooledConnection);
                                } else {
                                    // 连接无效，关闭
                                    pooledConnection.close();
                                    poolSemaphore.release();
                                }
                            } catch (Exception e) {
                                // 连接无效，关闭
                                try {
                                    if (pooledConnection != null) {
                                        pooledConnection.close();
                                    }
                                } catch (Exception ex) {
                                    // 忽略错误
                                }
                                poolSemaphore.release();
                            }
                        }
                        // 将有效连接放回池
                        while (!validConnections.isEmpty()) {
                            connectionPool.offer(validConnections.poll());
                        }
                    }
                }
            }
        }, CONNECTION_CHECK_INTERVAL, CONNECTION_CHECK_INTERVAL);
    }

    /**
     * 停止定期检查连接状态
     */
    private synchronized void stopConnectionChecks() {
        if (connectionCheckTimer != null) {
            connectionCheckTimer.cancel();
            connectionCheckTimer = null;
        }
    }

    /**
     * 将连接返回连接池
     */
    public synchronized void returnToPool() {
        if (connected && connection != null) {
            // 检查连接是否仍然有效
            if (isConnectionValid()) {
                connectionPool.offer(connection);
                Log.d(TAG, "ADB连接返回连接池，当前池大小：" + connectionPool.size());
                LogManager.getInstance(context).d(TAG, "ADB连接返回连接池，当前池大小：" + connectionPool.size());
            } else {
                // 连接无效，关闭并释放信号量
                try {
                    connection.close();
                } catch (Exception e) {
                    Log.d(TAG, "关闭无效连接时出错：" + e.getMessage());
                    LogManager.getInstance(context).d(TAG, "关闭无效连接时出错：" + e.getMessage());
                }
                poolSemaphore.release();
            }
            connection = null;
            connected = false;
        }
    }

    public synchronized String executeCommand(String command) {
        if (!connected && !connect()) {
            Log.e(TAG, "ADB未连接，无法执行命令");
            LogManager.getInstance(context).e(TAG, "ADB未连接，无法执行命令");
            return null;
        }

        // 最多尝试3次执行命令
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            AdbStream stream = null;
            try {
                Log.d(TAG, "执行ADB命令（尝试" + attempt + "/" + maxAttempts + "）：" + command);
                LogManager.getInstance(context).d(TAG, "执行ADB命令（尝试" + attempt + "/" + maxAttempts + "）：" + command);

                // 打开shell流来执行命令
                Log.d(TAG, "打开shell流");
                LogManager.getInstance(context).d(TAG, "打开shell流");
                try {
                    stream = connection.open("shell:");
                    Log.d(TAG, "shell流打开成功");
                    LogManager.getInstance(context).d(TAG, "shell流打开成功");
                } catch (Exception e) {
                    Log.e(TAG, "打开shell流失败：" + e.getMessage(), e);
                    LogManager.getInstance(context).e(TAG, "打开shell流失败：" + e.getMessage());
                    // 重新连接
                    if (connect()) {
                        // 重新尝试打开流
                        try {
                            stream = connection.open("shell:");
                            Log.d(TAG, "重新打开shell流成功");
                            LogManager.getInstance(context).d(TAG, "重新打开shell流成功");
                        } catch (Exception ex) {
                            Log.e(TAG, "重新打开shell流失败：" + ex.getMessage(), ex);
                            LogManager.getInstance(context).e(TAG, "重新打开shell流失败：" + ex.getMessage());
                            // 继续下一次尝试
                            continue;
                        }
                    } else {
                        Log.e(TAG, "重新连接失败，无法打开shell流");
                        LogManager.getInstance(context).e(TAG, "重新连接失败，无法打开shell流");
                        // 继续下一次尝试
                        continue;
                    }
                }

                // 写入命令
                Log.d(TAG, "写入命令：" + command);
                LogManager.getInstance(context).d(TAG, "写入命令：" + command);
                try {
                    // 写入命令，确保以换行符结束
                    byte[] commandBytes = (command + "\n").getBytes("UTF-8");
                    stream.write(commandBytes);
                    Log.d(TAG, "命令写入成功");
                    LogManager.getInstance(context).d(TAG, "命令写入成功");
                } catch (Exception e) {
                    Log.e(TAG, "写入命令失败：" + e.getMessage(), e);
                    LogManager.getInstance(context).e(TAG, "写入命令失败：" + e.getMessage());
                    // 重新连接
                    if (connect()) {
                        // 继续下一次尝试
                        continue;
                    }
                    // 继续下一次尝试
                    continue;
                }

                // 等待一小段时间，让命令有时间执行
                try {
                    Thread.sleep(1500); // 增加等待时间，确保命令执行完成
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 创建一个final的引用，以便在内部类中使用
                final AdbStream finalStream = stream;

                // 使用单独的线程来读取数据，避免阻塞
                final StringBuilder response = new StringBuilder();
                final boolean[] hasData = {false};
                final boolean[] readComplete = {false};
                
                Thread readThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 获取输入流
                            Log.d(TAG, "获取输入流");
                            LogManager.getInstance(context).d(TAG, "获取输入流");
                            InputStream inputStream = finalStream.getInputStream();
                            Log.d(TAG, "输入流获取成功");
                            LogManager.getInstance(context).d(TAG, "输入流获取成功");
                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            
                            // 读取数据，最多读取3000毫秒
                            long startTime = System.currentTimeMillis();
                            long timeout = 3000; // 3000毫秒超时
                            
                            Log.d(TAG, "开始读取数据");
                            LogManager.getInstance(context).d(TAG, "开始读取数据");
                            while ((bytesRead = inputStream.read(buffer)) != -1 && 
                                   System.currentTimeMillis() - startTime < timeout) {
                                hasData[0] = true;
                                String chunk = new String(buffer, 0, bytesRead, "UTF-8");
                                response.append(chunk);
                                Log.d(TAG, "读取到数据：" + chunk);
                                LogManager.getInstance(context).d(TAG, "读取到数据：" + chunk);
                            }
                            Log.d(TAG, "读取数据完成，共读取：" + response.length() + " 个字符");
                            LogManager.getInstance(context).d(TAG, "读取数据完成，共读取：" + response.length() + " 个字符");
                        } catch (Exception e) {
                            Log.e(TAG, "读取响应数据失败：" + e.getMessage(), e);
                            LogManager.getInstance(context).e(TAG, "读取响应数据失败：" + e.getMessage());
                        } finally {
                            readComplete[0] = true;
                            Log.d(TAG, "读取线程完成");
                            LogManager.getInstance(context).d(TAG, "读取线程完成");
                        }
                    }
                });
                
                readThread.start();
                
                // 等待读取线程完成，最多等待3000毫秒
                long startTime = System.currentTimeMillis();
                long timeout = 3000; // 3000毫秒超时
                Log.d(TAG, "等待读取线程完成");
                LogManager.getInstance(context).d(TAG, "等待读取线程完成");
                while (!readComplete[0] && System.currentTimeMillis() - startTime < timeout) {
                    try {
                        Thread.sleep(100); // 每100毫秒检查一次
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                // 如果读取线程还没有完成，中断它
                if (!readComplete[0]) {
                    Log.d(TAG, "读取响应超时，中断读取线程");
                    LogManager.getInstance(context).d(TAG, "读取响应超时，中断读取线程");
                }
                
                // 处理结果
                String resultStr = response.toString().trim();
                Log.d(TAG, "原始结果：" + resultStr);
                LogManager.getInstance(context).d(TAG, "原始结果：" + resultStr);
                
                // 尝试不同的方式来提取结果
                // 对于settings get命令，尝试直接提取值
                if (command.startsWith("settings get")) {
                    // 尝试简单的方式，直接返回结果
                    Log.d(TAG, "处理settings get命令结果");
                    LogManager.getInstance(context).d(TAG, "处理settings get命令结果");
                    
                    // 保存原始结果
                    String originalResult = resultStr;
                    
                    // 尝试多种方式提取结果
                    // 方式1：按行分割，查找包含命令的行，然后取下一行
                    String[] lines = originalResult.split("\\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.contains(command)) {
                            if (i + 1 < lines.length) {
                                resultStr = lines[i + 1].trim();
                                // 清理结果，移除可能的命令提示符或设备信息
                                resultStr = resultStr.replaceAll("^.*\\$\\s*", "").trim();
                                resultStr = resultStr.replaceAll("^.*:/\\s*", "").trim();
                                Log.d(TAG, "按行分割提取结果：" + resultStr);
                                LogManager.getInstance(context).d(TAG, "按行分割提取结果：" + resultStr);
                                break;
                            }
                        }
                    }
                    
                    // 方式2：如果方式1失败，尝试查找数字值
                    if (resultStr.isEmpty() || resultStr.equals("")) {
                        // 查找所有数字值
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\d+");
                        java.util.regex.Matcher matcher = pattern.matcher(originalResult);
                        if (matcher.find()) {
                            resultStr = matcher.group();
                            Log.d(TAG, "查找数字值提取结果：" + resultStr);
                            LogManager.getInstance(context).d(TAG, "查找数字值提取结果：" + resultStr);
                        }
                    }
                    
                    // 方式3：如果方式1和方式2都失败，尝试使用原始结果的最后一个单词
                    if (resultStr.isEmpty() || resultStr.equals("")) {
                        String[] parts = originalResult.split("\\s+");
                        if (parts.length > 0) {
                            String lastPart = parts[parts.length - 1].trim();
                            // 移除非数字字符
                            String numericValue = lastPart.replaceAll("[^0-9]", "");
                            if (!numericValue.isEmpty()) {
                                resultStr = numericValue;
                                Log.d(TAG, "使用最后一个单词提取结果：" + resultStr);
                                LogManager.getInstance(context).d(TAG, "使用最后一个单词提取结果：" + resultStr);
                            }
                        }
                    }
                    
                    // 方式4：如果方式1、2、3都失败，尝试直接查找不包含命令的行
                    if (resultStr.isEmpty() || resultStr.equals("")) {
                        for (int i = 0; i < lines.length; i++) {
                            String line = lines[i].trim();
                            if (!line.contains(command) && !line.contains("$ ") && !line.contains(":/") && !line.isEmpty()) {
                                resultStr = line.trim();
                                Log.d(TAG, "查找不包含命令的行提取结果：" + resultStr);
                                LogManager.getInstance(context).d(TAG, "查找不包含命令的行提取结果：" + resultStr);
                                break;
                            }
                        }
                    }
                } else {
                    // 对于其他命令，使用更合理的处理逻辑
                    // 移除可能包含的命令本身
                    if (resultStr.startsWith(command)) {
                        resultStr = resultStr.substring(command.length()).trim();
                    }
                    // 保留原始的多行格式，不要移除所有空白字符
                    // 这样 parseAdbProcessOutput 方法可以正确处理多行输出
                }
                
                // 关闭流
                try {
                    stream.close();
                    Log.d(TAG, "流关闭成功");
                    LogManager.getInstance(context).d(TAG, "流关闭成功");
                } catch (Exception e) {
                    Log.e(TAG, "关闭流失败：" + e.getMessage(), e);
                    LogManager.getInstance(context).e(TAG, "关闭流失败：" + e.getMessage());
                }
                
                // 如果这次尝试成功获取到结果，返回结果
                // 对于pm grant命令，执行成功时通常返回空字符串，也应该认为是成功
                // 对于pm check-permission命令，需要特殊处理以确保正确返回结果
                // 对于nohup命令，执行成功时通常返回空字符串或只包含进程ID的结果，也应该认为是成功
                if ((resultStr != null && !resultStr.isEmpty()) || command.startsWith("pm grant") || command.startsWith("pm check-permission") || command.startsWith("nohup")) {
                    Log.d(TAG, "ADB命令执行结果（尝试" + attempt + "）：" + resultStr);
                    LogManager.getInstance(context).d(TAG, "ADB命令执行结果（尝试" + attempt + "）：" + resultStr);
                    return resultStr;
                } else {
                    Log.d(TAG, "ADB命令执行无结果");
                    LogManager.getInstance(context).d(TAG, "ADB命令执行无结果");
                }

            } catch (Exception e) {
                Log.e(TAG, "执行命令失败：" + e.getMessage(), e);
                LogManager.getInstance(context).e(TAG, "执行命令失败：" + e.getMessage());
                // 继续下一次尝试
                continue;
            } finally {
                // 确保流被关闭
                try {
                    if (stream != null) {
                        stream.close();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "关闭流失败：" + e.getMessage(), e);
                    LogManager.getInstance(context).e(TAG, "关闭流失败：" + e.getMessage());
                }
            }
        }
        
        // 所有尝试都失败
        Log.e(TAG, "所有尝试都失败，无法执行命令：" + command);
        LogManager.getInstance(context).e(TAG, "所有尝试都失败，无法执行命令：" + command);
        return null;
    }

    /**
     * 执行命令并返回连接到池
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    public synchronized String executeCommandWithPooling(String command) {
        try {
            return executeCommand(command);
        } finally {
            // 命令执行完成后，将连接返回连接池
            returnToPool();
        }
    }



    private AdbCrypto loadOrGenerateCrypto() throws NoSuchAlgorithmException {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String privateKeyStr = prefs.getString(KEY_PRIVATE_KEY, null);
        String publicKeyStr = prefs.getString(KEY_PUBLIC_KEY, null);

        if (privateKeyStr != null && publicKeyStr != null) {
            try {
                // 加载已保存的密钥
                byte[] privateKey = Base64.decode(privateKeyStr, Base64.NO_WRAP);
                byte[] publicKey = Base64.decode(publicKeyStr, Base64.NO_WRAP);
                
                // 创建临时文件来保存密钥
                File privateKeyFile = File.createTempFile("private", ".key", context.getCacheDir());
                File publicKeyFile = File.createTempFile("public", ".key", context.getCacheDir());
                
                // 写入密钥数据
                FileOutputStream privateOut = new FileOutputStream(privateKeyFile);
                FileOutputStream publicOut = new FileOutputStream(publicKeyFile);
                privateOut.write(privateKey);
                publicOut.write(publicKey);
                privateOut.close();
                publicOut.close();
                
                // 加载密钥
                AdbCrypto crypto = AdbCrypto.loadAdbKeyPair(new AdbBase64() {
                    @Override
                    public String encodeToString(byte[] bytes) {
                        return Base64.encodeToString(bytes, Base64.NO_WRAP);
                    }
                }, privateKeyFile, publicKeyFile);
                
                // 删除临时文件
                privateKeyFile.delete();
                publicKeyFile.delete();
                
                Log.d(TAG, "成功加载已保存的RSA密钥");
                LogManager.getInstance(context).d(TAG, "成功加载已保存的RSA密钥");
                return crypto;
            } catch (Exception e) {
                Log.e(TAG, "加载密钥失败，将生成新密钥：" + e.getMessage(), e);
                LogManager.getInstance(context).e(TAG, "加载密钥失败，将生成新密钥：" + e.getMessage());
                // 加载失败，生成新密钥
                return generateAndSaveCrypto();
            }
        } else {
            // 没有保存的密钥，生成新密钥
            return generateAndSaveCrypto();
        }
    }

    private AdbCrypto generateAndSaveCrypto() throws NoSuchAlgorithmException {
        // 生成新的密钥对
        final AdbBase64 base64 = new AdbBase64() {
            @Override
            public String encodeToString(byte[] bytes) {
                return Base64.encodeToString(bytes, Base64.NO_WRAP);
            }
        };
        
        AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(base64);

        // 保存密钥
        try {
            // 创建临时文件来保存密钥
            File privateKeyFile = File.createTempFile("private", ".key", context.getCacheDir());
            File publicKeyFile = File.createTempFile("public", ".key", context.getCacheDir());
            
            // 保存密钥到临时文件
            crypto.saveAdbKeyPair(privateKeyFile, publicKeyFile);
            
            // 读取密钥数据
            byte[] privateKey = new byte[(int) privateKeyFile.length()];
            byte[] publicKey = new byte[(int) publicKeyFile.length()];
            FileInputStream privateIn = new FileInputStream(privateKeyFile);
            FileInputStream publicIn = new FileInputStream(publicKeyFile);
            privateIn.read(privateKey);
            publicIn.read(publicKey);
            privateIn.close();
            publicIn.close();
            
            // 编码并保存到SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_PRIVATE_KEY, Base64.encodeToString(privateKey, Base64.NO_WRAP));
            editor.putString(KEY_PUBLIC_KEY, Base64.encodeToString(publicKey, Base64.NO_WRAP));
            editor.apply();
            
            // 删除临时文件
            privateKeyFile.delete();
            publicKeyFile.delete();
            
            Log.d(TAG, "成功生成并保存新的RSA密钥");
            LogManager.getInstance(context).d(TAG, "成功生成并保存新的RSA密钥");
        } catch (Exception e) {
            Log.e(TAG, "保存密钥失败：" + e.getMessage(), e);
            LogManager.getInstance(context).e(TAG, "保存密钥失败：" + e.getMessage());
            // 保存失败不影响使用，只是下次需要重新授权
        }

        return crypto;
    }

    public synchronized void disconnect() {
        try {
            // 停止连接检查定时器
            stopConnectionChecks();
            
            // 关闭当前连接
            if (connection != null) {
                connection.close();
            }
            
            // 清空连接池
            while (!connectionPool.isEmpty()) {
                AdbConnection pooledConnection = connectionPool.poll();
                try {
                    if (pooledConnection != null) {
                        pooledConnection.close();
                    }
                } catch (Exception e) {
                    Log.d(TAG, "关闭池连接时出错：" + e.getMessage());
                    LogManager.getInstance(context).d(TAG, "关闭池连接时出错：" + e.getMessage());
                }
            }
            
            // 释放所有信号量
            while (poolSemaphore.availablePermits() < MAX_POOL_SIZE) {
                poolSemaphore.release();
            }
        } catch (IOException e) {
            Log.e(TAG, "ADB断开连接失败：" + e.getMessage(), e);
            LogManager.getInstance(context).e(TAG, "ADB断开连接失败：" + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "断开连接时出现异常：" + e.getMessage(), e);
            LogManager.getInstance(context).e(TAG, "断开连接时出现异常：" + e.getMessage());
        } finally {
            connection = null;
            connected = false;
            Log.d(TAG, "ADB连接已断开");
            LogManager.getInstance(context).d(TAG, "ADB连接已断开");
        }
    }

    public synchronized boolean isConnected() {
        return connected && connection != null;
    }

    /**
     * 验证ADB连接是否仍然有效
     * @return 连接是否有效
     */
    public synchronized boolean isConnectionValid() {
        if (!connected || connection == null) {
            return false;
        }
        
        try {
            // 通过执行一个简单的命令来验证连接是否有效
            AdbStream stream = connection.open("shell:");
            byte[] commandBytes = ("echo test\n").getBytes("UTF-8");
            stream.write(commandBytes);
            
            // 等待一小段时间
            Thread.sleep(500);
            
            // 读取响应
            InputStream inputStream = stream.getInputStream();
            byte[] buffer = new byte[1024];
            int bytesRead = inputStream.read(buffer);
            
            // 关闭流
            stream.close();
            
            return bytesRead > 0;
        } catch (Exception e) {
            Log.e(TAG, "验证ADB连接失败：" + e.getMessage(), e);
            LogManager.getInstance(context).e(TAG, "验证ADB连接失败：" + e.getMessage());
            // 连接无效，设置为断开状态
            connected = false;
            connection = null;
            return false;
        }
    }
}

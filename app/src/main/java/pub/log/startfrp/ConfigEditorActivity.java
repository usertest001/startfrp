package pub.log.startfrp;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class ConfigEditorActivity extends AppCompatActivity {
    private static final String TAG = "ConfigEditorActivity";
    private EditText editTextConfig;
    private Button btnSave;
    private String configFilePath;
    private boolean isRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config_editor);

        // 接收运行状态参数
        isRunning = getIntent().getBooleanExtra("isRunning", false);
        Log.d(TAG, "配置编辑器启动，当前FRP运行状态: " + (isRunning ? "运行中" : "已停止"));

        // 初始化视图
        editTextConfig = findViewById(R.id.et_config);
        btnSave = findViewById(R.id.btn_save);
        Button btnFiles = findViewById(R.id.btn_files);
        Button btnBack = findViewById(R.id.btn_back);

        // 初始化配置文件路径
        initConfigFilePath();

        // 加载配置文件
        loadConfigFile();

        // 设置按钮点击事件
        btnSave.setOnClickListener(v -> saveConfigFile());
        btnFiles.setOnClickListener(v -> startActivity(new Intent(ConfigEditorActivity.this, FileManagerActivity.class)));
        btnBack.setOnClickListener(v -> finish());

        // 检查运行状态并设置编辑功能
        checkRunningStatus();
    }

    private void initConfigFilePath() {
        String frpDir = getFrpDirPath();
        configFilePath = frpDir + File.separator + "frpc.toml";
        Log.d(TAG, "配置文件路径: " + configFilePath);
    }

    private String getFrpDirPath() {
        // 获取应用数据目录下的frp文件夹路径，与MainActivity保持一致
        try {
            // 首先尝试使用getDataDir()获取应用数据目录
            File dataDir = getDataDir();
            if (dataDir != null) {
                File frpDir = new File(dataDir, "frp");
                return frpDir.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取应用数据目录失败: " + e.getMessage());
        }
        // 备用路径
        return getFilesDir().getAbsolutePath() + File.separator + "frp";
    }

    private void loadConfigFile() {
        File configFile = new File(configFilePath);
        if (configFile.exists()) {
            try {
                FileInputStream fis = new FileInputStream(configFile);
                InputStreamReader isr = new InputStreamReader(fis, "UTF-8");

                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[1024];
                int length;
                while ((length = isr.read(buffer)) > 0) {
                    sb.append(buffer, 0, length);
                }

                editTextConfig.setText(sb.toString());
                Log.d(TAG, "配置文件加载成功");
            } catch (IOException e) {
                Log.e(TAG, "加载配置文件失败: " + e.getMessage());
                showToast("加载配置文件失败: " + e.getMessage());
                loadDefaultConfig();
            }
        } else {
            Log.d(TAG, "配置文件不存在，加载默认模板");
            loadDefaultConfig();
        }
    }

    private void loadDefaultConfig() {
        String defaultConfig = "# FRP 客户端配置示例\n\n[common]\nserver_addr = \"127.0.0.1\"\nserver_port = 7000\n\n# 示例代理配置\n[[proxies]]\nname = \"ssh\"\ntype = \"tcp\"\nlocal_ip = \"127.0.0.1\"\nlocal_port = 22\nremote_port = 6000\n";
        editTextConfig.setText(defaultConfig);
        Log.d(TAG, "已加载默认配置模板");
    }

    private void saveConfigFile() {
        if (isRunning) {
            showToast("FRP服务正在运行，无法保存配置");
            return;
        }

        String configContent = editTextConfig.getText().toString();
        File configFile = new File(configFilePath);

        // 确保目录存在
        File parentDir = configFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (parentDir.mkdirs()) {
                Log.d(TAG, "FRP目录创建成功");
            } else {
                Log.e(TAG, "FRP目录创建失败");
                showToast("无法创建配置文件目录");
                return;
            }
        }

        try {
            FileOutputStream fos = new FileOutputStream(configFile);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write(configContent);
            osw.flush();
            osw.close();
            fos.close();

            Log.d(TAG, "配置文件保存成功");
            showToast("配置文件保存成功");
            finish();
        } catch (IOException e) {
            Log.e(TAG, "保存配置文件失败: " + e.getMessage());
            showToast("保存配置文件失败: " + e.getMessage());
        }
    }

    private void checkRunningStatus() {
        if (isRunning) {
            editTextConfig.setEnabled(false);
            btnSave.setEnabled(false);
            showToast("FRP服务正在运行，无法编辑配置");
            Log.d(TAG, "FRP服务运行中，已禁用配置编辑功能");
        } else {
            editTextConfig.setEnabled(true);
            btnSave.setEnabled(true);
            Log.d(TAG, "FRP服务未运行，启用配置编辑功能");
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
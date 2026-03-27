package pub.log.startfrp;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 自定义命令设置活动
 * 用于设置自定义命令，支持开机自动执行
 * @author BY YYX
 */
public class CustomCommandActivity extends AppCompatActivity {

    private static final String TAG = "CustomCommandActivity";
    private static final String PREF_NAME = "custom_command_prefs";
    private static final String KEY_CUSTOM_COMMAND = "custom_command";
    private static final String KEY_ENABLE_CUSTOM_COMMAND = "enable_custom_command";

    private EditText etCustomCommand;
    private Switch swEnableCustomCommand;
    private Button btnSave;
    private Button btnBack;
    private SharedPreferences sharedPreferences;
    private LogManager logManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_command);

        // 初始化日志管理器
        logManager = LogManager.getInstance(this);
        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        // 初始化视图
        initViews();
        // 设置按钮点击事件
        setupButtonListeners();
        // 加载保存的设置
        loadSavedSettings();
    }

    /**
     * 初始化视图
     */
    private void initViews() {
        etCustomCommand = findViewById(R.id.etCustomCommand);
        swEnableCustomCommand = findViewById(R.id.swEnableCustomCommand);
        btnSave = findViewById(R.id.btnSave);
        btnBack = findViewById(R.id.btnBack);
    }

    /**
     * 设置按钮点击事件
     */
    private void setupButtonListeners() {
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 关闭当前Activity，返回上一级
                finish();
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 保存自定义命令设置
                saveCustomCommandSettings();
            }
        });
    }

    /**
     * 加载保存的设置
     */
    private void loadSavedSettings() {
        try {
            // 加载自定义命令
            String customCommand = sharedPreferences.getString(KEY_CUSTOM_COMMAND, "");
            etCustomCommand.setText(customCommand);

            // 加载启用状态
            boolean enableCustomCommand = sharedPreferences.getBoolean(KEY_ENABLE_CUSTOM_COMMAND, false);
            swEnableCustomCommand.setChecked(enableCustomCommand);

            logManager.d(TAG, "已加载保存的自定义命令设置");
        } catch (Exception e) {
            logManager.e(TAG, "加载设置时出错: " + e.getMessage(), e);
            showToast("加载设置失败: " + e.getMessage());
        }
    }

    /**
     * 保存自定义命令设置
     */
    private void saveCustomCommandSettings() {
        try {
            // 获取用户输入的自定义命令
            String customCommand = etCustomCommand.getText().toString().trim();
            // 获取启用状态
            boolean enableCustomCommand = swEnableCustomCommand.isChecked();

            // 保存设置
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(KEY_CUSTOM_COMMAND, customCommand);
            editor.putBoolean(KEY_ENABLE_CUSTOM_COMMAND, enableCustomCommand);
            editor.apply();

            logManager.d(TAG, "已保存自定义命令设置: " + (enableCustomCommand ? "启用" : "禁用") + ", 命令: " + customCommand);
            showToast("设置已保存");
        } catch (Exception e) {
            logManager.e(TAG, "保存设置时出错: " + e.getMessage(), e);
            showToast("保存设置失败: " + e.getMessage());
        }
    }

    /**
     * 显示Toast消息
     */
    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * 获取自定义命令
     * @return 自定义命令字符串
     */
    public static String getCustomCommand(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getString(KEY_CUSTOM_COMMAND, "");
    }

    /**
     * 检查是否启用自定义命令
     * @return 是否启用自定义命令
     */
    public static boolean isCustomCommandEnabled(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ENABLE_CUSTOM_COMMAND, false);
    }
}

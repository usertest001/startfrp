package pub.log.startfrp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class FileManagerActivity extends AppCompatActivity {
    private static final String TAG = "FileManagerActivity";
    private static final int PICK_FILE_REQUEST = 1;

    private ListView listFiles;
    private TextView tvNoFiles;
    private List<File> crtKeyFiles;
    private FileListAdapter adapter;

    private String frpDirPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        // 获取FRP目录路径
        frpDirPath = getFrpDirPath();
        Log.d(TAG, "FRP目录路径: " + frpDirPath);

        // 初始化UI组件
        initViews();

        // 设置点击事件
        setClickListeners();

        // 检查并更新文件状态
        updateFileStatus();
    }

    private void initViews() {
        listFiles = findViewById(R.id.list_files);
        tvNoFiles = findViewById(R.id.tv_no_files);
        crtKeyFiles = new ArrayList<>();
    }

    private void setClickListeners() {
        // 返回按钮
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 添加文件按钮
        findViewById(R.id.btn_add_file).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilePicker();
            }
        });

        // 初始化文件列表适配器
        adapter = new FileListAdapter(this, crtKeyFiles);
        listFiles.setAdapter(adapter);

        // 设置文件删除监听器
        adapter.setOnFileDeletedListener(this::updateFileStatus);
    }

    private void showFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // 获取文件名
                String fileName = getFileNameFromUri(uri);
                if (fileName != null) {
                    // 取消文件类型限制，允许上传任意文件
                    copyFileFromUri(uri, fileName);
                }
            }
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            // 从内容URI中获取文件名
            String[] projection = {android.provider.OpenableColumns.DISPLAY_NAME};
            try (android.database.Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "从内容URI获取文件名失败: " + e.getMessage());
            }
        } else {
            // 从文件URI中获取文件名
            result = uri.getLastPathSegment();
        }
        return result;
    }

    private void copyFileFromUri(Uri uri, String fileName) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream != null) {
                // 确保FRP目录存在
                File frpDir = new File(frpDirPath);
                if (!frpDir.exists()) {
                    if (frpDir.mkdirs()) {
                        Log.d(TAG, "FRP目录创建成功");
                    } else {
                        Log.e(TAG, "FRP目录创建失败");
                        showToast("无法创建FRP目录");
                        return;
                    }
                }

                // 创建目标文件
                File targetFile = new File(frpDir, fileName);
                OutputStream outputStream = new FileOutputStream(targetFile);

                // 复制文件内容
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }

                outputStream.flush();
                outputStream.close();
                inputStream.close();

                Log.d(TAG, "文件复制成功: " + targetFile.getAbsolutePath());
                showToast("文件上传成功");

                // 更新文件状态
                updateFileStatus();
            }
        } catch (IOException e) {
            Log.e(TAG, "文件复制失败: " + e.getMessage());
            showToast("文件上传失败: " + e.getMessage());
        }
    }



    private void updateFileStatus() {
        // 清空当前文件列表
        crtKeyFiles.clear();

        // 获取FRP目录下的所有文件
        File frpDir = new File(frpDirPath);
        File[] files = frpDir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    String fileName = file.getName();
                    // 筛选出后缀为.crt或.key的文件
                    if (fileName.endsWith(".crt") || fileName.endsWith(".key")) {
                        crtKeyFiles.add(file);
                    }
                }
            }
        }

        // 更新列表显示
        adapter.notifyDataSetChanged();

        // 如果没有符合条件的文件，显示"没有文件"提示
        if (crtKeyFiles.isEmpty()) {
            listFiles.setVisibility(View.GONE);
            tvNoFiles.setVisibility(View.VISIBLE);
        } else {
            listFiles.setVisibility(View.VISIBLE);
            tvNoFiles.setVisibility(View.GONE);
        }
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

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
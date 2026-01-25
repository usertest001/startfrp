package pub.log.startfrp;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

public class FileListAdapter extends ArrayAdapter<File> {
    private static final String TAG = "FileListAdapter";
    private Context context;
    private List<File> files;
    private OnFileDeletedListener onFileDeletedListener;

    public interface OnFileDeletedListener {
        void onFileDeleted();
    }

    public FileListAdapter(Context context, List<File> files) {
        super(context, 0, files);
        this.context = context;
        this.files = files;
    }

    public void setOnFileDeletedListener(OnFileDeletedListener listener) {
        this.onFileDeletedListener = listener;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        // 重用视图
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_file_list, parent, false);
            holder = new ViewHolder();
            holder.tvFileName = convertView.findViewById(R.id.tv_file_name);
            holder.tvFilePath = convertView.findViewById(R.id.tv_file_path);
            holder.btnCopyPath = convertView.findViewById(R.id.btn_copy_path);
            holder.btnDeleteFile = convertView.findViewById(R.id.btn_delete_file);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        // 获取当前文件
        File file = getItem(position);
        if (file != null) {
            // 设置文件名和路径
            holder.tvFileName.setText(file.getName());
            holder.tvFilePath.setText(file.getAbsolutePath());

            // 设置复制路径按钮点击事件
            holder.btnCopyPath.setOnClickListener(v -> copyPathToClipboard(file.getAbsolutePath()));

            // 设置删除文件按钮点击事件
            holder.btnDeleteFile.setOnClickListener(v -> deleteFile(file));
        }

        return convertView;
    }

    /**
     * 复制文件路径到剪贴板
     */
    private void copyPathToClipboard(String filePath) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("file_path", filePath);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, "路径已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    /**
     * 删除文件
     */
    private void deleteFile(File file) {
        if (file.exists()) {
            if (file.delete()) {
                Log.d(TAG, "文件删除成功: " + file.getAbsolutePath());
                Toast.makeText(context, "文件删除成功", Toast.LENGTH_SHORT).show();
                // 通知数据变更
                if (onFileDeletedListener != null) {
                    onFileDeletedListener.onFileDeleted();
                }
            } else {
                Log.e(TAG, "文件删除失败: " + file.getAbsolutePath());
                Toast.makeText(context, "文件删除失败", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 视图持有者模式
     */
    private static class ViewHolder {
        TextView tvFileName;
        TextView tvFilePath;
        ImageButton btnCopyPath;
        ImageButton btnDeleteFile;
    }
}
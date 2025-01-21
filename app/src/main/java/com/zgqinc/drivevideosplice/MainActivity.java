package com.zgqinc.drivevideosplice;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SELECT_FOLDER = 100;
    private Button selectFolderButton;
    private Button aboutButton;
    private ProgressBar progressBar;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        selectFolderButton = findViewById(R.id.select_folder_button);
        aboutButton = findViewById(R.id.about_button);  // 初始化“关于”按钮
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);

        selectFolderButton.setOnClickListener(v -> openFolderPicker());

        aboutButton.setOnClickListener(v -> showAboutDialog());

    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, REQUEST_CODE_SELECT_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SELECT_FOLDER && resultCode == Activity.RESULT_OK && data != null) {
            List<Uri> selectedUris = new ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    selectedUris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                selectedUris.add(data.getData());
            }
            handleFiles(selectedUris);
        }
    }

    private void handleFiles(List<Uri> uris) {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("正在处理文件...");
        new Thread(() -> {
            List<File> videoFiles = new ArrayList<>();
            for (Uri uri : uris) {
                DocumentFile documentFile = DocumentFile.fromSingleUri(this, uri);
                if (documentFile != null && isVideoFile(documentFile.getName())) {
                    File localFile = copyFileToLocalCache(documentFile);
                    if (localFile != null) {
                        videoFiles.add(localFile);
                    }
                }
            }
            if (videoFiles.isEmpty()) {
                runOnUiThread(() -> showError("未找到有效的视频文件"));
                return;
            }

            String inputListPath = generateConcatFile(videoFiles);
            String outputPath = getOutputPath(videoFiles.get(0).getName());
            mergeVideos(inputListPath, outputPath);
        }).start();
    }

    private boolean isVideoFile(String fileName) {
        return fileName != null && (fileName.toLowerCase().endsWith(".mp4") || fileName.toLowerCase().endsWith(".avi"));
    }

    private File copyFileToLocalCache(DocumentFile documentFile) {
        File localFile = new File(getCacheDir(), documentFile.getName());
        runOnUiThread(() -> statusText.setText("正在复制文件：" + documentFile.getName()));

        try (InputStream in = getContentResolver().openInputStream(documentFile.getUri());
             OutputStream out = new FileOutputStream(localFile)) {

            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }

        } catch (IOException e) {
            Log.e("CopyFile", "Error copying file: " + documentFile.getName(), e);
            return null;
        }
        return localFile;
    }

    private String generateConcatFile(List<File> videoFiles) {
        File concatFile = new File(getCacheDir(), "concat_list.txt");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(concatFile))) {
            for (File file : videoFiles) {
                writer.write("file '" + file.getAbsolutePath() + "'\n");
            }
        } catch (IOException e) {
            Log.e("GenerateConcatFile", "Error generating concat file", e);
        }
        return concatFile.getAbsolutePath();
    }

    private String getOutputPath(String firstFileName) {
        String timestamp = extractTimestamp(firstFileName);
        File outputDir = new File(getExternalFilesDir(null), "Movies/Driving_Recorder");
        if (!outputDir.exists()) outputDir.mkdirs();
        return new File(outputDir, timestamp + "_merged_video.mp4").getAbsolutePath();
    }

    private String extractTimestamp(String filename) {
        String[] parts = filename.split("-");
        return parts.length > 1 ? parts[0] + parts[1] : filename;
    }

    private void mergeVideos(String inputListPath, String outputPath) {
        File mainDir = new File("/storage/emulated/0/Movies/Driving_Recorder");
        String command = "-f concat -safe 0 -i \"" + inputListPath + "\" -c copy \"" + outputPath + "\"";

        FFmpegKit.executeAsync(command, session -> {
            ReturnCode returnCode = session.getReturnCode();
            runOnUiThread(() -> {
                if (ReturnCode.isSuccess(returnCode)) {
                    statusText.setText("视频拼接成功，输出路径：" + mainDir);
                    copyToMainDirectory(outputPath);
                } else {
                    statusText.setText("视频拼接失败：" + session.getFailStackTrace());
                }
                clearPrivateDirectoryCache(inputListPath, outputPath);
                progressBar.setVisibility(View.GONE);
            });
        });
    }

    private void copyToMainDirectory(String outputPath) {
        File videoFile = new File(outputPath);
        if (!videoFile.exists()) return;

        File mainDir = new File("/storage/emulated/0/Movies/Driving_Recorder");
        if (!mainDir.exists() && !mainDir.mkdirs()) {
            Log.e("CopyFile", "无法创建主目录");
            return;
        }

        File destination = new File(mainDir, videoFile.getName());
        try {
            if (!videoFile.renameTo(destination)) {
                try (InputStream in = new FileInputStream(videoFile);
                     OutputStream out = new FileOutputStream(destination)) {

                    byte[] buffer = new byte[4096];
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                }
            }
        } catch (IOException e) {
            Log.e("CopyFile", "文件复制失败", e);
        }
    }

    private void clearPrivateDirectoryCache(String concatFilePath, String videoFilePath) {
        deleteFile(new File(concatFilePath));
        deleteFile(new File(videoFilePath));
    }

    private void deleteFile(File file) {
        if (file.exists() && file.delete()) {
            Log.d("FileCleanup", "已删除文件：" + file.getName());
        } else {
            Log.d("FileCleanup", "未能删除文件：" + file.getName());
        }
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
            progressBar.setVisibility(View.GONE);
        });
    }

    private void showAboutDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_about, null);

        TextView dialogMessage = dialogView.findViewById(R.id.dialog_message);
        SpannableString spannableString = new SpannableString(dialogMessage.getText());
        setLinkClickable(spannableString, "https://ffmpeg.org", "FFmpeg");
        setLinkClickable(spannableString, "https://github.com/arthenica/ffmpeg-kit", "FFmpegKit");
        setLinkClickable(spannableString, "https://domain.zgqinc.gq", "domain.zgqinc.gq");
        dialogMessage.setText(spannableString);
        dialogMessage.setMovementMethod(LinkMovementMethod.getInstance());

        TextView dialogCommand = dialogView.findViewById(R.id.dialog_command);
        setCommandClickable(dialogCommand, "ffmpeg -f concat -safe 0 -i [/path/to/concat.txt] -c copy [/path/to/output.mp4]");

        TextView dialogAbout4 = dialogView.findViewById(R.id.dialog_message2);
        dialogAbout4.setText(getString(R.string.about4));

        TextView dialogCommand2 = dialogView.findViewById(R.id.dialog_message3);
        setCommandClickable(dialogCommand2, "find [/path/to/video_files_dir] -name \"*.MP4\" -printf \'%T@ %p\\n\' | sort -n | awk \'{print \"file \\x27\" $2 \"\\x27\"}\' > [/path/to/concat.txt]");

        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setCancelable(true)
                .setPositiveButton("确定", (dialog, which) -> dialog.dismiss());
        dialogBuilder.show();
    }

    private void setLinkClickable(SpannableString spannableString, String url, String text) {
        int start = spannableString.toString().indexOf(text);
        int end = start + text.length();
        if (start >= 0) {
            spannableString.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void setCommandClickable(TextView textView, String commandText) {
        textView.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("FFmpeg Command", commandText);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(MainActivity.this, "命令已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
    }

}

package com.tumuyan.ncnn.realsr;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class DirectoryProcessActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_INPUT_DIR = 1001;
    private static final int REQUEST_CODE_OUTPUT_DIR = 1002;

    private Uri inputDirUri;
    private Uri outputDirUri;

    private EditText etInputDirPath, etOutputDirPath;
    private TextView tvLog, tvTitle;
    private Button btnSelectInputDir, btnSelectOutputDir, btnStartProcess, btnStopProcess;
    private Spinner spinnerModel;
    private CheckBox cbAutoOutput;

    private ProcessingService processingService;
    private boolean isBound = false;

    private String dir;
    private String cache_dir;
    private int tileSize;
    private boolean useCPU;
    private String threadCount;
    private int mnnBackend;
    private int notifySetting;
    private boolean keepScreen;
    private CommandListManager commandListManager;
    private String[] commandList;
    private ProgressLogHelper progressLog;
    private String savePath;
    private boolean isUpdatingOutputPath = false;
    private boolean isUpdatingInputPath = false;
    private boolean isProcessing = false;
    private int dirNameFormat = 0;
    private int dirOutputFormat = 0;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            ProcessingService.LocalBinder binder = (ProcessingService.LocalBinder) service;
            processingService = binder.getService();
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_directory_process);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        cache_dir = this.getCacheDir().getAbsolutePath();
        dir = cache_dir + "/realsr";

        initViews();
        loadSettings();
        setupListeners();

        Intent serviceIntent = new Intent(this, ProcessingService.class);
        bindService(serviceIntent, connection, BIND_AUTO_CREATE);
    }

    private void initViews() {
        etInputDirPath = findViewById(R.id.et_input_dir_path);
        etOutputDirPath = findViewById(R.id.et_output_dir_path);
        tvLog = findViewById(R.id.tv_log);

        btnSelectInputDir = findViewById(R.id.btn_select_input_dir);
        btnSelectOutputDir = findViewById(R.id.btn_select_output_dir);
        btnStartProcess = findViewById(R.id.btn_start_process);
        btnStopProcess = findViewById(R.id.btn_stop_process);

        spinnerModel = findViewById(R.id.spinner_model);
        cbAutoOutput = findViewById(R.id.cb_auto_output);
        cbAutoOutput.setText(R.string.dir_auto_output_label);

        setTitle(R.string.dir_process_title);
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("config", MODE_PRIVATE);
        tileSize = sp.getInt("tileSize", 0);
        useCPU = sp.getBoolean("useCPU", false);
        threadCount = sp.getString("threadCount", "");
        mnnBackend = sp.getInt("mnnBackend", 3);
        notifySetting = sp.getInt("notify", 2);
        keepScreen = sp.getBoolean("keepScreen", false);

        String galleryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                + File.separator + "RealSR";
        savePath = sp.getString("savePath", "");
        if (savePath.isEmpty())
            savePath = galleryPath;

        dirNameFormat = sp.getInt("name3", 0);
        dirOutputFormat = sp.getInt("dirOutputFormat", 0);

        String[] presetLabels = getResources().getStringArray(R.array.style_array);
        boolean useCustomLabel = sp.getBoolean("useCustomLabel", false);
        commandListManager = new CommandListManager(presetLabels,
                sp.getString("extraPath", "").trim(),
                sp.getString("extraCommand", "").trim(),
                sp.getString("classicalFilters", getString(R.string.default_classical_filters)).split("\\s+"),
                sp.getString("magickFilters", getString(R.string.default_magick_filters)).split("\\s+"));
        commandListManager.loadCustomLabels(sp.getString("customLabels", ""));

        Set<String> hiddenPrograms = sp.getStringSet("hiddenPrograms", new HashSet<String>());

        // 只使用支持目录批量处理的命令（应用隐藏程序筛选）
        int totalCommands = commandListManager.getCommandCount();
        commandList = commandListManager.getDirectorySupportedCommands(hiddenPrograms);
        String[] displayLabels = commandListManager.getDirectorySupportedLabels(useCustomLabel, hiddenPrograms);

        // 记录过滤信息到日志
        Log.i("DirectoryProcess", "Total commands: " + totalCommands + 
              ", Directory supported: " + commandList.length +
              ", Filtered out: " + (totalCommands - commandList.length));

        if (commandList.length == 0) {
            Toast.makeText(this, R.string.dir_no_supported_commands, Toast.LENGTH_LONG).show();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, displayLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(adapter);

        spinnerModel.setSelection(0);
    }

    private void setupListeners() {
        btnSelectInputDir.setOnClickListener(v -> selectDirectory(REQUEST_CODE_INPUT_DIR));
        btnSelectOutputDir.setOnClickListener(v -> selectDirectory(REQUEST_CODE_OUTPUT_DIR));

        btnStartProcess.setOnClickListener(v -> startBatchProcess());
        btnStopProcess.setOnClickListener(v -> stopBatchProcess());

        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                updateStartButtonState();
                // 当模型改变时，如果自动勾选则更新输出路径
                if (cbAutoOutput.isChecked()) {
                    String inputPath = etInputDirPath.getText().toString().trim();
                    if (!inputPath.isEmpty()) {
                        updateAutoOutputPath(inputPath);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        etInputDirPath.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdatingInputPath) {
                    // 用户手动编辑输入路径 → 退出 SAF 模式，改用手动路径校验
                    inputDirUri = null;
                }
                String path = s.toString().trim();
                if (!path.isEmpty()) {
                    File file = new File(path);
                    if (file.exists() && file.isDirectory()) {
                        updateAutoOutputPath(path);
                    }
                }
                updateStartButtonState();
            }
        });

        etOutputDirPath.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdatingOutputPath) {
                    // 用户手动编辑输出路径 → 退出 SAF 模式
                    outputDirUri = null;
                    if (cbAutoOutput.isChecked()) {
                        cbAutoOutput.setChecked(false);
                    }
                }
                updateStartButtonState();
            }
        });

        cbAutoOutput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                String inputPath = etInputDirPath.getText().toString().trim();
                if (!inputPath.isEmpty()) {
                    updateAutoOutputPath(inputPath);
                }
            }
        });
    }

    private void updateAutoOutputPath(String inputPath) {
        if (cbAutoOutput.isChecked()) {
            // 叶子目录名优先取自 SAF tree 信息，回退到路径/文本；
            // 避免对 content:// 形式调用 new File(...).getName() 得到无意义结果。
            String dirName = currentInputLeafName(inputPath);

            // 根据设置生成目录名（独立目录名选项）
            int modelIndex = spinnerModel.getSelectedItemPosition();
            String commandName = "";
            if (modelIndex >= 0 && modelIndex < commandList.length) {
                String cmd = commandList[modelIndex];
                commandName = extractModelName(cmd);
            }

            String timeStr = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());

            switch (dirNameFormat) {
                case 0: // Input Directory Name → 目录名
                    break;
                case 1: // Input Directory Name-Command → 目录名-命令
                    dirName = dirName + "-" + commandName;
                    break;
                case 2: // Input Directory Name-Command-Time → 目录名-命令-时间
                    dirName = dirName + "-" + commandName + "-" + timeStr;
                    break;
                case 3: // Input Directory Name-Time → 目录名-时间
                    dirName = dirName + "-" + timeStr;
                    break;
                default:
                    break;
            }

            String autoOutputPath = savePath + File.separator + dirName;
            // 自动输出是 savePath 下的真实路径，不是 SAF 授权目录，清掉可能残留的输出 tree URI
            outputDirUri = null;
            isUpdatingOutputPath = true;
            etOutputDirPath.setText(autoOutputPath);
            isUpdatingOutputPath = false;
            
            // 更新复选框文本显示选项值
            updateCheckboxText();
        }
    }

    private void updateCheckboxText() {
        // 获取当前选中的目录名格式选项
        String[] nameOptions = getResources().getStringArray(R.array.name3);
        if (dirNameFormat >= 0 && dirNameFormat < nameOptions.length) {
            String optionName = nameOptions[dirNameFormat];
            cbAutoOutput.setText(getString(R.string.dir_auto_output_format, optionName));
        } else {
            cbAutoOutput.setText(R.string.dir_auto_output_label);
        }
    }

    private void selectDirectory(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(DocumentsContract.EXTRA_PROMPT, requestCode == REQUEST_CODE_INPUT_DIR ?
                getString(R.string.dir_select_input_prompt) : getString(R.string.dir_select_output_prompt));

        // 若已持有对应 SAF tree URI，作为选择器的初始位置（正确的 SAF 用法，
        // 不再用 Uri.fromFile(new File(...))——后者在新版系统会被忽略）。
        Uri initialUri = (requestCode == REQUEST_CODE_INPUT_DIR) ? inputDirUri : outputDirUri;
        if (initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }

        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();

            if (requestCode == REQUEST_CODE_INPUT_DIR) {
                inputDirUri = treeUri;
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                String path = resolveTreePath(treeUri);
                isUpdatingInputPath = true;
                etInputDirPath.setText(path != null ? path : treeUri.toString());
                isUpdatingInputPath = false;
                // SAF 选目录后主动刷新自动输出路径：此前仅依赖 File.isDirectory() 触发，
                // 对 SD 卡 / content 目录不会生效，会导致输出为空、开始按钮无法点亮。
                if (cbAutoOutput.isChecked()) {
                    updateAutoOutputPath(etInputDirPath.getText().toString().trim());
                }
            } else if (requestCode == REQUEST_CODE_OUTPUT_DIR) {
                outputDirUri = treeUri;
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                String path = resolveTreePath(treeUri);
                isUpdatingOutputPath = true;
                etOutputDirPath.setText(path != null ? path : treeUri.toString());
                isUpdatingOutputPath = false;
                if (cbAutoOutput.isChecked()) {
                    cbAutoOutput.setChecked(false);
                }
            }
            updateStartButtonState();
        }
    }

    /**
     * 把 SAF tree URI 解析为真实文件系统路径（供 native 程序使用）；无法解析时返回 {@code null}。
     * 解析规则见 {@link SafDirectoryResolver#resolveFilesystemPath(String, String)}（可单元测试）。
     */
    private String resolveTreePath(Uri treeUri) {
        if (treeUri == null) {
            return null;
        }
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            String primaryRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
            return SafDirectoryResolver.resolveFilesystemPath(docId, primaryRoot);
        } catch (Exception e) {
            Log.w("DirectoryProcess", "resolveTreePath failed: " + treeUri, e);
            return null;
        }
    }

    /**
     * 取当前输入目录的叶子名（用于自动输出命名）：
     * 持有 SAF tree URI 时优先取自 tree 信息，否则回退到路径/文本的最后一段，最终回退到 "output"。
     */
    private String currentInputLeafName(String inputPathOrDisplay) {
        if (inputDirUri != null) {
            try {
                String leaf = SafDirectoryResolver.leafName(DocumentsContract.getTreeDocumentId(inputDirUri));
                if (!leaf.isEmpty()) {
                    return leaf;
                }
            } catch (Exception ignored) {
            }
        }
        if (inputPathOrDisplay != null) {
            String name = new File(inputPathOrDisplay).getName();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return "output";
    }

    private void updateStartButtonState() {
        String inputPath = etInputDirPath.getText().toString().trim();
        String outputPath = etOutputDirPath.getText().toString().trim();

        // 合法性以“是否持有 SAF 授权”为准；仅在手动输入（无 tree URI）时才回退到 File 校验。
        // 不再对 SAF 选出的路径调用 new File(...).exists()——那正是 SD 卡 / 非 primary 存储 /
        // 厂商文档提供器被误判为非法路径的根因。
        boolean inputManualDir = inputDirUri == null && !inputPath.isEmpty()
                && new File(inputPath).isDirectory();
        boolean inputValid = SafDirectoryResolver.isInputDirValid(inputDirUri != null, inputManualDir);
        boolean outputValid = SafDirectoryResolver.isOutputDirValid(outputDirUri != null, !outputPath.isEmpty());

        int modelIndex = spinnerModel.getSelectedItemPosition();
        boolean modelAvailable = commandList != null && commandList.length > 0
                && modelIndex >= 0 && modelIndex < commandList.length;

        btnStartProcess.setEnabled(SafDirectoryResolver.canStart(inputValid, outputValid, modelAvailable));
    }

    private void startBatchProcess() {
        if (!isBound || processingService == null) {
            Toast.makeText(this, R.string.dir_service_error, Toast.LENGTH_SHORT).show();
            return;
        }

        String inputPath = etInputDirPath.getText().toString().trim();
        String outputPath = etOutputDirPath.getText().toString().trim();

        // 解析“执行用”的真实文件系统路径：SAF 模式从 tree URI 解析，手动模式直接用文本。
        // native 程序只能处理真实文件系统路径，无法消费 content:// URI。
        String execInputPath;
        if (inputDirUri != null) {
            execInputPath = resolveTreePath(inputDirUri);
            if (execInputPath == null) {
                Toast.makeText(this, R.string.dir_input_unresolvable, Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            if (inputPath.isEmpty()) {
                Toast.makeText(this, R.string.dir_input_path_error, Toast.LENGTH_SHORT).show();
                return;
            }
            File inputDir = new File(inputPath);
            if (!inputDir.exists() || !inputDir.isDirectory()) {
                Toast.makeText(this, R.string.dir_input_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            execInputPath = inputPath;
        }

        String execOutputPath;
        if (outputDirUri != null) {
            execOutputPath = resolveTreePath(outputDirUri);
            if (execOutputPath == null) {
                Toast.makeText(this, R.string.dir_output_unresolvable, Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            if (outputPath.isEmpty()) {
                Toast.makeText(this, R.string.dir_output_path_error, Toast.LENGTH_SHORT).show();
                return;
            }
            execOutputPath = outputPath;
        }

        // 输出目录可能尚不存在（自动命名 / 新建目录），尽力创建。
        try {
            new File(execOutputPath).mkdirs();
        } catch (Exception ignored) {
        }

        int modelIndex = spinnerModel.getSelectedItemPosition();
        if (modelIndex < 0 || modelIndex >= commandList.length) {
            Toast.makeText(this, R.string.dir_model_error, Toast.LENGTH_SHORT).show();
            return;
        }

        String baseCommand = commandList[modelIndex];
        StringBuilder cmdBuilder = new StringBuilder(baseCommand);

        if (baseCommand.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.+")) {
            if (tileSize > 0 && !baseCommand.contains(" -t "))
                cmdBuilder.append(" -t ").append(tileSize);
            if (!threadCount.isEmpty() && !baseCommand.contains(" -j "))
                cmdBuilder.append(" -j ").append(threadCount);
            if (useCPU && !baseCommand.startsWith("./srmd") && !baseCommand.startsWith("./mnnsr")
                    && !baseCommand.contains(" -g "))
                cmdBuilder.append(" -g -1");
            if (baseCommand.startsWith("./mnnsr") && !baseCommand.contains(" -b ")) {
                cmdBuilder.append(" -b ").append(mnnBackend);
            }
            String[] dirFormats = getResources().getStringArray(R.array.dir_output_format);
            if (dirOutputFormat > 0 && dirOutputFormat < dirFormats.length && !baseCommand.contains(" -f ")) {
                cmdBuilder.append(" -f ").append(dirFormats[dirOutputFormat]);
            }
        } else if (baseCommand.startsWith("./Anime4k")) {
            String[] dirFormats = getResources().getStringArray(R.array.dir_output_format);
            if (dirOutputFormat > 0 && dirOutputFormat < dirFormats.length && !baseCommand.contains(" -E ")) {
                cmdBuilder.append(" -E .").append(dirFormats[dirOutputFormat]);
            }
        }

        String finalCmd = cmdBuilder.toString();

        String safeInputPath = ShellUtils.escapeShellArgument(execInputPath + "/");
        String safeOutputPath = ShellUtils.escapeShellArgument(execOutputPath);
        String execCmd = finalCmd.replace("input.png", safeInputPath)
                .replace("output.png", safeOutputPath);

        progressLog = new ProgressLogHelper();
        progressLog.reset();
        progressLog.appendLine(getString(R.string.dir_log_starting, execInputPath));
        progressLog.appendLine(getString(R.string.dir_log_output_to, execOutputPath));
        progressLog.appendLine("Command: " + execCmd);
        tvLog.setText(progressLog.getDisplayText());
        btnStartProcess.setEnabled(false);
        btnStopProcess.setEnabled(true);
        isProcessing = true;

        if (keepScreen) {
            tvLog.setKeepScreenOn(true);
        }

        processingService.startTask(execCmd, dir, notifySetting, new ImageProcessor.ProcessCallback() {
            @Override
            public void onProgress(String line) {
                runOnUiThread(() -> {
                    progressLog.appendLine(line);
                    tvLog.setText(progressLog.getDisplayText());
                });
            }

            @Override
            public void onCompleted(String result, boolean success) {
                runOnUiThread(() -> {
                    isProcessing = false;
                    btnStartProcess.setEnabled(true);
                    btnStopProcess.setEnabled(false);

                    if (keepScreen) {
                        tvLog.setKeepScreenOn(false);
                    }

                    String modelName = extractModelName(finalCmd);
                    boolean isNcnn = finalCmd.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.*");
                    String summary = progressLog.getCompletionSummary(success, modelName, isNcnn);
                    progressLog.appendLine(summary);

                    tvLog.setText(progressLog.getDisplayText());

                    if (success) {
                        Toast.makeText(DirectoryProcessActivity.this,
                                R.string.save_succeed, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    isProcessing = false;
                    btnStartProcess.setEnabled(true);
                    btnStopProcess.setEnabled(false);

                    if (keepScreen) {
                        tvLog.setKeepScreenOn(false);
                    }

                    progressLog.appendLine("Error: " + error);
                    tvLog.setText(progressLog.getDisplayText());
                });
            }
        });
    }

    private void stopBatchProcess() {
        if (isProcessing && processingService != null) {
            processingService.cancelTask();
            progressLog.appendLine("\n--- Process stopped by user ---");
            tvLog.setText(progressLog.getDisplayText());
            isProcessing = false;
            btnStartProcess.setEnabled(true);
            btnStopProcess.setEnabled(false);

            if (keepScreen) {
                tvLog.setKeepScreenOn(false);
            }
        }
    }

    private String extractModelName(String cmd) {
        if (cmd.matches(".+\\s-m(\\s+)\\S*models-.+")) {
            return cmd.replaceFirst(".+\\s-m(\\s+)\\S*models-(\\S+).*", "$2");
        } else if (cmd.startsWith("./Anime4k")) {
            String name = "Anime4k";
            if (cmd.contains("-w")) name += "-ACNet";
            if (cmd.contains("-H")) name += "-HDN";
            return name;
        } else if (cmd.startsWith("./realcugan-ncnn")) {
            return "Real-CUGAN";
        } else if (cmd.matches(".+\\s-m(\\s+)(bicubic|bilinear|nearest|avir|de-nearest).*")) {
            return cmd.replaceFirst(".+\\s-m(\\s+)(bicubic|bilinear|nearest|lancir|avir|de-nearest).*", "Classical-$2");
        } else if (cmd.startsWith("./magick input")) {
            return "Magick";
        } else if (cmd.startsWith("./resize-ncnn")) {
            return "Resize";
        }
        return "";
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新复选框文本显示当前选项值
        updateCheckboxText();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
}

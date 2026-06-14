package com.tumuyan.ncnn.realsr;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 图像处理器类，负责管理后台任务和进程执行。
 * 使用 ExecutorService 替代原始线程，提供更好的并发控制。
 *
 * <p>使用 per-task {@link CancellationToken} 替代共享 volatile boolean，
 * 确保取消旧任务时其回调不会干扰新任务的通知和 UI 状态。</p>
 */
public class ImageProcessor {
    private static final String TAG = "ImageProcessor";
    private final ExecutorService executorService;
    private Process currentProcess;
    private Future<?> currentTask;

    /**
     * 每个任务独立的取消令牌。
     * <p>
     * 当任务被取消或被新任务取代时，{@link #cancelled} 被置为 true，
     * 阻止该任务的 catch/finally 块触发任何回调（onError / onCompleted），
     * 从而避免旧任务回调干扰新任务的前台通知和 UI 状态。
     * </p>
     */
    static class CancellationToken {
        volatile boolean cancelled = false;

        void cancel() {
            cancelled = true;
        }
    }

    /** 当前活跃任务的令牌；volatile 保证跨线程可见性 */
    private volatile CancellationToken activeToken;

    public interface ProcessCallback {
        void onProgress(String line);
        void onCompleted(String result, boolean success);
        void onError(String error);
    }

    public ImageProcessor() {
        // 使用单线程执行器，确保任务按顺序执行（如果需要并发可以改为 newFixedThreadPool）
        this(Executors.newSingleThreadExecutor());
    }

    /** 包级可见构造函数，用于测试注入自定义 ExecutorService */
    ImageProcessor(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public void executeCommand(String command, String workingDir, ProcessCallback callback) {
        cancelCurrentTask();
        CancellationToken token = new CancellationToken();
        activeToken = token;

        currentTask = executorService.submit(() -> {
            runProcess(command, workingDir, callback, token);
        });
    }

    private void runProcess(String command, String workingDir, ProcessCallback callback, CancellationToken token) {
        StringBuilder resultBuilder = new StringBuilder();
        boolean success = false;

        try {
            Log.d(TAG, "Executing command: " + command);
            ProcessBuilder processBuilder = new ProcessBuilder("sh");
            processBuilder.directory(new File(workingDir));
            processBuilder.redirectErrorStream(true);

            synchronized (this) {
                currentProcess = processBuilder.start();
            }

            OutputStream os = currentProcess.getOutputStream();
            // workingDir 来自应用缓存目录（getCacheDir），参数来源可信
            String setupCmd = "cd " + workingDir + "; chmod +x *ncnn 2>/dev/null; export LD_LIBRARY_PATH=" + workingDir + ";\n";
            os.write(setupCmd.getBytes());
            os.write((command + "\n").getBytes());
            os.write("exit\n".getBytes());
            os.flush();
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Task interrupted");
                }
                // 过滤无用日志
                if (line.contains("unused DT entry")) continue;
                if (line.startsWith("CPU Group:")) continue;
                if (line.startsWith("(last_midr")) continue;
                if (line.startsWith("Error tunning info")) continue;

                Log.d(TAG, line);
                callback.onProgress(line);
                resultBuilder.append(line).append("\n");
            }

            int exitCode = currentProcess.waitFor();
            success = (exitCode == 0);
            Log.d(TAG, "Process finished with exit code: " + exitCode);

        } catch (InterruptedException e) {
            // 任务被中断：仅当本任务未被取消/取代时触发 onError
            if (!token.cancelled) {
                token.cancel();
                Log.w(TAG, "Process interrupted");
                callback.onError("Process interrupted");
            }
        } catch (InterruptedIOException e) {
            // 进程流因取消被关闭：仅当本任务未被取消/取代时触发 onError
            if (!token.cancelled) {
                token.cancel();
                Log.w(TAG, "Process stream closed by cancel, treating as normal cancellation");
                callback.onError("Task cancelled");
            }
        } catch (Exception e) {
            // 其他异常：标记 token 以阻止 finally 中的 onCompleted，修复 onError+onCompleted 双重回调
            if (!token.cancelled) {
                token.cancel();
                Log.e(TAG, "Error executing process", e);
                callback.onError(e.getMessage());
            }
        } finally {
            synchronized (this) {
                if (currentProcess != null) {
                    currentProcess.destroy();
                    currentProcess = null;
                }
            }
            // 仅在任务未被取消且未发生异常时触发 onCompleted
            if (!token.cancelled) {
                callback.onCompleted(resultBuilder.toString(), success);
            }
        }
    }

    public void cancelCurrentTask() {
        CancellationToken token = activeToken;
        if (token != null) {
            token.cancel();
        }
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
        synchronized (this) {
            if (currentProcess != null) {
                currentProcess.destroy();
                currentProcess = null;
            }
        }
    }

    public void shutdown() {
        executorService.shutdownNow();
    }
}

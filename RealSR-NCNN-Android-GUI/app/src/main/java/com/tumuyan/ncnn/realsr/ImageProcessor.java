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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图像处理器类，负责管理后台任务和进程执行。
 * 使用 ExecutorService 替代原始线程，提供更好的并发控制。
 *
 * <p>取消语义（关键不变式）：每个任务持有自己的取消令牌（{@link AtomicBoolean}），
 * 而不是共享同一个标志位。这样旧任务被取消或被新任务取代后，永远不会再触发任何回调，
 * 避免旧任务的 {@code onCompleted()} 覆盖新任务的状态，也避免取消被错误地当作错误上报。
 * 一个任务最多触发一次终结回调：正常结束触发 {@code onCompleted}，真实异常触发
 * {@code onError}，被取消/被中断则保持静默。</p>
 */
public class ImageProcessor {
    private static final String TAG = "ImageProcessor";
    private final ExecutorService executorService;
    private Process currentProcess;
    private Future<?> currentTask;
    // 当前任务的取消令牌。每次开始新任务都会换成一个全新的令牌，
    // 旧任务仍持有旧令牌，因此互不干扰。
    private volatile AtomicBoolean currentCancelled;

    public interface ProcessCallback {
        void onProgress(String line);
        void onCompleted(String result, boolean success);
        void onError(String error);
    }

    public ImageProcessor() {
        // 使用单线程执行器，确保任务按顺序执行（如果需要并发可以改为 newFixedThreadPool）
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public synchronized void executeCommand(String command, String workingDir, ProcessCallback callback) {
        // beginTask() 会作废上一个任务并返回本任务专属的取消令牌。
        final AtomicBoolean cancelled = beginTask();
        currentTask = executorService.submit(() -> runProcess(command, workingDir, callback, cancelled));
    }

    /**
     * 开始一个新的逻辑任务：作废并清理上一个任务，分配并记录本任务专属的取消令牌。
     * 不会启动进程，便于单元测试验证令牌的失效逻辑。
     */
    synchronized AtomicBoolean beginTask() {
        cancelCurrentTaskLocked();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        currentCancelled = cancelled;
        return cancelled;
    }

    private void runProcess(String command, String workingDir, ProcessCallback callback, AtomicBoolean cancelled) {
        StringBuilder resultBuilder = new StringBuilder();
        boolean success = false;
        boolean interrupted = false;
        String errorMessage = null;

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

        } catch (InterruptedException | InterruptedIOException e) {
            // 任务被取消（用户停止，或被下一个任务取代）。取消是正常操作，保持静默，
            // 不上报为错误，否则界面会先闪一条错误再被覆盖。
            interrupted = true;
            Log.w(TAG, "Process cancelled: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error executing process", e);
            errorMessage = e.getMessage();
        } finally {
            synchronized (this) {
                // 单线程执行器保证任务串行，这里销毁的必然是本任务自己的进程。
                if (currentProcess != null) {
                    currentProcess.destroy();
                    currentProcess = null;
                }
            }
            // 在锁外派发回调，避免回调重入 ImageProcessor 造成死锁。
            dispatchResult(cancelled.get(), interrupted, errorMessage, resultBuilder.toString(), success, callback);
        }
    }

    /**
     * 决定一个结束的任务应该触发哪个终结回调（如果有的话）。这是修复的核心规则，
     * 是纯函数（不依赖 Android/IO），便于穷举式单元测试：
     * <ul>
     *     <li>已取消（令牌被置位）或被中断 —— 保持静默，不触发任何回调；</li>
     *     <li>否则真实异常 —— 触发一次 {@code onError}；</li>
     *     <li>否则 —— 触发一次 {@code onCompleted}。</li>
     * </ul>
     * 任何任务最多触发一次回调，永不同时触发 onError 和 onCompleted。
     */
    static void dispatchResult(boolean cancelled, boolean interrupted, String errorMessage,
            String result, boolean success, ProcessCallback callback) {
        if (cancelled || interrupted) {
            return;
        }
        if (errorMessage != null) {
            callback.onError(errorMessage);
        } else {
            callback.onCompleted(result, success);
        }
    }

    public synchronized void cancelCurrentTask() {
        cancelCurrentTaskLocked();
    }

    /**
     * 取消并清理当前任务。调用方必须持有 {@code this} 锁，以保证“置位令牌 + 中断线程 +
     * 销毁进程”这三步相对于 {@link #executeCommand} / {@link #cancelCurrentTask} 是原子的。
     */
    private void cancelCurrentTaskLocked() {
        if (currentCancelled != null) {
            currentCancelled.set(true);
        }
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
        if (currentProcess != null) {
            currentProcess.destroy();
            currentProcess = null;
        }
    }

    public void shutdown() {
        executorService.shutdownNow();
    }
}

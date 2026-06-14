package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Regression tests for {@link ImageProcessor} task-cancellation boundaries.
 *
 * <p>These run as plain host-side JUnit tests (no Android runtime / no process spawning):
 * {@code ImageProcessor}'s constructor only builds an executor, and the methods exercised
 * here ({@link ImageProcessor#beginTask()}, {@link ImageProcessor#cancelCurrentTask()},
 * {@link ImageProcessor#dispatchResult}) are free of Android/IO dependencies.</p>
 *
 * <p>They pin down the two invariants that the original bug violated:</p>
 * <ol>
 *     <li>每个任务有独立的取消令牌；开始新任务不会复活旧任务的取消状态。</li>
 *     <li>被取消/被中断的任务保持静默；存活的任务只触发一次终结回调，
 *         绝不会同时触发 onError 和 onCompleted。</li>
 * </ol>
 */
public class ImageProcessorCancellationTest {

    /** 记录回调被调用情况的桩实现。 */
    private static class RecordingCallback implements ImageProcessor.ProcessCallback {
        int progressCount = 0;
        int completedCount = 0;
        int errorCount = 0;
        String lastResult = null;
        boolean lastSuccess = false;
        String lastError = null;

        @Override
        public void onProgress(String line) {
            progressCount++;
        }

        @Override
        public void onCompleted(String result, boolean success) {
            completedCount++;
            lastResult = result;
            lastSuccess = success;
        }

        @Override
        public void onError(String error) {
            errorCount++;
            lastError = error;
        }

        int terminalCallbacks() {
            return completedCount + errorCount;
        }
    }

    private ImageProcessor processor;

    @Before
    public void setUp() {
        processor = new ImageProcessor();
    }

    @After
    public void tearDown() {
        processor.shutdown();
    }

    // ---------------------------------------------------------------------
    // 令牌生命周期：旧任务失效
    // ---------------------------------------------------------------------

    @Test
    public void beginTask_marksPreviousTaskCancelled() {
        AtomicBoolean first = processor.beginTask();
        assertFalse("a fresh task must not start cancelled", first.get());

        AtomicBoolean second = processor.beginTask();
        assertTrue("starting a new task must cancel the previous one", first.get());
        assertFalse("the new task must be live", second.get());
    }

    @Test
    public void cancelCurrentTask_cancelsLiveTask() {
        AtomicBoolean token = processor.beginTask();
        assertFalse(token.get());

        processor.cancelCurrentTask();
        assertTrue("explicit cancel must mark the current task cancelled", token.get());
    }

    /**
     * 核心回归：原实现用一个共享布尔标志，executeCommand 里在取消旧任务后立刻把它
     * 重置为 false，导致旧任务在 finally 里读到 false 又触发 onCompleted。
     * 这里验证：一旦某个任务被取代，它的取消状态必须永久保持，后续再开新任务也不能复活它。
     */
    @Test
    public void supersededTask_staysCancelledForever() {
        AtomicBoolean first = processor.beginTask();
        processor.beginTask();   // 取代 first
        assertTrue(first.get());

        processor.beginTask();   // 再开一个任务
        assertTrue("a superseded task must never be resurrected by later tasks", first.get());
    }

    // ---------------------------------------------------------------------
    // 终结回调派发：取消静默、最多一次回调
    // ---------------------------------------------------------------------

    @Test
    public void dispatch_liveSuccess_firesCompletedOnly() {
        RecordingCallback cb = new RecordingCallback();
        ImageProcessor.dispatchResult(false, false, null, "output", true, cb);

        assertEquals(1, cb.completedCount);
        assertEquals(0, cb.errorCount);
        assertEquals("output", cb.lastResult);
        assertTrue(cb.lastSuccess);
    }

    @Test
    public void dispatch_liveFailure_firesCompletedWithFailure() {
        RecordingCallback cb = new RecordingCallback();
        ImageProcessor.dispatchResult(false, false, null, "partial", false, cb);

        assertEquals(1, cb.completedCount);
        assertEquals(0, cb.errorCount);
        assertFalse(cb.lastSuccess);
    }

    @Test
    public void dispatch_liveException_firesErrorOnly() {
        RecordingCallback cb = new RecordingCallback();
        ImageProcessor.dispatchResult(false, false, "boom", "", false, cb);

        assertEquals(1, cb.errorCount);
        assertEquals(0, cb.completedCount);
        assertEquals("boom", cb.lastError);
    }

    /** 被取代/被取消的任务必须完全静默——绝不能再触发 onCompleted 覆盖新任务。 */
    @Test
    public void dispatch_cancelled_isSilent() {
        RecordingCallback cb = new RecordingCallback();
        ImageProcessor.dispatchResult(true, false, null, "stale-output", true, cb);

        assertEquals(0, cb.terminalCallbacks());
    }

    /**
     * 被中断的任务必须静默，尤其不能上报 onError——原实现在
     * InterruptedException / InterruptedIOException 分支里调用了 onError。
     */
    @Test
    public void dispatch_interrupted_isSilentNotError() {
        RecordingCallback cb = new RecordingCallback();
        ImageProcessor.dispatchResult(false, true, null, "", false, cb);

        assertEquals("interruption must not be reported as an error", 0, cb.errorCount);
        assertEquals(0, cb.completedCount);
    }

    /** 取消时即便进程关闭顺带抛了个异常，也必须静默，不能把取消显示成错误。 */
    @Test
    public void dispatch_cancelledWithIncidentalError_isSilent() {
        RecordingCallback cb = new RecordingCallback();
        ImageProcessor.dispatchResult(true, false, "stream closed", "", false, cb);

        assertEquals(0, cb.terminalCallbacks());
    }

    /**
     * 穷举所有标志组合，确保：
     *  - 任何任务最多触发一次终结回调（杜绝“先错误后完成覆盖”）；
     *  - 只要取消或中断，就绝不触发任何终结回调；
     *  - dispatchResult 永远不会调用 onProgress。
     */
    @Test
    public void dispatch_neverFiresBothCallbacks_forAnyFlagCombination() {
        boolean[] bools = {false, true};
        String[] errors = {null, "err"};
        for (boolean cancelled : bools) {
            for (boolean interrupted : bools) {
                for (String error : errors) {
                    for (boolean success : bools) {
                        RecordingCallback cb = new RecordingCallback();
                        ImageProcessor.dispatchResult(cancelled, interrupted, error, "r", success, cb);

                        String label = "cancelled=" + cancelled + " interrupted=" + interrupted
                                + " error=" + error + " success=" + success;
                        assertTrue("at most one terminal callback for " + label,
                                cb.terminalCallbacks() <= 1);
                        assertEquals("dispatch must never call onProgress for " + label,
                                0, cb.progressCount);

                        if (cancelled || interrupted) {
                            assertEquals("cancelled/interrupted task must stay silent for " + label,
                                    0, cb.terminalCallbacks());
                        } else {
                            assertEquals("live task must report exactly once for " + label,
                                    1, cb.terminalCallbacks());
                        }
                    }
                }
            }
        }
    }

    /** 存活、无异常、无取消时不应该有错误回调。 */
    @Test
    public void dispatch_liveSuccess_doesNotError() {
        RecordingCallback cb = new RecordingCallback();
        ImageProcessor.dispatchResult(false, false, null, "ok", true, cb);
        assertEquals(0, cb.errorCount);
        assertNull(cb.lastError);
    }
}

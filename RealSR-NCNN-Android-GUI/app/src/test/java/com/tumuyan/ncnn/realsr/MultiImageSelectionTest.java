package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Regression tests for the multi-image selection result handling.
 * <p>
 * The picker launched with {@code ACTION_GET_CONTENT} + {@code EXTRA_ALLOW_MULTIPLE} does not always
 * return a {@code ClipData}: when a single item is chosen (or a document provider only ever returns
 * one URI) the result arrives via {@code Intent.getData()} instead. The previous implementation
 * assumed a non-null {@code ClipData} and crashed with a {@link NullPointerException} in that case,
 * breaking the batch pre-processing flow.
 * <p>
 * These tests exercise {@link MainActivity#mergeSelectedUris(List, Object)} — the pure,
 * Android-free core of the new {@code collectImageUris(Intent)} helper. {@code String}s stand in for
 * {@code Uri}s because {@code android.net.Uri} is only a non-functional stub on the host JVM.
 */
public class MultiImageSelectionTest {

    /**
     * Single-URI selection (no {@code ClipData}) — the exact case that used to throw an NPE.
     * The single {@code getData()} URI must still be collected.
     */
    @Test
    public void singleUriOnly_returnsThatUri() {
        List<String> result = MainActivity.mergeSelectedUris(new ArrayList<>(), "content://single");
        assertEquals(Collections.singletonList("content://single"), result);
    }

    /** A null {@code ClipData} list (modeling {@code getClipData() == null}) must not crash. */
    @Test
    public void nullClipList_withSingleUri_returnsSingle() {
        List<String> result = MainActivity.<String>mergeSelectedUris(null, "content://single");
        assertEquals(Collections.singletonList("content://single"), result);
    }

    /** Genuine multi-selection via {@code ClipData} keeps every URI in selection order. */
    @Test
    public void multipleClipUris_returnedInOrder() {
        List<String> clip = Arrays.asList("content://a", "content://b", "content://c");
        List<String> result = MainActivity.mergeSelectedUris(clip, null);
        assertEquals(Arrays.asList("content://a", "content://b", "content://c"), result);
    }

    /** Mixed return (both {@code ClipData} and a distinct {@code getData()}) merges, preserving order. */
    @Test
    public void mixedClipAndDistinctSingle_appendsSingle() {
        List<String> clip = Arrays.asList("content://a", "content://b");
        List<String> result = MainActivity.mergeSelectedUris(clip, "content://c");
        assertEquals(Arrays.asList("content://a", "content://b", "content://c"), result);
    }

    /** Mixed return where {@code getData()} duplicates a {@code ClipData} entry must de-duplicate. */
    @Test
    public void mixedClipAndDuplicateSingle_dedupsSingle() {
        List<String> clip = Arrays.asList("content://a", "content://b");
        List<String> result = MainActivity.mergeSelectedUris(clip, "content://b");
        assertEquals(Arrays.asList("content://a", "content://b"), result);
    }

    /** Duplicate entries within {@code ClipData} itself are collapsed. */
    @Test
    public void duplicateClipUris_areDeduped() {
        List<String> clip = Arrays.asList("content://a", "content://a", "content://b");
        List<String> result = MainActivity.mergeSelectedUris(clip, null);
        assertEquals(Arrays.asList("content://a", "content://b"), result);
    }

    /** Null elements (e.g. a non-URI {@code ClipData.Item}) are skipped rather than added. */
    @Test
    public void nullElementsInClip_areSkipped() {
        List<String> clip = Arrays.asList("content://a", null, "content://b");
        List<String> result = MainActivity.mergeSelectedUris(clip, null);
        assertEquals(Arrays.asList("content://a", "content://b"), result);
    }

    /** No selection at all yields an empty (never null) list. */
    @Test
    public void emptyClipAndNullSingle_returnsEmpty() {
        List<String> result = MainActivity.mergeSelectedUris(new ArrayList<>(), null);
        assertTrue(result.isEmpty());
    }

    /** Both inputs null is tolerated and yields an empty list. */
    @Test
    public void bothNull_returnsEmpty() {
        List<String> result = MainActivity.<String>mergeSelectedUris(null, null);
        assertTrue(result.isEmpty());
    }
}

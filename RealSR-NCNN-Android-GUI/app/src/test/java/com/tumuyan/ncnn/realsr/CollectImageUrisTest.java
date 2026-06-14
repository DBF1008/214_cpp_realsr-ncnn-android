package com.tumuyan.ncnn.realsr;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for {@link MainActivity#collectImageUris(Intent)}.
 * <p>
 * Verifies that multi-image selection result handling is safe against:
 * <ul>
 *   <li>ClipData-only return (normal multi-select)</li>
 *   <li>getData()-only return (single-select fallback on some providers)</li>
 *   <li>Mixed return (both ClipData and getData() present)</li>
 *   <li>Null / empty inputs that previously caused NullPointerException</li>
 * </ul>
 */
public class CollectImageUrisTest {

    // ── Helper ──────────────────────────────────────────────

    private static Uri mockUri(String value) {
        Uri uri = Mockito.mock(Uri.class);
        when(uri.toString()).thenReturn(value);
        return uri;
    }

    private static ClipData.Item mockClipItem(Uri uri) {
        ClipData.Item item = Mockito.mock(ClipData.Item.class);
        when(item.getUri()).thenReturn(uri);
        return item;
    }

    private static ClipData mockClipData(ClipData.Item... items) {
        ClipData clip = Mockito.mock(ClipData.class);
        when(clip.getItemCount()).thenReturn(items.length);
        for (int i = 0; i < items.length; i++) {
            when(clip.getItemAt(i)).thenReturn(items[i]);
        }
        return clip;
    }

    private static Intent mockIntent(ClipData clipData, Uri dataUri) {
        Intent intent = Mockito.mock(Intent.class);
        when(intent.getClipData()).thenReturn(clipData);
        when(intent.getData()).thenReturn(dataUri);
        return intent;
    }

    // ── Test: normal multi-select via ClipData ──────────────

    @Test
    public void collectImageUris_clipDataOnly_returnsAllUris() {
        Uri uri1 = mockUri("content://media/image1");
        Uri uri2 = mockUri("content://media/image2");
        Uri uri3 = mockUri("content://media/image3");

        ClipData clip = mockClipData(
                mockClipItem(uri1),
                mockClipItem(uri2),
                mockClipItem(uri3)
        );
        Intent intent = mockIntent(clip, null);

        List<Uri> result = MainActivity.collectImageUris(intent);

        assertEquals(3, result.size());
        assertSame(uri1, result.get(0));
        assertSame(uri2, result.get(1));
        assertSame(uri3, result.get(2));
    }

    // ── Test: single-select fallback via getData() ──────────

    @Test
    public void collectImageUris_clipDataNull_fallsBackToGetData() {
        Uri singleUri = mockUri("content://media/single_image");
        Intent intent = mockIntent(null, singleUri);

        List<Uri> result = MainActivity.collectImageUris(intent);

        assertEquals(1, result.size());
        assertSame(singleUri, result.get(0));
    }

    // ── Test: ClipData present → getData() NOT appended ─────

    @Test
    public void collectImageUris_bothClipAndData_clipTakesPrecedence() {
        Uri clipUri = mockUri("content://media/clip_image");
        Uri dataUri = mockUri("content://media/data_image");

        ClipData clip = mockClipData(mockClipItem(clipUri));
        Intent intent = mockIntent(clip, dataUri);

        List<Uri> result = MainActivity.collectImageUris(intent);

        assertEquals(1, result.size());
        assertSame(clipUri, result.get(0));
    }

    // ── Test: null Intent ───────────────────────────────────

    @Test
    public void collectImageUris_nullIntent_returnsEmptyList() {
        List<Uri> result = MainActivity.collectImageUris(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ── Test: Intent with no ClipData and no getData() ──────

    @Test
    public void collectImageUris_noClipDataNoData_returnsEmptyList() {
        Intent intent = mockIntent(null, null);

        List<Uri> result = MainActivity.collectImageUris(intent);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ── Test: ClipData item with null URI is skipped ────────

    @Test
    public void collectImageUris_clipItemWithNullUri_skipped() {
        Uri validUri = mockUri("content://media/valid");
        ClipData.Item nullItem = mockClipItem(null);
        ClipData.Item validItem = mockClipItem(validUri);

        ClipData clip = mockClipData(nullItem, validItem);
        Intent intent = mockIntent(clip, null);

        List<Uri> result = MainActivity.collectImageUris(intent);

        assertEquals(1, result.size());
        assertSame(validUri, result.get(0));
    }

    // ── Test: empty ClipData (0 items) falls back to getData() ─

    @Test
    public void collectImageUris_emptyClipData_fallsBackToGetData() {
        Uri fallbackUri = mockUri("content://media/fallback");
        ClipData emptyClip = mockClipData(); // 0 items
        Intent intent = mockIntent(emptyClip, fallbackUri);

        List<Uri> result = MainActivity.collectImageUris(intent);

        assertEquals(1, result.size());
        assertSame(fallbackUri, result.get(0));
    }

    // ── Test: single ClipData item (one image via multi-picker) ─

    @Test
    public void collectImageUris_singleClipItem_returnsOneUri() {
        Uri uri = mockUri("content://media/one_image");
        ClipData clip = mockClipData(mockClipItem(uri));
        Intent intent = mockIntent(clip, null);

        List<Uri> result = MainActivity.collectImageUris(intent);

        assertEquals(1, result.size());
        assertSame(uri, result.get(0));
    }
}

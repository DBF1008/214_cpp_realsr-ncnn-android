package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for {@link SafDirectoryResolver}.
 *
 * <p>These pin down the fix for the directory-batch SAF bug: directory validity must be
 * driven by whether a SAF tree grant is held, NOT by {@code new File(path).exists()} on a
 * path string that is bogus for SD cards / non-primary storage / vendor providers /
 * content-only trees. The tests also cover mapping a tree document id to a real filesystem
 * path (needed because the native binary can only consume real paths).
 *
 * <p>Runs on the host JVM — {@link SafDirectoryResolver} deliberately has no Android deps.
 */
public class SafDirectoryResolverTest {

    private static final String PRIMARY_ROOT = "/storage/emulated/0";

    // ---------------------------------------------------------------------
    // resolveFilesystemPath: primary volume
    // ---------------------------------------------------------------------

    @Test
    public void resolve_primary_withSubPath() {
        assertEquals("/storage/emulated/0/DCIM/RealSR",
                SafDirectoryResolver.resolveFilesystemPath("primary:DCIM/RealSR", PRIMARY_ROOT));
    }

    @Test
    public void resolve_primary_volumeRoot() {
        assertEquals("/storage/emulated/0",
                SafDirectoryResolver.resolveFilesystemPath("primary:", PRIMARY_ROOT));
    }

    @Test
    public void resolve_primary_deepPath() {
        assertEquals("/storage/emulated/0/a/b/c/d",
                SafDirectoryResolver.resolveFilesystemPath("primary:a/b/c/d", PRIMARY_ROOT));
    }

    @Test
    public void resolve_primary_rootHasTrailingSlash_noDoubleSlash() {
        assertEquals("/storage/emulated/0/Pictures",
                SafDirectoryResolver.resolveFilesystemPath("primary:Pictures", "/storage/emulated/0/"));
    }

    @Test
    public void resolve_primary_collapsesDoubleSlashInRelPath() {
        assertEquals("/storage/emulated/0/DCIM/RealSR",
                SafDirectoryResolver.resolveFilesystemPath("primary:DCIM//RealSR", PRIMARY_ROOT));
    }

    // ---------------------------------------------------------------------
    // resolveFilesystemPath: secondary volume (SD card / USB) — the case the
    // old new File(...) check wrongly rejected.
    // ---------------------------------------------------------------------

    @Test
    public void resolve_secondaryVolume_withSubPath() {
        assertEquals("/storage/1A2B-3C4D/Pictures",
                SafDirectoryResolver.resolveFilesystemPath("1A2B-3C4D:Pictures", PRIMARY_ROOT));
    }

    @Test
    public void resolve_secondaryVolume_root() {
        assertEquals("/storage/1A2B-3C4D",
                SafDirectoryResolver.resolveFilesystemPath("1A2B-3C4D:", PRIMARY_ROOT));
    }

    @Test
    public void resolve_secondaryVolume_deepPath() {
        assertEquals("/storage/ABCD-1234/foo/bar",
                SafDirectoryResolver.resolveFilesystemPath("ABCD-1234:foo/bar", PRIMARY_ROOT));
    }

    // ---------------------------------------------------------------------
    // resolveFilesystemPath: raw: and unmappable ids
    // ---------------------------------------------------------------------

    @Test
    public void resolve_rawAbsolutePath() {
        assertEquals("/storage/emulated/0/x",
                SafDirectoryResolver.resolveFilesystemPath("raw:/storage/emulated/0/x", PRIMARY_ROOT));
    }

    @Test
    public void resolve_rawEmpty_returnsNull() {
        assertNull(SafDirectoryResolver.resolveFilesystemPath("raw:", PRIMARY_ROOT));
    }

    @Test
    public void resolve_noColon_returnsNull() {
        // e.g. some vendor providers / a Downloads tree whose id has no "volume:rel" shape.
        assertNull(SafDirectoryResolver.resolveFilesystemPath("downloads", PRIMARY_ROOT));
    }

    @Test
    public void resolve_nullId_returnsNull() {
        assertNull(SafDirectoryResolver.resolveFilesystemPath(null, PRIMARY_ROOT));
    }

    @Test
    public void resolve_blankId_returnsNull() {
        assertNull(SafDirectoryResolver.resolveFilesystemPath("   ", PRIMARY_ROOT));
    }

    @Test
    public void resolve_primaryButNoRoot_returnsNull() {
        assertNull(SafDirectoryResolver.resolveFilesystemPath("primary:DCIM", ""));
        assertNull(SafDirectoryResolver.resolveFilesystemPath("primary:DCIM", null));
    }

    // ---------------------------------------------------------------------
    // leafName
    // ---------------------------------------------------------------------

    @Test
    public void leafName_primarySubPath() {
        assertEquals("Camera", SafDirectoryResolver.leafName("primary:DCIM/Camera"));
    }

    @Test
    public void leafName_singleSegment() {
        assertEquals("RealSR", SafDirectoryResolver.leafName("primary:RealSR"));
    }

    @Test
    public void leafName_volumeRoot_isEmpty() {
        assertEquals("", SafDirectoryResolver.leafName("primary:"));
        assertEquals("", SafDirectoryResolver.leafName("1A2B-3C4D:"));
    }

    @Test
    public void leafName_raw() {
        assertEquals("c", SafDirectoryResolver.leafName("raw:/a/b/c"));
    }

    @Test
    public void leafName_trailingSlashIgnored() {
        assertEquals("Camera", SafDirectoryResolver.leafName("primary:DCIM/Camera/"));
    }

    @Test
    public void leafName_null_isEmpty() {
        assertEquals("", SafDirectoryResolver.leafName(null));
    }

    // ---------------------------------------------------------------------
    // Validity decision — the core regression contract.
    // ---------------------------------------------------------------------

    @Test
    public void inputDir_validWhenTreeGrantHeld_evenIfFileChecksWouldFail() {
        // The crux of the bug: a held SAF tree grant alone must be enough, regardless of
        // whether a manual File.isDirectory() check would pass.
        assertTrue(SafDirectoryResolver.isInputDirValid(true, false));
    }

    @Test
    public void inputDir_validWhenManualPathIsExistingDir() {
        assertTrue(SafDirectoryResolver.isInputDirValid(false, true));
    }

    @Test
    public void inputDir_invalidWhenNeither() {
        assertFalse(SafDirectoryResolver.isInputDirValid(false, false));
    }

    @Test
    public void outputDir_validWhenTreeGrantHeld() {
        assertTrue(SafDirectoryResolver.isOutputDirValid(true, false));
    }

    @Test
    public void outputDir_validWhenManualNonEmpty() {
        assertTrue(SafDirectoryResolver.isOutputDirValid(false, true));
    }

    @Test
    public void outputDir_invalidWhenNeither() {
        assertFalse(SafDirectoryResolver.isOutputDirValid(false, false));
    }

    // ---------------------------------------------------------------------
    // canStart
    // ---------------------------------------------------------------------

    @Test
    public void canStart_requiresAllThree() {
        assertTrue(SafDirectoryResolver.canStart(true, true, true));
        assertFalse(SafDirectoryResolver.canStart(false, true, true));
        assertFalse(SafDirectoryResolver.canStart(true, false, true));
        assertFalse(SafDirectoryResolver.canStart(true, true, false));
    }

    /**
     * End-to-end of the reported scenario: user picks an SD-card folder via SAF.
     * We hold the tree grant, so the dir is valid (button enables) AND the tree id maps
     * to a real path for the native binary — instead of being wrongly rejected.
     */
    @Test
    public void scenario_sdCardPickedViaSaf_isValidAndResolvable() {
        String treeDocId = "1A2B-3C4D:Camera/burst";
        boolean hasGrant = true; // we just took a persistable permission

        assertTrue(SafDirectoryResolver.isInputDirValid(hasGrant, false));
        assertEquals("/storage/1A2B-3C4D/Camera/burst",
                SafDirectoryResolver.resolveFilesystemPath(treeDocId, PRIMARY_ROOT));
        assertEquals("burst", SafDirectoryResolver.leafName(treeDocId));
    }
}

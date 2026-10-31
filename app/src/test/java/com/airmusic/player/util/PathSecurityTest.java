package com.airmusic.player.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PathSecurityTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void rejectsSimilarDirectoryName() throws Exception {
        File root = temporary.newFolder("Music");
        File outside = temporary.newFolder("MusicBackup");
        File file = new File(outside, "track.mp3");
        assertTrue(file.createNewFile());
        assertFalse(PathSecurity.isInside(root, file));
    }

    @Test
    public void rejectsParentTraversal() throws Exception {
        File root = temporary.newFolder("Music");
        File outside = temporary.newFolder("private");
        File file = new File(outside, "track.mp3");
        assertTrue(file.createNewFile());
        File traversal = new File(root, "../private/track.mp3");
        assertFalse(PathSecurity.isInside(root, traversal));
    }

    @Test
    public void rejectsSymlinkEscape() throws Exception {
        File root = temporary.newFolder("Music");
        File outside = temporary.newFolder("outside");
        File secret = new File(outside, "secret.mp3");
        assertTrue(secret.createNewFile());
        File link = new File(root, "linked.mp3");
        try {
            Files.createSymbolicLink(link.toPath(), secret.toPath());
        } catch (Exception unsupportedOrDenied) {
            return;
        }
        assertFalse(PathSecurity.isInside(root, link));
    }

    @Test
    public void acceptsNormalChild() throws Exception {
        File root = temporary.newFolder("Music");
        File album = new File(root, "Album");
        assertTrue(album.mkdir());
        File file = new File(album, "track.mp3");
        assertTrue(file.createNewFile());
        assertTrue(PathSecurity.isInside(root, file));
    }

    @Test
    public void rejectsUnsafeUploadNames() {
        assertFalse(PathSecurity.isSafeFileName("../track.mp3"));
        assertFalse(PathSecurity.isSafeFileName("folder/track.mp3"));
        assertFalse(PathSecurity.isSafeFileName("folder\\track.mp3"));
        assertFalse(PathSecurity.isSafeFileName(".."));
        assertTrue(PathSecurity.isSafeFileName("track.mp3"));
    }
}

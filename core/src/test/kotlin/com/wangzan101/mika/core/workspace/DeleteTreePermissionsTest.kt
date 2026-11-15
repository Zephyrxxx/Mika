package com.wangzan101.mika.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

class DeleteTreePermissionsTest {
    @Test fun staleDirectoryStatDoesNotChangeSymlinkTargetPermissions() {
        val tmp = Files.createTempDirectory("gate-stale-stat")
        val root = Files.createDirectory(tmp.resolve("workspace"))
        val target = Files.createDirectory(tmp.resolve("outside"))
        val original = PosixFilePermissions.fromString("r-x------")
        val entry = root.resolve("entry")
        try {
            Files.setPosixFilePermissions(target, original)
            Files.createSymbolicLink(entry, target)
            // Deterministic fake of a directory replaced after lstat, entirely in this fixture.
            val staleStatSys = object : FdSys by NioFdSys {
                override fun lstat(dir: OpenFd, name: String): FdStat? =
                    NioFdSys.lstat(dir, name)?.let {
                        if (name == "entry") it.copy(kind = FdStat.Kind.DIR) else it
                    }
            }
            WorkspaceFileGate(staleStatSys).deleteTree(root.toFile(), "entry")
            assertFalse(Files.exists(entry, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isDirectory(target))
            assertEquals(original, Files.getPosixFilePermissions(target))
        } finally {
            Files.deleteIfExists(entry)
            Files.deleteIfExists(target)
            Files.deleteIfExists(root)
            Files.deleteIfExists(tmp)
        }
    }

    @Test fun cannotOpenDirectoryFailsWithoutChangingPermissions() {
        val tmp = Files.createTempDirectory("gate-denied-open")
        val entry = Files.createDirectory(tmp.resolve("entry"))
        val original = PosixFilePermissions.fromString("r-x------")
        val denied = IOException("simulated permission denied")
        try {
            Files.setPosixFilePermissions(entry, original)
            val deniedSys = object : FdSys by NioFdSys {
                override fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd {
                    if (name == "entry") throw denied
                    return NioFdSys.openDir(dir, name, create)
                }
            }
            try {
                WorkspaceFileGate(deniedSys).deleteTree(tmp.toFile(), "entry")
                fail("expected conservative failure")
            } catch (e: IOException) {
                assertTrue(e === denied)
            }
            assertTrue(Files.isDirectory(entry))
            assertEquals(original, Files.getPosixFilePermissions(entry))
        } finally {
            Files.deleteIfExists(entry)
            Files.deleteIfExists(tmp)
        }
    }
}

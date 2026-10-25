package com.lielu.todo

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFileNamesTest {
    @Test
    fun slugIsLowercaseAndFileSystemSafe() {
        assertEquals("lieluinit", BackupFileNames.slug("LieluInit"))
        assertEquals("ma-super-app", BackupFileNames.slug("Ma  Super App!"))
        assertEquals("yakwa", BackupFileNames.slug("Yakwa"))
    }

    @Test
    fun slugFallsBackWhenNothingUsable() {
        assertEquals("app", BackupFileNames.slug("???"))
    }
}

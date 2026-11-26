package app.einkradio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun newerPatchIsNewer() = assertTrue(Updater.isNewer("1.2.1", "1.2.0"))
    @Test fun newerMinorBeatsLargerPatch() = assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
    @Test fun sameVersionIsNotNewer() = assertFalse(Updater.isNewer("1.2.1", "1.2.1"))
    @Test fun olderIsNotNewer() = assertFalse(Updater.isNewer("1.1.9", "1.2.0"))
    @Test fun tagPrefixIsIgnored() = assertTrue(Updater.isNewer("v1.3.0", "1.2.9"))
    @Test fun missingPartsCountAsZero() = assertFalse(Updater.isNewer("1.2", "1.2.0"))
}

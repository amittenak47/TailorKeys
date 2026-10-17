package app.tailorkeys

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import app.tailorkeys.settings.PreferenceRevisions
import org.junit.Assert.*
import org.junit.Test

class PreferenceRevisionsTest {
    private fun invalidatedBy(key: String?): Set<String> {
        val revisions = PreferenceRevisions()
        val invalidated = mutableSetOf<String>()
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            val onChange: (String) -> Unit = { invalidated += it }
            observer.observeReads("size", onChange) { revisions.read("height", "width") }
            observer.observeReads("look", onChange) { revisions.read("theme", "radius") }
            observer.observeReads("typing", onChange) { revisions.read("swipe") }
            Snapshot.withMutableSnapshot { revisions.changed(key) }
            Snapshot.sendApplyNotifications()
            return invalidated
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test fun slidersInvalidateOnlyControlsThatReadThatPreference() {
        assertEquals(setOf("size"), invalidatedBy("height"))
        assertEquals(setOf("size"), invalidatedBy("width"))
        assertEquals(setOf("look"), invalidatedBy("radius"))
        assertEquals(setOf("look"), invalidatedBy("theme"))
        assertEquals(setOf("typing"), invalidatedBy("swipe"))
    }

    @Test fun resetInvalidatesAllObservedPreferencesAndUnknownKeysInvalidateNone() {
        assertEquals(setOf("size", "look", "typing"), invalidatedBy(null))
        assertTrue(invalidatedBy("unobserved").isEmpty())
    }
}

package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudentCasingTest {
    @Test fun aWordTypedInCapitalsIsFixedInCapitals() {
        assertEquals("THE", StudentCorrector.casedAs("TEH", "the"))
        assertEquals("THE", StudentCorrector.casedAs("TEH", "The"))
    }

    @Test fun firstCapitalsAndLowerCaseKeepTheModelsCasing() {
        assertEquals("The", StudentCorrector.casedAs("Teh", "The"))
        assertEquals("the", StudentCorrector.casedAs("teh", "the"))
        assertEquals("I", StudentCorrector.casedAs("i", "I"))
    }

    @Test fun otherCapitalsInsideAWordLeaveItAsTyped() {
        assertNull(StudentCorrector.casedAs("iPhnoe", "iphone"))
        assertNull(StudentCorrector.casedAs("McDonlad", "mcdonald"))
    }
}

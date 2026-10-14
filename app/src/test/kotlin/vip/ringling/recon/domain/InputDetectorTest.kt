package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InputDetectorTest {
    @Test fun detectsEachType() {
        assertEquals(InputType.EMAIL, InputDetector.detect("jane.doe+x@example.co.uk"))
        assertEquals(InputType.DOMAIN, InputDetector.detect("example.com"))
        assertEquals(InputType.DOMAIN, InputDetector.detect("https://sub.example.org/path"))
        assertEquals(InputType.ZIP, InputDetector.detect("83702"))
        assertEquals(InputType.ZIP, InputDetector.detect("83702-1234"))
        assertEquals(InputType.PHONE, InputDetector.detect("+1 (208) 555-0142"))
        assertEquals(InputType.PHONE, InputDetector.detect("208-555-0142"))
        assertEquals(InputType.USERNAME, InputDetector.detect("@ghost_in_shell"))
        assertEquals(InputType.USERNAME, InputDetector.detect("neo"))
    }

    @Test fun rejectsBlankAndGarbage() {
        assertNull(InputDetector.detect("   "))
        assertNull(InputDetector.detect("a b c!"))
    }

    @Test fun normalizes() {
        assertEquals("a@b.com", InputDetector.normalize(" A@B.com ", InputType.EMAIL))
        assertEquals("+12085550142", InputDetector.normalize("+1 (208) 555-0142", InputType.PHONE))
        assertEquals("example.com", InputDetector.normalize("HTTPS://Example.com/x", InputType.DOMAIN))
        assertEquals("neo", InputDetector.normalize("@neo", InputType.USERNAME))
        assertEquals("83702", InputDetector.normalize("83702-1234", InputType.ZIP))
    }
}

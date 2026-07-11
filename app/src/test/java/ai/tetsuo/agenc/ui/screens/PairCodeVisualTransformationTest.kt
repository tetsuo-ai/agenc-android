package ai.tetsuo.agenc.ui.screens

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class PairCodeVisualTransformationTest {
    @Test
    fun `separator is visual only and offsets round trip`() {
        val transformed = PairCodeVisualTransformation.filter(AnnotatedString("E32M1C0N"))

        assertEquals("E32M-1C0N", transformed.text.text)
        assertEquals(9, transformed.offsetMapping.originalToTransformed(8))
        assertEquals(8, transformed.offsetMapping.transformedToOriginal(9))
        assertEquals(4, transformed.offsetMapping.originalToTransformed(4))
        assertEquals(4, transformed.offsetMapping.transformedToOriginal(5))
    }
}

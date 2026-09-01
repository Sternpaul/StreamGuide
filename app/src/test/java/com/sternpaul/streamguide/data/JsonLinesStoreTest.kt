package com.sternpaul.streamguide.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class JsonLinesStoreTest {
    @Test
    fun roundTripsFiftyThousandRecordsWithoutBuildingOneGiantJsonArray() {
        val file = File.createTempFile("streamguide-channels", ".jsonl")
        try {
            JsonLinesStore.write(file, (0 until 50_000).asSequence()) { it.toString() }

            val restored = JsonLinesStore.read(file) { it.toIntOrNull() }

            assertEquals(50_000, restored.size)
            assertEquals(0, restored.first())
            assertEquals(49_999, restored.last())
        } finally {
            file.delete()
        }
    }
}

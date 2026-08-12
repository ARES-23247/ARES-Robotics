package com.areslib.routine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RoutineCodecStrictTest {
    @Test
    fun `strict codec accepts the exact nested drive schema`() {
        val decoded = AresRoutineCodec.decode(validDriveDocument())

        assertEquals(1.25, decoded.steps.single().drive?.target?.xMeters)
        assertEquals("intake.start", decoded.steps.single().drive?.markers?.single()?.actionKey)
    }

    @Test
    fun `strict codec rejects misspelled missing and mistyped target primitives`() {
        val valid = validDriveDocument()
        val malformed = listOf(
            valid.replace("\"xMeters\":1.25", "\"xMetres\":1.25"),
            valid.replace("\"yMeters\":2.5,", ""),
            valid.replace("\"headingRadians\":0.5", "\"headingRadians\":\"0.5\""),
            valid.replace("\"target\":{", "\"target\":{\"unexpected\":0,")
        )

        malformed.forEach(::assertRejected)
    }

    @Test
    fun `strict codec rejects malformed marker arrays and action-key arrays`() {
        val valid = validDriveDocument()
        val malformed = listOf(
            valid.replace(",\"actionKey\":\"intake.start\"", ""),
            valid.replace("\"progress\":0.5", "\"progress\":\"0.5\""),
            valid.replace("\"actionKey\":\"intake.start\"", "\"actionKey\":\"intake.start\",\"typo\":true"),
            valid.replace("[\"intake.hold\"]", "[1]")
        )

        malformed.forEach(::assertRejected)
    }

    @Test
    fun `strict codec rejects non-string arguments and duplicate keys at every depth`() {
        val actionDocument = """
            {
              "documentId":"strict-action",
              "name":"Strict Action",
              "steps":[{"kind":"ACTION","actionKey":"intake.start","arguments":{"speed":"fast"}}]
            }
        """.trimIndent()
        val malformed = listOf(
            actionDocument.replace("\"fast\"", "0.5"),
            actionDocument.replace("\"documentId\":\"strict-action\"", "\"documentId\":\"one\",\"documentId\":\"two\""),
            actionDocument.replace("\"speed\":\"fast\"", "\"speed\":\"slow\",\"speed\":\"fast\""),
            validDriveDocument().replace("\"xMeters\":1.25", "\"xMeters\":0.0,\"xMeters\":1.25")
        )

        malformed.forEach(::assertRejected)
    }

    private fun assertRejected(json: String) {
        assertThrows(IllegalArgumentException::class.java) { AresRoutineCodec.decode(json) }
    }

    private fun validDriveDocument(): String = """
        {
          "schemaVersion":1,
          "documentId":"strict-drive",
          "revision":1,
          "name":"Strict Drive",
          "steps":[
            {
              "kind":"DRIVE_TO",
              "arguments":{},
              "drive":{
                "target":{"xMeters":1.25,"yMeters":2.5,"headingRadians":0.5},
                "motionPresetKey":"balanced",
                "preferredEngineKey":"holonomic",
                "markers":[{"progress":0.5,"actionKey":"intake.start"}],
                "duringActionKeys":["intake.hold"],
                "arrivalActionKeys":["intake.stop"]
              },
              "children":[],
              "elseChildren":[]
            }
          ]
        }
    """.trimIndent()
}

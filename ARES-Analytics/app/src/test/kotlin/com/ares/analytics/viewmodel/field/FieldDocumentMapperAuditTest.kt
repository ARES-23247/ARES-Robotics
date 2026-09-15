// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.viewmodel.field

import com.ares.analytics.domain.project.FieldDocumentMapper

import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FieldDocumentMapperAuditTest {
    @Test
    fun `legacy names with the same generated ID retain distinct catalog entries`() {
        val base = FieldDocumentMapper.newDocument(League.FTC)
        val pieces = listOf("Custom A", "Custom-A", "温度", "位置").mapIndexed { index, type ->
            GamePiece("piece-$index", "Piece $index", 0.0, 0.0, type = type)
        }
        val document = FieldDocumentMapper.withEditorData(base, League.FTC,
            FieldDocumentMapper.image(base), emptyList(), pieces, emptyList(), emptyList(), emptyList())
        val types = document.elementTypes.associateBy { it.id }
        assertEquals(pieces.size, types.size)
        document.elements.zip(pieces).forEach { (element, piece) ->
            assertEquals(piece.type, types.getValue(element.elementTypeId).name)
        }
        val savedAgain = FieldDocumentMapper.withEditorData(document, League.FTC,
            FieldDocumentMapper.image(document), emptyList(), FieldDocumentMapper.gamePieces(document),
            FieldDocumentMapper.gamePieceTypes(document), emptyList(), emptyList())
        assertEquals(document.elements, savedAgain.elements)
        assertEquals(document.elementTypes.map { it.id to it.name }, savedAgain.elementTypes.map { it.id to it.name })
    }

    @Test
    fun `legacy migration never overwrites an authored catalog ID`() {
        val base = FieldDocumentMapper.newDocument(League.FTC)
        val authored = GamePieceType("game-piece-custom-a", "Reviewed type", massKg = 1.7)
        val pieces = listOf(
            GamePiece("authored", "Authored", 0.0, 0.0, type = authored.name, typeId = authored.id),
            GamePiece("legacy", "Legacy", 0.0, 0.0, type = "Custom A"),
        )
        val document = FieldDocumentMapper.withEditorData(base, League.FTC,
            FieldDocumentMapper.image(base), emptyList(), pieces, listOf(authored), emptyList(), emptyList())
        val retained = document.elementTypes.single { it.id == authored.id }
        assertEquals(authored.name, retained.name)
        assertEquals(1.7, retained.massKg)
        assertNotEquals(document.elements[0].elementTypeId, document.elements[1].elementTypeId)
    }
}

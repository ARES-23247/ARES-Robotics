package com.ares.analytics.service.tuning

import com.areslib.tuning.*
import kotlin.test.*

class TuningValueResolutionAuditTest {
    private fun declaration(name: String = "gain", type: TuningParameterType = TuningParameterType.DOUBLE,
        value: TuningValue = TuningValue(doubleValue = 2.0), options: List<String> = emptyList()) =
        TuningParameterDeclaration("drive.$name", "drive.$name", "drive.primary", name, "Test $name", type,
            defaultValue = value, enumOptions = options, applyPolicy = TuningApplyPolicy.LIVE_SAFE)
    private val root = TuningProfileDocument(
        uid = "profile.base", profileId = "base", displayName = "Base", description = "Base profile",
        projectId = "robot.project", authority = TuningProfileAuthority.CANONICAL_CHECKED_IN, values = emptyList(),
    )
    private val allTypes = listOf(
        declaration(), declaration("count", TuningParameterType.INT, TuningValue(intValue = 3)),
        declaration("enabled", TuningParameterType.BOOLEAN, TuningValue(booleanValue = true)),
        declaration("label", TuningParameterType.TEXT, TuningValue(textValue = "test")),
        declaration("mode", TuningParameterType.ENUM, TuningValue(textValue = "coast"), listOf("coast", "brake")),
    )
    private fun rows(declarations: List<TuningParameterDeclaration> = allTypes,
        live: Map<String, Double> = emptyMap(), typed: Map<String, TuningValue> = emptyMap()) =
        resolveTuningProfile(root, listOf(root), declarations, live, emptyMap(), typed)

    @Test fun `finite displayed doubles retain their exact editable value`() {
        val random = java.util.Random(236)
        val values = listOf(Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            0.0, -0.0, Math.PI, 1.0 / 3.0, 3.000001, 3.000002, 1e-8) +
            List(2048) { Double.fromBits(random.nextLong()) }.filter { it.isFinite() }
        for (value in values) {
            val rendered = TuningValue(doubleValue = value).displayValue()
            val parsed = assertNotNull(rendered.toDoubleOrNull(), "Not editable: $rendered")
            assertEquals(value.toRawBits(), parsed.toRawBits(), "Lost precision for $value -> $rendered")
        }
    }

    @Test fun `large and small doubles have bounded readable display length`() {
        for (value in listOf(Double.MAX_VALUE, Double.MIN_VALUE, -1e200, 1e-200)) {
            assertTrue(TuningValue(doubleValue = value).displayValue().length <= 26, "Unbounded fixed-point rendering")
        }
    }

    @Test fun `numeric display remains editable regardless of desktop locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(1.25, TuningValue(doubleValue = 1.25).displayValue().toDoubleOrNull())
        } finally { java.util.Locale.setDefault(previous) }
    }

    @Test fun `unassigned Studio sources match the typed defaults used by the robot`() {
        val runtime = TypedTuningRuntime(allTypes, emptyMap(),
            TuningMetadataSnapshot(root.projectId, null, root.uid, allTypes, listOf(root.uid)))
        for (row in rows()) {
            assertEquals(runtime.value(row.declaration.uid), row.sourceTypedValue, row.declaration.uid)
            assertEquals("DEFAULT", row.status.name)
            assertNull(row.sourceProfileId)
        }
    }

    @Test fun `restating a declared default does not create a change or require fabricated provenance`() {
        val proposals = allTypes.associate { it.key to it.defaultValue }
        val (changes, errors) = buildTuningReview(root, listOf(root), allTypes, proposals, emptyMap())
        assertTrue(changes.isEmpty())
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test fun `selected profile snapshot must match the supplied profile collection`() {
        val changed = root.copy(values = listOf(TuningAssignment(allTypes.first().uid, TuningValue(doubleValue = 5.0))))
        assertFailsWith<IllegalArgumentException> {
            resolveTuningProfile(root, listOf(changed), allTypes, emptyMap(), emptyMap())
        }
    }

    @Test fun `invalid declaration defaults are rejected consistently with the robot`() {
        for (bad in listOf(declaration(value = TuningValue(doubleValue = Double.NaN)),
            declaration(type = TuningParameterType.INT, value = TuningValue(doubleValue = 3.0)),
            declaration(value = TuningValue(doubleValue = 2.0)).copy(minimum = 3.0))) {
            assertFailsWith<IllegalArgumentException> { rows(listOf(bad)) }
        }
    }

    @Test fun `legacy numeric observations preserve integer type and reject fractional or overflowing integers`() {
        val count = allTypes[1]
        assertEquals(TuningValue(intValue = 4), rows(listOf(count), live = mapOf(count.key to 4.0)).single().liveTypedValue)
        for (value in listOf(4.5, Int.MAX_VALUE.toDouble() + 1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(rows(listOf(count), live = mapOf(count.key to value)).single().liveTypedValue)
        }
    }

    @Test fun `numeric observations cannot masquerade as text or boolean and malformed typed data is ignored`() {
        for (d in allTypes.drop(2)) assertNull(rows(listOf(d), live = mapOf(d.key to 1.0)).single().liveTypedValue)
        val gain = allTypes.first()
        for (value in listOf(TuningValue(doubleValue = Double.NaN), TuningValue(intValue = 2),
            TuningValue(doubleValue = 2.0, booleanValue = true))) {
            assertNull(rows(listOf(gain), typed = mapOf(gain.key to value)).single().liveTypedValue)
        }
    }

    @Test fun `blank origin cannot produce an approvable change`() {
        val d = allTypes.first()
        for (provenance in listOf(TuningValueProvenance(" ", "Reason"), TuningValueProvenance("\n", ""))) {
            val (changes, errors) = buildTuningReview(root, listOf(root), allTypes,
                mapOf(d.key to TuningValue(doubleValue = 4.0)), mapOf(d.key to provenance))
            assertTrue(changes.isEmpty())
            assertTrue(errors.isNotEmpty())
        }
    }

    @Test fun `profile overrides inheritance and defaults retain their exact typed source and UID`() {
        fun value(d: TuningParameterDeclaration, child: Boolean) = when (d.type) {
            TuningParameterType.DOUBLE -> TuningValue(doubleValue = if (child) 4.0 else 3.0)
            TuningParameterType.INT -> TuningValue(intValue = if (child) 5 else 4)
            TuningParameterType.BOOLEAN -> TuningValue(booleanValue = child)
            TuningParameterType.TEXT -> TuningValue(textValue = if (child) "child" else "parent")
            TuningParameterType.ENUM -> TuningValue(textValue = if (child) "coast" else "brake")
        }
        for (mask in 0..31) {
            val parentValues = allTypes.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.map { TuningAssignment(it.uid, value(it, false)) }
            val childMask = (mask + 11) % 32
            val childValues = allTypes.filterIndexed { i, _ -> childMask and (1 shl i) != 0 }.map { TuningAssignment(it.uid, value(it, true)) }
            val parent = root.copy(values = parentValues)
            val child = root.copy(uid = "profile.child", profileId = "competition", baseProfileUid = parent.uid, values = childValues)
            val resolved = resolveTuningProfile(child, listOf(parent, child), allTypes, emptyMap(), emptyMap())
            for (row in resolved) {
                val direct = childValues.firstOrNull { it.parameterUid == row.declaration.uid }
                val inherited = parentValues.firstOrNull { it.parameterUid == row.declaration.uid }
                assertEquals(direct?.value ?: inherited?.value ?: row.declaration.defaultValue, row.sourceTypedValue)
                assertEquals(if (direct != null) child.uid else if (inherited != null) parent.uid else null, row.sourceProfileId)
                assertEquals(if (direct != null) "PROFILE" else if (inherited != null) "INHERITED" else "DEFAULT", row.status.name)
            }
        }
    }

    @Test fun `proposal keys differ from source UIDs and unchanged inherited values stay inherited`() {
        val d = declaration().copy(key = "drive.translation.kP")
        val parent = root.copy(values = listOf(TuningAssignment(d.uid, TuningValue(doubleValue = 3.0))))
        val child = root.copy(uid = "profile.child", profileId = "competition", baseProfileUid = root.uid)
        val unchanged = buildTuningReview(child, listOf(parent, child), listOf(d), mapOf(d.key to TuningValue(doubleValue = 3.0)), emptyMap())
        assertTrue(unchanged.first.isEmpty()); assertTrue(unchanged.second.isEmpty())
        val changed = buildTuningReview(child, listOf(parent, child), listOf(d), mapOf(d.key to TuningValue(doubleValue = 4.0)),
            mapOf(d.key to TuningValueProvenance("manual", "")))
        assertTrue(changed.second.isEmpty())
        assertEquals(d.uid, changed.first.single().parameterUid)
        assertEquals(d.key, changed.first.single().key)
        assertEquals(TuningValue(doubleValue = 3.0), changed.first.single().before)
    }

    @Test fun `typed observations take precedence without hiding finite out of range feedback`() {
        val d = declaration().copy(minimum = 0.0, maximum = 5.0)
        val live = rows(listOf(d), live = mapOf(d.key to 4.0), typed = mapOf(d.key to TuningValue(doubleValue = 9.0))).single()
        assertEquals(9.0, live.liveValue)
        assertEquals(2.0, live.sourceValue)
        assertEquals("DEFAULT", live.status.name)
        assertNull(rows(listOf(d), live = mapOf(d.key to 4.0), typed = mapOf(d.key to TuningValue(intValue = 3))).single().liveTypedValue)
        for (decl in allTypes) {
            assertEquals(decl.defaultValue, rows(listOf(decl), typed = mapOf(decl.key to decl.defaultValue)).single().liveTypedValue)
        }
    }

    @Test fun `nonnumeric display and valid source with an optional note retain their values`() {
        for ((value, text) in listOf(TuningValue(intValue = Int.MIN_VALUE) to Int.MIN_VALUE.toString(),
            TuningValue(booleanValue = false) to "false", TuningValue(textValue = "") to "", TuningValue() to "unavailable")) {
            assertEquals(text, value.displayValue())
        }
        val d = declaration()
        val review = buildTuningReview(root, listOf(root), listOf(d), mapOf(d.key to TuningValue(doubleValue = 4.0)),
            mapOf(d.key to TuningValueProvenance("Guided simulation experiment", "")))
        assertEquals(1, review.first.size); assertTrue(review.second.isEmpty())
    }

    @Test fun `parent membership and unknown proposal checks do not rescan each input for every row`() {
        val count = 256
        val declarations = List(count) { declaration("p$it") }
        val assignments = CountedList(declarations.map { TuningAssignment(it.uid, TuningValue(doubleValue = 3.0)) })
        val parent = root.copy(values = assignments)
        val child = root.copy(uid = "profile.child", profileId = "competition", baseProfileUid = parent.uid)
        assertEquals(count, resolveTuningProfile(child, listOf(parent, child), declarations, emptyMap(), emptyMap()).size)
        assertTrue(assignments.reads <= count * 16, "Parent assignment reads: ${assignments.reads}")
        val catalog = CountedList(declarations)
        val proposals = (0 until count).associate { "unknown.p$it" to TuningValue(doubleValue = 3.0) }
        val (changes, errors) = buildTuningReview(root, listOf(root), catalog, proposals, emptyMap())
        assertTrue(changes.isEmpty()); assertEquals(count, errors.size)
        assertTrue(catalog.reads <= count * 16, "Declaration reads: ${catalog.reads}")
        println("Resolution input reads at 256 parameters: parent=${assignments.reads}, catalog=${catalog.reads}")
    }

    private class CountedList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): T { reads++; return values[index] }
    }
}

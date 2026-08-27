package com.ares.analytics.ui.components.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.tuning.*
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.TuningIntent
import com.ares.analytics.viewmodel.TuningState
import com.ares.analytics.viewmodel.TuningViewModel

@Composable
fun GainTuningPanel(
    viewModel: TuningViewModel,
    state: TuningState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.background(AresSurface, RoundedCornerShape(12.dp)).border(1.dp, AresBorder, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Robot tuning profiles", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Source is canonical. Live is observation. Proposed is an unsaved experiment.", color = AresTextSecondary, fontSize = 10.sp)
            }
            ProfileMenu(state, viewModel)
        }
        state.selectedProfile?.let { profile ->
            Text(
                "${profile.displayName} · ${com.areslib.tuning.TuningProfileDocumentCodec.contentHash(profile, state.catalog).take(12)}" + (profile.baseProfileUid?.let { " · inherits $it" } ?: " · no parent"),
                color = AresCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
        Text("Connecting a robot, Gemini proposal, or AutoTuner result cannot change this profile. Promotion requires validation, a structured diff, and explicit confirmation.", color = AresGold, fontSize = 10.sp)
        TuningEvidenceBoundary()
        state.errorMessage?.let { Banner(it, AresError) }
        if (state.saveStatus.isNotBlank()) Banner(state.saveStatus, AresGreen)
        Row(Modifier.fillMaxWidth().padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Header("Component / value", Modifier.weight(1.4f))
            Header("Source", Modifier.width(86.dp))
            Header("Live", Modifier.width(86.dp))
            Header("Proposed", Modifier.width(106.dp))
            Header("Policy", Modifier.width(116.dp))
        }
        HorizontalDivider(color = AresBorder)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.rows.groupBy { it.declaration.componentUid }.forEach { (component, rows) ->
                Text(component, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                rows.forEach { row ->
                    TuningValueRow(row, state.consumerSupportByUid[row.declaration.uid], viewModel)
                }
            }
            if (state.rows.isEmpty()) Text("No component declarations were found. Add a .arestuningcomponent file under .ares/tuning-components, or declare parameters in a drivetrain/subsystem document, then reload.", color = AresTextSecondary, fontSize = 11.sp)
        }
        HorizontalDivider(color = AresBorder)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                state.reviewerName,
                { viewModel.onIntent(TuningIntent.SetReviewerName(it)) },
                Modifier.weight(.7f).semantics { contentDescription = "Reviewer name required for canonical profile promotion" },
                label = { Text("Reviewer") }, singleLine = true
            )
            OutlinedTextField(
                state.reviewSummary,
                { viewModel.onIntent(TuningIntent.SetReviewSummary(it)) },
                Modifier.weight(1.3f).semantics { contentDescription = "Review summary explaining why the proposed tuning values should become canonical" },
                label = { Text("Review summary") }, singleLine = true
            )
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = { viewModel.onIntent(TuningIntent.DiscardProposal) }, enabled = state.proposals.isNotEmpty()) { Text("Discard proposal") }
                OutlinedButton(onClick = { viewModel.onIntent(TuningIntent.PullAllFromRobot) }, enabled = state.liveTypedValues.isNotEmpty()) { Text("Propose all live") }
                OutlinedButton(onClick = { viewModel.onIntent(TuningIntent.PushAllToRobot) }, enabled = state.proposals.isNotEmpty()) { Text("Live-test eligible") }
            }
            Button(onClick = { viewModel.onIntent(TuningIntent.ReviewPromotion) }, enabled = state.proposals.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)) { Text("Review promotion") }
        }
        state.review?.let { PromotionReview(it, viewModel) }
    }
}

@Composable
private fun TuningEvidenceBoundary() {
    Row(
        Modifier.fillMaxWidth()
            .background(AresBackground.copy(alpha = .45f), RoundedCornerShape(7.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(7.dp))
            .padding(8.dp)
            .semantics {
                contentDescription = "Tuning evidence flow: canonical source, local proposal, acknowledged live experiment, explicit profile promotion"
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EvidenceStage("1 · SOURCE", "Checked-in profile\nunchanged", Modifier.weight(1f))
        EvidenceStage("2 · PROPOSE", "Session-only value\nreversible", Modifier.weight(1f))
        EvidenceStage("3 · LIVE TEST", "NT4 request + exact\nrobot acknowledgement", Modifier.weight(1f))
        EvidenceStage("4 · PROMOTE", "Reviewed diff + history\nno robot push", Modifier.weight(1f))
    }
}

@Composable
private fun EvidenceStage(title: String, body: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Text(body, color = AresTextSecondary, fontSize = 8.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun ProfileMenu(state: TuningState, viewModel: TuningViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.widthIn(min = 150.dp, max = 220.dp),
        ) {
            Text(
                state.selectedProfile?.displayName ?: "Choose profile",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded, { expanded = false }) {
            state.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Column { Text(profile.displayName); Text("${profile.profileId} · ${profile.baseProfileUid?.let { "inherits $it" } ?: "root"}", color = AresTextSecondary, fontSize = 9.sp) } },
                    onClick = { expanded = false; viewModel.onIntent(TuningIntent.SelectProfile(profile.profileId)) }
                )
            }
        }
    }
}

@Composable
private fun TuningValueRow(row: ResolvedTuningValue, consumerSupported: Boolean?, viewModel: TuningViewModel) {
    val d = row.declaration
    var raw by remember(d.key, row.proposedTypedValue) { mutableStateOf(row.proposedTypedValue?.displayValue().orEmpty()) }
    var rawEdited by remember(d.key) { mutableStateOf(false) }
    var evidencePath by remember(d.key, row.provenance?.evidencePath) { mutableStateOf(row.provenance?.evidencePath.orEmpty()) }
    var evidenceHash by remember(d.key, row.provenance?.evidenceSha256) { mutableStateOf(row.provenance?.evidenceSha256.orEmpty()) }
    val policyText = when (d.applyPolicy) {
        com.areslib.tuning.TuningApplyPolicy.LIVE_SAFE -> "LIVE-SAFE"
        com.areslib.tuning.TuningApplyPolicy.DISABLED_ONLY -> "DISABLED ONLY"
        com.areslib.tuning.TuningApplyPolicy.RESTART_REQUIRED -> "RESTART"
        com.areslib.tuning.TuningApplyPolicy.REBUILD_REQUIRED -> "REBUILD"
        com.areslib.tuning.TuningApplyPolicy.CALIBRATION_ONLY -> "CALIBRATION"
        com.areslib.tuning.TuningApplyPolicy.READ_ONLY_VENDOR -> "READ-ONLY VENDOR"
    }
    Column(Modifier.fillMaxWidth().background(AresSurfaceElevated, RoundedCornerShape(8.dp)).border(1.dp, if (row.validationMessage == null) AresBorder else AresError, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.4f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.displayName, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    TuningHelp("${d.description} Unit: ${d.unit}. Declared range: ${d.minimum}–${d.maximum}. Owner: ${d.owner().name.lowercase().replace('_', ' ')}. Policy: ${policyText.lowercase()}.")
                }
                Text("${d.unit.orEmpty()} · ${d.minimum ?: "unbounded"}–${d.maximum ?: "unbounded"} · owner ${d.owner().name.lowercase().replace('_', ' ')}", color = AresTextSecondary, fontSize = 9.sp)
                Text(
                    when {
                        row.sourceProfileId != null && row.sourceProfileId != viewModel.state.value.selectedProfileId -> "Inherited from ${row.sourceProfileId}"
                        row.provenance != null -> "Provenance: ${row.provenance.source} — ${row.provenance.note}"
                        else -> "No source provenance recorded"
                    },
                    color = AresTextSecondary, fontSize = 9.sp, maxLines = 2
                )
            }
            TypedValueCell(row.sourceTypedValue, d.unit, "Canonical source value from ${row.sourceProfileId ?: "no profile"}", Modifier.width(86.dp))
            Column(Modifier.width(86.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TypedValueCell(row.liveTypedValue, d.unit, "Observed live robot value. This never changes source.", Modifier.fillMaxWidth())
                TextButton(onClick = { viewModel.onIntent(TuningIntent.PullFromRobot(d.key)) }, enabled = row.liveTypedValue != null && d.applyPolicy != com.areslib.tuning.TuningApplyPolicy.READ_ONLY_VENDOR, contentPadding = PaddingValues(0.dp)) { Text("Propose", fontSize = 9.sp) }
            }
            val rawError = rawEdited && when (d.type) {
                com.areslib.tuning.TuningParameterType.INT -> raw.toIntOrNull() == null
                com.areslib.tuning.TuningParameterType.DOUBLE -> raw.toDoubleOrNull()?.isFinite() != true
                else -> false
            }
            TypedProposalEditor(row, raw, { raw = it; rawEdited = true }, rawError, viewModel, Modifier.width(106.dp))
            Column(Modifier.width(116.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(policyText, color = when (d.applyPolicy) { com.areslib.tuning.TuningApplyPolicy.LIVE_SAFE -> AresGreen; com.areslib.tuning.TuningApplyPolicy.READ_ONLY_VENDOR -> AresTextSecondary; else -> AresGold }, fontWeight = FontWeight.Bold, fontSize = 8.sp)
                if (consumerSupported == false) {
                    Text("NO RUNTIME\nCONSUMER", color = AresError, fontWeight = FontWeight.Bold, fontSize = 8.sp)
                }
                OutlinedButton(
                    onClick = { viewModel.onIntent(TuningIntent.PushToRobot(d.key)) },
                    enabled = consumerSupported != false && row.proposedTypedValue != null &&
                        row.validationMessage == null &&
                        d.applyPolicy == com.areslib.tuning.TuningApplyPolicy.LIVE_SAFE,
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                ) { Text("Live-test", fontSize = 8.sp) }
            }
        }
        row.validationMessage?.let { Text(it, color = AresError, fontSize = 9.sp) }
        if (rawEdited && row.proposedTypedValue == null && d.type in setOf(com.areslib.tuning.TuningParameterType.INT, com.areslib.tuning.TuningParameterType.DOUBLE)) {
            Text("Enter a valid ${if (d.type == com.areslib.tuning.TuningParameterType.INT) "whole" else "finite"} number before live testing or review.", color = AresError, fontSize = 9.sp)
        }
        if (row.proposedTypedValue != null && (d.applyPolicy == com.areslib.tuning.TuningApplyPolicy.CALIBRATION_ONLY || row.provenance?.source?.contains("live", true) == true || row.provenance?.source?.contains("autotuner", true) == true)) {
            Text("Evidence required for promotion", color = AresGold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    evidencePath,
                    { value ->
                        evidencePath = value
                        val provenance = row.provenance ?: TuningValueProvenance("Student evidence", "Added in proposal review")
                        viewModel.onIntent(TuningIntent.SetProposalProvenance(d.key, provenance.source, provenance.note, value.ifBlank { null }, evidenceHash.ifBlank { null }))
                    },
                    Modifier.weight(1f).semantics { contentDescription = "Project-relative evidence path for ${d.displayName}" },
                    label = { Text("Project evidence path") }, singleLine = true
                )
                OutlinedTextField(
                    evidenceHash,
                    { value ->
                        evidenceHash = value
                        val provenance = row.provenance ?: TuningValueProvenance("Student evidence", "Added in proposal review")
                        viewModel.onIntent(TuningIntent.SetProposalProvenance(d.key, provenance.source, provenance.note, evidencePath.ifBlank { null }, value.ifBlank { null }))
                    },
                    Modifier.weight(1f).semantics { contentDescription = "SHA-256 evidence hash for ${d.displayName}" },
                    label = { Text("Evidence SHA-256") }, singleLine = true
                )
            }
        }
    }
}

@Composable
private fun TypedValueCell(value: TuningValue?, unit: String?, accessibility: String, modifier: Modifier) {
    val display = value?.displayValue()
    val shownUnit = unit.orEmpty()
    Text(display?.let { "$it\n$shownUnit" } ?: "—\n$shownUnit", color = if (value == null) AresTextSecondary else AresTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 9.sp, modifier = modifier.semantics { contentDescription = "$accessibility. ${display ?: "Unavailable"} $shownUnit" }, maxLines = 2)
}

@Composable
private fun TypedProposalEditor(row: ResolvedTuningValue, raw: String, onRaw: (String) -> Unit, rawError: Boolean, viewModel: TuningViewModel, modifier: Modifier) {
    val d = row.declaration
    val enabled = d.applyPolicy != com.areslib.tuning.TuningApplyPolicy.READ_ONLY_VENDOR
    when (d.type) {
        com.areslib.tuning.TuningParameterType.BOOLEAN -> Switch(
            checked = row.proposedTypedValue?.booleanValue ?: row.sourceTypedValue?.booleanValue ?: false,
            onCheckedChange = { viewModel.onIntent(TuningIntent.UpdateTypedConstant(d.key, TuningValue(booleanValue = it))) },
            enabled = enabled,
            modifier = modifier.semantics { contentDescription = "Proposed ${d.displayName}. ${d.description}" }
        )
        com.areslib.tuning.TuningParameterType.ENUM -> {
            var open by remember(d.key) { mutableStateOf(false) }
            Box(modifier) {
                OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(row.proposedTypedValue?.textValue ?: row.sourceTypedValue?.textValue ?: "Choose", fontSize = 9.sp) }
                DropdownMenu(open, { open = false }) { d.enumOptions.forEach { option -> DropdownMenuItem({ Text(option) }, { open = false; viewModel.onIntent(TuningIntent.UpdateTypedConstant(d.key, TuningValue(textValue = option))) }) } }
            }
        }
        com.areslib.tuning.TuningParameterType.TEXT,
        com.areslib.tuning.TuningParameterType.DOUBLE,
        com.areslib.tuning.TuningParameterType.INT -> OutlinedTextField(
            raw,
            { text ->
                onRaw(text)
                when (d.type) {
                    com.areslib.tuning.TuningParameterType.TEXT -> viewModel.onIntent(TuningIntent.UpdateTypedConstant(d.key, TuningValue(textValue = text)))
                    com.areslib.tuning.TuningParameterType.INT -> text.toIntOrNull()
                        ?.let { viewModel.onIntent(TuningIntent.UpdateTypedConstant(d.key, TuningValue(intValue = it))) }
                        ?: viewModel.onIntent(TuningIntent.InvalidateTypedConstant(d.key, "${d.displayName} requires a whole number."))
                    else -> text.toDoubleOrNull()?.takeIf(Double::isFinite)
                        ?.let { viewModel.onIntent(TuningIntent.UpdateTypedConstant(d.key, TuningValue(doubleValue = it))) }
                        ?: viewModel.onIntent(TuningIntent.InvalidateTypedConstant(d.key, "${d.displayName} requires a finite number."))
                }
            },
            modifier.semantics { contentDescription = "Proposed ${d.displayName} in ${d.unit}. ${d.description}. Range ${d.minimum} to ${d.maximum}." },
            enabled = enabled, singleLine = true, placeholder = { Text("—") }, isError = rawError || row.validationMessage != null
        )
    }
}

@Composable
private fun PromotionReview(review: TuningProposalReview, viewModel: TuningViewModel) {
    Column(Modifier.fillMaxWidth().background(AresBackground.copy(alpha = .55f), RoundedCornerShape(8.dp)).border(1.dp, if (review.canPromote) AresCyan else AresError, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("STRUCTURED PROFILE DIFF · base ${review.baseContentHash.take(12)}", color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        review.changes.forEach { change -> Text("${change.displayName}: ${change.before?.displayValue() ?: "unset"} → ${change.after.displayValue()} ${change.unit} · ${change.policy.name.lowercase().replace('_', ' ')} · ${change.provenance.source}", color = AresTextPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
        review.errors.forEach { Text("BLOCKED: $it", color = AresError, fontSize = 9.sp) }
        Text("Confirmation ${review.confirmationToken}", color = AresTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Button(onClick = { viewModel.onIntent(TuningIntent.ConfirmPromotion(review.confirmationToken)) }, enabled = review.canPromote, colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent)) { Text("Confirm atomic profile promotion") }
        Text("A history backup is created first. Promotion writes one .arestuning file and never pushes NT4 or edits source/vendor code.", color = AresTextSecondary, fontSize = 9.sp)
    }
}

@Composable private fun Header(text: String, modifier: Modifier) { Text(text.uppercase(), color = AresTextSecondary, fontWeight = FontWeight.Bold, fontSize = 8.sp, modifier = modifier) }
@Composable private fun Banner(text: String, color: androidx.compose.ui.graphics.Color) { Text(text, color = color, fontSize = 10.sp, modifier = Modifier.fillMaxWidth().background(color.copy(alpha = .08f), RoundedCornerShape(5.dp)).padding(7.dp)) }

@Composable
private fun TuningHelp(help: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(26.dp).semantics { contentDescription = "Help: $help" }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "Show tuning field help", tint = AresTextSecondary, modifier = Modifier.size(14.dp)) }
        DropdownMenu(open, { open = false }) { Text(help, color = AresTextPrimary, fontSize = 10.sp, modifier = Modifier.widthIn(max = 330.dp).padding(12.dp)) }
    }
}

private fun format(value: Double): String = "%.5f".format(value).trimEnd('0').trimEnd('.')

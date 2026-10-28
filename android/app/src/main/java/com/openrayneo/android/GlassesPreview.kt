// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Renders the logical cells from the exact payload, without generating a second arrow or road
 * label.
 */
@Composable
internal fun NavigationGrid(frame: String, modifier: Modifier = Modifier) {
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
    }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(20f / 7f).semantics {
            contentDescription =
                "Glasses navigation layout, 20 columns by 5 rows." + frame.replace('█', ' ').replace('　', ' ').trim()
        }
    ) {
        val cellWidth = size.width / 20
        val cellHeight = size.height / 5
        paint.color = SignalGreen.toArgb()
        paint.textSize = cellWidth * .95f
        val baselineOffset = -(paint.ascent() + paint.descent()) / 2
        frame.split('\n').forEachIndexed { row, text ->
            text.forEachIndexed { column, character ->
                if (character == '█') {
                    drawRect(
                        SignalGreen,
                        Offset(column * cellWidth, row * cellHeight),
                        Size(cellWidth, cellHeight),
                    )
                } else if (character != '　' && character != ' ') {
                    drawContext.canvas.nativeCanvas.drawText(
                        character.toString(),
                        (column + .5f) * cellWidth,
                        (row + .5f) * cellHeight + baselineOffset,
                        paint,
                    )
                }
            }
        }
    }
}

@Composable
internal fun GridPreview(frame: String, label: String) {
    Surface(
        color = LensBlack,
        contentColor = SignalGreen,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            NavigationGrid(frame)
        }
    }
}

@Composable
internal fun GlassesOutputPreview(state: GlassesState) {
    val session = state.session
    val content = session?.writtenContent
    val available =
        state.link.phase == LinkPhase.FULL &&
            state.ownership == DisplayOwnership.APP &&
            content != null
    val title =
        when {
            state.ownership == DisplayOwnership.YIELDED -> "Display released"
            state.link.phase != LinkPhase.FULL -> "Not sent to glasses yet"
            session == null -> "App display inactive"
            session.phase == PagePhase.CLOSING ->
                if (state.intent == DisplayIntent.Idle) "Closing display" else "Switching display"
            content == null -> "Waiting for first display update"
            session.content != content -> "Updating · Last sent content below"
            session.mode == PageMode.NAVIGATION -> "Navigation · Sent content"
            else -> "Live prompts · Sent content"
        }
    Surface(
        color = LensBlack,
        contentColor = SignalGreen,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            if (available) {
                if (session.mode == PageMode.NAVIGATION) NavigationGrid(content.text)
                else
                    Column(
                        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(content.text, style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(color = SignalGreen.copy(alpha = .2f))
                        Text(content.answer.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    }
            } else {
                Text(
                    when {
                        state.ownership == DisplayOwnership.YIELDED -> "The glasses control their current page; the phone cannot read it."
                        state.link.phase != LinkPhase.FULL -> "After a full connection, content sent in this session appears here."
                        session != null -> state.displayDescription
                        else -> "Select navigation or open live prompts."
                    },
                    Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Note("Preview of sent content, not a live screenshot.", Modifier.padding(top = 8.dp))
}

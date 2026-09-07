package com.adong.adchat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.*

/** A single scrollable options surface for compact screens, large fonts and keyboards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsterOptionsSheet(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    headerIcon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Canvas
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AsterSheetHeader(title, subtitle, headerIcon, onDismiss)
            content()
        }
    }
}

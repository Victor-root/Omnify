package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.theme.LocalOnAccentBarColor

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(width = (24 + 12).dp, height = 40.dp)
            // TV only: visible focus ring on the accent top bar (no-op on touch).
            .tvFocusOutline(RoundedCornerShape(12.dp), color = LocalOnAccentBarColor.current),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

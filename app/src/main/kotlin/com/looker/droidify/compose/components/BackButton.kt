package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.theme.LocalIsTelevision

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            // TV: a square button so the focus halo is a clean circle (the slim default size makes it
            // an oval); the focused arrow also scales up. Unchanged on touch.
            .then(
                if (LocalIsTelevision.current) {
                    Modifier.size(48.dp)
                } else {
                    Modifier.size(width = (24 + 12).dp, height = 40.dp)
                },
            )
            .tvFocusScale(),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

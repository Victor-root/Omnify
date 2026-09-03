package com.looker.droidify.compose.repoEdit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.forFloatingBackground
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.compose.theme.tvTopAppBarColors
import com.looker.droidify.compose.tv.TvAccentBackground
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoEditScreen(
    repoId: Int?,
    onBackClick: () -> Unit,
    viewModel: RepoEditViewModel = hiltViewModel(),
) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorState by viewModel.errorState.collectAsStateWithLifecycle()
    val authEnabled by viewModel.authEnabled.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val isFormValid by remember { derivedStateOf { !errorState.hasError } }

    // The screen's job is done the moment the repository is in the database, so it steps aside. It used
    // to stay open on a save that had worked, which reads exactly like one that hadn't.
    LaunchedEffect(saved) {
        if (saved) {
            viewModel.consumeSaved()
            onBackClick()
        }
    }

    // TV / D-pad: the top bar doesn't release focus downward on its own, so "down" on the back arrow
    // would leave the user stuck in the header. This points at the first field; the key handler below
    // moves focus into the form. No effect on touch.
    val contentFocusRequester = remember { FocusRequester() }
    val isTelevision = LocalIsTelevision.current
    // Android TV must always land the D-pad focus somewhere on entry, or a remote press with nothing
    // focused times out input dispatch and kills the app. Lands on the address field, retried briefly
    // because it isn't laid out on the very first frame. No-op on touch.
    if (isTelevision) {
        LaunchedEffect(Unit) {
            repeat(20) {
                if (runCatching { contentFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(50)
            }
        }
    }

    LaunchedEffect(repoId) {
        repoId?.let { viewModel.loadRepo(it) }
    }

    // A repository someone was sent, opened from a message: the form starts filled in with whatever
    // the link named. Read once and dropped, so coming back to this screen later never refills a form
    // the user has since cleared on purpose, and never over a repository being edited.
    val pendingLink by PendingRepoLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        if (repoId == null) viewModel.setFromLink(link)
        PendingRepoLink.clear()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    if (isTelevision) TvAccentBackground()
    Scaffold(
        containerColor = if (isTelevision) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = if (isTelevision) tvTopAppBarColors() else accentTopAppBarColors(),
                expandedHeight = if (isTelevision) TopAppBarDefaults.TopAppBarExpandedHeight else AccentBarHeight,
                modifier = Modifier.tvDpadDownTo(contentFocusRequester),
                title = {
                    Text(
                        text = stringResource(
                            if (repoId != null) {
                                R.string.edit_repository
                            } else {
                                R.string.add_repository
                            },
                        ),
                        fontWeight = if (isTelevision) FontWeight.Bold else null,
                    )
                },
                navigationIcon = { BackButton(onBackClick) },
                actions = {
                    IconButton(
                        onClick = { viewModel.saveRepository() },
                        enabled = isFormValid,
                        modifier = Modifier.tvFocusScale(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Its own, edge-to-edge-aware padding (see forFloatingBackground's doc comment) — not the
            // real content's paddingValues below, so the wash reaches a transparent navigation bar
            // instead of stopping short of it.
            if (!isTelevision) FloatingAppCardsBackground(
                Modifier.padding(paddingValues.forFloatingBackground()),
            )
            // The keyboard covers the form otherwise, with nothing to scroll: the window is drawn
            // edge-to-edge, so it no longer resizes itself around the keyboard and the fields underneath
            // it simply stay there, out of reach. consumeWindowInsets says the bars are already paid for
            // just above, so what imePadding adds is the keyboard alone rather than the two stacked.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .imePadding(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val hasAddressError by remember { derivedStateOf { errorState.addressError != null } }
                    OutlinedTextField(
                        value = viewModel.addressState.text.toString(),
                        onValueChange = { viewModel.addressState.edit { replace(0, length, it) } },
                        label = { Text(stringResource(R.string.address)) },
                        isError = hasAddressError,
                        supportingText = { errorState.addressError?.let { Text(stringResource(it)) } },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(contentFocusRequester),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val hasFingerprintError by remember { derivedStateOf { errorState.fingerprintError != null } }
                    OutlinedTextField(
                        value = viewModel.fingerprintState.text.toString(),
                        onValueChange = { viewModel.fingerprintState.edit { replace(0, length, it) } },
                        label = { Text(stringResource(R.string.fingerprint)) },
                        isError = hasFingerprintError,
                        supportingText = { errorState.fingerprintError?.let { Text(stringResource(it)) } },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.requires_authentication))
                        Switch(
                            checked = authEnabled,
                            onCheckedChange = { viewModel.setAuthEnabled(it) },
                        )
                    }

                    AnimatedVisibility(visible = authEnabled) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))

                            val hasUsernameError by remember { derivedStateOf { errorState.usernameError != null } }
                            OutlinedTextField(
                                value = viewModel.usernameState.text.toString(),
                                onValueChange = { viewModel.usernameState.edit { replace(0, length, it) } },
                                label = { Text(stringResource(R.string.username)) },
                                isError = hasUsernameError,
                                supportingText = { errorState.usernameError?.let { Text(stringResource(it)) } },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            val hasPasswordError by remember { derivedStateOf { errorState.passwordError != null } }
                            OutlinedTextField(
                                value = viewModel.passwordState.text.toString(),
                                onValueChange = { viewModel.passwordState.edit { replace(0, length, it) } },
                                label = { Text(stringResource(R.string.password)) },
                                isError = hasPasswordError,
                                supportingText = { errorState.passwordError?.let { Text(stringResource(it)) } },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Saves without asking the server anything first, for a repository that can't answer
                    // right now: it's offline, it's behind a VPN that isn't up yet, or it doesn't publish
                    // the index file the check looks for. Without it those are all turned away as "the
                    // repository wasn't found". "Skip" said none of that.
                    Button(
                        onClick = { viewModel.saveRepository(skipCheck = true) },
                        enabled = isFormValid || isLoading,
                        modifier = Modifier.align(Alignment.End).tvFocusScale(),
                    ) {
                        Text(stringResource(R.string.add_without_checking))
                    }
                }

                // Loading overlay
                AnimatedVisibility(
                    visible = isLoading,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.checking_repository),
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

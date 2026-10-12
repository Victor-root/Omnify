package com.looker.droidify.compose.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.PageIndicator
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.components.verticalScrollbar
import com.looker.droidify.compose.repoList.AppLauncherIcon
import com.looker.droidify.compose.settings.InstallerTypeSetting
import com.looker.droidify.compose.settings.components.SettingHeader
import com.looker.droidify.compose.welcome.PrefetchWelcomeLogos
import com.looker.droidify.compose.welcome.WelcomeItem
import com.looker.droidify.compose.welcome.WelcomeKind
import com.looker.droidify.compose.welcome.WelcomeSourceDescription
import com.looker.droidify.compose.welcome.WelcomeSourceIcon
import com.looker.droidify.compose.welcome.WelcomeStep
import com.looker.droidify.compose.welcome.WelcomeViewModel
import com.looker.droidify.compose.welcome.rememberWelcomeSteps
import com.looker.droidify.datastore.model.InstallerType
import kotlinx.coroutines.delay

private const val PAGE_COUNT = 2

/**
 * The Android TV welcome of a fresh install (see [com.looker.droidify.compose.welcome.WelcomeScreen] for
 * what the two pages are for), laid out for a remote. Each page keeps its title and buttons on the left
 * and its content on the right, so the button that goes on never has to be scrolled to, and focus
 * starts on it: accepting everything as it stands takes one press per page. Back steps to the page
 * before. It is the same [WelcomeViewModel], so what it does is exactly what the phone does. Never
 * composed off TV.
 */
@Composable
fun TvWelcomeScreen(
    viewModel: WelcomeViewModel,
    onDone: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page -= 1 }

    // The logos of the second page are fetched while the first is still being read.
    PrefetchWelcomeLogos(viewModel.items.collectAsStateWithLifecycle().value)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TvAccentBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = TvOverscan + 8.dp,
                    end = TvOverscan + 8.dp,
                    top = TvOverscan,
                    bottom = TvOverscan,
                ),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 5 else -it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 5 else it / 5 } + fadeOut())
                },
                label = "welcomePage",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { target ->
                if (target == 0) {
                    TvSetupPage(viewModel = viewModel, onNext = { page = 1 })
                } else {
                    TvSourcesPage(viewModel = viewModel, onBack = { page = 0 }, onDone = onDone)
                }
            }
            PageIndicator(
                pageCount = PAGE_COUNT,
                currentPage = page,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
            )
        }
    }
}

/** Focus on entry, or a remote press with nothing focused times out input dispatch and kills the app.
 *  Retried briefly because the page isn't laid out on its very first frame. */
@Composable
private fun rememberEntryFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
    return requester
}

/** The first page: the welcome on the left, what Omnify needs to be allowed on the right. */
@Composable
private fun TvSetupPage(viewModel: WelcomeViewModel, onNext: () -> Unit) {
    val installerType by viewModel.installerType.collectAsStateWithLifecycle()
    val steps = rememberWelcomeSteps()
    val nextFocus = rememberEntryFocus()
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = spacedBy(48.dp)) {
        Column(
            modifier = Modifier.weight(0.4f).fillMaxHeight().focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            AppLauncherIcon(modifier = Modifier.size(88.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .tvFocusScale(1.05f)
                    .focusRequester(nextFocus),
            ) { Text(stringResource(R.string.welcome_next)) }
        }
        TvSetupCard(
            steps = steps,
            installerType = installerType,
            onInstallerSelected = viewModel::setInstaller,
            modifier = Modifier.weight(0.6f).fillMaxHeight().focusGroup(),
        )
    }
}

/** What Omnify needs from the device: each permission as a focus stop, then the installer. */
@Composable
private fun TvSetupCard(
    steps: List<WelcomeStep>,
    installerType: InstallerType,
    onInstallerSelected: (InstallerType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = spacedBy(6.dp, Alignment.CenterVertically)) {
        steps.forEach { step -> TvStepRow(step) }
        InstallerTypeSetting(
            icon = painterResource(R.drawable.ic_tabler_package),
            selectedInstaller = installerType,
            onInstallerSelected = onInstallerSelected,
        )
    }
}

/** One permission, as a single focus stop: OK goes and gets it. A tick replaces the label once given. */
@Composable
private fun TvStepRow(step: WelcomeStep) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusFill(RoundedCornerShape(18.dp))
            .clickable(enabled = !step.done, onClick = step.onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Icon(
            painter = painterResource(step.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(step.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(step.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        if (step.done) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.welcome_allowed),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = stringResource(R.string.allow),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The second page: the question and the buttons on the left, the sources on the right. */
@Composable
private fun TvSourcesPage(viewModel: WelcomeViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val finishing by viewModel.finishing.collectAsStateWithLifecycle()
    val allChecked = items.isNotEmpty() && items.all { it.checked }
    val continueFocus = rememberEntryFocus()
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = spacedBy(48.dp)) {
        Column(
            modifier = Modifier.weight(0.4f).fillMaxHeight().focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(R.string.welcome_sources_page_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.welcome_sources_page_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.finish(onDone) },
                enabled = !finishing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .tvFocusScale(1.05f)
                    .focusRequester(continueFocus),
            ) {
                Text(
                    stringResource(
                        if (items.any { it.checked }) {
                            R.string.welcome_continue
                        } else {
                            R.string.welcome_continue_without_sources
                        },
                    ),
                )
            }
            OutlinedButton(
                onClick = { viewModel.setAll(!allChecked) },
                enabled = !finishing,
                modifier = Modifier.fillMaxWidth().height(60.dp).tvFocusScale(1.05f),
            ) {
                Text(
                    stringResource(
                        if (allChecked) R.string.welcome_deselect_all else R.string.welcome_select_all,
                    ),
                )
            }
            OutlinedButton(
                onClick = onBack,
                enabled = !finishing,
                modifier = Modifier.fillMaxWidth().height(60.dp).tvFocusScale(1.05f),
            ) { Text(stringResource(R.string.tv_back)) }
        }
        val listState = rememberLazyListState()
        // The apps made for television follow the repositories under a heading of their own.
        val firstTvApp = items.indexOfFirst { it.kind is WelcomeKind.TvApp }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .verticalScrollbar(listState)
                .focusGroup(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                Column {
                    if (index == firstTvApp) SettingHeader(stringResource(R.string.discover_tv_apps))
                    TvWelcomeSourceRow(
                        item = item,
                        enabled = !finishing,
                        onToggle = { viewModel.toggle(item.id) },
                    )
                }
            }
        }
    }
}

/** One source. The whole row, tick included, is a single focus stop that flips the tick, the simplest
 *  interaction on a remote, as on the repositories screen's own rows. */
@Composable
private fun TvWelcomeSourceRow(
    item: WelcomeItem,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusFill(RoundedCornerShape(16.dp))
            .toggleable(
                value = item.checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        WelcomeSourceIcon(item = item, size = 56.dp)
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WelcomeSourceDescription(item = item, maxLines = 2)
        }
        Spacer(Modifier.size(12.dp))
        // Display-only: the row is the focus stop, so the tick is not a second one.
        Checkbox(checked = item.checked, onCheckedChange = null)
    }
}

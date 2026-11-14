package app.lectrice.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lectrice.core.designsystem.ChevronRight
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.ListRow
import app.lectrice.core.designsystem.StatusBarScrim
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** File 18 §3.2: the GPL notice, then one row per component; a row opens its license texts from `assets/licenses/`. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val c = LectriceTheme.colors
    var openIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    BackHandler(enabled = openIndex != null) { openIndex = null }
    val open = openIndex?.let { Licenses.components.getOrNull(it) }
    if (open != null) {
        LicenseTextScreen(open, onBack = { openIndex = null })
        return
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(stringResource(R.string.licenses_title), onBack, stringResource(R.string.common_back))
            Text(Licenses.GPL_NOTICE, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = LectriceTheme.type.body)
            Licenses.components.forEachIndexed { index, component ->
                ListRow(component.name, subtitle = component.license, onClick = { openIndex = index }, trailing = { ChevronRight() })
            }
            Spacer(Modifier.navigationBarsPadding().height(48.dp))
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun LicenseTextScreen(component: LicensedComponent, onBack: () -> Unit) {
    val c = LectriceTheme.colors
    val context = LocalContext.current
    var text by rememberSaveable(component.name) { mutableStateOf("") }
    LaunchedEffect(component) {
        if (text.isEmpty()) {
            text = withContext(Dispatchers.IO) {
                component.files.joinToString("\n\n\n") { file -> context.assets.open("$ASSET_FOLDER/$file").bufferedReader().use { it.readText() } }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(component.name, onBack, stringResource(R.string.common_back))
            Text(component.license, Modifier.padding(horizontal = 16.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
            Text(text, Modifier.padding(16.dp), style = LectriceTheme.type.bodyS)
            Spacer(Modifier.navigationBarsPadding().height(32.dp))
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
    }
}

private const val ASSET_FOLDER = "licenses"

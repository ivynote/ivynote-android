package app.ivynote.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.android.IvyApp
import app.ivynote.core.L10n
import app.ivynote.core.Language
import app.ivynote.core.LanguageChoice
import app.ivynote.core.Strings

/**
 * The strings of the language in use. Static: a change recomposes everything
 * below, so text built outside composables (with [L10n.s]) updates too.
 */
val LocalStrings = staticCompositionLocalOf<Strings> { L10n.s }

/** Provides the app's current language to [content]. */
@Composable
fun ProvideStrings(content: @Composable () -> Unit) {
    val language by IvyApp.instance.language.current.collectAsState()
    CompositionLocalProvider(LocalStrings provides language.strings, content = content)
}

/** Each language in its own name, so it can be found whatever the interface speaks. */
val Language.nativeName: String
    get() = when (this) {
        Language.ZH -> "简体中文"
        Language.EN -> "English"
    }

/** "跟随系统（English）", "简体中文". */
fun choiceLabel(choice: LanguageChoice, s: Strings = L10n.s): String = when (choice) {
    LanguageChoice.System -> s.followSystemWith(IvyApp.instance.language.systemLanguage.nativeName)
    is LanguageChoice.Fixed -> choice.language.nativeName
}

/**
 * Picks the interface language: follow the system, or one language. Signed in
 * to a synced library, the choice is the account's (guide: the account's
 * language wins on every device); otherwise this device's.
 */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val c = Ivy.colors
    val settings = IvyApp.instance.language
    val selected by settings.selected.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.language) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (choice in LanguageChoice.all) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.RadioButton) {
                                settings.choose(choice)
                                onDismiss()
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.ivy))
                        Spacer(Modifier.width(12.dp))
                        Text(choiceLabel(choice, s), color = c.ink, fontSize = 15.sp)
                    }
                }
                Text(
                    if (settings.hasAccount) s.languageAccountHint else s.languageDeviceHint,
                    color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.done) } },
    )
}

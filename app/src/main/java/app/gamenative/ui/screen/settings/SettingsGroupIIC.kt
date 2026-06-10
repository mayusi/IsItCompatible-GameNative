package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink

/**
 * Settings section describing IIC Edition features.
 * Shown in Settings so users know what this fork adds over official GameNative.
 */
@Composable
fun SettingsGroupIIC() {
    SettingsGroup {
        // Disclaimer row
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_iic_disclaimer)) },
            onClick = {},
        )

        // Feature list as a readable block
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• " + stringResource(R.string.settings_iic_feature_fixes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• " + stringResource(R.string.settings_iic_feature_storage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• " + stringResource(R.string.settings_iic_feature_import),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

package skidworks.app.simpleboot

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import skidworks.app.simpleboot.ui.theme.SimpleBootTheme

@Composable
fun CreditsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            SimpleBootTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.baseline_skip_previous_24),
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Skidworks logo
                Image(
                    painter = painterResource(id = R.drawable.skidworks_logo),
                    contentDescription = "Skidworks Logo",
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .aspectRatio(3.2f)
                        .padding(bottom = 16.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                    contentScale = ContentScale.Fit
                )

                // Profile picture (Matthew DaLuz)
                Image(
                    painter = painterResource(id = R.drawable.matthew_profile),
                    contentDescription = "Matthew DaLuz",
                    modifier = Modifier
                        .size(112.dp)
                        .padding(bottom = 1.dp)
                        .clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                        .rotate(-90f),
                    contentScale = ContentScale.Crop
                )

                Text(
                    text = "Matthew DaLuz",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Lead Developer, Skidworks\nmatthewdaluz@skidworks.sh",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text(
                    text = "SimpleBoot is a free and open-source Android application developed under Skidworks.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Licensed under the GNU General Public License v3 (GPLv3).",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(onClick = {
                    val url = "https://skidworks.sh/".toUri()
                    val intent = Intent(Intent.ACTION_VIEW, url)
                    context.startActivity(intent)
                }) {
                    Text("Visit Skidworks")
                }

                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    text = "v${BuildConfig.VERSION_NAME}\n© 2026 Skidworks",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Light
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PreviewCreditsScreen() {
    SimpleBootTheme {
        CreditsScreen(onBack = {})
    }
}


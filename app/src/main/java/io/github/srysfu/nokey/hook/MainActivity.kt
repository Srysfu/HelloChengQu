package io.github.srysfu.nokey.hook

import android.media.MediaPlayer
import android.media.RingtoneManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import io.github.srysfu.nokey.hook.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AdaptiveTextColors(
    val primary: Color,
    val secondary: Color,
    val muted: Color,
    val icon: Color,
    val isLightBackground: Boolean
)

private val LocalAdaptiveTextColors = compositionLocalOf {
    AdaptiveTextColors(
        primary = Color.White,
        secondary = Color.White.copy(alpha = 0.82f),
        muted = Color.White.copy(alpha = 0.68f),
        icon = Color.White,
        isLightBackground = false
    )
}

private fun bitmapLuminance(bitmap: android.graphics.Bitmap): Float {
    val sample = android.graphics.Bitmap.createScaledBitmap(bitmap, 1, 1, true)
    val pixel = sample.getPixel(0, 0)
    if (sample !== bitmap) sample.recycle()
    val r = android.graphics.Color.red(pixel) / 255f
    val g = android.graphics.Color.green(pixel) / 255f
    val b = android.graphics.Color.blue(pixel) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun adaptiveColors(bitmap: android.graphics.Bitmap?): AdaptiveTextColors {
    val isLight = bitmap != null && bitmapLuminance(bitmap) >= 0.52f
    return if (isLight) {
        AdaptiveTextColors(
            primary = Color(0xFF111111),
            secondary = Color(0xFF222222).copy(alpha = 0.86f),
            muted = Color(0xFF333333).copy(alpha = 0.76f),
            icon = Color(0xFF111111),
            isLightBackground = true
        )
    } else {
        AdaptiveTextColors(
            primary = Color.White,
            secondary = Color.White.copy(alpha = 0.88f),
            muted = Color.White.copy(alpha = 0.74f),
            icon = Color.White,
            isLightBackground = false
        )
    }
}


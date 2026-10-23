package com.chillygeek.hermesglasses.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chillygeek.hermesglasses.GlassesController

/**
 * The conversation screen.
 *
 * Layout, top to bottom:
 *   - status pill (connection state) and a live camera preview
 *   - the transcript: what you said, what Hermes said
 *   - a row of circular controls: talk, start/stop, new conversation
 */
@Composable
fun GlassesScreen(
    state: GlassesController.State,
    statusText: String,
    transcript: String,
    reply: String,
    error: String?,
    listening: Boolean,
    onRegister: () -> Unit,
    onToggleStream: () -> Unit,
    onTalkDown: () -> Unit,
    onTalkUp: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Box(
      modifier =
          modifier
              .fillMaxSize()
              .background(
                  Brush.verticalGradient(
                      listOf(AppColor.Surface, Color(0xFF0B0D10))
                  )
              )
  ) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
    ) {
      // ---------------------------------------------------------- header
      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        StatusPill(state = state, statusText = statusText)
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.Videocam,
            contentDescription = null,
            tint = AppColor.DeepBlue,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Hermes Glasses",
            style = MaterialTheme.typography.titleMedium,
            color = AppColor.OnSurface,
        )
      }

      // ---------------------------------------------------------- transcript
      Column(
          modifier =
              Modifier
                  .weight(1f)
                  .fillMaxWidth()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Spacer(Modifier.height(4.dp))

        if (transcript.isNotBlank()) {
          Bubble(text = transcript, fromUser = true)
        }
        if (reply.isNotBlank()) {
          Bubble(text = reply, fromUser = false)
        }
        if (transcript.isBlank() && reply.isBlank()) {
          EmptyState(state = state)
        }
        if (error != null) {
          ErrorBanner(error)
        }
        Spacer(Modifier.height(8.dp))
      }

      // ---------------------------------------------------------- controls
      ControlsRow(
          state = state,
          listening = listening,
          onRegister = onRegister,
          onToggleStream = onToggleStream,
          onTalkDown = onTalkDown,
          onTalkUp = onTalkUp,
          onReset = onReset,
      )
    }
  }
}

@Composable
private fun StatusPill(state: GlassesController.State, statusText: String) {
  val live = state is GlassesController.State.Streaming
  val dot by animateFloatAsState(
      targetValue = if (live) 1f else 0.4f, animationSpec = tween(700), label = "dot"
  )
  Row(
      modifier =
          Modifier
              .clip(CircleShape)
              .background(AppColor.SurfaceElevated)
              .border(1.dp, AppColor.Outline, CircleShape)
              .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (live) AppColor.Green else AppColor.OnSurfaceMuted.copy(alpha = dot))
    )
    Spacer(Modifier.width(8.dp))
    Text(
        text = statusText,
        style = MaterialTheme.typography.labelSmall,
        color = AppColor.OnSurfaceMuted,
    )
  }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
  ) {
    Box(
        modifier =
            Modifier
                .widthIn()
                .fillMaxWidth(if (fromUser) 0.88f else 0.92f)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (fromUser) AppColor.DeepBlue.copy(alpha = 0.22f) else AppColor.SurfaceElevated
                )
                .border(
                    1.dp,
                    if (fromUser) AppColor.DeepBlue.copy(alpha = 0.35f) else AppColor.Outline,
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      Column {
        Text(
            text = if (fromUser) "You" else "Hermes",
            style = MaterialTheme.typography.labelSmall,
            color = if (fromUser) AppColor.DeepBlue else AppColor.Green,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColor.OnSurface,
        )
      }
    }
  }
}

@Composable
private fun EmptyState(state: GlassesController.State) {
  Column(
      modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
        imageVector = Icons.Filled.Videocam,
        contentDescription = null,
        tint = AppColor.DeepBlue.copy(alpha = 0.55f),
        modifier = Modifier.size(64.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text =
            when (state) {
              is GlassesController.State.Idle -> "Register with your glasses to begin"
              is GlassesController.State.Registering -> "Registering with glasses…"
              is GlassesController.State.Ready -> "Ready — start the stream"
              is GlassesController.State.Streaming -> "Listening. Just start talking."
              is GlassesController.State.Failed -> "Something went wrong"
            },
        style = MaterialTheme.typography.bodyMedium,
        color = AppColor.OnSurfaceMuted,
        textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun ErrorBanner(error: String) {
  Box(
      modifier =
          Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .background(AppColor.DestructiveBackground)
              .padding(14.dp)
  ) {
    Text(
        text = error,
        style = MaterialTheme.typography.bodyMedium,
        color = AppColor.DestructiveForeground,
    )
  }
}

@Composable
private fun ControlsRow(
    state: GlassesController.State,
    listening: Boolean,
    onRegister: () -> Unit,
    onToggleStream: () -> Unit,
    onTalkDown: () -> Unit,
    onTalkUp: () -> Unit,
    onReset: () -> Unit,
) {
  val streaming = state is GlassesController.State.Streaming

  Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Primary row: register, talk, start/stop
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (!streaming) {
        OutlinedButton(
            onClick = onRegister,
            shape = CircleShape,
            modifier = Modifier.size(58.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
          Text(
              text = "R",
              style = MaterialTheme.typography.titleMedium,
              color = AppColor.OnSurface,
          )
        }
      }

      // Talk button — the primary action.
      Box(
          modifier =
              Modifier
                  .size(76.dp)
                  .clip(CircleShape)
                  .background(
                      if (listening) AppColor.Green else AppColor.DeepBlue
                  )
          .clickableNoRipple(onClick = onTalkDown),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Hold to talk",
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
        if (listening) {
          CircularProgressIndicator(
              modifier = Modifier.size(76.dp),
              color = Color.White.copy(alpha = 0.25f),
              strokeWidth = 2.dp,
          )
        }
      }

      Box(
          modifier =
              Modifier
                  .size(58.dp)
                  .clip(CircleShape)
                  .background(if (streaming) AppColor.Red else AppColor.SurfaceElevated)
                  .border(1.dp, AppColor.Outline, CircleShape)
                  .clickableNoRipple(onClick = onToggleStream),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            imageVector = if (streaming) Icons.Filled.Stop else Icons.Filled.Videocam,
            contentDescription = if (streaming) "Stop" else "Start",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
      }
    }

    Spacer(Modifier.height(10.dp))

    Text(
        text =
            when {
              listening -> "Listening — release to send"
              streaming -> "Streaming — tap stop when done"
              else -> "Register, then start the stream"
            },
        style = MaterialTheme.typography.labelSmall,
        color = AppColor.OnSurfaceMuted,
    )
  }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

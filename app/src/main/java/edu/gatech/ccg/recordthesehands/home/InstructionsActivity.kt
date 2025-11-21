package edu.gatech.ccg.recordthesehands.home

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_IDLE_TIMEOUT
import edu.gatech.ccg.recordthesehands.recording.PromptView
import edu.gatech.ccg.recordthesehands.thisDeviceIsATablet
import edu.gatech.ccg.recordthesehands.ui.components.PrimaryButton
import edu.gatech.ccg.recordthesehands.ui.components.SecondaryButton
import edu.gatech.ccg.recordthesehands.upload.InstructionsData
import edu.gatech.ccg.recordthesehands.upload.UploadPauseManager
import java.io.File

class InstructionsActivity : ComponentActivity() {

  private var windowInsetsController: WindowInsetsControllerCompat? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val isTablet = thisDeviceIsATablet(applicationContext)
    if (isTablet) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    } else {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }

    windowInsetsController =
      WindowCompat.getInsetsController(window, window.decorView).also {
        it.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }

    val title = intent.getStringExtra("title") ?: return finish()
    val instructionsData: InstructionsData? =
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra("instructionsData", InstructionsData::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra("instructionsData")
      }

    if (instructionsData == null) {
      return finish()
    }

    setContent {
      InstructionsScreen(
        title = title,
        instructionsData = instructionsData,
        onContinueClick = { finish() }
      )
    }
  }

  override fun onResume() {
    super.onResume()
    UploadPauseManager.pauseUploadTimeout(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
    windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
  }

  override fun onStop() {
    super.onStop()
    windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
  }
}

@Composable
fun VideoPlayer(
  videoPath: String,
  modifier: Modifier = Modifier,
  videoViewRef: (VideoView) -> Unit = {},
  onVideoStarted: () -> Unit = {},
  onVideoCompleted: () -> Unit = {}
) {
  val context = LocalContext.current
  var aspectRatio by remember { mutableStateOf<Float?>(null) }
  val videoViewInstance = remember { VideoView(context) }

  AndroidView(
    modifier = modifier.then(
      if (aspectRatio != null) {
        Modifier.aspectRatio(aspectRatio!!, matchHeightConstraintsFirst = true)
      } else {
        Modifier
      }
    ),
    factory = {
      videoViewInstance.apply {
        val videoFile = File(context.filesDir, videoPath)
        setVideoURI(Uri.fromFile(videoFile))
        setOnPreparedListener { mp ->
          mp.isLooping = false
          val videoWidth = mp.videoWidth
          val videoHeight = mp.videoHeight
          if (videoHeight > 0) {
            aspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
          }
          start()
          onVideoStarted()
        }
        setOnCompletionListener {
          onVideoCompleted()
        }
        videoViewRef(this) // Pass the VideoView instance back
      }
    }
  )
}

@Composable
fun InstructionsScreen(
  title: String,
  instructionsData: InstructionsData,
  onContinueClick: () -> Unit
) {
  var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }
  var isPlaying by remember { mutableStateOf(true) }
  var isMinimized by remember { mutableStateOf(false) }
  var showExamplePrompt by remember { mutableStateOf(false) }
  val configuration = LocalConfiguration.current
  val screenHeight = configuration.screenHeightDp.dp

  ConstraintLayout(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
  ) {
    val (header, videoPlayer, instructionsText, instructionsTextTopFade, instructionsTextBottomFade, continueButton, restartButton, playPauseButton, expandButton, exampleButton) = createRefs()

    Text(
      text = title,
      fontSize = 32.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.constrainAs(header) {
        top.linkTo(parent.top, margin = 16.dp)
        start.linkTo(parent.start, margin = 16.dp)
        end.linkTo(parent.end, margin = 16.dp)
      },
      textAlign = TextAlign.Center,
    )

    val hasVideo = instructionsData.instructionsVideo != null
    val hasText = instructionsData.instructionsText != null
    val hasExample = instructionsData.examplePrompt != null

    if (hasExample || hasText) {
      val scrollState = rememberScrollState()
      Column(
        modifier = Modifier
          .constrainAs(instructionsText) {
            top.linkTo(if (hasVideo) videoPlayer.bottom else header.bottom, margin = 16.dp)
            start.linkTo(parent.start, margin = 16.dp)
            end.linkTo(parent.end, margin = 16.dp)
            bottom.linkTo(continueButton.top, margin = 16.dp)
            height = Dimension.fillToConstraints
          }
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
          ) {
            isMinimized = true
            videoViewInstance?.pause()
            isPlaying = false
          }
          .verticalScroll(scrollState)
          .fillMaxWidth()
      ) {
        if (hasExample) {
          SecondaryButton(
            text = "Example",
            onClick = {
              showExamplePrompt = true
              videoViewInstance?.pause()
              isPlaying = false
            },
            modifier = Modifier
              .padding(bottom = 16.dp, end = 16.dp)
              .align(Alignment.End)
          )
        }
        instructionsData.instructionsText?.let { instructions ->
          Text(
            text = instructions,
            fontSize = 24.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp)
          )
        }
      }
      if (scrollState.canScrollBackward) {
        Box(
          modifier = Modifier
            .constrainAs(instructionsTextTopFade) {
              top.linkTo(instructionsText.top, margin = 0.dp)
              start.linkTo(parent.start)
              end.linkTo(parent.end)
              height = Dimension.value(100.dp)
            }
            .fillMaxWidth()
            .background(
              brush = Brush.verticalGradient(
                colors = listOf(Color.White, Color.Transparent),
              )
            )
        ) {}
      }
      if (scrollState.canScrollForward) {
        Box(
          modifier = Modifier
            .constrainAs(instructionsTextBottomFade) {
              bottom.linkTo(instructionsText.bottom, margin = 0.dp)
              start.linkTo(parent.start)
              end.linkTo(parent.end)
              height = Dimension.value(100.dp)
            }
            .fillMaxWidth()
            .background(
              brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.White),
              )
            )
        ) {}
      }
    }

    if (showExamplePrompt && instructionsData.examplePrompt != null) {
      Dialog(
        onDismissRequest = {
          showExamplePrompt = false
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .clickable { showExamplePrompt = false },
          contentAlignment = Alignment.TopCenter
        ) {
          PromptView(
            prompt = instructionsData.examplePrompt,
            modifier = Modifier
              .fillMaxWidth()
              .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
              ) { showExamplePrompt = false }
          )
        }
      }
    }

    instructionsData.instructionsVideo?.let { instructionsVideoPath ->
      VideoPlayer(
        videoPath = instructionsVideoPath,
        modifier = Modifier
          .constrainAs(videoPlayer) {
            top.linkTo(header.bottom, margin = 16.dp)
            if (!hasText) {
              bottom.linkTo(continueButton.top, margin = 16.dp)
            }
            start.linkTo(parent.start, margin = 16.dp)
            end.linkTo(parent.end, margin = 16.dp)
          }
          .fillMaxWidth()
          .heightIn(max = if (isMinimized) 100.dp else screenHeight / 2)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
          ) {
            if (isMinimized) {
              isMinimized = false
              return@clickable
            }
            videoViewInstance?.let { videoView ->
              if (isPlaying) {
                videoView.pause()
              } else {
                videoView.start()
              }
              isPlaying = !isPlaying
            }
          },
        videoViewRef = { videoViewInstance = it },
        onVideoStarted = { isPlaying = true },
        onVideoCompleted = { isPlaying = false }
      )
    }

    if (isMinimized) {
      videoViewInstance?.let {
        IconButton(
          onClick = { isMinimized = false },
          modifier = Modifier
            .constrainAs(expandButton) {
              top.linkTo(videoPlayer.top)
              start.linkTo(videoPlayer.start)
            }
            .background(
              color = Color.White.copy(alpha = 0.5f),
              shape = CircleShape
            )
        ) {
          Icon(Icons.Default.Fullscreen, contentDescription = "Expand Video")
        }
      }
    }

    videoViewInstance?.let { videoView ->
      IconButton(
        onClick = {
          isMinimized = false
          videoView.seekTo(0)
          videoView.start()
          isPlaying = true
        },
        modifier = Modifier
          .constrainAs(restartButton) {
            if (hasText) {
              bottom.linkTo(videoPlayer.bottom)
            } else {
              top.linkTo(videoPlayer.bottom)
            }
            end.linkTo(videoPlayer.end)
          }
          .background(
            color = Color.White.copy(alpha = 0.5f),
            shape = CircleShape
          )
      ) {
        Icon(Icons.Default.RestartAlt, contentDescription = "Restart Video")
      }
    }

    videoViewInstance?.let { videoView ->
      IconButton(
        onClick = {
          isMinimized = false
          if (isPlaying) {
            videoView.pause()
          } else {
            videoView.start()
          }
          isPlaying = !isPlaying
        },
        modifier = Modifier
          .constrainAs(playPauseButton) {
            if (hasText) {
              bottom.linkTo(videoPlayer.bottom)
            } else {
              top.linkTo(videoPlayer.bottom)
            }
            start.linkTo(videoPlayer.start)
          }
          .background(
            color = Color.White.copy(alpha = 0.5f),
            shape = CircleShape
          )
      ) {
        Icon(
          if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
          contentDescription = if (isPlaying) "Pause Video" else "Play Video"
        )
      }
    }

    PrimaryButton(
      onClick = onContinueClick,
      text = "Continue",
      modifier = Modifier.constrainAs(continueButton) {
        bottom.linkTo(parent.bottom, margin = 16.dp)
        start.linkTo(parent.start)
        end.linkTo(parent.end)
      }
    )
  }
}

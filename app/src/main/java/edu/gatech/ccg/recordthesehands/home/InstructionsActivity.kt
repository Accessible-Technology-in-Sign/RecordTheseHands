package edu.gatech.ccg.recordthesehands.home

import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.ui.components.PrimaryButton
import edu.gatech.ccg.recordthesehands.upload.DataManager
import java.io.File

class InstructionsActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val sectionName = intent.getStringExtra("sectionName") ?: return finish()

    setContent {
      InstructionsScreen(
        sectionName = sectionName,
        onContinueClick = { finish() }
      )
    }
  }
}

@Composable
fun VideoPlayer(
  videoPath: String,
  modifier: Modifier = Modifier,
  videoViewRef: (VideoView) -> Unit = {}
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
          mp.isLooping = true
          val videoWidth = mp.videoWidth
          val videoHeight = mp.videoHeight
          if (videoHeight > 0) {
            aspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
          }
          start()
        }
        videoViewRef(this) // Pass the VideoView instance back
      }
    }
  )
}

@Composable
fun InstructionsScreen(
  sectionName: String,
  onContinueClick: () -> Unit
) {
  val dataManager = DataManager.getInstance(LocalContext.current.applicationContext)
  val promptState by dataManager.promptState.observeAsState()
  val metadata = promptState?.promptsCollection?.sections?.get(sectionName)?.metadata
  var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }
  val configuration = LocalConfiguration.current
  val screenHeight = configuration.screenHeightDp.dp

  ConstraintLayout(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
  ) {
    val (header, videoPlayer, instructionsText, instructionsTextTopFade, instructionsTextBottomFade, continueButton, restartButton) = createRefs()

    Text(
      text = stringResource(R.string.instructions_for_section, sectionName),
      fontSize = 32.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.constrainAs(header) {
        top.linkTo(parent.top, margin = 16.dp)
        start.linkTo(parent.start, margin = 16.dp)
        end.linkTo(parent.end, margin = 16.dp)
      },
      textAlign = TextAlign.Center,
    )

    val hasVideo = metadata?.instructionsVideo != null
    val hasText = metadata?.instructionsText != null

    metadata?.instructionsText?.let { instructions ->
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
          .verticalScroll(scrollState)
          .fillMaxWidth()
      ) {

        Text(
          text = instructions,
          fontSize = 18.sp
        )
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

    metadata?.instructionsVideo?.let { instructionsVideoPath ->
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
          .heightIn(max = screenHeight / 2)
      ) {
        videoViewInstance = it
      }
    }

    videoViewInstance?.let { videoView ->
      IconButton(
        onClick = {
          videoView.seekTo(0)
          videoView.start()
        },
        modifier = Modifier
          .constrainAs(restartButton) {
            bottom.linkTo(videoPlayer.bottom)
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

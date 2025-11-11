package edu.gatech.ccg.recordthesehands.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.ui.components.PrimaryButton
import edu.gatech.ccg.recordthesehands.upload.DataManager

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
fun InstructionsScreen(
  sectionName: String,
  onContinueClick: () -> Unit
) {
  val dataManager = DataManager.getInstance(LocalContext.current.applicationContext)
  val promptState by dataManager.promptState.observeAsState()
  val metadata = promptState?.promptsCollection?.sections?.get(sectionName)?.metadata

  ConstraintLayout(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
  ) {
    val (header, videoPlaceholder, instructionsText, instructionsTextTopFade, instructionsTextBottomFade, continueButton) = createRefs()

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

    val hasVideo = metadata?.instructionsVideo?.let { instructions ->
      // Placeholder for Video
      Box(
        modifier = Modifier
          .constrainAs(videoPlaceholder) {
            top.linkTo(header.bottom, margin = 16.dp)
            start.linkTo(parent.start, margin = 16.dp)
            end.linkTo(parent.end, margin = 16.dp)
          }
          .fillMaxWidth()
          .background(Color.Gray)
      ) {
        Text("Video Placeholder", modifier = Modifier.padding(16.dp))
      }
      true
    } ?: false

    metadata?.instructionsText?.let { instructions ->
      val scrollState = rememberScrollState()
      Column(
        modifier = Modifier
          .constrainAs(instructionsText) {
            top.linkTo(if (hasVideo) videoPlaceholder.bottom else header.bottom, margin = 16.dp)
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

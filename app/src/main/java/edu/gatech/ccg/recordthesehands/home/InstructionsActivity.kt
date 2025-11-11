package edu.gatech.ccg.recordthesehands.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.ui.components.PrimaryButton

class InstructionsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val sectionName = intent.getStringExtra("sectionName") ?: "Unknown Section"

    setContent {
      InstructionsScreen(
        sectionName = sectionName,
        onBackClick = { finish() },
        onContinueClick = { finish() }
      )
    }
  }
}

@Composable
fun InstructionsScreen(
  sectionName: String,
  onBackClick: () -> Unit,
  onContinueClick: () -> Unit
) {
  ConstraintLayout(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
  ) {
    val (backButton, header, videoPlaceholder, instructionsText, continueButton) = createRefs()

    Image(
      painter = painterResource(id = R.drawable.back_arrow),
      contentDescription = stringResource(R.string.back_button),
      modifier = Modifier
        .constrainAs(backButton) {
          start.linkTo(parent.start, margin = 16.dp)
          top.linkTo(parent.top, margin = 16.dp)
        }
        .clickable(onClick = onBackClick)
    )

    Text(
      text = stringResource(R.string.instructions_for_section, sectionName),
      fontSize = 32.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.constrainAs(header) {
        top.linkTo(backButton.bottom, margin = 16.dp)
        start.linkTo(parent.start)
        end.linkTo(parent.end)
      }
    )

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

    val scrollState = rememberScrollState()
    Column(
      modifier = Modifier
        .constrainAs(instructionsText) {
          top.linkTo(videoPlaceholder.bottom, margin = 16.dp)
          start.linkTo(parent.start, margin = 16.dp)
          end.linkTo(parent.end, margin = 16.dp)
          bottom.linkTo(continueButton.top, margin = 16.dp)
        }
        .verticalScroll(scrollState)
    ) {
      Text(
        text = "These are the instructions for section $sectionName. ".repeat(50),
        fontSize = 18.sp
      )
    }

    PrimaryButton(
      onClick = onContinueClick,
      text = "Continue",
      modifier = Modifier.constrainAs(continueButton) {
        bottom.linkTo(parent.bottom, margin = 24.dp)
        start.linkTo(parent.start)
        end.linkTo(parent.end)
      }
    )
  }
}

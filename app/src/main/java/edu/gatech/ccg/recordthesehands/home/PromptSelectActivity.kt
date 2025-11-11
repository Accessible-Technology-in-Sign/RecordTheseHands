/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2025
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package edu.gatech.ccg.recordthesehands.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_IDLE_TIMEOUT
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.thisDeviceIsATablet
import edu.gatech.ccg.recordthesehands.ui.components.SecondaryButton
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.UploadPauseManager
import kotlinx.coroutines.launch

class PromptSelectActivity : ComponentActivity() {

  private var windowInsetsController: WindowInsetsControllerCompat? = null

  companion object {
    private val TAG = PromptSelectActivity::class.simpleName
  }

  private lateinit var dataManager: DataManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    windowInsetsController =
      WindowCompat.getInsetsController(window, window.decorView)?.also {
        it.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    dataManager = DataManager.getInstance(applicationContext)

    setContent {
      PromptSelectScreenContent(
        dataManager = dataManager,
        onBackClick = { finish() },
        onToggleTutorialMode = {
          lifecycleScope.launch {
            dataManager.toggleTutorialMode()
            finish()
          }
        },
        onSectionClick = { sectionName ->
          lifecycleScope.launch {
            UploadPauseManager.pauseUploadTimeout(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
            dataManager.resetToSection(sectionName)
            finish()
          }
        }
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
fun PromptSelectScreenContent(
  dataManager: DataManager,
  onBackClick: () -> Unit,
  onToggleTutorialMode: () -> Unit,
  onSectionClick: (String) -> Unit
) {
  val promptState by dataManager.promptState.observeAsState()
  val isTablet = thisDeviceIsATablet(LocalContext.current)

  ConstraintLayout(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
  ) {
    val (backButton, header, toggleTutorialButton, sectionsList) = createRefs()
    val topBarrier = createBottomBarrier(backButton, header, toggleTutorialButton)

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
      text = stringResource(R.string.prompt_select_title),
      fontSize = 48.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.constrainAs(header) {
        top.linkTo(parent.top, margin = 16.dp)
        start.linkTo(parent.start)
        end.linkTo(parent.end)
      }
    )

    SecondaryButton(
      onClick = onToggleTutorialMode,
      modifier = Modifier.constrainAs(toggleTutorialButton) {
        top.linkTo(parent.top, margin = 16.dp)
        end.linkTo(parent.end, margin = 16.dp)
      },
      text = if (promptState?.tutorialMode == true) {
        stringResource(R.string.switch_to_normal_prompts)
      } else {
        stringResource(R.string.switch_to_tutorial_prompts)
      }
    )

    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(
      modifier = Modifier
        .constrainAs(sectionsList) {
          top.linkTo(topBarrier, margin = 16.dp)
          start.linkTo(parent.start, margin = 16.dp)
          end.linkTo(parent.end, margin = 16.dp)
          bottom.linkTo(parent.bottom, margin = 0.dp)
          height = Dimension.fillToConstraints
        }
        .fillMaxWidth()
    ) {
      LazyColumn(
        state = lazyListState,
      ) {
        val sections = promptState?.promptsCollection?.sections?.keys?.sorted() ?: emptyList()
        val twice = sections.flatMap { listOf(it, it) }  // DO NOT SUBMIT
        items(sections) { sectionName ->
          val section = promptState?.promptsCollection?.sections?.get(sectionName)!!
          val prompts = section.mainPrompts
          val total = prompts.array.size
          val sectionProgress = promptState?.promptProgress?.get(sectionName)
          val completed = sectionProgress?.get("mainIndex") ?: 0
          val isCompleted = total <= 0 || completed >= total

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
              contentAlignment = Alignment.CenterEnd
            ) {
              SecondaryButton(
                onClick = { onSectionClick(sectionName) },
                enabled = !isCompleted || promptState?.tutorialMode == true,
                text = sectionName
              )
            }
            Box(
              modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
              contentAlignment = Alignment.CenterStart
            ) {
              Text(
                text = stringResource(
                  if (isTablet) R.string.prompts_completed_progress else R.string.prompts_completed_progress_compact,
                  completed,
                  total
                ),
                fontSize = 24.sp,
              )
            }
          }
        }
      }
      if (lazyListState.canScrollForward) {
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(
              brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.White),
                startY = 0f,
                endY = 160f
              )
            )
            .padding(top = 80.dp)
        ) {}
      }
      if (lazyListState.canScrollBackward) {
        Box(
          modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(
              brush = Brush.verticalGradient(
                colors = listOf(Color.White, Color.Transparent),
                startY = 0f,
                endY = 120f
              )
            )
            .padding(top = 60.dp)
        ) {}
      }
    }
  }
}

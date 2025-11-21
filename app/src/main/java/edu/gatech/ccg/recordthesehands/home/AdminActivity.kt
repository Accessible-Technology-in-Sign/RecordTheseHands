/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2021-2025
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

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_IDLE_TIMEOUT
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.thisDeviceIsATablet
import edu.gatech.ccg.recordthesehands.ui.components.PrimaryButton
import edu.gatech.ccg.recordthesehands.ui.components.SecondaryButton
import edu.gatech.ccg.recordthesehands.ui.components.StyledTextField
import edu.gatech.ccg.recordthesehands.ui.theme.LightBlue
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.UploadPauseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdminActivity : ComponentActivity() {

  private lateinit var dataManager: DataManager
  private var windowInsetsController: WindowInsetsControllerCompat? = null
  private val adminViewModel: AdminViewModel by viewModels()

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

    dataManager = DataManager.getInstance(applicationContext)

    setContent {
      AdminScreenContent(
        dataManager = dataManager,
        adminViewModel = adminViewModel,
        onBackClick = { finish() },
        onSetDeviceId = { newDeviceId ->
          lifecycleScope.launch(Dispatchers.IO) {
            dataManager.setDeviceId(newDeviceId)
          }
        },
        onAttachToAccount = { mustMatchDeviceId ->
          adminViewModel.attachToAccount(dataManager, mustMatchDeviceId)
        },
        onDownloadApk = {
          val serverAddress = dataManager.getServer()
          val apkUrl = "$serverAddress/apk"
          val intent = Intent(Intent.ACTION_VIEW)
          intent.data = apkUrl.toUri()
          startActivity(intent)
        },
        onDisableDismissCountdownCircle = {
          lifecycleScope.launch(Dispatchers.IO) {
            dataManager.setEnableDismissCountdownCircle(false)
          }
        },
        onResetOverviewInstructions = {
          lifecycleScope.launch(Dispatchers.IO) {
            dataManager.setOverviewInstructionsShown(false)
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

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun AdminScreenContent(
  dataManager: DataManager,
  adminViewModel: AdminViewModel,
  onBackClick: () -> Unit,
  onSetDeviceId: (String) -> Unit,
  onAttachToAccount: (Boolean) -> Unit,
  onDownloadApk: () -> Unit,
  onDisableDismissCountdownCircle: () -> Unit,
  onResetOverviewInstructions: () -> Unit
) {
  val promptState by dataManager.promptState.observeAsState()
  val userSettings by dataManager.userSettings.observeAsState()
  val appStatus by dataManager.appStatus.observeAsState()
  val deviceId = promptState?.deviceId ?: "Unknown Device Id"
  val username = promptState?.username ?: stringResource(R.string.unknown_username)

  var showResultDialog by remember { mutableStateOf(false) }
  var dialogTitle by remember { mutableStateOf("") }
  var dialogMessage by remember { mutableStateOf("") }
  var showRemoveDeviceCheckbox by remember { mutableStateOf(false) }
  var removePreviousDeviceChecked by remember { mutableStateOf(false) }
  var numTitleClicks by remember { mutableStateOf(0) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    adminViewModel.attachResultFlow.collect { (success, errorMessage) ->
      if (success) {
        onBackClick()
      } else {
        dialogTitle = "Failed"
        dialogMessage =
          errorMessage ?: "Failed to create account for \"${adminViewModel.newUsername}\"."
        showRemoveDeviceCheckbox =
          errorMessage?.contains("device id does not match old device id") == true
        if (showRemoveDeviceCheckbox) {
          removePreviousDeviceChecked = false
        }
        showResultDialog = true
      }
    }
  }

  val focusManager = LocalFocusManager.current

  val isTablet = thisDeviceIsATablet(LocalContext.current)

  ConstraintLayout(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
      .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        focusManager.clearFocus()
      }
  ) {
    val (backButton, content, headerText, downloadButton, disableDismissCountdownCircleButton, resetOverviewInstructionsButton) = createRefs()

    Image(
      painter = painterResource(id = R.drawable.back_arrow),
      contentDescription = stringResource(R.string.back_button),
      modifier = Modifier
        .constrainAs(backButton) {
          start.linkTo(parent.start, margin = 16.dp)
          top.linkTo(parent.top, margin = 16.dp)
        }
        .clickable(onClick = {
          if (adminViewModel.isAttaching) {
            scope.launch {
              dataManager.waitForDataLock()
              onBackClick()
            }
          } else {
            onBackClick()
          }
        })
    )

    Text(
      text = stringResource(R.string.admin_interface_title),
      fontSize = 32.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier
        .constrainAs(headerText) {
          top.linkTo(backButton.top)
          bottom.linkTo(backButton.bottom)
          start.linkTo(backButton.end, margin = 16.dp)
          end.linkTo(parent.end, margin = 16.dp)
        }
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
          numTitleClicks++
          if (numTitleClicks == 5) {
            numTitleClicks = 0
            scope.launch(Dispatchers.IO) {
              dataManager.setEnableDismissCountdownCircle(true)
            }
          }
        }
    )

    Column(
      modifier = Modifier
        .constrainAs(content) {
          start.linkTo(parent.start, margin = if (isTablet) 32.dp else 16.dp)
          end.linkTo(parent.end, margin = if (isTablet) 32.dp else 16.dp)
          top.linkTo(backButton.bottom, margin = 6.dp)
          width = Dimension.fillToConstraints
        }
    ) {
      @Composable
      fun deviceIdWithCurrentText() {
        Text(
          text = stringResource(R.string.device_id_with_current, deviceId),
          fontSize = 24.sp
        )
      }

      @Composable
      fun newDeviceIdTextField() {
        StyledTextField(
          value = adminViewModel.newDeviceId,
          onValueChange = { adminViewModel.newDeviceId = it },
          modifier = Modifier.weight(1f),
          fontSize = 24.sp,
        )
      }

      @Composable
      fun setDeviceIdButton() {
        PrimaryButton(
          onClick = {
            onSetDeviceId(adminViewModel.newDeviceId)
            focusManager.clearFocus()
          },
          modifier = Modifier.padding(top = if (isTablet) 8.dp else 0.dp),
          text = if (isTablet) {
            stringResource(R.string.set_device_id_button)
          } else {
            stringResource(R.string.set_button)
          }
        )
      }
      // Device ID Section
      if (isTablet) {
        Row(
          modifier = Modifier.padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          deviceIdWithCurrentText()
          newDeviceIdTextField()
        }
        setDeviceIdButton()
      } else {
        Row(
          modifier = Modifier.padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          setDeviceIdButton()
          deviceIdWithCurrentText()
        }
        Row(
          modifier = Modifier.padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          newDeviceIdTextField()
        }
      }
      // Attach to Account Section
      @Composable
      fun newUsernameText() {
        Text(
          text = stringResource(R.string.username_label_with_current, username),
          fontSize = 24.sp
        )
      }

      @Composable
      fun newUsernameTextField() {
        StyledTextField(
          value = adminViewModel.newUsername,
          onValueChange = { adminViewModel.newUsername = it },
          modifier = Modifier.weight(1f),
          fontSize = 24.sp,
        )
      }

      @Composable
      fun adminPasswordText() {
        Text(stringResource(R.string.password_label), fontSize = 24.sp)
      }

      @Composable
      fun adminPasswordTextField() {
        StyledTextField(
          value = adminViewModel.adminPassword,
          onValueChange = { adminViewModel.adminPassword = it },
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.weight(1f),
          fontSize = 24.sp,
        )
      }

      @Composable
      fun attachToAccountButton() {
        PrimaryButton(
          onClick = {
            focusManager.clearFocus()
            onAttachToAccount(!removePreviousDeviceChecked)
          },
          enabled = !adminViewModel.isAttaching,
          modifier = Modifier.padding(top = 8.dp),
          text = if (adminViewModel.isAttaching) {
            stringResource(R.string.loading_button)
          } else {
            if (isTablet) {
              stringResource(R.string.set_username_button)
            } else {
              stringResource(R.string.set_button)
            }
          }
        )
      }

      @Composable
      fun removeDeviceCheckbox() {
        if (showRemoveDeviceCheckbox) {
          Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
              Checkbox(
                checked = removePreviousDeviceChecked,
                onCheckedChange = {
                  if (!adminViewModel.isAttaching) {
                    removePreviousDeviceChecked = it
                  }
                },
                colors = CheckboxDefaults.colors(
                  checkedColor = LightBlue,
                  uncheckedColor = Color.Black
                )
              )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = stringResource(R.string.remove_device_warning),
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold,
              color = Color.Red,
              modifier = Modifier.clickable {
                if (!adminViewModel.isAttaching) {
                  removePreviousDeviceChecked = !removePreviousDeviceChecked
                }
              }
            )
          }
        }
      }

      Column(modifier = Modifier.padding(top = 24.dp)) {
        if (isTablet) {
          Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            newUsernameText()
            newUsernameTextField()
          }
          Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            adminPasswordText()
            adminPasswordTextField()
          }
          removeDeviceCheckbox()
          attachToAccountButton()
        } else {
          Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            attachToAccountButton()
            newUsernameText()
          }
          Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            newUsernameTextField()
          }
          Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            adminPasswordText()
          }
          Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            adminPasswordTextField()
          }
          removeDeviceCheckbox()
        }
      }
    }

    SecondaryButton(
      onClick = onDownloadApk,
      modifier = Modifier.constrainAs(downloadButton) {
        bottom.linkTo(parent.bottom, margin = 32.dp)
        start.linkTo(parent.start, margin = if (isTablet) 32.dp else 16.dp)
      },
      text = stringResource(R.string.download_apk_button)
    )

    val showDisableDismissCountdownCircleButton =
      userSettings?.enableDismissCountdownCircle ?: false
    if (showDisableDismissCountdownCircleButton) {
      SecondaryButton(
        onClick = onDisableDismissCountdownCircle,
        modifier = Modifier.constrainAs(disableDismissCountdownCircleButton) {
          bottom.linkTo(downloadButton.top, margin = 48.dp)
          start.linkTo(downloadButton.start)
        },
        text = stringResource(R.string.disable_dismiss_circle_button)
      )
    }

    if (appStatus?.overviewInstructionsShown == true) {
      SecondaryButton(
        onClick = onResetOverviewInstructions,
        modifier = Modifier.constrainAs(resetOverviewInstructionsButton) {
          if (showDisableDismissCountdownCircleButton) {
            bottom.linkTo(disableDismissCountdownCircleButton.top, margin = 16.dp)
            start.linkTo(disableDismissCountdownCircleButton.start)
          } else {
            bottom.linkTo(downloadButton.top, margin = 48.dp)
            start.linkTo(downloadButton.start)
          }
        },
        text = stringResource(R.string.reset_overview_instructions)
      )
    }

    if (showResultDialog) {
      AlertDialog(
        onDismissRequest = { showResultDialog = false },
        title = {
          Text(
            dialogTitle,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
          )
        },
        text = {
          Text(
            dialogMessage,
            fontSize = 18.sp,
            color = Color.Black,
          )
        },
        confirmButton = {
          PrimaryButton(
            text = stringResource(R.string.ok_button),
            modifier = Modifier.padding(bottom = 16.dp, end = 16.dp),
            onClick = {
              showResultDialog = false
            }
          )
        }
      )
    }
  }
}

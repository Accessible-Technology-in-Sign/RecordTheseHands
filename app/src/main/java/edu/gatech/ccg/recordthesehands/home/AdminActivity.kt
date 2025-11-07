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
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.ui.components.PrimaryButton
import edu.gatech.ccg.recordthesehands.ui.components.SecondaryButton
import edu.gatech.ccg.recordthesehands.upload.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdminActivity : ComponentActivity() {

  private lateinit var dataManager: DataManager
  private var windowInsetsController: WindowInsetsControllerCompat? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    windowInsetsController =
      WindowCompat.getInsetsController(window, window.decorView).also {
        it.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }

    dataManager = DataManager.getInstance(applicationContext)

    setContent {
      AdminScreenContent(
        dataManager = dataManager,
        onBackClick = { finish() },
        onSetDeviceId = { newDeviceId ->
          lifecycleScope.launch(Dispatchers.IO) {
            dataManager.setDeviceId(newDeviceId)
          }
        },
        onAttachToAccount = { username, password ->
          lifecycleScope.launch(Dispatchers.IO) {
            dataManager.attachToAccount(username, password)
          }
        },
        onDownloadApk = {
          val serverAddress = dataManager.getServer()
          val apkUrl = "$serverAddress/apk"
          val intent = Intent(Intent.ACTION_VIEW)
          intent.data = apkUrl.toUri()
          startActivity(intent)
        }
      )
    }
  }

  override fun onResume() {
    super.onResume()
    windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
  }

  override fun onStop() {
    super.onStop()
    windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
  }
}

@Composable
fun AdminScreenContent(
  dataManager: DataManager,
  onBackClick: () -> Unit,
  onSetDeviceId: (String) -> Unit,
  onAttachToAccount: (String, String) -> Unit,
  onDownloadApk: () -> Unit
) {
  val promptState by dataManager.promptState.observeAsState()
  val deviceId = promptState?.deviceId ?: "Unknown Device Id"
  val username = promptState?.username ?: stringResource(R.string.unknown_username)

  var newDeviceId by remember { mutableStateOf("") }
  var newUsername by remember { mutableStateOf("") }
  var adminPassword by remember { mutableStateOf("") }
  var isAttaching by remember { mutableStateOf(false) }

  var showResultDialog by remember { mutableStateOf(false) }
  var dialogTitle by remember { mutableStateOf("") }
  var dialogMessage by remember { mutableStateOf("") }

  val focusManager = LocalFocusManager.current

  ConstraintLayout(
    modifier = Modifier
        .fillMaxSize()
        .background(Color.White)
        .clickable { focusManager.clearFocus() }
  ) {
    val (backButton, content) = createRefs()

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

    Column(
      modifier = Modifier
          .constrainAs(content) {
              start.linkTo(backButton.end, margin = 20.dp)
              end.linkTo(parent.end, margin = 32.dp)
              top.linkTo(parent.top)
          }
          .fillMaxWidth()
    ) {
      Text(
        text = "Admin Interface",
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp)
      )

      // Device ID Section
      Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = stringResource(R.string.device_id_with_current, deviceId),
          fontSize = 24.sp
        )
        TextField(
          value = newDeviceId,
          onValueChange = { newDeviceId = it },
          modifier = Modifier.weight(1f)
        )
      }
      PrimaryButton(
        onClick = {
          onSetDeviceId(newDeviceId)
          focusManager.clearFocus()
        },
        modifier = Modifier.padding(top = 8.dp),
        text = "Set DeviceId"
      )
      // Attach to Account Section
      Column(modifier = Modifier.padding(top = 24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = stringResource(R.string.username_label_with_current, username),
            fontSize = 24.sp
          )
          TextField(
            value = newUsername,
            onValueChange = { newUsername = it },
            modifier = Modifier.weight(1f)
          )
        }
        Row(
          modifier = Modifier.padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text("Admin Password:", fontSize = 24.sp)
          TextField(
            value = adminPassword,
            onValueChange = { adminPassword = it },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f)
          )
        }
      }

      PrimaryButton(
        onClick = {
          isAttaching = true
          focusManager.clearFocus()
          onAttachToAccount(newUsername, adminPassword)
        },
        enabled = !isAttaching,
        modifier = Modifier.padding(top = 8.dp),
        text = if (isAttaching) "Attaching to account..." else "Set Username"
      )

      SecondaryButton(
        onClick = onDownloadApk,
        modifier = Modifier.padding(top = 16.dp),
        text = "Download APK"
      )
    }

    if (showResultDialog) {
      AlertDialog(
        onDismissRequest = { showResultDialog = false },
        title = { Text(dialogTitle) },
        text = { Text(dialogMessage) },
        confirmButton = {
          Button(onClick = { showResultDialog = false }) {
            Text("OK")
          }
        }
      )
    }
  }
}

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
package edu.gatech.ccg.recordthesehands.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StyledTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  fontSize: TextUnit = 24.sp,
  contentPadding: PaddingValues = PaddingValues(8.dp),
  visualTransformation: VisualTransformation = VisualTransformation.None,
) {
  /*
  TextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    keyboardOptions = keyboardOptions,
    textStyle = LocalTextStyle.current.copy(
      fontSize = fontSize,
    ),
    visualTransformation = visualTransformation,
    colors = TextFieldDefaults.textFieldColors(
      backgroundColor = Color.LightGray,
      focusedIndicatorColor = Color.DarkGray,
      unfocusedIndicatorColor = Color.Transparent,
      disabledIndicatorColor = Color.Transparent,
      cursorColor = Color.DarkGray,
    ),
  )
  */
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.background(Color.LightGray),
    keyboardOptions = keyboardOptions,
    visualTransformation = visualTransformation,
    textStyle = LocalTextStyle.current.copy(
      fontSize = fontSize,
      platformStyle = PlatformTextStyle(includeFontPadding = false),
      color = Color.Black,
      background = Color.LightGray,
    ),
    decorationBox = @Composable { innerTextField: @Composable () -> Unit ->
      Box(modifier = Modifier.padding(contentPadding)) {
        innerTextField()
      }
    }
  )
}

package edu.gatech.ccg.recordthesehands.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.gatech.ccg.recordthesehands.ui.theme.AlertRed
import edu.gatech.ccg.recordthesehands.ui.theme.ButtonGreen
import edu.gatech.ccg.recordthesehands.ui.theme.LightBlue
import edu.gatech.ccg.recordthesehands.ui.theme.LightGray
import edu.gatech.ccg.recordthesehands.ui.theme.MediumGray

@Composable
fun PrimaryButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  text: String,
  grayOnDisabled: Boolean = false
) {
  Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = RoundedCornerShape(4.dp),
    colors = ButtonDefaults.buttonColors(
      backgroundColor = ButtonGreen,
      disabledBackgroundColor = if (grayOnDisabled) {
        MediumGray
      } else {
        AlertRed
      },
      contentColor = Color.White,
      disabledContentColor = Color.White
    ),
    contentPadding = PaddingValues(12.dp)
  ) {
    Text(
      text = text,
      fontSize = 24.sp
    )
  }
}

@Composable
fun SecondaryButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  text: String
) {
  Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = RoundedCornerShape(4.dp),
    colors = ButtonDefaults.buttonColors(
      backgroundColor = LightBlue,
      disabledBackgroundColor = LightGray,
      contentColor = Color.White,
      disabledContentColor = Color.White
    ),
    contentPadding = PaddingValues(12.dp)
  ) {
    Text(
      text = text,
      fontSize = 24.sp
    )
  }
}

@Composable
fun AlertButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  text: String
) {
  Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = RoundedCornerShape(4.dp),
    colors = ButtonDefaults.buttonColors(
      backgroundColor = AlertRed,
      disabledBackgroundColor = MediumGray,
      contentColor = Color.White,
      disabledContentColor = Color.White
    ),
    contentPadding = PaddingValues(12.dp)
  ) {
    Text(
      text = text,
      fontSize = 24.sp
    )
  }
}

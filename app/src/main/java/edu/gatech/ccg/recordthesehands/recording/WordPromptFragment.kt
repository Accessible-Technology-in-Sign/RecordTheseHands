/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2021-2024
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
package edu.gatech.ccg.recordthesehands.recording

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.annotation.LayoutRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import edu.gatech.ccg.recordthesehands.Constants.TABLET_SIZE_THRESHOLD_INCHES
import androidx.transition.Visibility
import edu.gatech.ccg.recordthesehands.upload.Prompt
import edu.gatech.ccg.recordthesehands.upload.PromptType
import edu.gatech.ccg.recordthesehands.R
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * This is the little rectangle at the top of the screen that prompts the
 * user with what to sign.
 */
class WordPromptFragment(
  private var prompt: Prompt, @LayoutRes layout: Int,
) : Fragment(layout) {

  companion object {
    private val TAG = VideoPromptController::class.java.simpleName
  }

  var videoPromptController: VideoPromptController? = null

  private var displayMode: PromptDisplayModeListener? = null

  private var isTablet = false

  /**
   * Holds the original prompt layout parameters and constraints for resetting the prompt back
   * to default.
   */
  private lateinit var origPromptLayout: ConstraintSet

  /**
   * Experimental values for portrait, landscape, tablet, non-tablet reference video scaling.
   */
  private val originalPortraitWidthScaleFactor = 0.45f

  private val originalLandscapeWidthScaleFactor = 0.25f

  private val splitPortraitWidthScaleFactor = 0.95f

  private val splitLandscapeWidthScaleFactor = 0.5f

  /**
   * A map to store display mode button parameters via the button ids.
   */
  private lateinit var originalButtonParams: Map<Int, ConstraintLayout.LayoutParams>

  /**
   * Enum class defining the possible display modes. Allows [WordPromptFragment] to communicate display
   * mode changes to [RecordingActivity].
   */
  enum class PromptDisplayMode {
    FULL,
    SPLIT,
    ORIGINAL
  }

  interface PromptDisplayModeListener {
    fun displayModeListener(displayMode: PromptDisplayMode?)
  }

  /**
   * Binds the fragment to [RecordingActivity]. Checks if it properly implements [PromptDisplayModeListener] interface.
   */
  override fun onAttach(context: Context) {
    super.onAttach(context)
    if (context is PromptDisplayModeListener) {
      displayMode = context
    }
  }

  /**
   * Unbinds the fragment when finished with the activity.
   */
  override fun onDetach() {
    super.onDetach()
    displayMode = null
  }

  /**
   * Lay out the UI for this fragment.
   */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val currentOrientation = resources.configuration.orientation

    val screenWidth = resources.displayMetrics.widthPixels
    val screenHeight = resources.displayMetrics.heightPixels
    val pixelDensity = resources.displayMetrics.density

    val displayMetrics = resources.displayMetrics
    val heightInches = displayMetrics.heightPixels / displayMetrics.ydpi
    val widthInches = displayMetrics.widthPixels / displayMetrics.xdpi
    val diagonal = sqrt((heightInches * heightInches) + (widthInches * widthInches))
    isTablet = diagonal > TABLET_SIZE_THRESHOLD_INCHES
    Log.i(TAG, "Computed screen size: $diagonal inches")
    Log.i(TAG, "Tablet? " + isTablet.toString())

    if (!isTablet) {
      scalePromptSection(0.5f)
    }

    val promptLayout = view.findViewById<ConstraintLayout>(R.id.promptLayout)
    origPromptLayout = ConstraintSet().apply {
      clone(promptLayout)
    }

    val textView = view.findViewById<TextView>(R.id.promptText)
    if (prompt.prompt != null) {
      Log.d(TAG, "setting prompt to ${prompt.prompt}")
      textView.text = prompt.prompt
      textView.visibility = View.VISIBLE
    } else {
      Log.d(TAG, "no prompt available for ${prompt.key}.")
      textView.visibility = View.GONE
    }

    when (prompt.type) {
      PromptType.TEXT -> {
      }

      PromptType.IMAGE -> {
        Log.d(TAG, "Rendering Image for ${prompt.key}.")
        if (prompt.resourcePath != null) {
          Log.d(TAG, "resourcePath ${prompt.resourcePath}.")
          val imageView = view.findViewById<ImageView>(R.id.promptImage)
          val filepath = File(requireContext().filesDir, prompt.resourcePath)
          imageView.setImageURI(Uri.fromFile(filepath))
          imageView.visibility = View.VISIBLE
        }
      }

      PromptType.VIDEO -> {
        if (prompt.resourcePath != null) {
          val videoView = view.findViewById<VideoView>(R.id.promptVideo)
          val videoViewParams = videoView.layoutParams as LayoutParams

          // Initialize button for adjusting video size
          val fullScreenButton = view.findViewById<ImageButton>(R.id.fullScreenButton)
          val minimizeScreenButton = view.findViewById<ImageButton>(R.id.originalScreenButton)
          val splitScreenButton = view.findViewById<ImageButton>(R.id.splitScreenButton)
          val disableSplitScreenButton =
            view.findViewById<ImageButton>(R.id.disableSplitScreenButton)

          originalButtonParams = mapOf(
            R.id.fullScreenButton to ConstraintLayout.LayoutParams(fullScreenButton.layoutParams as ConstraintLayout.LayoutParams),
            R.id.originalScreenButton to ConstraintLayout.LayoutParams(minimizeScreenButton.layoutParams as ConstraintLayout.LayoutParams),
            R.id.splitScreenButton to ConstraintLayout.LayoutParams(splitScreenButton.layoutParams as ConstraintLayout.LayoutParams),
            R.id.disableSplitScreenButton to ConstraintLayout.LayoutParams(disableSplitScreenButton.layoutParams as ConstraintLayout.LayoutParams)
          )

          var lastDisplayMode: PromptDisplayMode? = PromptDisplayMode.ORIGINAL

          fullScreenButton.setOnClickListener {
            resetPromptTypeConstraint()
            setFullScreen(videoView, videoViewParams, currentOrientation)
            fullScreenButton.visibility = View.GONE
            minimizeScreenButton.visibility = View.VISIBLE
            splitScreenButton.visibility = View.VISIBLE
            lastDisplayMode = PromptDisplayMode.FULL
            Log.i(TAG, "Enlarging video to full screen size")
          }

          minimizeScreenButton.setOnClickListener {
            resetPromptTypeConstraint()
            resetButtonPositions()
            setOriginalScreen(
              videoView,
              videoViewParams,
              currentOrientation,
              lastDisplayMode,
              screenWidth,
              pixelDensity
            )
            fullScreenButton.visibility = View.VISIBLE
            minimizeScreenButton.visibility = View.GONE
            splitScreenButton.visibility = View.VISIBLE
            lastDisplayMode = PromptDisplayMode.ORIGINAL
          }

          splitScreenButton.setOnClickListener {
            resetPromptTypeConstraint()
            resetButtonPositions()
            setSplitScreen(
              videoView,
              videoViewParams,
              currentOrientation,
              lastDisplayMode,
              screenWidth,
              pixelDensity
            )
            if (lastDisplayMode == PromptDisplayMode.ORIGINAL) {
              minimizeScreenButton.visibility = View.GONE
              fullScreenButton.visibility = View.VISIBLE
            } else {
              fullScreenButton.visibility = View.GONE
              minimizeScreenButton.visibility = View.VISIBLE
            }
            splitScreenButton.visibility = View.GONE
            disableSplitScreenButton.visibility = View.VISIBLE
            Log.i(TAG, "Splitting screen")
          }

          disableSplitScreenButton.setOnClickListener {
            resetPromptTypeConstraint()
            undoSplitScreen(
              videoView,
              videoViewParams,
              currentOrientation,
              lastDisplayMode,
              screenWidth,
              pixelDensity
            )
            if (lastDisplayMode == PromptDisplayMode.ORIGINAL) {
              minimizeScreenButton.visibility = View.GONE
              fullScreenButton.visibility = View.VISIBLE
              resetButtonPositions()
            } else if (lastDisplayMode == PromptDisplayMode.FULL) {
              fullScreenButton.visibility = View.GONE
              minimizeScreenButton.visibility = View.VISIBLE
            }
            splitScreenButton.visibility = View.VISIBLE
            disableSplitScreenButton.visibility = View.GONE
          }

          videoPromptController = VideoPromptController(
            requireContext(), null, videoView, prompt.resourcePath!!, true
          )
          resetPromptTypeConstraint()
          setOriginalScreen(
            videoView,
            videoViewParams,
            currentOrientation,
            lastDisplayMode,
            screenWidth,
            pixelDensity
          )
          videoView.layoutParams = videoViewParams
          videoView.visibility = View.VISIBLE
          fullScreenButton.visibility = View.VISIBLE
          minimizeScreenButton.visibility = View.GONE
          splitScreenButton.visibility = View.VISIBLE
        }
      }
    }

    // if (!hasVideo) {
    //   val promptView = view.findViewById<ConstraintLayout>(R.id.promptLayout)

    //   ConstraintSet().apply {
    //     clone(promptView)
    //     connect(
    //       R.id.promptText, ConstraintSet.END, R.id.promptView,
    //       ConstraintSet.END, 8
    //     )
    //     constrainWidth(
    //       R.id.promptText,
    //       ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
    //     )
    //     applyTo(promptView)
    //   }
    // }

    //if (prompt.prompt.length > 20) {
    //  textView.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
    //  textView.textSize = 18.0f
    // }
  }

  /**
   * Sets the reference video to default screen mode.
   */
  private fun setOriginalScreen(
    videoView: VideoView,
    videoViewParams: LayoutParams,
    currentOrientation: Int,
    lastDisplayMode: PromptDisplayMode?,
    screenWidth: Int,
    pixelDensity: Float
  ) {
    val widthDp = (screenWidth / pixelDensity)
    val desiredPortraitWidthPx =
      calculateScaledPixelWidth(widthDp, originalPortraitWidthScaleFactor)
    val desiredLandscapeWidthPx =
      calculateScaledPixelWidth(widthDp, originalLandscapeWidthScaleFactor)

    displayMode?.displayModeListener(PromptDisplayMode.ORIGINAL)
    if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
      if (isTablet) {
        videoViewParams.width = desiredPortraitWidthPx
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
        videoViewParams.topMargin = 10
        videoViewParams.topToBottom = R.id.promptView
        videoViewParams.bottomToBottom = LayoutParams.UNSET
        videoViewParams.startToStart = LayoutParams.PARENT_ID
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
      } else {
        videoViewParams.width = desiredPortraitWidthPx
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
        videoViewParams.topMargin = 10
        videoViewParams.topToBottom = R.id.promptView
        videoViewParams.bottomToBottom = LayoutParams.UNSET
        videoViewParams.startToStart = LayoutParams.PARENT_ID
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
      }
    } else if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
      if (isTablet) {
        videoViewParams.width = desiredLandscapeWidthPx
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
        videoViewParams.topToBottom = LayoutParams.UNSET
        videoViewParams.startToStart = LayoutParams.UNSET
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
        videoViewParams.topToTop = LayoutParams.PARENT_ID
        videoViewParams.bottomToBottom = LayoutParams.PARENT_ID
      } else {
        videoViewParams.width = desiredLandscapeWidthPx
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
        videoViewParams.topToBottom = LayoutParams.UNSET
        videoViewParams.startToStart = LayoutParams.UNSET
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
        videoViewParams.topToTop = LayoutParams.PARENT_ID
        videoViewParams.bottomToBottom = LayoutParams.PARENT_ID
      }
    }
    videoView.layoutParams = videoViewParams
  }

  /**
   * Sets the reference video to full screen mode.
   */
  private fun setFullScreen(
    videoView: VideoView,
    videoViewParams: LayoutParams,
    currentOrientation: Int
  ) {
    displayMode?.displayModeListener(PromptDisplayMode.FULL)
    if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
      if (isTablet) {
        videoViewParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        videoViewParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        shiftScreenModifierButtons(50f)
      } else {
        videoViewParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        videoViewParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        shiftScreenModifierButtons(25f)
      }
    } else if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
      if (isTablet) {
        videoViewParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        videoViewParams.topToBottom = R.id.promptView
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
        videoViewParams.startToStart = LayoutParams.PARENT_ID
        videoViewParams.bottomToBottom = LayoutParams.PARENT_ID
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
      } else {
        videoViewParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        videoViewParams.topToBottom = R.id.promptView
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
        videoViewParams.startToStart = LayoutParams.PARENT_ID
        videoViewParams.bottomToBottom = LayoutParams.PARENT_ID
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
      }
    }
    videoView.layoutParams = videoViewParams
  }

  /**
   * Sets the reference video to split screen mode.
   */
  private fun setSplitScreen(
    videoView: VideoView,
    videoViewParams: LayoutParams,
    currentOrientation: Int,
    lastDisplayMode: PromptDisplayMode?,
    screenWidth: Int,
    pixelDensity: Float
  ) {
    val widthDp = (screenWidth / pixelDensity)
    val desiredPortraitWidthPx = calculateScaledPixelWidth(widthDp, splitPortraitWidthScaleFactor)
    val desiredLandscapeWidthPx = calculateScaledPixelWidth(widthDp, splitLandscapeWidthScaleFactor)

    displayMode?.displayModeListener(PromptDisplayMode.SPLIT)
    if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
      if (isTablet) {
        videoViewParams.width = desiredPortraitWidthPx
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
        videoViewParams.startToStart = LayoutParams.PARENT_ID
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
        videoViewParams.topToBottom = R.id.promptView
        videoViewParams.topToTop = LayoutParams.UNSET
        videoViewParams.topMargin = 10
      } else {
        videoViewParams.width = desiredPortraitWidthPx
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
        videoViewParams.startToStart = LayoutParams.PARENT_ID
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
        videoViewParams.topToBottom = R.id.promptView
        videoViewParams.topToTop = LayoutParams.UNSET
        videoViewParams.topMargin = 10
      }
    } else if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
      if (isTablet) {
        videoViewParams.width = desiredLandscapeWidthPx
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
        videoViewParams.topToTop = LayoutParams.PARENT_ID
        videoViewParams.bottomToBottom = LayoutParams.PARENT_ID
        videoViewParams.startToStart = LayoutParams.UNSET
      } else {
        videoViewParams.width = desiredLandscapeWidthPx
        videoViewParams.height = (videoViewParams.width * (9f / 16f)).toInt()
        videoViewParams.endToEnd = LayoutParams.PARENT_ID
        videoViewParams.topToTop = LayoutParams.PARENT_ID
        videoViewParams.bottomToBottom = LayoutParams.PARENT_ID
        videoViewParams.startToStart = LayoutParams.UNSET
      }
    }
    videoView.layoutParams = videoViewParams
  }

  /**
   * Sets the reference video to the last detected display screen mode.
   */
  private fun undoSplitScreen(
    videoView: VideoView,
    videoViewParams: LayoutParams,
    currentOrientation: Int,
    lastDisplayMode: PromptDisplayMode?,
    screenWidth: Int,
    pixelDensity: Float
  ) {
    if (lastDisplayMode == PromptDisplayMode.ORIGINAL) {
      displayMode?.displayModeListener(PromptDisplayMode.ORIGINAL)
      setOriginalScreen(
        videoView,
        videoViewParams,
        currentOrientation,
        lastDisplayMode,
        screenWidth,
        pixelDensity
      )
    } else {
      displayMode?.displayModeListener(PromptDisplayMode.FULL)
      setFullScreen(videoView, videoViewParams, currentOrientation)
    }
  }

  /**
   * Scales the word prompt barrier at the top as well as the text size down for non-tablet devices.
   * Currently uses a fixed value for all mobile devices.
   */
  private fun scalePromptSection(scaleFactor: Float) {
    val promptText = requireView().findViewById<TextView>(R.id.promptText)
    val promptView = requireView().findViewById<View>(R.id.promptView)

    val textParams = promptText.layoutParams as ConstraintLayout.LayoutParams

    textParams.marginStart = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      12f * scaleFactor,
      resources.displayMetrics
    ).toInt()

    textParams.marginEnd = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      12f * scaleFactor,
      resources.displayMetrics
    ).toInt()

    textParams.topMargin = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      8f * scaleFactor,
      resources.displayMetrics
    ).toInt()

    promptText.layoutParams = textParams

    val originalTextSizeSp = promptText.textSize / resources.displayMetrics.scaledDensity
    val scaledTextSizeSp = originalTextSizeSp * scaleFactor
    promptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledTextSizeSp)

    val viewParams = promptView.layoutParams as ConstraintLayout.LayoutParams

    viewParams.marginStart = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      12f * scaleFactor,
      resources.displayMetrics
    ).toInt()

    viewParams.marginEnd = viewParams.marginStart

    viewParams.topMargin = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      12f * scaleFactor,
      resources.displayMetrics
    ).toInt()

    viewParams.bottomMargin = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      -12f * scaleFactor,
      resources.displayMetrics
    ).toInt()

    promptView.layoutParams = viewParams
  }

  /**
   * Shifts the display mode buttons up by a pixel density value to avoid overlapping buttons
   * for landscape full screen mode.
   */
  private fun shiftScreenModifierButtons(shiftDp: Float) {
    val fullScreenButton = requireView().findViewById<ImageButton>(R.id.fullScreenButton)
    val minimizeScreenButton = requireView().findViewById<ImageButton>(R.id.originalScreenButton)
    val splitScreenButton = requireView().findViewById<ImageButton>(R.id.splitScreenButton)
    val disableSplitScreenButton =
      requireView().findViewById<ImageButton>(R.id.disableSplitScreenButton)

    val displayMetrics = requireContext().resources.displayMetrics
    val density = displayMetrics.density
    val shiftPx = shiftDp * density

    val fullScreenParam = fullScreenButton.layoutParams as ConstraintLayout.LayoutParams
    val minScreenParam = minimizeScreenButton.layoutParams as ConstraintLayout.LayoutParams
    val splitScreenParam = splitScreenButton.layoutParams as ConstraintLayout.LayoutParams
    val disableSplitScreenParam =
      disableSplitScreenButton.layoutParams as ConstraintLayout.LayoutParams

    fullScreenParam.bottomMargin += TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      shiftPx,
      displayMetrics
    ).toInt()
    minScreenParam.bottomMargin += TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      shiftPx,
      displayMetrics
    ).toInt()
    splitScreenParam.bottomMargin += TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      shiftPx,
      displayMetrics
    ).toInt()
    disableSplitScreenParam.bottomMargin += TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      shiftPx,
      displayMetrics
    ).toInt()

    fullScreenButton.layoutParams = fullScreenParam
    minimizeScreenButton.layoutParams = minScreenParam
    splitScreenButton.layoutParams = splitScreenParam
    disableSplitScreenButton.layoutParams = disableSplitScreenParam
  }

  /**
   * Resets the display mode button positions using the original parameters of the buttons.
   * Used to undo the [shiftScreenModifierButtons] function.
   */
  private fun resetButtonPositions() {
    val fullScreenButton = requireView().findViewById<ImageButton>(R.id.fullScreenButton)
    val minimizeScreenButton = requireView().findViewById<ImageButton>(R.id.originalScreenButton)
    val splitScreenButton = requireView().findViewById<ImageButton>(R.id.splitScreenButton)
    val disableSplitScreenButton =
      requireView().findViewById<ImageButton>(R.id.disableSplitScreenButton)

    fullScreenButton.layoutParams = originalButtonParams[fullScreenButton.id]
    minimizeScreenButton.layoutParams = originalButtonParams[minimizeScreenButton.id]
    splitScreenButton.layoutParams = originalButtonParams[splitScreenButton.id]
    disableSplitScreenButton.layoutParams = originalButtonParams[disableSplitScreenButton.id]
  }

  /**
   * Scales reference video by a scale factor to accommodate portrait, landscape, tablet, and
   * non-tablet videos.
   */
  private fun calculateScaledPixelWidth(pixelWidthDensity: Float, scaleFactor: Float): Int {
    val scaledPixelDensityWidth = pixelWidthDensity * scaleFactor
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      scaledPixelDensityWidth,
      resources.displayMetrics
    ).toInt()
  }

  /**
   * Resets the prompt layout back to its original parameters.
   */
  private fun resetPromptTypeConstraint() {
    val promptLayout = view?.findViewById<ConstraintLayout>(R.id.promptLayout)
    origPromptLayout.applyTo(promptLayout)
  }

}

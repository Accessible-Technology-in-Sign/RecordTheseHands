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
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import edu.gatech.ccg.recordthesehands.databinding.WordPromptBinding
import edu.gatech.ccg.recordthesehands.upload.Prompt
import edu.gatech.ccg.recordthesehands.upload.PromptType
import java.io.File
import kotlin.math.sqrt

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

  private var _binding: WordPromptBinding? = null
  private val binding get() = _binding!!

  var videoPromptController: VideoPromptController? = null

  private var infoListener: RecordingActivityInfoListener? = null

  private var isTablet = false

  /**
   * Binds the fragment to [RecordingActivity]. Checks if it properly implements [RecordingActivityInfoListener] interface.
   */
  override fun onAttach(context: Context) {
    super.onAttach(context)
    if (context is RecordingActivityInfoListener) {
      infoListener = context
    }
  }

  /**
   * Unbinds the fragment when finished with the activity.
   */
  override fun onDetach() {
    super.onDetach()
    infoListener = null
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    _binding = WordPromptBinding.inflate(inflater, container, false)
    return binding.root
  }

  /**
   * Lay out the UI for this fragment.
   */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val displayMetrics = resources.displayMetrics
    val heightInches = displayMetrics.heightPixels / displayMetrics.ydpi
    val widthInches = displayMetrics.widthPixels / displayMetrics.xdpi
    val diagonal = sqrt((heightInches * heightInches) + (widthInches * widthInches))
    isTablet = diagonal > edu.gatech.ccg.recordthesehands.Constants.TABLET_SIZE_THRESHOLD_INCHES
    Log.i(TAG, "Computed screen size: $diagonal inches")
    Log.i(TAG, "Tablet? " + isTablet.toString())

    if (!isTablet) {
      scalePromptSection(0.5f)
    }

    if (prompt.prompt != null) {
      Log.d(TAG, "setting prompt to ${prompt.prompt}")
      binding.promptText.text = prompt.prompt
      binding.promptText.visibility = View.VISIBLE
    } else {
      Log.d(TAG, "no prompt available for ${prompt.key}.")
      binding.promptText.visibility = View.GONE
    }
    binding.promptView.doOnLayout {
      notifyDisplayMode(PromptDisplayMode.ORIGINAL)
    }
    when (prompt.type) {
      PromptType.TEXT -> {
        binding.playerControlsContainer.visibility = View.GONE
        binding.promptVideo.visibility = View.GONE
      }

      PromptType.IMAGE -> {
        Log.d(TAG, "Rendering Image for ${prompt.key}.")
        binding.playerControlsContainer.visibility = View.GONE
        binding.promptVideo.visibility = View.GONE
        prompt.resourcePath?.let { resourcePath ->
          Log.d(TAG, "resourcePath $resourcePath.")
          val filepath = File(requireContext().filesDir, resourcePath)
          binding.promptImage.setImageURI(Uri.fromFile(filepath))
          binding.promptImage.visibility = View.VISIBLE
        }
      }

      PromptType.VIDEO -> {
        binding.playerControlsContainer.visibility = View.VISIBLE
        binding.promptVideo.visibility = View.VISIBLE
        prompt.resourcePath?.let { resourcePath ->
          val videoViewParams = binding.promptVideo.layoutParams as LayoutParams

          binding.fullScreenButton.setOnClickListener {
            notifyDisplayMode(PromptDisplayMode.FULL)
            binding.fullScreenButton.visibility = View.GONE
            binding.originalScreenButton.visibility = View.VISIBLE
            binding.splitScreenButton.visibility = View.VISIBLE
            Log.i(TAG, "Enlarging video to full screen size")
          }

          binding.originalScreenButton.setOnClickListener {
            notifyDisplayMode(PromptDisplayMode.ORIGINAL)
            binding.fullScreenButton.visibility = View.VISIBLE
            binding.originalScreenButton.visibility = View.GONE
            binding.splitScreenButton.visibility = View.VISIBLE
          }

          binding.splitScreenButton.setOnClickListener {
            notifyDisplayMode(PromptDisplayMode.SPLIT)
            binding.fullScreenButton.visibility = View.VISIBLE
            binding.originalScreenButton.visibility = View.VISIBLE
            binding.splitScreenButton.visibility = View.GONE
            Log.i(TAG, "Splitting screen")
          }

          videoPromptController = VideoPromptController(
            requireContext(), null, binding.promptVideo, resourcePath, true
          )
          binding.promptVideo.layoutParams = videoViewParams
          binding.promptVideo.visibility = View.VISIBLE
          binding.fullScreenButton.visibility = View.VISIBLE
          binding.originalScreenButton.visibility = View.GONE
          binding.splitScreenButton.visibility = View.VISIBLE
        }
      }
    }

  }

  /**
   * Scales the word prompt barrier at the top as well as the text size down for non-tablet devices.
   * Currently uses a fixed value for all mobile devices.
   */
  private fun scalePromptSection(scaleFactor: Float) {
    val textParams = binding.promptText.layoutParams as LayoutParams

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

    binding.promptText.layoutParams = textParams

    binding.promptText.setTextSize(
      TypedValue.COMPLEX_UNIT_PX,
      binding.promptText.textSize * scaleFactor
    )

    val viewParams = binding.promptView.layoutParams as LayoutParams

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

    binding.promptView.layoutParams = viewParams
  }


  override fun onResume() {
    super.onResume()
    // The view is already laid out when this is called on subsequent page views,
    // so we can notify the listener directly. The initial call is handled by
    // the doOnLayout in onViewCreated.
    if (binding.promptView.height > 0) {
      notifyDisplayMode(PromptDisplayMode.ORIGINAL)
    }
  }

  /**
   * Calculates the position of the prompt view and notifies the listener with the
   * correct height, so the camera preview can be positioned correctly.
   */
  private fun notifyDisplayMode(mode: PromptDisplayMode) {
    val offsetViewBounds = Rect()
    val target = binding.promptView
    val margin = 10
    binding.root.offsetDescendantRectToMyCoords(target, offsetViewBounds)
    infoListener?.onActivityInfoChanged(mode, offsetViewBounds.top + target.bottom + margin)
  }

}

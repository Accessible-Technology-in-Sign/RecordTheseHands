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

import android.app.Activity.RESULT_OK
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import edu.gatech.ccg.recordthesehands.databinding.EndOfRecordingPageBinding
import edu.gatech.ccg.recordthesehands.hapticFeedbackOnTouchListener
import edu.gatech.ccg.recordthesehands.upload.Prompt

class SaveRecordingFragment(
  private var recordingActivity: RecordingActivity,
  private var prompts: ArrayList<Prompt>,
  @LayoutRes layout: Int
) :
  Fragment(layout) {

  private var _binding: EndOfRecordingPageBinding? = null
  private val binding get() = _binding!!

  private var infoListener: RecordingActivityInfoListener? = null

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
    _binding = EndOfRecordingPageBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.promptView.doOnLayout {
      notifyDisplayMode(PromptDisplayMode.ORIGINAL)
    }
    // Set the save button to finish the recording activity when pressed.
    binding.finishButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
    binding.finishButton.setOnClickListener {
      binding.finishButton.isEnabled = false
      recordingActivity.concludeRecordingSession(RESULT_OK, "RESULT_OK")
    }

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

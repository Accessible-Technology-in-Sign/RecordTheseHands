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
package edu.gatech.ccg.recordthesehands.summary

import android.app.Activity.RESULT_OK
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import edu.gatech.ccg.recordthesehands.databinding.RecordingListBinding
import edu.gatech.ccg.recordthesehands.hapticFeedbackOnTouchListener
import edu.gatech.ccg.recordthesehands.recording.RecordingActivity

/**
 * Represents the recording summary page that shows up at the end of the user's recording session.
 */
class RecordingListFragment(
  private val activity: RecordingActivity,
  @LayoutRes layout: Int
) : Fragment(layout) {

  private var _binding: RecordingListBinding? = null
  private val binding get() = _binding!!

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    _binding = RecordingListBinding.inflate(inflater, container, false)
    return binding.root
  }

  /**
   * Overridden method from Fragment - called when the fragment is initialized.
   */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    binding.recordingList.layoutManager = LinearLayoutManager(this.context)

    // Set up recycler view
    val recordingListAdapter = RecordingListAdapter(activity)
    binding.recordingList.adapter = recordingListAdapter

    // Set the save button to finish the recording activity when pressed.
    binding.closeSession.setOnTouchListener(::hapticFeedbackOnTouchListener)
    binding.closeSession.setOnClickListener {
      binding.closeSession.isEnabled = false
      activity.concludeRecordingSession(RESULT_OK, "RESULT_OK")
    }

    // Hide the loading spinner and gray-out screen once the view is loaded.
    // The loading screen exists to clarify to the user that saving is in progress.
    binding.loadingScreen.alpha = 0.0f
    binding.loadingPanel.visibility = View.INVISIBLE
  }

}

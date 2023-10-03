/**
 * RecordingListFragment.kt
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2021-23
 *   Georgia Institute of Technology
 *   Authors:
 *     Sahir Shahryar <contact@sahirshahryar.com>
 *     Matthew So <matthew.so@gatech.edu>
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

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.recording.ClipDetails
import edu.gatech.ccg.recordthesehands.recording.RecordingActivity
import java.util.*
import kotlin.collections.HashMap

/**
 * Represents the recording summary page that shows up at the end of the user's recording session.
 */
class RecordingListFragment(
  private val wordList: ArrayList<String>,
  private val sessionFiles: HashMap<String, ArrayList<ClipDetails>>,
  private val activity: RecordingActivity,
  @LayoutRes layout: Int
) : Fragment(layout) {

  /**
   * Overridden method from Fragment - called when the fragment is initialized.
   */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val scrollView = view.findViewById<RecyclerView>(R.id.recordingList)
    scrollView.layoutManager = LinearLayoutManager(this.context)

    // Set up recycler view
    val recordingListAdapter = RecordingListAdapter(wordList, sessionFiles, activity)
    scrollView.adapter = recordingListAdapter

    // Set the save button to finish the recording activity when pressed.
    val saveButton = view.findViewById<Button>(R.id.closeSession)
    saveButton.setOnClickListener {
      saveButton.isEnabled = false
      activity.concludeRecordingSession()
    }

    // Hide the loading spinner and gray-out screen once the view is loaded.
    // The loading screen exists to clarify to the user that saving is in progress.
    val loadingScreen = view.findViewById<LinearLayout>(R.id.loadingScreen)
    loadingScreen.alpha = 0.0f

    val loadingWheel = view.findViewById<RelativeLayout>(R.id.loadingPanel)
    loadingWheel.visibility = View.INVISIBLE
  }

}

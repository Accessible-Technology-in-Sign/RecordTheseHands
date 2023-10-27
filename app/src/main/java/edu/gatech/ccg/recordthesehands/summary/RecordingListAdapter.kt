/**
 * RecordingListAdapter.kt
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
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.clipText
import edu.gatech.ccg.recordthesehands.hapticFeedbackOnTouchListener
import edu.gatech.ccg.recordthesehands.recording.ClipDetails
import edu.gatech.ccg.recordthesehands.recording.RecordingActivity
import edu.gatech.ccg.recordthesehands.recording.VideoPreviewFragment
import edu.gatech.ccg.recordthesehands.recording.saveClipData
import edu.gatech.ccg.recordthesehands.upload.Prompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * The RecyclerView adapter for the list of recordings shown at the end of the recording session.
 * This view is contained within [RecordingListFragment].
 */
class RecordingListAdapter(
  private val activity: RecordingActivity
) : RecyclerView.Adapter<RecordingListAdapter.RecordingListItem>() {

  /**
   * Represents an individual entry within the list of recordings.
   */
  class RecordingListItem(itemView: View) : RecyclerView.ViewHolder(itemView) {
    /**
     * Sets up the listener for opening the video preview or marking the recording as invalid.
     */
    fun setData(
      clipDetails: ClipDetails,
      activity: RecordingActivity,
      listAdapter: RecordingListAdapter
    ) {
      val label = itemView.findViewById<TextView>(R.id.recordingTitle)
      if (clipDetails.prompt.prompt.length > 40) {
        label.text = clipText(clipDetails.prompt.prompt, 37) + "..."
      } else {
        label.text = clipDetails.prompt.prompt
      }

      val clipIsValidImage = itemView.findViewById<ImageButton>(R.id.clipIsValidImage)
      val clipIsInvalidImage = itemView.findViewById<ImageButton>(R.id.clipIsInvalidImage)
      if (clipDetails.valid) {
        clipIsValidImage.visibility = View.VISIBLE
        clipIsInvalidImage.visibility = View.GONE
      } else {
        clipIsValidImage.visibility = View.GONE
        clipIsInvalidImage.visibility = View.VISIBLE
      }

      /**
       * Allow the user to mark the clip valid or invalid as they wish.
       */

      clipIsValidImage.setOnTouchListener(::hapticFeedbackOnTouchListener)
      clipIsValidImage.setOnClickListener {
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        clipDetails.valid = false
        clipDetails.lastModifiedTimestamp = now

        activity.dataManager.logToServerAtTimestamp(
          timestamp,
          "setting clip invalid ${clipDetails.toJson()}")

        CoroutineScope(Dispatchers.IO).launch {
          activity.dataManager.saveClipData(clipDetails)
        }

        clipIsValidImage.visibility = View.GONE
        clipIsInvalidImage.visibility = View.VISIBLE
      }

      clipIsInvalidImage.setOnTouchListener(::hapticFeedbackOnTouchListener)
      clipIsInvalidImage.setOnClickListener {
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        clipDetails.valid = true
        activity.dataManager.logToServerAtTimestamp(
          timestamp,
          "setting clip valid ${clipDetails.toJson()}")

        clipDetails.lastModifiedTimestamp = now
        CoroutineScope(Dispatchers.IO).launch {
          activity.dataManager.saveClipData(clipDetails)
        }

        clipIsValidImage.visibility = View.VISIBLE
        clipIsInvalidImage.visibility = View.GONE
      }

      /**
       * When the user taps the word itself, we show a popup of the user's recording for
       * that word. This is possible because the recording has already been saved locally
       * and we know the timestamp data for that recording, so we just play that segment
       * on a loop. (See [VideoPreviewFragment].)
       */
      label.setOnTouchListener(::hapticFeedbackOnTouchListener)
      label.setOnClickListener {
        val bundle = Bundle()
        bundle.putString("prompt", clipDetails.prompt.prompt)
        bundle.putString("filepath", "upload" + File.separator + clipDetails.filename)
        bundle.putBoolean("isTablet", activity.isTablet())
        // TODO landscape ?

        bundle.putLong(
          "startTimeMs", Duration.between(
            clipDetails.videoStart, clipDetails.signStart()
          ).toMillis()
        )

        bundle.putLong(
          "endTimeMs", Duration.between(
            clipDetails.videoStart, clipDetails.signEnd()
          ).toMillis()
        )

        val previewFragment = VideoPreviewFragment(R.layout.recording_preview)
        previewFragment.arguments = bundle

        previewFragment.show(activity.supportFragmentManager, "videoPreview")
      }

    }
  }

  /**
   * Implemented method from RecyclerView.Adapter - generates a view of a particular type.
   * For this view, there's only one type of view, [RecordingListItem].
   */
  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingListItem {
    if (parent.layoutTransition != null) {
      parent.layoutTransition.setAnimateParentHierarchy(false)
    }

    val view = LayoutInflater.from(parent.context).inflate(
      R.layout.recording_list_item,
      parent, false
    )
    return RecordingListItem(view)
  }

  /**
   * Implemented method from RecyclerView.Adapter - loads the data at a given position into
   * the view for that position. Here, we are just passing the word data to a [RecordingListItem].
   */
  override fun onBindViewHolder(holder: RecordingListItem, position: Int) {
    holder.setData(activity.clipData[position], activity, this)
  }

  /**
   * Implemented method from RecyclerView.Adapter - gets the number of items in the RecyclerView.
   * Here, it's just the number of clips recorded in the session.
   */
  override fun getItemCount(): Int {
    return activity.clipData.size
  }

}

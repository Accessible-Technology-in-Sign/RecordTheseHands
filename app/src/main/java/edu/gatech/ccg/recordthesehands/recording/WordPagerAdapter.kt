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

import android.util.Log
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.summary.RecordingListFragment

/**
 * The Adapter for swiping through the prompts.  The name "Word" is in
 * reference to the original prompts which were single words.
 */
class WordPagerAdapter(
  private var recordingActivity: RecordingActivity,
  private val useSummaryPage: Boolean,
) : FragmentStateAdapter(recordingActivity) {

  val numPromptPages = recordingActivity.sessionLimit - recordingActivity.sessionStartIndex
  override fun getItemCount(): Int {
    if (useSummaryPage) {
      return numPromptPages + 2
    }
    return numPromptPages + 1
  }

  override fun createFragment(position: Int): Fragment {
    Log.d("WordPagerAdapter", "Page changed to {$position}")
    if (position < numPromptPages) {
      // TODO Add the videos back in.
      val prompt = recordingActivity.prompts.array[recordingActivity.sessionStartIndex + position]
      return WordPromptFragment(prompt, R.layout.word_prompt)
    } else if (position == numPromptPages) {
      return SaveRecordingFragment(recordingActivity.prompts.array, R.layout.save_record)
    } else {
      return RecordingListFragment(
        recordingActivity, R.layout.recording_list
      )
    }
  }

}

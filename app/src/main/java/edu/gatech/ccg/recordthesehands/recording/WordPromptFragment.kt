/**
 * WordPromptFragment.kt
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
package edu.gatech.ccg.recordthesehands.recording

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.annotation.LayoutRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import edu.gatech.ccg.recordthesehands.upload.Prompt
import edu.gatech.ccg.recordthesehands.upload.PromptType
import edu.gatech.ccg.recordthesehands.R
import java.io.File

/**
 * This is the little rectangle at the top of the screen that prompts the
 * user with what to sign.
 */
class WordPromptFragment(
  private var prompt: Prompt, @LayoutRes layout: Int,
) : Fragment(layout) {

  /**
   * Lay out the UI for this fragment.
   */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    // Set the heading for the UI as the word
    val textView = view.findViewById<TextView>(R.id.promptText)
    if (prompt.prompt != null) {
      textView.text = prompt.prompt
    } else {
      textView.visibility = View.INVISIBLE
    }

    when (prompt.type) {
      PromptType.TEXT -> {
      }
      PromptType.IMAGE -> {
        if (prompt.resourcePath != null) {
          val imageView = view.findViewById<ImageView>(R.id.promptImage)
          val filepath = File(requireContext().filesDir, prompt.resourcePath)
          imageView.setImageURI(Uri.fromFile(filepath))
        }
      }
      PromptType.VIDEO -> {
        if (prompt.resourcePath != null) {
          val videoView = view.findViewById<VideoView>(R.id.promptVideo)
          VideoPromptController(requireContext(), null, videoView, prompt.resourcePath!!)
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

}

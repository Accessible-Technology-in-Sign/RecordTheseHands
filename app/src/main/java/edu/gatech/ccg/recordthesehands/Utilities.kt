/**
 * Utilities.kt
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
package edu.gatech.ccg.recordthesehands

import android.util.Log
import edu.gatech.ccg.recordthesehands.recording.ClipDetails
import java.io.File
import java.lang.Math.min
import java.security.MessageDigest
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlin.collections.ArrayList
import kotlin.random.Random
import org.json.JSONObject

/**
 * Selects `count` elements from `list` at random, using the designated seed if given,
 * or a random seed otherwise.
 */
fun <T> randomChoice(list: List<T>, count: Int, seed: Long? = null): ArrayList<T> {
  when (count) {
    0 -> return ArrayList()
    list.size -> return ArrayList(list)
  }

  // Initialize Random object and resulting ArrayList.
  val rand = Random(seed ?: Random.nextLong())
  val result = ArrayList<T>()

  if (count == 1) {
    result.add(list[rand.nextInt(list.size)])
    return result
  }

  // Fisher-Yates shuffle algorithm, `count` iterations, then return the `count` last items
  // in the array.
  val copy = ArrayList(list)
  for (iter in 1..count) {
    val destIndex = list.size - iter
    val destVal = copy[destIndex]
    val selIndex = rand.nextInt(destIndex + 1)
    val selVal = copy[selIndex]

    copy[destIndex] = selVal
    copy[selIndex] = destVal
  }

  for (i in list.size - count until list.size) {
    result.add(copy[i])
  }

  return result
}

/**
 * Prefixes a number with zeros so that the total length of the output string is at least
 * `digits` characters long.
 */
fun padZeroes(number: Int, digits: Int = 5): String {
  val asString = number.toString()
  if (asString.length >= digits) {
    return asString
  }

  return "0".repeat(digits - asString.length) + asString
}


/**
 * Generates the formatted EXIF data for the given clip data (list of start/stop times for
 * user recordings).
 */
fun generateClipExif(
  sessionVideoFiles: HashMap<String, ArrayList<ClipDetails>>,
  duration: Long = 0L, frames: Int = 0
): String {
  JSONObject().apply {
    put("__version", Constants.APP_VERSION)
    put("durationMs", duration)
    put("expectedFrames", frames)
    for ((key, value) in sessionVideoFiles) {
      put(key, value.mapIndexed { index: Int, clip: ClipDetails ->
        clip.toString(index + 1)
      })
    }

    return toString()
  }
}

/**
 * Selects `count` words at random, preferring the least-recorded words in the list first.
 */
fun <T> lowestCountRandomChoice(list: List<T>, numRecordings: List<Int>, count: Int): ArrayList<T> {
  val result = ArrayList<T>()
  if (count == 0 || list.isEmpty()) {
    return result
  }

  // Sort words into buckets by the number of recordings
  // map<number of recordings, list(words)>
  val recordingCounts = numRecordings.zip(list)
  val recordingCountsMap = TreeMap<Int, ArrayList<T>>()
  for (signCount in recordingCounts) {
    if (!recordingCountsMap.containsKey(signCount.first)) {
      recordingCountsMap[signCount.first] = ArrayList()
    }

    recordingCountsMap[signCount.first]?.add(signCount.second)
  }

  // Sort recording counts
  var countRemaining = count
  val sortedCounts = ArrayList(recordingCountsMap.keys)
  sortedCounts.sortBy { it }

  // Choose from least-recorded words first until room runs out
  for (key in sortedCounts) {
    if (countRemaining <= 0) {
      break
    }

    val currSigns = recordingCountsMap[key]!!
    val numSelected = min(currSigns.size, countRemaining)
    result.addAll(randomChoice(currSigns, numSelected))
    countRemaining -= numSelected
  }

  return result
}

/**
 * Sends an email from the given address to the list of recipients,
 * with a given subject and message content. Currently only compatible with
 * Gmail addresses.
 *
 * Based on code by Stack Overflow user Blundell (CC BY-SA 4.0)
 * https://stackoverflow.com/a/60090464
 */
fun sendEmail(from: String, to: List<String>, subject: String, content: String, password: String) {
  val props = Properties()

  val server = "smtp.gmail.com"
  val auth = PasswordAuthentication(from, password)

  props["mail.smtp.auth"] = "true"
  props["mail.user"] = from
  props["mail.smtp.host"] = server
  props["mail.smtp.port"] = "587"
  props["mail.smtp.starttls.enable"] = "true"
  props["mail.smtp.ssl.trust"] = server
  props["mail.mime.charset"] = "UTF-8"

  props["mail.smtp.connectiontimeout"] = "10000"
  props["mail.smtp.timeout"] = "10000"

  val msg: Message = MimeMessage(Session.getDefaultInstance(props, object : Authenticator() {
    override fun getPasswordAuthentication() = auth
  }))

  msg.setFrom(InternetAddress(from))
  msg.sentDate = Calendar.getInstance().time

  val recipients = to.map { InternetAddress(it) }
  msg.setRecipients(Message.RecipientType.TO, recipients.toTypedArray())

  msg.replyTo = arrayOf(InternetAddress(from))

  msg.addHeader("X-Mailer", "RecordTheseHands")
  msg.addHeader("Precedence", "bulk")
  msg.subject = subject

  msg.setContent(MimeMultipart().apply {
    addBodyPart(MimeBodyPart().apply {
      setText(content, "iso-8859-1")
    })
  })

  Log.d(
    "EMAIL", "Attempting to send email with subject '$subject' and " +
        "message '$content'"
  )

  try {
    Transport.send(msg)
  } catch (ex: MessagingException) {
    Log.d("EMAIL", "Email send failed: ${ex.message}")
  }
}

/**
 * Computes the MD5 hash for a File object.
 *
 * Written by Stack Overflow user broc.seib (CC BY-SA 4.0)
 * https://stackoverflow.com/a/62963461
 */
fun File.md5(): String {
  val md = MessageDigest.getInstance("MD5")
  return this.inputStream().use { fis ->
    val buffer = ByteArray(8192)
    generateSequence {
      when (val bytesRead = fis.read(buffer)) {
        -1 -> null
        else -> bytesRead
      }
    }.forEach { bytesRead -> md.update(buffer, 0, bytesRead) }
    md.digest().joinToString("") { "%02x".format(it) }
  }
}

fun clipText(text: String, len: Int = 20): String {
  return if (text.length > len - 3) {
    text.substring(0, len - 3) + "..."
  } else {
    text
  }
}
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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.recording.ClipDetails
import edu.gatech.ccg.recordthesehands.recording.saveClipData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.time.Instant
import java.time.format.DateTimeFormatter

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
    "EMAIL", "Attempting to send email with subject '$subject'")

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

fun toHex(data: ByteArray): String {
  return data.joinToString(separator = "") { x -> "%02x".format(x) }
}

fun fromHex(hex: String): ByteArray {
  check(hex.length % 2 == 0) { "Must have an even length" }

  val byteIterator = hex.chunkedSequence(2)
    .map { it.toInt(16).toByte() }
    .iterator()

  return ByteArray(hex.length / 2) { byteIterator.next() }
}

fun msToHMS(ms: Long, compact: Boolean = false): String {
  var seconds = ms / 1000L
  var minutes = seconds / 60L
  var hours = minutes / 60L
  seconds = seconds % 60L
  minutes = minutes % 60L
  val msRemaining = ms % 1000L

  if (compact) {
    return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, msRemaining)
  } else {
    var output = ""
    if (hours > 0) {
      output += "$hours hours, "
    }
    if (minutes > 0 || hours > 0) {
      output += "$minutes minutes, "
    }
    if (minutes > 0 || hours > 0 || seconds > 0 || msRemaining > 0) {
      output += "%d.%03d seconds".format(seconds, msRemaining)
    } else {
      output += "0 seconds"
    }
    return output
  }
}
fun hapticFeedbackOnTouchListener(view: View, event: MotionEvent): Boolean {
  when (event.action) {
    MotionEvent.ACTION_DOWN -> {
      view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    MotionEvent.ACTION_UP -> {
      view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
    }
  }
  return false // Allow other listeners to receive events.
}

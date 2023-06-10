/**
 * RecordingActivity.kt
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

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.hardware.camera2.*
import android.media.ExifInterface
import android.media.ExifInterface.TAG_IMAGE_DESCRIPTION
import android.media.MediaCodec
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ThumbnailUtils
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import edu.gatech.ccg.recordthesehands.*
import edu.gatech.ccg.recordthesehands.Constants.RECORDINGS_PER_WORD
import edu.gatech.ccg.recordthesehands.Constants.WORDS_PER_SESSION
import edu.gatech.ccg.recordthesehands.Constants.RESULT_CAMERA_DIED
import edu.gatech.ccg.recordthesehands.Constants.RESULT_NO_ERROR
import edu.gatech.ccg.recordthesehands.Constants.RESULT_RECORDING_DIED
import edu.gatech.ccg.recordthesehands.databinding.ActivityRecordBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Contains the data for a clip within the greater recording.
 *
 * @param file       (File) The filename for the (overall) video recording.
 * @param videoStart (Date) The timestamp that the overall video recording started at.
 * @param signStart  (Date) The timestamp that the clip within the video started at.
 * @param signEnd    (Date) The timestamp that the clip within the video ended at.
 * @param isValid    (Boolean) true if this clip should be considered usable data, false otherwise.
 *                   We assume that if the user creates a new recording for a particular word, then
 *                   there was something wrong with their previous recording, and we then mark that
 *                   recording as invalid.
 */
data class ClipDetails(val file: File, val videoStart: Date, val signStart: Date,
                       val signEnd: Date, var isValid: Boolean) {

    /**
     * Creates a string representation for this recording. Used when sending confirmation emails.
     */
    override fun toString(): String {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss.SSS", Locale.US)
        val isValidPython = if (isValid) "True" else "False"
        return "(file=${file.absolutePath}, videoStart=${sdf.format(videoStart)}, " +
                "signStart=${sdf.format(signStart)}, signEnd=${sdf.format(signEnd)}, " +
                "isValid=${isValidPython})"
    }

}

/**
 * This class handles the recording of ASL into videos.
 *
 * @author  Matthew So <matthew.so@gatech.edu>, Sahir Shahryar <contact@sahirshahryar.com>
 * @since   October 4, 2021
 * @version 1.1.0
 */
class RecordingActivity : AppCompatActivity() {
    companion object {
        private val TAG = RecordingActivity::class.java.simpleName

        /**
         * Record video at 15 Mbps. At 1944x2592 @ 30 fps, this level of detail should be more
         * than high enough.
         */
        private const val RECORDER_VIDEO_BITRATE: Int = 15_000_000

        /**
         * Height, width, and frame rate of the video recording. Using a 4:3 aspect ratio allows us
         * to get the widest possible field of view on a Pixel 4a camera, which has a 4:3 sensor.
         * Any other aspect ratio would result in some degree of cropping.
         */
        private const val RECORDING_HEIGHT = 2592
        private const val RECORDING_WIDTH = 1944
        private const val RECORDING_FRAMERATE = 30

        /**
         * Whether or not an instructional video should be shown to the user.
         */
        private const val SHOW_INSTRUCTION_VIDEO = true

        /**
         * The length of the countdown (in milliseconds), after which the recording will end
         * automatically. Currently configured to be 15 minutes.
         */
        private const val COUNTDOWN_DURATION = 15 * 60 * 1000L

        /**
         * This constant is used when sending confirmation emails, in case we need to debug
         * something.
         */
        private const val APP_VERSION = "1.2"
    }



    // UI elements
    /**
     * Big red button used to start/stop a clip. (Note that we are continuously recording;
     * the button only marks when the user started or stopped signing to the camera.)
     */
    lateinit var recordButton: FloatingActionButton

    /**
     * The UI that allows a user to swipe back and forth and make recordings for 10 different
     * words in one session. The "Swipe right to finish recording" and recording summary screens
     * are also included in this ViewPager.
     */
    lateinit var wordPager: ViewPager2

    /**
     * The UI that shows how much time is left on the recording before it auto-concludes.
     */
    lateinit var countdownText: TextView

    /**
     * The flashing recording light.
     */
    private lateinit var recordingLightView: ImageView

    /**
     * The recording preview.
     */
    lateinit var cameraView: SurfaceView

    /**
     * The instructional video, if [SHOW_INSTRUCTION_VIDEO] is true. If false, this value will
     * be null.
     */
    private var tutorialView: VideoTutorialController? = null



    // UI state variables
    /**
     * Marks whether or not the recording button is enabled. If not, then the button should be
     * invisible, and it should be neither clickable (tappable) nor focusable.
     */
    private var recordButtonEnabled = false

    /**
     * Marks whether or not the camera has been successfully initialized. This is used to prevent
     * parts of the code related to camera initialization from running multiple times.
     */
    private var cameraInitialized = false

    /**
     * Marks whether or not the user is currently signing a word. This is essentially only true
     * for the duration that the user holds down the Record button.
     */
    private var isSigning = false

    /**
     * Marks whether or not the camera is currently recording or not. We record continuously as soon
     * as the activity launches, so this value will be true in some instances that `isSigning` may
     * be false.
     */
    private var isRecording = false

    /**
     * In the event that the user is holding down the Record button when the timer runs out, this
     * value will be set to true. Once the user releases the button, the session will end
     * immediately.
     */
    private var endSessionOnRecordButtonRelease = false

    /**
     * The page of the ViewPager UI that the user is currently on. If there are K words that have
     * been selected for the user to record, indices 0 to K - 1 (inclusive) are the indices
     * corresponding to those words, index K is the index for the "Swipe right to end recording"
     * page, and index K + 1 is the recording summary page.
     */
    private var currentPage: Int = 0

    /**
     * A mutex lock for the recording button. The lock ensures that multiple quick taps of the
     * record button can't cause thread safety issues.
     */
    private val buttonLock = ReentrantLock()

    /**
     * A timer for the recording, which starts with a time limit of `COUNTDOWN_DURATION` and
     * shows its current value in `countdownText`. When the timer expires, the recording
     * automatically stops and the user is taken to the summary screen.
     */
    private lateinit var countdownTimer: CountDownTimer



    // Word data
    /**
     * The list of words that have been selected for this recording session. These are selected
     * at random by the SplashScreenActivity and passed to this activity.
     */
    private lateinit var wordList: ArrayList<String>

    /**
     * A list of all words contained within res/values/strings.xml. We use this when generating
     * confirmation emails, to determine the user's overall progress.
     */
    private lateinit var completeWordList: ArrayList<String>

    /**
     * The currently selected word.
     */
    private lateinit var currentWord: String



    // Recording and session data
    /**
     * The filename for the current video recording.
     */
    private lateinit var filename: String

    /**
     * The file handle for the current video recording.
     */
    private lateinit var outputFile: File

    /**
     * We store the clip data separately from the video file itself. `metadataFilename` is
     * the name of the file containing that clip data.
     */
    private lateinit var metadataFilename: String

    /**
     * A tag for the type of recording being done. The tag is added to the video's filename.
     *
     * This can be provided by SplashScreenActivity as a string value with the key "CATEGORY",
     * but if it is not provided, it will default to "randombatch" (i.e., we are selecting a batch
     * of words at random so that we get users signing the same word across different settings
     * and backgrounds, rather than all of the videos for a particular word having the same
     * background).
     */
    private lateinit var recordingCategory: String

    /**
     * The user's unique identifier. This value is provided by SplashScreenActivity under the
     * "UID" key.
     */
    private lateinit var userUID: String

    /**
     * A mapping of each of the words in `wordList` to a list of recordings of that word.
     * Each entry within the list is a [ClipDetails] object containing the video filename,
     * start time, end time, and validity of the clip. (See the [ClipDetails] class for more info.)
     */
    private var sessionClipData = HashMap<String, ArrayList<ClipDetails>>()

    /**
     * The time at which the recording session started.
     */
    private lateinit var sessionStartTime: Date

    /**
     * The time at which the current clip started (i.e., when the user presses down the Record
     * button).
     */
    private lateinit var segmentStartTime: Date

    /**
     * Because the email and password have been put in a .gitignored file for security
     * reasons, the app is designed to not completely fail to compile if those constants are
     * missing. The constants' existence is checked in SplashScreenActivity and if any of them
     * don't exist, sending email confirmations is disabled. See the README for information on
     * how to set these constants, if that functionality is desired.
     */
    private var emailConfirmationEnabled: Boolean = false



    // Camera API variables
    /**
     * The thread for handling camera-related actions.
     */
    private lateinit var cameraThread: HandlerThread

    /**
     * The Handler object for accessing the camera. We primarily use this when initializing or
     * shutting down the camera.
     */
    private lateinit var cameraHandler: Handler

    /**
     * The buffer to which camera frames are projected. This is used by the MediaRecorder to
     * record the video
     */
    private lateinit var recordingSurface: Surface

    /**
     * The camera recorder instance, used to set up and control the recording settings.
     */
    private lateinit var recorder: MediaRecorder


    /**
     * A CameraCaptureSession object, which functions as a wrapper for handling / stopping the
     * recording.
     */
    private lateinit var session: CameraCaptureSession

    /**
     * Details of the camera being used to record the video.
     */
    private lateinit var camera: CameraDevice

    /**
     * The buffer to which the camera sends frames for the purposes of displaying a live preview
     * (rendered in the `cameraView`).
     */
    private var previewSurface: Surface? = null

    /**
     * The operating system's camera service. We can get camera information from this service.
     */
    private val cameraManager: CameraManager by lazy {
        val context = this.applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }



    // Permissions
    /**
     * Marks whether the user has enabled the necessary permissions to record successfully. If
     * we don't check this, the app will crash instead of presenting an error.
     */
    private var permissions: Boolean = true

    /**
     * When the activity starts, this routine checks the CAMERA and WRITE_EXTERNAL_STORAGE
     * permissions. (We do not need the MICROPHONE permission as we are just recording silent
     * videos.)
     */
    val permission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            map.entries.forEach { entry ->
                when (entry.key) {
                    Manifest.permission.CAMERA ->
                        permissions = permissions && entry.value
                    Manifest.permission.WRITE_EXTERNAL_STORAGE ->
                        permissions = permissions && entry.value
                }
            }
        }

    /**
     * A function to initialize a new thread for camera-related code to run on.
     */
    private fun generateCameraThread() = HandlerThread("CameraThread").apply { start() }

    /**
     * Generates a new [Surface] for storing recording data, which will promptly be assigned to
     * the [recordingSurface] field above.
     */
    private fun createRecordingSurface(): Surface {
        val surface = MediaCodec.createPersistentInputSurface()
        recorder = MediaRecorder(this)

        /**
         * Save the output video to the system Movies folder, as we can use this as a quick and easy
         * workaround to upload videos to a user's Google Photos library. It also lets us use
         * the OS-level implementation of background uploads and file sync, allowing us to
         * sidestep the work of uploading to our own servers or working with Google Drive APIs.
         */
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.absolutePath
        outputFile = File(outputDir, "$filename.mp4")

        setRecordingParameters(recorder, surface).prepare()

        return surface
    }

    /**
     * Prepares a [MediaRecorder] using the given surface.
     */
    private fun setRecordingParameters(rec: MediaRecorder, surface: Surface)
            = rec.apply {
        // Set the video settings from our predefined constants.
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)

        setVideoFrameRate(RECORDING_FRAMERATE)
        setVideoSize(RECORDING_HEIGHT, RECORDING_WIDTH)

        setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
        setInputSurface(surface)

        /**
         * The orientation of 270 degrees (-90 degrees) was determined through
         * experimentation. For now, we do not need to support other
         * orientations than the default portrait orientation.
         */
        setOrientationHint(270)
    }

    /**
     * This code initializes the camera-related portion of the code, adding listeners to enable
     * video recording as long as we hold down the Record button.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        /**
         * First, check camera permissions. If the user has not granted permission to use the
         * camera, give a prompt asking them to grant that permission in the Settings app, then
         * relaunch the app.
         */
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
               PackageManager.PERMISSION_GRANTED) {

            val errorRoot = findViewById<ConstraintLayout>(R.id.main_root)
            val errorMessage = layoutInflater.inflate(R.layout.permission_error, errorRoot,
                false)
            errorRoot.addView(errorMessage)

            // We essentially "gray out" the record button using the ARGB code #FFFA9389
            // (light pink).
            recordButton.backgroundTintList = ColorStateList.valueOf(0xFFFA9389.toInt())

            // Since the user hasn't granted camera permissions, we need to stop here.
            return@launch
        }

        /**
         * User has given permission to use the camera. First, find the front camera. If no
         * front-facing camera is available, crash. (This shouldn't fail on any modern
         * smartphone.)
         */
        var cameraId = ""

        for (id in cameraManager.cameraIdList) {
            val face = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (face == CameraSelector.LENS_FACING_FRONT) {
                cameraId = id
                break
            }
        }

        if (cameraId == "") {
            throw IllegalStateException("No front camera available")
        }

        /**
         * Open the front-facing camera.
         */
        camera = openCamera(cameraManager, cameraId, cameraHandler)

        /**
         * Send video feed to both [previewSurface] and [recordingSurface], then start the
         * recording.
         */
        val targets = listOf(previewSurface!!, recordingSurface)
        session = createCaptureSession(camera, targets, cameraHandler)

        startRecording()

        /**
         * Set a listener for when the user presses the record button.
         */
        recordButton.setOnTouchListener { _, event ->
            /**
             * Do nothing if the record button is disabled.
             */
            if (!recordButtonEnabled) {
                return@setOnTouchListener false
            }

            when (event.action) {
                /**
                 * User presses down the record button: mark the start of a recording.
                 */
                MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {
                    Log.d(TAG, "Record button down")

                    buttonLock.withLock {
                        Log.d(TAG, "Recording starting")
                        segmentStartTime = Calendar.getInstance().time
                        isSigning = true
                    }

                    // Prevent the user from swiping from one word to another while recording
                    // is active.
                    wordPager.isUserInputEnabled = false

                    // Add a tint to the record button as feedback.
                    recordButton.backgroundTintList = ColorStateList.valueOf(0xFF7C0000.toInt())
                    recordButton.setColorFilter(0x80ffffff.toInt(), PorterDuff.Mode.MULTIPLY)
                }

                /**
                 * User releases the record button: mark the end of the recording
                 */
                MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
                    Log.d(TAG, "Record button up")

                    buttonLock.withLock {
                        /**
                         * Add this recording to the list of recordings for the currently-selected
                         * word.
                         */
                        // If there isn't a list of recordings for this word, create one first.
                        if (!sessionClipData.containsKey(currentWord)) {
                            sessionClipData[currentWord] = ArrayList()
                        }

                        // If there were previous recordings for this word, then we should mark them
                        // as invalid. Currently, we only assume the last recording for any given
                        // word is valid.
                        val recordingList = sessionClipData[currentWord]!!
                        if (recordingList.size > 0) {
                            recordingList[recordingList.size - 1].isValid = false
                        }

                        // Add the current clip's details to the recording list.
                        recordingList.add(ClipDetails(
                            outputFile, sessionStartTime, segmentStartTime,
                            Calendar.getInstance().time, true
                        ))

                        // Give the user some haptic feedback to confirm the recording is done.
                        recordButton.performHapticFeedback(HapticFeedbackConstants.REJECT)

                        runOnUiThread {
                            // Move to the next word and allow the user to swipe back and forth
                            // again now that the record button has been released.
                            wordPager.setCurrentItem(wordPager.currentItem + 1, false)
                            wordPager.isUserInputEnabled = true
                            recordButton.backgroundTintList = ColorStateList.valueOf(0xFFF80000.toInt())
                            recordButton.clearColorFilter()
                        }

                        isSigning = false

                        // If the user ran out of time while recording a word, then
                        // endSessionOnRecordButtonRelease will be true. Once they release the
                        // button, we should immediately take them to the end of the recording
                        // session.
                        if (endSessionOnRecordButtonRelease) {
                            runOnUiThread {
                                wordPager.currentItem = wordList.size + 1
                            }
                        }
                    }
                }
            }

            return@setOnTouchListener true
        }
    }

    /**
     * Adds a callback to the camera view, which is used primarily to assign a value to
     * [previewSurface] once Android has finished creating the Surface for us. We need this
     * because we cannot initialize a Surface object directly but we still need to be able to
     * pass a Surface object around to the UI, which uses the contents of the Surface (buffer) to
     * render the camera preview.
     */
    private fun setupCameraCallback() {
        cameraView.holder.addCallback(object: SurfaceHolder.Callback {
            /**
             * Called when the OS has finished creating a surface for us.
             */
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG,"Initializing surface!")
                previewSurface = holder.surface
                initializeCamera()
            }

            /**
             * Called if the surface had to be reassigned. In practical usage thus far, we have
             * not run into any issues here by not reassigning [previewSurface] when this callback
             * is triggered.
             */
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                Log.d(TAG, "New format, width, height: $format, $w, $h")
                Log.d(TAG, "Camera preview surface changed!")
            }

            /**
             * Called when the surface is destroyed. Typically this will occur when the activity
             * closes.
             */
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Camera preview surface destroyed!")
                previewSurface = null
            }
        })
    }


    /**
     * Opens up the requested Camera for a recording session.
     *
     * @suppress linter for "MissingPermission": acceptable here because this function is only
     * ever called from [initializeCamera], which exits before calling this function if camera
     * permission has been denied.
     */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { caller ->
        manager.openCamera(cameraId, object: CameraDevice.StateCallback() {
            /**
             * Once the camera has been successfully opened, resume execution in the calling
             * function.
             */
            override fun onOpened(device: CameraDevice) {
                Log.d(TAG, "openCamera: New camera created with ID $cameraId")
                caller.resume(device)
            }

            /**
             * If the camera is disconnected, end the activity and return to the splash screen.
             */
            override fun onDisconnected(device: CameraDevice) {
                Log.e(TAG, "openCamera: Camera $cameraId has been disconnected")
                this@RecordingActivity.apply {
                    setResult(RESULT_CAMERA_DIED)
                    finish()
                }
            }

            /**
             * If there's an error while opening the camera, pass that exception to the
             * calling function.
             */
            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (caller.isActive) {
                    caller.resumeWithException(exc)
                }
            }
        },

        // Pass this code onto the camera handler thread
        handler)
    }

    /**
     * Create a CameraCaptureSession. This is required by the camera API
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->
        /**
         * Set up the camera capture session with the success / failure handlers defined below
         */
        device.createCaptureSession(targets, object: CameraCaptureSession.StateCallback() {
            // Capture session successfully configured - resume execution
            override fun onConfigured(session: CameraCaptureSession) {
                cont.resume(session)
            }

            // Capture session config failed - throw exception
            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }


    /**
     * Starts the camera recording once we have device and capture session information within
     * [initializeCamera].
     */
    private fun startRecording() {
        // Lock screen orientation
        this@RecordingActivity.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LOCKED

        /**
         * Create a request to record at 30fps.
         */
        val cameraRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            // Add the previewSurface buffer as a destination for the camera feed if it exists
            previewSurface?.let {
                addTarget(it)
            }

            // Add the recording buffer as a destination for the camera feed
            addTarget(recordingSurface)

            // Lock FPS at 30
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(30, 30)
            )

            // Disable video stabilization. This ensures that we don't get a cropped frame
            // due to software stabilization.
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }.build()

        session.setRepeatingRequest(cameraRequest, null, cameraHandler)

        recorder.start()

        isRecording = true
        sessionStartTime = Calendar.getInstance().time

        // Make sure the Record button is visible.
        recordButton.animate().apply {
            alpha(1.0f)
            duration = 250
        }.start()

        recordButton.visibility = View.VISIBLE
        recordButtonEnabled = true
        recordButton.isClickable = true
        recordButton.isFocusable = true

        // Set up the countdown timer.
        countdownText = findViewById(R.id.timerLabel)
        countdownTimer = object : CountDownTimer(COUNTDOWN_DURATION, 1000) {
            // Update the timer text every second.
            override fun onTick(p0: Long) {
                val rawSeconds = (p0 / 1000).toInt() + 1
                val minutes = padZeroes(rawSeconds / 60, 2)
                val seconds = padZeroes(rawSeconds % 60, 2)
                countdownText.text = "$minutes:$seconds"
            }

            // When the timer expires, move to the summary page (or have the app move there as soon
            // as the user finishes the recording they're currently working on).
            override fun onFinish() {
                if (isSigning) {
                    endSessionOnRecordButtonRelease = true
                } else {
                    wordPager.currentItem = wordList.size + 1
                }
            }
        } // CountDownTimer

        countdownTimer.start()

        // Set the recording light to red
        val filterMatrix = ColorMatrix()
        filterMatrix.setSaturation(1.0f)
        val filter = ColorMatrixColorFilter(filterMatrix)
        recordingLightView.colorFilter = filter
    }

    /**
     * Handler code for when the activity restarts. Right now, we return to the splash screen if the
     * user exits mid-session, as the app is continuously recording throughout this activity's
     * lifespan.
     */
    override fun onRestart() {
        try {
            super.onRestart()
            // Shut down app when no longer recording
            setResult(RESULT_RECORDING_DIED)
            finish()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error in RecordingActivity.onRestart()", exc)
        }
    }

    /**
     * Handles stopping the recording session.
     */
    override fun onStop() {
        try {
            /**
             * We only need to stop all of the camera-related functionality if the user is not
             * on the summary screen when this function is called. If they are on the summary page,
             * the camera has already been closed.
             */
            if (wordPager.currentItem <= wordList.size) {
                recorder.stop()
                session.stopRepeating()
                session.close()
                recorder.release()
                camera.close()
                cameraThread.quitSafely()
                recordingSurface.release()
                countdownTimer.cancel()
                cameraHandler.removeCallbacksAndMessages(null)
                Log.d(TAG, "onStop: Stop and release all recording data")
            }

            /**
             * This is remnant code from when we were attempting to find and fix a memory leak
             * that occurred if the user did too many recording sessions in one sitting. It is
             * unsure whether this helped; however, we will leave it as-is for now.
             */
            wordPager.adapter = null
            super.onStop()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error in RecordingActivity.onStop()", exc)
        }
    }

    /**
     * Handle the activity being destroyed.
     */
    override fun onDestroy() {
        try {
            super.onDestroy()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error in RecordingActivity.onDestroy()", exc)
        }
    }

    /**
     * Entry point for the RecordingActivity.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up activity's layout
        val binding = ActivityRecordBinding.inflate(this.layoutInflater)
        val view = binding.root
        setContentView(view)

        // Set up view pager
        this.wordPager = findViewById(R.id.wordPager)

        // Fetch word data, user id, etc. from the splash screen activity which
        // initiated this activity
        val bundle = this.intent.extras ?: Bundle()

        completeWordList = ArrayList(listOf(*resources.getStringArray(R.array.all)))
        wordList = if (bundle.containsKey("WORDS")) {
            ArrayList(bundle.getStringArrayList("WORDS")!!)
        } else {
            randomChoice(completeWordList, WORDS_PER_SESSION)
        }

        emailConfirmationEnabled = bundle.getBoolean("SEND_CONFIRMATION_EMAIL")

        currentWord = wordList[0]

        /**
         * See the documentation for [recordingCategory] for more info.
         */
        this.recordingCategory = bundle.getString("CATEGORY") ?: "randombatch"
        this.userUID = bundle.getString("UID") ?: "anonymous"

        // Set up file name for this recording
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss.SSS", Locale.US)
        filename = "${userUID}-${recordingCategory}-${sdf.format(Date())}"
        metadataFilename = filename

        // Set title bar text
        title = "1 of ${wordList.size}"

        // Enable record button
        recordButton = findViewById(R.id.recordButton)
        recordButton.isHapticFeedbackEnabled = true
        recordButton.visibility = View.INVISIBLE

        wordPager.adapter = WordPagerAdapter(this, wordList, sessionClipData)

        if (SHOW_INSTRUCTION_VIDEO) {
            val videoView: VideoView = findViewById(R.id.demoVideo)
            this.tutorialView = VideoTutorialController(this, videoView, currentWord)
        }

        // Set up swipe handler for the word selector UI
        wordPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {

            /**
             * Page changed
             */
            override fun onPageSelected(position: Int) {
                currentPage = wordPager.currentItem
                super.onPageSelected(currentPage)

                /**
                 * Indices 0 to `wordList.size - 1` (inclusive): words within the list
                 */
                if (currentPage < wordList.size) {
                    // Animate the record button back in, if necessary
                    runOnUiThread {
                        this@RecordingActivity.currentWord = wordList[currentPage]
                        this@RecordingActivity.tutorialView?.setWord(currentWord)

                        title = "${currentPage + 1} of ${wordList.size}"

                        if (!recordButtonEnabled) {
                            recordButton.isClickable = true
                            recordButton.isFocusable = true
                            recordButtonEnabled = true

                            recordButton.animate().apply {
                                alpha(1.0f)
                                duration = 250
                            }.start()
                        }
                    }
                }

                /**
                 * Index `wordList.size`: "Save or continue" page - gives the user a
                 * chance to continue recording or, if they're done, swipe to finish
                 * recording.
                 */
                else if (currentPage == wordList.size) {
                    runOnUiThread {
                        title = "Save or continue?"

                        this@RecordingActivity.tutorialView?.setVisibility(View.INVISIBLE)

                        // Disable and hide the record button on this page
                        recordButton.isClickable = false
                        recordButton.isFocusable = false
                        recordButtonEnabled = false

                        recordButton.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()
                    }
                }

                /**
                 * Index `wordList.size + 1`: Recording summary page
                 */
                else {
                    // Hide record button and move the slider to the front (so users can't
                    // accidentally press record)
                    Log.d(
                        TAG, "Recording stopped. Check " +
                                this@RecordingActivity.getExternalFilesDir(null)?.absolutePath
                    )

                    // For the duration of the recording being stopped, disable UI interaction.
                    runOnUiThread {
                        this@RecordingActivity.tutorialView?.setVisibility(View.INVISIBLE)

                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        )
                    }

                    // Close out the recording session.
                    if (isRecording) {
                        recorder.stop()
                        session.stopRepeating()
                        session.close()
                        recorder.release()
                        camera.close()
                        cameraThread.quitSafely()
                        recordingSurface.release()
                        countdownTimer.cancel()
                        cameraHandler.removeCallbacksAndMessages(null)
                    }

                    isRecording = false

                    // Re-enable UI interaction and set up the UI elements.
                    runOnUiThread {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

                        wordPager.isUserInputEnabled = false

                        // Hide the countdown timer
                        countdownText.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()

                        countdownText.visibility = View.GONE

                        // Hide the record button
                        recordButton.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()

                        recordButton.visibility = View.GONE

                        recordButton.isClickable = false
                        recordButton.isFocusable = false
                        recordButtonEnabled = false

                        title = "Session summary"

                        // Turn off the recording light
                        val filterMatrix = ColorMatrix()
                        filterMatrix.setSaturation(0.0f)
                        val filter = ColorMatrixColorFilter(filterMatrix)
                        recordingLightView.colorFilter = filter
                    } // runOnUiThread
                } // else [i.e., currentPage == wordList.size + 1]
            } // onPageSelected(Int)
        }) // wordPager.registerOnPageChangeCallback()

        // Set up the camera preview's size
        cameraView = findViewById(R.id.cameraPreview)
        cameraView.holder.setSizeFromLayout()

        val aspectRatioConstraint = findViewById<ConstraintLayout>(R.id.aspectRatioConstraint)
        val layoutParams = aspectRatioConstraint.layoutParams
        layoutParams.height = layoutParams.width * 4 / 3
        aspectRatioConstraint.layoutParams = layoutParams

        recordingLightView = findViewById(R.id.videoRecordingLight3)

        // Enable the recording light
        val filterMatrix = ColorMatrix()
        filterMatrix.setSaturation(0.0f)
        val filter = ColorMatrixColorFilter(filterMatrix)
        recordingLightView.colorFilter = filter
    }

    /**
     * Handle activity resumption (typically from multitasking)
     */
    override fun onResume() {
        super.onResume()

        // Create camera thread
        cameraThread = generateCameraThread()
        cameraHandler = Handler(cameraThread.looper)

        recordingSurface = createRecordingSurface()

        /**
         * If we already finished the recording activity, no need to restart the camera thread
         */
        if (wordPager.currentItem >= wordList.size) {
            return
        } else if (!cameraInitialized) {
            setupCameraCallback()
            cameraInitialized = true
        }
    }

    /**
     * Used in RecordingListAdapter since the fields here are not externally accessible.
     */
    fun deleteMostRecentRecording(word: String) {
        // Since only the last recording is shown, the last recording should be deleted
        if (sessionClipData.containsKey(word)) {
            sessionClipData[word]?.get(sessionClipData[word]!!.size - 1)?.isValid = false
        }
    }

    /**
     * Handle the file saving and confirmation behavior when the recording is done.
     */
    fun concludeRecordingSession() {
        // Update the recording count for each recorded word
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        with (prefs.edit()) {
            for (entry in sessionClipData) {
                val key = "RECORDING_COUNT_${entry.key}"
                val recordingCount = prefs.getInt(key, 0)
                if (entry.value.isNotEmpty() and entry.value.last().isValid) {
                    putInt(key, recordingCount + 1)
                }
            }
            commit()
        }

        // Create the complementary image file which contains timestamp data.
        createTimestampFileAllInOne(sessionClipData)

        /**
         * Save the video file to the user's downloads folder.
         */
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.VideoColumns.DISPLAY_NAME, outputFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.SIZE, outputFile.length())
        }

        val uri = this.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: run {
            Log.e(TAG, "Unable to open a URI to save the recording!")
            return
        }

        contentResolver.openOutputStream(uri).use { outputStream ->
            val brr = ByteArray(1024)
            var len: Int
            val bufferedInputStream = BufferedInputStream(
                FileInputStream(outputFile.absoluteFile)
            )

            // warning: cursed kotlin moment below
            while ((bufferedInputStream.read(brr, 0, brr.size).also { len = it }) != -1) {
                outputStream?.write(brr, 0, len)
            }

            outputStream?.flush()
            bufferedInputStream.close()
        }

        Log.d(TAG, "Email confirmations enabled? = $emailConfirmationEnabled")

        /**
         * Send a confirmation email to our preferred inbox and then close the app.
         */
        if (emailConfirmationEnabled) {
            sendConfirmationEmail()
        }

        setResult(RESULT_NO_ERROR)
        finish()
    } // concludeRecordingSession()


    /**
     * Handles the creation and sending of a confirmation email, allowing us to track
     * the user's progress.
     */
    private fun sendConfirmationEmail() {
        val userId = this.userUID
        val wordList = ArrayList<Pair<String, Int>>()
        val recordings = ArrayList<Pair<String, ClipDetails>>()
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        /**
         * Generate summary data for each of the currently recorded words (how many times has this
         * user signed the given word in total?)
         */
        for (entry in sessionClipData) {
            if (entry.value.isNotEmpty()) {
                val prefsKey = "RECORDING_COUNT_${entry.key}"
                val recordingCount = prefs.getInt(prefsKey, 0)

                wordList.add(Pair(entry.key, recordingCount))
                recordings.addAll(entry.value.map { Pair(entry.key, it) })
            }
        }

        // Sort clips chronologically (by starting time)
        recordings.sortBy { it.second.signStart }

        // Include file integrity hash
        val fileDigest = outputFile.md5()

        val subject = "Recording confirmation for $userId"
        val wordListWithCounts = wordList.joinToString(", ", "", "", -1,
        "") {
            "${it.first} (${it.second} / ${RECORDINGS_PER_WORD})"
        }

        var body = "The user '$userId' recorded the following ${wordList.size} word(s) to the " +
                "file $filename.mp4 (MD5 = $fileDigest): $wordListWithCounts\n\n"

        var totalWordCount = 0
        for (word in completeWordList) {
            totalWordCount += prefs.getInt("RECORDING_COUNT_$word", 0)
        }

        body += "Overall progress: $totalWordCount / " +
                "${RECORDINGS_PER_WORD * completeWordList.size}\n\n"

        fun formatTime(millis: Long): String {
            val minutes = millis / 60_000
            val seconds = ((millis % 60_000) / 1000).toInt()
            val millisRemaining = (millis % 1_000).toInt()

            return "$minutes:${padZeroes(seconds, 2)}.${padZeroes(millisRemaining, 3)}"
        }

        // Include timestamps for individual clips
        for (entry in recordings) {
            body += "- '${entry.first}'"
            if (!entry.second.isValid) {
                body += " (discarded)"
            }

            val clipData = entry.second

            val startMillis = clipData.signStart.time - clipData.videoStart.time
            val endMillis = clipData.signEnd.time - clipData.videoStart.time

            body += ": ${formatTime(startMillis)} - ${formatTime(endMillis)}\n"
        }

        // Include the app version string in the confirmation email for posterity
        body += "\n\nApp version $APP_VERSION\n\n"

        // Include raw EXIF string in the email
        body += "EXIF data:\n ${generateClipExif(sessionClipData)}"

        val emailTask = Thread {
            kotlin.run {
                Log.d(TAG, "Running thread to send email...")

                /**
                 * Send the email from `sender` (authorized by `password`) to the emails in
                 * `recipients`. The reason we don't use `R.string.confirmation_email_sender` is
                 * that the file containing these credentials is not published to the Internet,
                 * so people downloading this repository would face compilation errors unless
                 * they create this file for themselves (which is detailed in the README).
                 *
                 * To let people get up and running quickly, we just check for the existence of
                 * these string resources manually instead of making people create an app password
                 * in Gmail for what is really just an optional component of the app.
                 */
                val senderStringId = resources.getIdentifier("confirmation_email_sender",
                    "string", packageName)
                val passwordStringId = resources.getIdentifier("confirmation_email_password",
                    "string", packageName)
                val recipientArrayId = resources.getIdentifier("confirmation_email_recipients",
                    "array", packageName)

                val sender = resources.getString(senderStringId)
                val password = resources.getString(passwordStringId)
                val recipients = ArrayList(listOf(*resources.getStringArray(recipientArrayId)))

                sendEmail(sender, recipients, subject, body, password)
            }
        } // emailTask (Thread)

        emailTask.start()
    } // sendConfirmationEmail()


    /**
     * Create an image file with all the timestamp data saved as EXIF metadata.
     */
    private fun createTimestampFileAllInOne(sampleVideos: HashMap<String, ArrayList<ClipDetails>>) {
        // resort sampleVideos around files
        if (sampleVideos.size > 0) {
            // Set filename, type, and path
            val thumbnailValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$metadataFilename-timestamps.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }

            // Save file
            val uri = contentResolver.insert(
                MediaStore.Images.Media.getContentUri("external"),
                thumbnailValues
            ) ?: run {
                Log.e(TAG, "Unable to generate a URI where the thumbnail containing EXIF " +
                        "data for ${outputFile.name} can be saved")
                return
            }

            // Generate a thumbnail for the EXIF data to be stored with.
            val outputThumbnail = contentResolver.openOutputStream(uri) ?: run {
                Log.e(TAG, "While saving EXIF data: Unable to open output stream at URI $uri")
                return
            }

            try {
                ThumbnailUtils.createVideoThumbnail(
                    outputFile,
                    Size(640, 480),
                    null
                ).apply {
                    compress(Bitmap.CompressFormat.JPEG, 90, outputThumbnail)
                    recycle()
                }
            } catch (exc: IOException) {
                Log.e(
                    TAG, "Unable to save thumbnail containing EXIF data for " +
                            "${outputFile.name}: ${exc.message}"
                )
                return
            } finally {
                outputThumbnail.flush()
                outputThumbnail.close()
            }

            // Add the EXIF data to the thumbnail image.
            val imageFd = contentResolver.openFileDescriptor(uri, "rw")
            val exif = imageFd?.let { ExifInterface(it.fileDescriptor) }

            exif?.apply {
                setAttribute(TAG_IMAGE_DESCRIPTION, generateClipExif(sessionClipData))
                saveAttributes()
            }

            imageFd?.close()
        }

        // Show a confirmation to the user that the video was saved
        val text = "Video successfully saved"
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.show()
    } // createTimestampFileAllInOne()

} // RecordingActivity

/**
 * A simple class to manage the video player at the top of the screen. See
 * [RecordingActivity.tutorialView].
 */
class VideoTutorialController(
    private val activity: RecordingActivity,
    private val videoView: VideoView,
    initialWord: String
): SurfaceHolder.Callback, MediaPlayer.OnPreparedListener {

    companion object {
        private val TAG = VideoTutorialController::class.java.simpleName
    }

    /**
     * The currently selected word.
     */
    private var word: String = initialWord

    /**
     * The video file being played back.
     */
    private var dataSource: AssetFileDescriptor? = null

    /**
     * The video playback controller.
     */
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Initializes the controller.
     */
    init {
        Log.d(TAG, "Constructor called")

        setWord(this.word)

        // When we click on the video, it should expand to a larger preview.
        videoView.setOnClickListener {
            val bundle = Bundle()
            bundle.putString("word", this.word)
            bundle.putBoolean("landscape", true)

            val previewFragment = VideoPreviewFragment(R.layout.recording_preview)
            previewFragment.arguments = bundle

            previewFragment.show(activity.supportFragmentManager, "videoPreview")
        }
    }

    /**
     * Allows us to control the visibility of the video player directly from [RecordingActivity].
     */
    fun setVisibility(visibility: Int) {
        videoView.visibility = visibility
    }

    /**
     * Sets the video player to play the given word.
     */
    fun setWord(newWord: String) {
        word = newWord
        try {
            dataSource = activity.applicationContext.resources?.
            assets?.openFd("videos/$newWord.mp4")
        } catch (exc: IOException) {
            Log.e(TAG, "The file assets/videos/$newWord.mp4 could not be found. If this is " +
                    "unexpected, please make sure the file name for the video you want to play " +
                    "is exactly equal to '$newWord.mp4' under the assets/videos folder in the APK.")
            videoView.visibility = View.INVISIBLE
            return
        }

        dataSource ?: run {
            videoView.visibility = View.INVISIBLE
            return
        }

        videoView.visibility = View.VISIBLE

        // If the media player already exists, just change its data source and start playback again.
        mediaPlayer?.let {
            it.stop()
            it.reset()
            it.setDataSource(dataSource!!)
            it.setOnPreparedListener(this@VideoTutorialController)
            it.prepareAsync()
        }

        // If the media player has not been initialized, set up the video player.
        // We will set up the data source from the surfaceCreated() function
        ?: run {
            videoView.holder.addCallback(this)
        }
    }


    /**
     * When the videoView's pixel buffer is set up, this function is called. The MediaPlayer object
     * will then target the videoView's canvas.
     */
    override fun surfaceCreated(holder: SurfaceHolder) {
        this.mediaPlayer = MediaPlayer().apply {
            dataSource?.let {
                setDataSource(dataSource!!)
                setSurface(holder.surface)
                setOnPreparedListener(this@VideoTutorialController)
                prepareAsync()
            }
        }
    }

    /**
     * We don't use surfaceChanged right now since the same surface is used for the entire
     * duration of the activity.
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // TODO("Not yet implemented")
    }

    /**
     * When the recording activity is finished, we can free the memory used by the media player.
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        this.mediaPlayer?.let {
            it.stop()
            it.release()
        }
    }

    /**
     * This function is called since we used `setOnPreparedListener(this@VideoTutorialController)`.
     * When the video file is ready, this function makes it so that the video loops and then starts
     * playing back.
     */
    override fun onPrepared(mp: MediaPlayer?) {
        mp?.let {
            it.isLooping = true
            it.start()
        }
    }

}
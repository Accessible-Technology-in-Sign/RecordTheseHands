![app-icon-updated](https://github.com/Accessible-Technology-in-Sign/RecordTheseHands/assets/1849924/80c0241b-4d86-4710-8012-1c17032e6538)

# Record These Hands!
© 2021&ndash;2023 Georgia Institute of Technology / 
Center for Accessible Technology in Sign (GT-CATS)<br>
Licensed under the MIT license.

Principal authors:

* Sahir Shahryar (sahirshahryar@gmail.com)
* Matthew So (matthew.so@gatech.edu)

Instruction videos provided by the [Deaf Professional Arts Network (DPAN)](https://dpan.tv).

## About
*Record These Hands!* is an Android app that researchers can use to
collect sign language training data from Deaf and hard-of-hearing (DHH)
volunteers. An earlier version of the app, titled ASLRecorder, was used to
collect data to train machine learning models for 
[PopSignAI](https://www.popsign.org/), a bubble shooter game that hearing 
parents of Deaf children can use to learn the basics of American Sign
Language.

The data collected using this app was also used for a 
[Kaggle machine learning competition](https://www.kaggle.com/competitions/asl-signs)
sponsored by Google, with the intent of using the best-performing model directly
within PopSignAI. The Kaggle competition and PopSignAI received 
[a brief mention](https://www.youtube.com/watch?v=r8T0SnwHRNI&t=3965s) at
the Google I/O &rsquo;23 Developer Keynote. To learn more about the dataset 
and how we went about collecting it, please see our paper on the subject,
*PopSign ASL v1.0: An Isolated American Sign Language Dataset Collected 
via Smartphones* (arXiv link available shortly).

This updated version of ASLRecorder has a few improvements over the original
app, and it has been cleaned up to make it possible for researchers to get a 
running start when collecting their own sign language datasets.  It is named
*Record These Hands!* in honor of a 
[popular song by Sean Forbes](https://www.youtube.com/watch?v=7lQx1f5lEFo), who
is a cofounder of DPAN. He and several people working at DPAN collaborated
extensively with students at the Georgia Institute of Technology and the
Rochester Institute of Technology to make PopSignAI possible.

## How it works
The app has been designed around the model of lending volunteers an Android
smartphone which is signed into a temporary Google account. This is because we
use Google Photos&rsquo; backup feature to handle the uploading of recorded videos
to the Internet. Doing so allowed us to skip the process of maintaining our own
servers and writing complex handler code for uploads, and it also allows the user
the convenience of uploading photos when the app is closed. However, since the
entire photo library gets backed up to an account that should be accessible to 
researchers, **this app is not intended for volunteers to install on their 
personal devices**. 

In the process of  collecting the dataset for PopSign, we distributed 
Google Pixel 4as with pre-provisioned accounts created specifically for data
capture.

* Users enter the app and see a simple home screen showing how many recordings
they've done so far, as well as the signs they'll record in the next session.
The user's ID, which is included in the filename for all videos they record,
is based on the phone's signed-in Google account, or a random number is used if
the user denies permission to access contacts.
* When the user presses "Start", the camera opens and begins recording. Users
see a prompt at the top of the screen telling them which sign they should
record, as well as an instructional video (as some signs can have several 
variations that mean the same thing, or the English word presented to the 
user can have several different meanings). The user can tap the video to
expand it.
* Users hold down the "Record" button, sign the word presented to them, and
then release the "Record" button. The app swipes to the next word, and they
can continue. Users can page freely back and forth between words. Rather
than making a video file for each word signed, the app records continuously
and we instead keep track of the timestamps when the user pressed and released
the Record button. This is done so that we can accommodate users who press the 
Record button too late (or release it too early) by simply adjusting the 
timestamps. 
* For each video, we generate a corresponding thumbnail image. The timestamps
are encoded into the EXIF metadata of this thumbnail, so that we can upload
the timestamps to Google Photos too (via the image). Code for generating 
clips from a recording and its timestamp info can be found in the `scripts` 
folder.
* We keep track of how many times each user has recorded each sign. You can
customize how many words are chosen per recording session, as well as how
many recordings of each word the user should do (across all of their sessions),
by changing the `WORDS_PER_SESSION` and `RECORDINGS_PER_WORD` values in
`Constants.kt`. 
* The app chooses which words the user will sign next at random from the set
of least frequently-recorded words. This ensures that, by the time they finish,
the user will have recorded at least `RECORDINGS_PER_WORD` clips for every
word.
* The app can be configured to send confirmation emails to an observer inbox.
This allows the person who recruited the volunteers to keep track of each
volunteer's progress. See the **Enabling confirmation emails** section below
for more info. **No APK available for download in the Releases section of
this repository has this functionality enabled**, as the credentials needed 
to enable this feature must be present at compile time.

## Defining your own signs to record
You can change which words volunteers need to sign by modifying the string array
named `"all"` inside `app/src/main/res/values/strings.xml`. If you want the
app to display corresponding tutorial videos, you should replace the video files
within `app/src/main/assets/videos/`. Each video file should be named exactly
the same as the word inside `strings.xml` (plus `.mp4`); for example, 
the word `"french fries"` should have a video file named `french fries.mp4`.

## Enabling confirmation e-mails
To enable the sending of confirmation emails when a user completes a recording
session, you should add the following resources. Although these values can be
placed in any string resource file, we recommend creating a new file, such as
`app/src/main/res/values/credentials.xml`, and adding that file to your 
`.gitignore` in case you want to open-source your code. (Don't ask why we 
know that! 😅)

```xml
<resources>
    <string name="confirmation_email_sender">example@gmail.com</string>
    <string name="confirmation_email_password">abcdefghijklmnop</string>
    <string-array name="confirmation_email_recipients">
        <item>watcher@gmail.com</item>
    </string-array>
</resources>
```

The `confirmation_email_sender` should be a Gmail account with two-factor
authentication enabled, and `confirmation_email_password` should be an 
[app password](https://support.google.com/accounts/answer/185833?hl=en)
for that account. You can add as many emails as you want to the 
`confirmation_email_recipients` array, though you should note that
adding too many emails to this list can cause you to hit
[Gmail's limits](https://support.google.com/mail/answer/22839?hl=en).

If you want to use another email service or your own SMTP server, you can
tweak the `sendEmail()` function in `Utilities.kt`.

## Feedback
If you have some feedback or would like to contribute to the app, please feel free to reach out to us, or to submit issues or pull requests! We'll do our best to respond promptly.

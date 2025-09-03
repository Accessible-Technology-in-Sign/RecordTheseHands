![app-icon-updated](https://github.com/Accessible-Technology-in-Sign/RecordTheseHands/assets/1849924/80c0241b-4d86-4710-8012-1c17032e6538)

# Record These Hands!

© 2021–2024 Georgia Institute of Technology / Center for Accessible Technology
in Sign (GT-CATS) / Google LLC<br> Licensed under the MIT license.

Principal authors:

- Sahir Shahryar (sahirshahryar@gmail.com)
- Manfred Georg (mgeorg@google.com)
- Matthew So (matthew.so@gatech.edu)

Instruction videos provided by the
[Deaf Professional Arts Network (DPAN)](https://dpan.tv).

## About

*Record These Hands!* is an Android app that researchers can use to collect sign
language training data from Deaf and hard-of-hearing (DHH) volunteers. An
earlier version of the app, titled ASLRecorder, was used to collect data to
train machine learning models for [PopSignAI](https://www.popsign.org/), a
bubble shooter game that hearing parents of Deaf children can use to learn the
basics of American Sign Language.

The data collected using this app was also used for a
[Kaggle machine learning competition](https://www.kaggle.com/competitions/asl-signs)
sponsored by Google, with the intent of using the best-performing model directly
within PopSignAI. The Kaggle competition and PopSignAI received
[a brief mention](https://www.youtube.com/watch?v=r8T0SnwHRNI&t=3965s) at the
Google I/O ’23 Developer Keynote. To learn more about the dataset and how we
went about collecting it, please see our paper on the subject, *PopSign ASL
v1.0: An Isolated American Sign Language Dataset Collected via Smartphones*
(arXiv link available shortly).

This updated version of ASLRecorder has a few improvements over the original
app, and it has been cleaned up to make it possible for researchers to get a
running start when collecting their own sign language datasets. It is named
*Record These Hands!* in honor of a
[popular song by Sean Forbes](https://www.youtube.com/watch?v=7lQx1f5lEFo), who
is a cofounder of DPAN. He and several people working at DPAN collaborated
extensively with students at the Georgia Institute of Technology and the
Rochester Institute of Technology to make PopSignAI possible.

## How it works

The app communicates with a Google Cloud Platform server (code is in the server
directory). This server must be setup before the app is able to function. The
app routinely queries the server for directives which allow for the
configuration and reconfiguration of the device without physically needing to
attend to it. The videos the participant records are automatically uploaded to
the server (and deleted from the device once upload is verified). No internet
connection is required during data collection. Metadata and video data will be
stored on the device until it can be uploaded to the server once an internet
connection is established.

The intended use case is to have researchers provide devices to participants
that are preloaded and configured for a data collection. Ideally, the
participant would connect the device to their wifi so that videos and metadata
are uploaded while the device is out in the field. This data can be monitored
from the server website. Once the participant has completed their data
collection they would return the device and the researchers can verify that all
data has been uploaded.

Please be aware that data is stored in the local filesystem and uninstalling the
app will completely erase all local data.

The data collection process proceeds as follows:

- Users enter the app and see a simple home screen showing their progress.
  - Generally the app would be configured to be in "tutorial" mode. Which has a
    separate set of prompts which can be used for training purposes and will be
    uploaded and stored to a separate part of the database. This data would
    generally be ignored, as it generally includes conversation with the
    researcher and any other training setup and mistakes which might happen.
- When the user presses "Start", the camera opens and begins recording. Users
  see a prompt at the top of the screen letting them know what to sign.
- Note that the app records continously and simply keeps track of the timestamp
  of all events such as button presses. If the participant wishes to end the
  session they can do that at any time by closing the app (at which point the
  partial session video will be saved on device).
- On each prompt page, the participant presses "START" then signs. If a mistake
  is made, then they can press "MISTAKE RESTART" and sign again.
- When they are satisfied with what they have signed, they swipe their finger
  from right to left to get to the next prompt.
- The app can be configured to send confirmation emails to an observer inbox.
  This allows the researcher to keep track of each participant's progress. See
  the **Enabling confirmation emails** section below for more info.

## Defining your own prompts

The scripts to generate prompt files and upload them to the server are in the
server/collector directory. Image and Videos can be provided in the prompts.

## Enabling confirmation e-mails

To enable the sending of confirmation emails when a user completes a recording
session, you should add the following resources. Although these values can be
placed in any string resource file, we recommend creating the file
`app/src/main/res/values/credentials.xml`, and ensuring that file is in your
`.gitignore` so it will not be uploaded to the repository.

```xml
<resources>
    <string name="backend_server">https://localhost:8050</string>
    <string name="confirmation_email_sender">example@gmail.com</string>
    <string name="confirmation_email_password">abcdefghijklmnop</string>
    <string-array name="confirmation_email_recipients">
        <item>watcher@gmail.com</item>
    </string-array>
</resources>
```

The `confirmation_email_sender` should be a Gmail account with two-factor
authentication enabled, and `confirmation_email_password` should be an
[app password](https://support.google.com/accounts/answer/185833?hl=en) for that
account. You can add as many emails as you want to the
`confirmation_email_recipients` array, though you should note that adding too
many emails to this list can cause you to hit
[Gmail's limits](https://support.google.com/mail/answer/22839?hl=en).

If you want to use another email service or your own SMTP server, you can tweak
the `sendEmail()` function in `Utilities.kt`.

## Using RecordTheseHands with tablets

As of v1.3, the app supports being used on tablets in landscape mode. As of
right now, the app is configured to use tablet mode on any display larger than 7
inches in size, as determined by the built-in methods for screen resolution and
pixel density in the Android SDK.

The video orientation has been determined experimentally for the Google Pixel
Tablet (2023) —
[shown here](https://www.theverge.com/23765921/google-pixel-tablet-review) —
which is designed to be used in one specific orientation (USB-C charging port to
the left) while attached to the included stand. Other tablets may run into
issues, as the app has not been set up to handle any other orientations.

## Feedback

If you have some feedback or would like to contribute to the app, please feel
free to reach out to us, or to submit issues or pull requests! We'll do our best
to respond promptly.

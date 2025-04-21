# DPAN Server Usage

## Prompt Creation

1. Activate the local environment by running either:
```
source setup_local_env.sh
```
or
```
source {APP_ENV_DIR}/bin/activate
```
where APP_ENV_DIR is where you installed the virtual environment.

1. Copy any images and video files to the GCP bucket if necessary:
```
gsutil cp reference_video.mp4 gs://bucket-name/resource/path/to/file
```
It is crucial that the reference images and files are under `/resource` as the RecordTheseHands app will not download any files unless it is under this directory. Otherwise the prompt will fail to download and no data will be collected for that prompt.

1. Create the prompt text file in the format that follows:

```
TEXT CAPTION
IMAGE PATH/TO/IMAGE CAPTION
VIDEO PATH/TO/VIDEO CAPTION
```
The first word `TEXT/IMAGE/VIDEO` is the type of prompt you are creating. The PATH is the path to the file within the GCP bucket, not on the local machine. The CAPTION is shown to the user as text at the top of the screen.

1. Convert the prompt text file to a *.json file used by RecordTheseHands:
```
python phrases_to_prompts.py PREFIX prompt.txt
```
The outputted file will be in format `prompts-PREFIX-TIMESTAMP.json`, and the location of the file is included within the output.

1. Upload the prompt file to the DPAN Server:
```
gsutil cp prompt-file.json gs://bucket-name/prompts/path/to/file
```


## Using directives

Directives are how the server admin can issue specific commands to user devices. The server admin runs these commands on their local machine, which is sent to the DPAN Server. The DPAN server then sends these instructions to the user devices. Once the device downloads and completes these directives, a completion message is sent to the server, marking the directive as complete.

1. Activate the local environment by running either:
```
source setup_local_env.sh
```
or
```
source {APP_ENV_DIR}/bin/activate
```
where APP_ENV_DIR is where you installed the virtual environment.

1. Set the environment variables for the Google Cloud Project:

```
export GOOGLE_CLOUD_PROJECT=$(cat config.py | grep 'DEV_PROJECT *=' | sed 's/DEV_PROJECT *= *['\''"]\([^'\''"]*\)['\''"].*/\1/')
```

1. Issue a directive:
```
python create_directive.py USERNAME OPERATION ARGS...
```
Valid operations:
- `noop` – Do nothing
- `printDirectives` – Print out all existing directives for a user (in your console).
- `updateApk` – Tells the device to download the latest APK on the DPAN Server
- `downloadPrompts /path/to/prompt` – Tells the device to download prompts from the path provided (Path is GCP bucket path)
- `downloadTutorialPrompts /path/to/prompt` – Similar but for “tutorial” prompts.
- `deleteFile` – Instructs the device to delete a file from local storage.
- `setTutorialMode` – Enables a user to enter into tutorial mode
- `cancel` – Marks a specific directive (by ID) as canceled.
- `uploadState` - Directs the app to dump its diagnostic state to firestore for inspection / debugging.
- `deleteUser` – Deletes a user and all associated information with that user
- `changeUser` - Update the user to use a new login (with a new login name). This creates the new user with the provided password. 


> For `changeUser`: Since the login token is directly provided to the app, the password is largely irrelevant and should probably be a long randomly generated string. The user will immediately stop reading directives after this and log back in with the new username (running that user's associated directives).

## Producing clips
The following scripts are used to create individual clips for each sign after uploading videos and metadata to a Google Cloud Project. Before running any of the following scripts, use the CLI to set the environment variable `GOOGLE_CLOUD_PROJECT` to the name of your Google Cloud Project.

```
export GOOGLE_CLOUD_PROJECT=name-of-project
```

### Scripts
- `dump_clips.py` - Retrieve all video metadata, including start and end times for sign clips, from Firestore in the Google Cloud Project. This creates the files `metadata_dump.csv` and `metadata_dump.json` with the requisite data.
- `download_videos.py` - Locally download all videos from the buckets in the Google Cloud Project to the `video_dump` directory.
- `clip_video.py` - Produce clips for each video by using the start and end times from `metadata_dump.csv` or `metadata_dump.json`, depending on which is configured. Clips are uploaded to the `clip_dump` directory.
- `local_video_pipeline.py` - Runs the full post-processing pipeline, which consists of the above scripts.
    - Adding the `--clean` argument automatically cleans all existing output directories. 
    - To use configured buffers, use `--buffer <filename>`.

The output directories listed above are also configurable in the `constants.py` file. To use configurable buffers, format the configurable JSON file as shown below:

```
{ 
    "user_name": { 
        "start": 1, 
        "end": 1.5 
    },
    "test006": { 
        "start": 1, 
        "end": 1.5 
    },
    "test007": {
        "start": 0, 
        "end": 0
    }
}
```
### Dependencies
The following are required dependencies for running the post-processing pipeline.
- `ffmpeg`
- `google-cloud-firestore`
- `google-api-core`
- `google-cloud-storage`

To download the Google Cloud dependencies, use `pip install`.
```
pip install google-cloud-firestore
pip install google-api-core
pip install google-cloud-storage
```

The `ffmpeg` dependency is OS specific, so refer to the [relevant documentation](https://ffmpeg.org/download.html) for instructions on downloading for your device.

### Data Formatting
To run these scripts, the following metadata fields in Firestore must be formatted as described below. This is enforced throughout the script using regex patterns.

- `username`
    - The script will only download metadata from users in the Firestore directory (`collector/users/{username}`) that follow the format `dpqNN`, where `NN` denotes two digits.
        - For example, ``dpq01`` is a valid username, while ``dpq001`` is not.
- `clipId`
    - The `clipId` for each clip follows the format `{sessionId}-{index}`.
        - For example, a clip with a session ID of `52c45ea0-s001` and an index of `2` would have a clipId of `52c45ea0-s001-002`.
        - Note that the index is padded with zeros to make it three digits long.
- `filename`
    - The `filename` for the videos corresponding to each clip follows the format `{username}-{sessionId}-{index}-{}.mp4`.
        - The `filename` may also have an optional `tutorial-` prefix for data recorded in tutorial mode.


## Metadata extraction (dump_clips.py)
As stated above, this script downloads metadata from Firestore in a Google Cloud Project to the files `metadata_dump.csv` and `metadata_dump.json`. This metadata corresponds to the videos retrieved from RecordTheseHands, including the following:
- `clipId` - ID of the clip of the sign.
- `filename` - Name of the video file.
- `promptData` - Data about the prompt.
    - `index`
    - `key`
    - `prompt`
    - `type`
- `sessionId` - ID of the recording session.
- `startButtonDownTimestamp` - Exact date and time when the start button was pushed down by the user to record the current clip.
- `startButtonUpTimestamp` - Exact date and time when the start button was released after being pushed.
- `swipeForwardTimestamp` - Exact date and time when the next clip after the current was swiped to.
- `valid` - Boolean value for if the clip is valid.
- `videoStart` - Date and time the video was recorded.

### Code Explanation
Data collection is primarily done using two functions, `get_data` and `get_clip_bounds_in_video`.

#### Getting clip and session data
Taking in a `username` as an argument, `get_data` retrieves all relevant metadata for every clip recorded by the target user.

It first connects to Firestore, then iterates over all of the `clipData` and `sessionData` for the user specified by `username`. In each iteration, if it detects `clipData`, it checks that `clip-id` and `filename` (1) conform to the previously described formatting and (2) are non-null. Otherwise, it will print a message notifying invalid data and skip the current clip. If all checks pass, a dictionary is created to represent the current clip with the metadata values (namely: `userID`, `sessionIndex`, `clipIndex`, `filename`, `promptText`, and `valid`) mapped to appropriate keys. The function then calculates `start_s` and `end_s`, the start and end timestamps for the clip, using `get_clip_bounds_in_video`. These values will be important when splitting up the full videos. The clip dictionary is then appended to a running list of clips.

If `sessionData` is detected, the function simply appends all of the data to a running list of sessions.

After all iterations are finished, the function returns both the `clips` and `sessions` lists.

#### Calcluating bounds
Calculating the timestamp bounds for a clip based on the user's button pushes and swipes. Since `startButtonDownTimestamp`, `startButtonUpTimestamp`, `swipeForwardTimestamp`, and `videoStart` are all formatted as exact dates and times (ISO 8601 format, specifically), we can calculate the difference between them to get exact bounds.

This function first checks which data exists for the given clip. If `videoStart` is null, the function returns because the clip bounds cannot be calculated without a reference as to when the full video started. For the start time of the clip, we check if there is data for when the user pressed down on the start button. If not, we check for a timestamp of the start button releasing from a press. If neither is there, we return since we cannot calculate the start bound. Repeating the same for the clip end time, we check if there is data for the press of the restart button, a forward swipe, or a swipe back, in that order. If none exist, we return.

Once there is a valid time for the `video_start`,`clip_start`, and `clip_end`, we calculate the timestamps for the start and end of the clip by taking the difference between the `datetime` for the respective clip variable and the video start `datetime`. Then we convert from `datetime` to seconds, leaving us with a pair of start and end bounds.
#### Putting it all together
To collect the requisite data for all users, we simply loop through all valid users in the Firestore and run `get_data` on each. This is done in the `main` function. The returned `clips` and `sessions` for each user are added to respective lists storing all users' data. After all `clips` and `sessions` have been retrieved from all users, the data is written to the output `JSON` and `csv` files.

## Video downloading (download_videos.py)
This script is used to locally download the RecordTheseHands videos from cloud storage buckets in the set Google Cloud Project.

### Code Explanation
The video downloading is done in three steps:
1. Obtaining the video metadata, including associated md5 hash and video path
2. Concurrently downloading all videos from Google Cloud using a process pool
3. Comparing downloaded hashes to computed hashes for each video as data validation

#### 1. Obtaining the video metadata
Metadata collection for video downloads is done through the `get_video_metadata` function.

Taking in a reference to the Firestore (`db`) and the target user (`username`) as arguments, the function iterates through all files listed under the target user in Firestore. For each instance of file data, it iterates through all fields and saves the `hash` and `path` values to a list. After finishing iteration, it returns two lists containing all hashes and paths associated to videos generated by the target user.

#### 2. Downloading all videos
Downloading videos is done through the `download_all_videos` function. It takes in the target bucket (`bucket_name`), video file names in Google Cloud Storage (`blob_names`), the destination directory (`destination_directory`, defaults to current directory if not specified), and the number of concurrent workers (`workers`, set to 8).

First, it connects to the Google Cloud bucket by instantiating a `Client` and creating an object for the specified bucket using the client. Using the `transfer_manager` from Google Cloud Storage, it downloads multiple videos specified by `blob_names` in parallel. After downloads are finished, the function loops through the results and reports the status of each video; either the video was successfully downloaded, or the video failed to download due to an exception. In either case, an appropriate message is printed to console.

#### 3. Data validation

In the `main` function, the script retrieves hash and path metadata for each video, then downloads each one. After all downloads are finished, it computes the md5 hash for each video and compares it to the has retrieved from Google Cloud. If they match, the video passes validation. After `main` finishes running, all valid videos should be downloaded to the specified directory.

## Local video clipping (clip_video.py)
This script uses `ffmpeg`, a multimedia framework for processing video and audio, to extract clips from the locally downloaded videos in a way that preserves quality while capturing the entire signed word. 

### Code Explanation
Actually creating the clips is the longest of the three parts of post-processing. This is because we have to cut the videos in a way that preserves video quality and minimizes computational resources. For the latter, we must specifically avoid having to reencode each clip. The methodology for creating clips is elaborated  below.

#### Video probing
Before we create any clips from a video, we need the packet info to determine the timestamps and duration of the keyframes to split on. In addition, we obtain the md5 hashes of the packet data, which will be used later for validation.

To do this, we run `ffprobe_packet_info` on a specified video, which runs the following `ffprobe` command to obtain the necessary data:

```
ffprobe -v error -select_streams v -show_packets -show_data_hash md5 -show_entries packet=pts_time,duration_time,flags,data_hash -of compact video
```

After this, we parse through the output of the command, returning the keyframe timestamps, duration, packet flags, and md5 hash in a dictionary.

#### Making an individual clip
To make a clip, we use a copy codec (coder-decoder) through `ffmpeg` that copies the input bitstream directly to an output container. By copying the input bitstream "as-is," we avoid decoding and reencoding each clip, making the clipping process much faster. In addition, we avoid potential quality loss from the reencoding process.

Note that, since we are using codec copy, we must cut on keyframes. This is because other types of frames (namely B-frames) rely on other frames, so cutting on these other frames without reencoding (which we are trying to avoid) can lead to issues in video quality.

Before we make the clip, we determine the correct keyframes to split on. These will be the keyframes immediately before and after the inputted clip start and end.

Since the input times are assumed to start at 0 seconds, we must align the input start and end taken from `dump_clips.py` with `ffmpeg`'s expected time by adding the first packet's presentation timestamp.

After the keyframe timestamps to search for are determined, we search in the packet info for the last keyframe before the start time and the first before the end time. Then, we compute initial start and end time guesses with great precision. However, if the initial guesses fail due to rounding errors, we perform a binary search on the start time to generate a packet-accurate clip.

In each iteration of the search, we create a clip to the output directory using the following `ffmpeg` call with the guessed start and end times:

```
ffmpeg -v error -y -ss  start_time -to end_time -i video -an -c:v copy output_filename
```

Afterward, we retrieve its packet info using `ffprobe_packet_info` so that we can check for dropped flags and compare md5 hashes.

If the clip has a dropped flag in the first packet, our start time was incorrect. In this case, we check if the first packet has the same md5 hash as the expected starting packet. If it does, this means that the first packet was the correct one but the start time was a little too late. Otherwise, the timestamp was too early. After the md5 check, we adjust the start time accordingly and try the loop again with the new start.

To analyze the end time, we check if the md5 of the last packet of the clip matches that of the expected clip. If it does, our end time is incorrect. We then adjust the end time depending on if the last packet is before or after the keyframe we wanted.

Once both the first and last packets of the clip are what we expect, we have a frame-perfect clip and can break out of the loop. We return with all necessary data about the clip.

#### Clipping all videos

Once we have the methodology for creating a single clip, we can make every clip for the videos we have downloaded locally through `make_clips`. Using the output for `dump_clips.py`, we compile the input data for every clip (`user_id`, `video`, `start_time`, `end_time`). Then, for each full video, we create all of its clips in parallel by submitting each call to `make_clip` as a task to a process pool executor. Note that this is the point where start and end times for each clip are adjusted using the configurable buffers.

Once the function finishes running, all valid clips will have been downloaded to the output directory.

## FAQs / Common Issues

**How do I view regular / active prompts instead of tutorial prompts?**

In order to do regular prompt collection, issue both a tutorial prompt and a regular / active prompt to a user. Go through the regular 
tutorial process on the device and exit the tutorial mode. When you reopen the app, it should take you to the primary data collection
screen, in which the active prompts will be used.

**My prompt is not downloading to the device!**

If it is an image / video based prompt, double check that the filepaths and file types for everything is correct in the prompt and on the DPAN server. If even one of the filepaths is incorrect, then the entire prompt will fail to download and will not be used for data collection.

Otherwise, double check that the tutorial mode prompts have uploaded and completed prior to proceeding to the primary prompts. 

For further help and debugging, please contact the developers. 




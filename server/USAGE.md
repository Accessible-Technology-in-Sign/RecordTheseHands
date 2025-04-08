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
The following scripts are used to create individual clips for each sign after uploading videos and metadata to a Google Cloud Project.

- `dump_clips.py` - Retrieve all video metadata, including start and end times for sign clips, from Firestore in the Google Cloud Project. This creates the files `metadata_dump.csv` and `metadata_dump.json` with the requisite data.
- `download_videos.py` - Locally download all videos from the buckets in the Google Cloud Project to the `video_dump` directory.
- `clip_video.py` - Produce clips for each video by using the start and end times from `metadata_dump.csv` or `metadata_dump.json`, depending on which is configured. Clips are uploaded to the `clip_dump` directory.
- `local_video_pipeline.py` - Runs the full post-processing pipeline, which consists of the above scripts.
    - Adding the `--clean` argument automatically cleans all existing output directories. 
    - To use configured buffers, use `--buffer <filename>`.

The output directories listed above are also configurable in the `constant.py` file. To use configurable buffers, format the configurable JSON file as shown below:

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


## FAQs / Common Issues

**How do I view regular / active prompts instead of tutorial prompts?**

In order to do regular prompt collection, issue both a tutorial prompt and a regular / active prompt to a user. Go through the regular 
tutorial process on the device and exit the tutorial mode. When you reopen the app, it should take you to the primary data collection
screen, in which the active prompts will be used.

**My prompt is not downloading to the device!**

If it is an image / video based prompt, double check that the filepaths and file types for everything is correct in the prompt and on the DPAN server. If even one of the filepaths is incorrect, then the entire prompt will fail to download and will not be used for data collection.

Otherwise, double check that the tutorial mode prompts have uploaded and completed prior to proceeding to the primary prompts. 

For further help and debugging, please contact the developers. 




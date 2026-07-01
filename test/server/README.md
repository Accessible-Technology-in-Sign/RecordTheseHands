## Test server

To avoid having to create a Google Cloud Platform instance for testing changes locally, you can use this test server which provides barebones functionality.


### Requirements
* `uv`: See instructions [here](https://docs.astral.sh/uv/getting-started/installation/).
* `adb`: See installation instructions [here](https://developer.android.com/tools/adb). If you're using Android Studio, you probably already have this command installed.
* Enable developer mode on your test device and connect it via USB.

Once you have these dependencies installed, run `uv sync` to install any dependencies and then run the following commands:

```sh
adb reverse tcp:8080 tcp:8080
uv run uvicorn mock_server:app --host 0.0.0.0 --port 8080
```

Add the following values to your `credentials.xml`:
```xml
<string name="backend_server">http://localhost:8080</string>
<string name="trustable_hosts">localhost</string>
```

Once you install the app via Android Studio or `adb`, the app should now be
able to connect to the mock server. Tap "RecordTheseHands" at the top of the
screen several times to access the admin page, then type in any username to 
download prompts to the device. You can now begin testing the recording activity.

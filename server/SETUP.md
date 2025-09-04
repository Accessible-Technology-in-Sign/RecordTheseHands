# Set Up

## Create a Google Account

Create an account at [http://gmail.com/](http://gmail.com/) of form
`acronym.server@gmail.com`. Set the birthdate as `1970-01-01`, and avoid putting
any personal information such as a phone number (to avoid accidentally enabling
2-factor authentication). Disable any option to store personal info (such that
web browsing information is not revealed).

**Do not add a phone number to this account.**

## Setting up the cloud project

1. While logged into `acronym.server@gmail.com`, go to
   [http://console.cloud.google.com/](http://console.cloud.google.com/)

1. Create a new project

   - Click on the active project (probably "My First Project").
   - Click on "new project"
   - Set project name such as "Acronym Data Collection"
   - Set project ID to `acronym-data`. **It is important you do this step when
     creating the project as otherwise a project ID will be randomly generated
     for you.**
   - Delete the first project (to clean up the project management).

1. Activate the free trial

   - In order to ensure the project does not die after 30 days, you will want to
     add a credit card to the account
     - For business, add a credit card number
     - As of January 15th, 2025, removing the credit card information from a
       project has been difficult, but the project is lightweight and has
       charged a rather small amount in my experience. You will also be given
       $300 of compute credits for free during your free 30 day trial.

1. Enable APIs

   - The following services need to be enabled within the API services page
     - FireStore API
     - App Engine API
     - IAM Service API
     - Secret Manager API
     - Cloud Resource Manager API
   - Add the above sections and the cloud solutions page to the pinned section
     (for convenient access) by clicking on the Google Cloud Solutions page.

1. Create Firestore database.

   - Select "Native mode" for the data store (which is recommended for a mobile
     app like this one)
   - This database is responsible for storing all metadata associated with user
     recordings when interacting with the GCP server

1. Go into the App Engine page and create an App Engine Application

   - Select the default service account
     `acronym-data@appspot.gserviceaccount.com` when creating the App Engine
     application
   - Creating the App Engine Application will implicitly create a bucket of form
     `acronym-data.appspot.com`.
     - You do not need to verify domain name ownership of the above domain. If
       you are at this step, this is probably because you did not set up the app
       engine.

1. Add Permissions to your bucket

   - Find the default bucket `acronym-data.appspot.com` (after the App Engine
     application has been created)
   - In permissions, modify the permissions for the principal:
     `acronym-data@appspot.gserviceaccount.com`
     - Add the role: "Storage Legacy Bucket Owner"
     - Add the role: "Storage Legacy Object Owner"
   - Add storage legacy object owner to any editors and owners to this project
     as well. If you do not, you may run into permissions related issues.

1. In order to administrate the project from a different Google account:

   - From "IAM & Admin" in the menu, select "IAM"
   - Click "+" to grant access
   - Add the new google account with the role "Owner".
   - An invitation will be sent to that account and will need to be accepted to
     proceed
     - This will allow this new user to access the Google Cloud Console pages
       and use command line tools for this project.

## Integrating the DPAN Server with `RecordTheseHands`

This section will all be done locally within project code (except for the last
step)

1. Install Google Cloud SDK:
   [https://cloud.google.com/sdk/docs/install-sdk](https://cloud.google.com/sdk/docs/install-sdk)

1. Set up Google Cloud within the Terminal

   ```
   gcloud auth login 
   gcloud auth application-default login
   gcloud init
   gcloud auth application-default set-quota-project acronym-data
   gcloud app create
   ```

   - Login with the same account you'll be using to administer the gcloud
     account with. This can be your personal account as long as permissions have
     been granted.
   - When initializing the project:
     - Use a project name of `acronym-data`
     - Enter the project id: `acronym-data`
   - Set the default quota for the project. This suppresses a bunch of warnings
     and might help access APIs
   - When creating the app, pick a region such as `us-central`

1. Create Server Config. In `server/collector/`, copy `example_config.py` to
   `config.py`.

   ```
   cd server/collector
   cp example_config.py config.py
   ```

   Edit `config.py` to use the project id `acronym-data` for the prod server and
   either the same for the dev server, or the dev project name if you're using
   one (`acronym-data-dev`) \[This is only if you are using two projects for
   production and development\].

1. Set Parameters in `credentials.xml` within the app

   - Copy the example credentials to
     `record_these_hands/app/src/main/res/values/credentials.xml`

   ```
   cp example_credentials.xml app/src/main/res/values/credentials.xml
   ```

   - Modify values as needed

     - Replace the backend server url to:
       `https://acronym-data.uc.r.appspot.com`
     - If enabling email notifications, replace username and password with an
       email notification account

     > Warning: The password is in plain text in credentials file which is
     > easily accessible to anyone who has access to the apk. This account
     > **will** get hijacked very easily, do not use an account you care about.
     > If you have sensitive prompts, do not use this feature at all, as an
     > attacker will have access to all e-mails sent.

1. Deploy the Server to Cloud

   ```
   cd server/collector
   source setup_local_env.sh
   ./deploy_dev.sh
   ```

   - The `setup_local_env.sh` script is intended for Debian-based systems. It
     uses `apt` to install `google-cloud-cli` and `python3-venv`. If you are on
     a different OS, you will need to install these manually.
   - If you are not on a Debian-based system, you can set up the local
     environment with these commands:
     ```bash
     # For non-debian systems:
     python3 -m venv APP_ENV_DIR
     source APP_ENV_DIR/bin/activate
     pip install -r requirements.txt
     pip install gunicorn
     ```
   - After setting up the local environment, you need to authenticate with
     Google Cloud:
     ```bash
     gcloud auth login
     gcloud auth application-default login
     ```
   - The `deploy_dev.sh` script will deploy the python scripts in the collector
     directory to the App Engine.
   - There is also a `deploy_prod.sh` for deploying to the production App Engine
     instance.
   - The `setup_local_env.sh` script will also print instructions on how to set
     up a local server for testing. Here is a summary of the steps:
     - Generate self-signed credentials:
       ```bash
       openssl req -x509 -newkey rsa:4096 -keyout ~/.ssh/https/key.pem -out ~/.ssh/https/cert.pem -sha256 -nodes -days 365
       ```
     - Start the server using gunicorn:
       ```bash
       export GOOGLE_CLOUD_PROJECT=$(cat config.py | grep 'DEV_PROJECT *=' | sed 's/DEV_PROJECT *= *['\'"']\([^'\'"']\+\)['\'"'].*/\1/')
       gunicorn --certfile=~/.ssh/https/cert.pem --keyfile=~/.ssh/https/key.pem -b :8050 main:app
       ```
     - You can also set up a proxy using `nginx`. Please refer to the output of
       `source setup_local_env.sh` for more details.

1. Create a Secret (locally)

   ```
   cd server/collector
   source setup_local_env.sh 
   python3 token_maker.py
   ```

   - For the username, specify "admin"
   - For the password, create a simple password (this will be something you will
     use everytime you are setting up a user account).
   - Store both the `login_token` and the `login_hash` (including both
     `username:hash`)

1. Go to the Secret Manager under the Security Manager (within the Google Cloud
   Console)

   - Create a secret
     - name: `admin_token_hash`
     - Secret Value: `login_hash` from before (of form: `admin:hash`)
   - Create another secret
     - name: `app_secret_key`
     - Secret Value: Use a random string, such as the output of
       `openssl rand -base64 30`. The output of this does not need to be stored

1. Now that `RecordTheseHands` has the token hash and backend server
   information, it can communicate with the `DPAN Server`. Compile
   `RecordTheseHands` using Android Studio and refer to [USAGE.md](USAGE.md) for
   further use.

> Documentation made by Bill Neubauer and Manfred Georg. For any questions
> please contact `bill.neubauer.4@gmail.com`

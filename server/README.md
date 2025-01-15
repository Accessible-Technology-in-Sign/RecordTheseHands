
# DPAN Server Set Up and Usage

## About

The DPAN Server is a Flask-based web server that communicates with the RecordTheseHands app.
Users register accounts and download instructions / prompts from the server. Once users are done
collecting data, users can upload their recorded videos to the server, in which the data/metadata is stored in
a GCP bucket and firestore database. Server admins can issue instructions to user accounts via *directives*, 
which handles account management, which prompts users must complete, and app updates. The scripts under 
`/collector` are responsible for how the user interacts with the DPAN server.


## Set Up

### Create a Google Account

Create an account at http://gmail.com/ of form `acronym.server@gmail.com`. Set the birthdate as `1970-01-01`, and avoid putting
any personal information such as a phone number (to avoid accidentally enabling 2-factor authentication). Disable any option
to store personal info (such that web browsing information is not revealed).

**Do not add a phone number to this account.**

### Setting up the cloud project

1. While logged into `acronym.server@gmail.com`, go to http://console.cloud.google.com/
2. Create a new project
    - Click on the active project (probably "My First Project").
    - Click on "new project"
    - Set project name such as "Acronym Data Collection"
    - Set project ID to "acronym-data". **It is important you do this step when creating the project as otherwise a project ID will be randomly generated for you.**
    - Delete the first project (to clean up the project management).
3. Activate the free trial
    - In order to ensure the project does not die after 30 days, you will want to add a credit card to the account
        - For business, add a credit card number
        - As of January 15th, 2024, removing the credit card information from a project has been difficult, but the project is lightweight and has charged a rather small amount in my experience. You will also be given $300 of compute credits for
        free during your free 30 day trial.
4. Enable APIs
    - The following services need to be enabled within the API services page
        - FireStore API
        - App Engine API
        - IAM Service API
        - Secret Manager API
        - Cloud Resource Manager API
    - Add the above sections and the cloud solutions page to the pinned section (for convenient access) by clicking on the Google Cloud Solutions page.
5. Create Firestore database. 
    - Select Native mode for the data store (which is recommended for a mobile app like this one)
    - This database is responsible for storing all metadata associated with user recordings when interacting with the GCP server
6. Go into the App Engine page and create an App Engine Application
    - Select the default service account `acronym-data@appspot.gserviceaccount.com` when creating the App Engine application
    - Creating the App Engine Application will implicitly create a bucket of form `acronym-data.appspot.com`.
        - You do not need to verify domain name ownership of the above domain.  If you are at this step, this is probably because you did not set up the app engine.

7. Add Permissions to your bucket
    - Find the default bucket `acronym-data.appspot.com` (after the App Engine application has been created)
    - In permissions, modify the permissions for the principal: `acronym-data@appspot.gserviceaccount.com`
        - Add the role: "Storage Legacy Bucket Owner"
        - Add the role: "Storage Legacy Object Owner"
    - Add storage legacy object owner to any editors and owners to this project as well. If you do not, you may run into permissions related issues.

8. In order to administrate the project from a different Google account:
    - From "IAM & Admin" in the menu, select "IAM"
    - Click "+" to grant access
    - Add the new google account with the role "Owner". 
    - An invitation will be sent to that account and will need to be accepted to proceed
        - This will allow this new user to access the Google Cloud Console pages and use command line tools for this project.

### Integrating the DPAN Server with RecordTheseHands
This section will all be done locally within project code

1. 
2. Create Server Config:
    - In `server/collector/`, copy `example_config.py` to `config.py`:
    ```
    cd server/collector
    cp example_config.py config.py
    ```
    - Edit config.py to use the project id "acronym-data" for the prod server and either the same for the dev server, or the dev project name if you're using one ("acronym-data-dev") [This is only if you are using two projects for production and development].
    ``

3. Set Parameters in credentials.xml within the app
    - Copy the example credentials to `record_these_hands/app/src/main/res/values/credentials.xml`
    ```
    cp example_credentials.xml app/src/main/res/values/credentials.xml
    ```
    - Modify values as needed
        - Replace the backend server url to: `https://acronym-data.uc.r.appspot.com`
        - If enabling email notifications, replace username and password with an email notification account
        > Warning: The password is in plain text in credentials file which is easily accessible to anyone who has access to the apk.  This account **will** get hijacked very easily, do not use an account you care about. If you have sensitive prompts, do not use this feature at all, as an attacker will have access to all e-mails sent. 
        
        [//]: # (It should also be possible to use an app password/access token, which you should be able to generate in "manage account" -> "security" -> ??? but this feature has yet to be implemented)

4. Deploy Server Cloud 



## Usage

    







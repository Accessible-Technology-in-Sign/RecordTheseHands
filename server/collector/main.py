#!/usr/bin/python3

# Copyright 2023 Google LLC
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
"""Main flask based server to facilitate sign language data collection."""

import base64
import copy
import datetime
import functools
import hashlib
import io
import json
import logging
import mimetypes
import os
import pathlib
import re
import sys
import urllib.request

import config
import flask
import flask_login
import flask_paranoid
import generate_signed_url
import google.auth
import google.auth.iam
from google.cloud import firestore
from google.cloud import secretmanager
from google.cloud import storage
import google.cloud.logging
import token_maker

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'
IS_PROD_ENV = PROJECT_ID == config.PROD_PROJECT
IS_LOCAL_ENV = os.environ.get('GAE_ENV', 'localdev') == 'localdev'
SERVER_VERSION = 'v2.3.8'

_BANNED_USERNAMES = frozenset(
    [
        'apk',
        'download',
        'metadata',
        'prompts',
        'renamed_videos',
        'resource',
        'upload',
    ]
)

# globals, including Flask environment.
app = flask.Flask(__name__)
login_manager = flask_login.LoginManager()

if IS_LOCAL_ENV:
  root = logging.getLogger()
  root.setLevel(logging.DEBUG)

  handler = logging.StreamHandler(sys.stdout)
  handler.setLevel(logging.DEBUG)
  formatter = logging.Formatter('%(asctime)s [%(levelname)s]: %(message)s')
  handler.setFormatter(formatter)
  root.addHandler(handler)


def json_pretty(data):
  return json.dumps(data, sort_keys=True, indent=2)


app.jinja_env.filters['tojson_pretty'] = json_pretty


class Error(Exception):
  """Module errors."""


# The flask-login user object.
class User(flask_login.UserMixin):

  def __init__(self):
    super().__init__()
    self.allow_webapp_access = False


@login_manager.user_loader
def user_loader(username):
  """Load the user given an id."""
  logging.debug('user_loader {username!r}')
  if not username:
    return None
  db = firestore.Client()
  # Get the user information from firestore.
  doc_ref = db.document(f'collector/users/{username}/login_hash').get()
  if not doc_ref.exists:
    return None
  allow_webapp_access = doc_ref.to_dict().get('allow_webapp_access', False)
  user = User()
  user.id = username
  user.allow_webapp_access = allow_webapp_access
  return user


@login_manager.request_loader
def request_loader(request):
  """Load a request, possibly logging the user in."""
  logging.debug('request_loader {request!r}')
  login_token = request.values.get('login_token', '')
  is_valid_login, username, allow_webapp_access = check_login_token(login_token)
  if not is_valid_login:
    return None

  user = User()
  user.id = username
  user.allow_webapp_access = allow_webapp_access
  # Anytime a login_token is provided in a form request we automatically
  # log the user in, regardless of which page they're on.
  #
  # Be very careful logging a user in on a subapplication,
  # If you create two session cookies things can get very messed up.
  # So, make sure the session cookies and app secret have the same settings
  # or you use different names (and hence different login sessions).
  flask_login.login_user(user, remember=True)
  return user


def initialize_app():
  logging_client = google.cloud.logging.Client()
  logging_client.setup_logging()

  app.config['APPLICATION_ROOT'] = '/'
  app.config['SESSION_COOKIE_NAME'] = 'session'
  app.config['SESSION_COOKIE_PATH'] = '/'

  app.secret_key = get_secret('app_secret_key')
  mimetypes.add_type('image/webp', '.webp', strict=False)

  paranoid = flask_paranoid.Paranoid(app)
  paranoid.redirect_view = '/'

  login_manager.init_app(app)

  print('initialized (printing on stdout).')
  logging.info('initialized (printing on logging).')


def get_secret(secret_id):
  """Get a secret from the secret manager."""
  # Create the Secret Manager client.
  client = secretmanager.SecretManagerServiceClient()
  # Build the resource name of the secret version.
  version_id = 'latest'
  name = f'projects/{PROJECT_ID}/secrets/{secret_id}/versions/{version_id}'

  # Access the secret version.
  response = client.access_secret_version(request={'name': name})

  return response.payload.data.decode('utf-8')


@app.context_processor
def inject_dev_header():
  """Create a dev header that is always available in the Jinja templates."""
  if IS_PROD_ENV:
    if IS_LOCAL_ENV:
      server_title = 'localhost Prod'
    else:
      return dict(dev_header='')
  else:
    if IS_LOCAL_ENV:
      server_title = 'localhost Dev'
    else:
      server_title = 'Dev'
  return dict(
      dev_header=f"""
<div style="font-size:30px;color:blue;font-weight:bold;">
  {server_title}
  <a href="{config.DEV_URL}">(dev)</a>
  <a href="{config.PROD_URL}">(prod)</a>
  (GCP <a href="{config.DEV_DASHBOARD}">dev</a>,
  <a href="{config.PROD_DASHBOARD}">prod</a>)
</div>"""
  )


@app.context_processor
def inject_favicon():
  """Make the path to the favicon avaialble in the Jinja templates."""
  return dict(favicon=favicon_path())


@app.context_processor
def inject_server_version():
  """Make the server version available in the Jinja templates."""
  return dict(server_version=SERVER_VERSION)


def favicon_path():
  """Return the path to the favicon."""
  if IS_PROD_ENV:
    if IS_LOCAL_ENV:
      return '/static/red_ilu.ico'
    else:
      return '/favicon.ico'
  else:
    if IS_LOCAL_ENV:
      return '/static/red_ilu.ico'
    else:
      return '/static/red_ilu.ico'


def check_login_token(login_token):
  """Checks the login token with the database and returns login information.

  Args:
    login_token: The login token as a string.

  Returns
    A Tuple of (is_valid, username, allow_webapp_access)
  """
  m = re.match(r'^([a-z][a-z0-9_]{2,}):[0-9a-f]{64}$', login_token)
  if not m:
    return (False, None, False)
  username = m.group(1)

  db = firestore.Client()
  doc_ref = db.document(f'collector/users/{username}/login_hash').get()
  if not doc_ref.exists:
    return (False, username, False)
  doc_dict = doc_ref.to_dict()
  login_hash = doc_dict.get('login_hash')
  if login_hash != token_maker.get_login_hash(username, login_token):
    return (False, username, False)
  allow_webapp_access = doc_dict.get('allow_webapp_access', False)
  return (True, username, allow_webapp_access)


def get_username(login_token):
  m = re.match(r'^([a-z][a-z0-9_]{2,}):[0-9a-f]{64}$', login_token)
  if not m:
    return None
  return m.group(1)


def get_download_link(object_name, query_parameters=None):
  """Get a signed url for downloading an object."""
  gcs_path = str(object_name)

  auth_request = google.auth.transport.requests.Request()
  credentials, unused_project = google.auth.default()
  signer = google.auth.iam.Signer(
      auth_request, credentials, SERVICE_ACCOUNT_EMAIL
  )

  return generate_signed_url.generate_signed_url(
      signer=signer,
      service_account_email=SERVICE_ACCOUNT_EMAIL,
      bucket_name=BUCKET_NAME,
      object_name=gcs_path,
      expiration=datetime.timedelta(minutes=120),
      query_parameters=query_parameters,
  )


def get_upload_link(object_name):
  """Get a signed url for uploading an object."""
  gcs_path = str(object_name)

  auth_request = google.auth.transport.requests.Request()
  credentials, unused_project = google.auth.default()
  signer = google.auth.iam.Signer(
      auth_request, credentials, SERVICE_ACCOUNT_EMAIL
  )

  return generate_signed_url.generate_signed_url(
      signer=signer,
      service_account_email=SERVICE_ACCOUNT_EMAIL,
      bucket_name=BUCKET_NAME,
      object_name=gcs_path,
      expiration=datetime.timedelta(minutes=120),
      http_method='POST',
      headers={'X-Goog-Resumable': 'start'},
  )


@app.route('/', methods=['GET', 'POST'])
def home_page():
  """The homepage."""
  if flask_login.current_user.is_authenticated:
    return flask.render_template('index.html')

  # If we're using POST and get this far, then the password was incorrect.
  incorrect_password = flask.request.method != 'GET'

  return flask.render_template(
      'login.html', incorrect_password=incorrect_password
  )


@app.route('/is_authenticated', methods=['GET', 'POST'])
def is_authenticated_page():
  """Check if the user is authenticated."""
  if not flask_login.current_user.is_authenticated:
    return 'Authentication Failed', 401
  username = flask_login.current_user.id
  return f'You are logged in as {username!r}', 200


def version_parts_from_string(version_string):
  if not version_string:
    return None
  m = re.fullmatch(r'^[vV]?(\d+(?:.\d+)*)?$', version_string.strip())
  if not m:
    return None
  return m.group(1).split('.')


@app.route('/check_version', methods=['POST'])
def check_version_page():
  """Check if the user is using an allowed version."""
  if not flask_login.current_user.is_authenticated:
    return 'Authentication Failed', 401
  username = flask_login.current_user.id
  app_version = flask.request.values.get('app_version')
  app_version_parts = version_parts_from_string(app_version)
  if not app_version_parts:
    return f'OUT_OF_RANGE: App version {app_version!r} is malformed.', 200

  db = firestore.Client()
  doc_ref = db.document(
      f'collector/users/{username}/data/prompts/version_constraints'
  )
  doc_data = doc_ref.get()
  if not doc_data.exists:
    return f'You are logged in as {username!r}', 200
  doc_dict = doc_data.to_dict()
  min_version_parts = version_parts_from_string(doc_dict.get('min_version'))
  max_version_parts = version_parts_from_string(doc_dict.get('max_version'))

  # Extend versions with zeros.
  max_version_length = max(
      len(app_version_parts),
      len(min_version_parts) if min_version_parts else 0,
      len(max_version_parts) if max_version_parts else 0,
  )
  app_version_parts = app_version_parts + [0] * (
      max_version_length - len(app_version_parts)
  )

  if min_version_parts:
    min_version_parts = min_version_parts + [0] * (
        max_version_length - len(min_version_parts)
    )
    if app_version_parts < min_version_parts:
      return (
          f'OUT_OF_RANGE: App version {app_version!r} is below the '
          f'minimum of {".".join(min_version_parts)!r}.'
      ), 200

  if max_version_parts:
    max_version_parts = max_version_parts + [0] * (
        max_version_length - len(max_version_parts)
    )
    print(
        f'checking app_version_parts > max_version_parts which is {app_version_parts > max_version_parts}'
    )
    if app_version_parts > max_version_parts:
      return (
          f'OUT_OF_RANGE: App version {app_version!r} is above the '
          f'maximum of {".".join(max_version_parts)!r}.'
      ), 200

  return f'You are logged in as {username!r}', 200


@app.route('/apk', methods=['GET'])
def apk_page():
  """Download the latest apk by version number."""
  gcs = storage.Client()
  blobs = gcs.list_blobs(BUCKET_NAME, prefix='apk/')

  latest_version = None
  latest_apk_blob = None

  version_pattern = re.compile(
      r'record_these_hands.*_v(\d+)[._-](\d+)[._-](\d+)\.apk'
  )

  for blob in blobs:
    match = version_pattern.fullmatch(os.path.basename(blob.name))
    if match:
      major, minor, patch = map(int, match.groups())
      current_version = (major, minor, patch)
      if latest_apk_blob is None or current_version > latest_version:
        latest_version = current_version
        latest_apk_blob = blob

  if not latest_apk_blob:
    return 'No valid APK file found in the storage bucket.', 404

  print(f'Found apk file: {latest_apk_blob.name}')

  latest_apk_filename = os.path.basename(latest_apk_blob.name)
  query_parameters = {
      'response-content-disposition': (
          f'attachment; filename="{latest_apk_filename}"'
      )
  }
  download_link = get_download_link(
      latest_apk_blob.name, query_parameters=query_parameters
  )
  return flask.redirect(download_link, code=303)


@app.route('/resource', methods=['POST'])
@flask_login.login_required
def resource_page():
  """Download a resource item."""
  username = flask_login.current_user.id
  assert username

  path = flask.request.values.get('path', '')

  logging.info(f'/resource username={username} path={path!r}')

  m = re.match(r'^[a-zA-Z0-9_:.-]+(?:/[a-zA-Z0-9_:.-]+){,5}$', path)
  if not m:
    return 'path had weird characters in it or too much depth.', 400

  # Ensure the path starts with "resource/" but don't add it if it's
  # already there.
  if not path.startswith('resource/'):
    path = 'resource/' + path

  download_link = get_download_link(f'{path}')

  return flask.redirect(download_link, code=303)


@app.route('/prompts', methods=['POST'])
def prompts_page():
  """Download a prompts."""
  login_token = flask.request.values.get('login_token', '')
  is_valid_login, username, _ = check_login_token(login_token)
  if not is_valid_login:
    return 'login_token invalid', 401

  assert username

  logging.info(f'/prompts {username}')

  db = firestore.Client()
  doc_ref = db.document(f'collector/users/{username}/data/prompts/active')
  doc_data = doc_ref.get()
  if not doc_data.exists:
    return (f'no prompts found for user {username}'), 404
  doc_dict = doc_data.to_dict()
  path = doc_dict.get('path')
  if not path:
    return (f'prompt file not found for user {username}'), 404

  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
  server_log_key = f'prompts-{timestamp}'
  app_version = flask.request.values.get('app_version', 'unknown')
  doc_ref = db.document(
      f'collector/users/{username}/data/server/{server_log_key}'
  ).set(
      {
          'appVersion': app_version,
          'path': path,
          'timestamp': timestamp,
      }
  )

  download_link = get_download_link(path)

  return flask.redirect(download_link, code=303)


@app.route('/upload', methods=['POST'])
def upload():
  """Upload an item."""
  login_token = flask.request.values.get('login_token', '')
  is_valid_login, username, _ = check_login_token(login_token)
  if not is_valid_login:
    return 'login_token invalid', 401
  app_version = flask.request.values.get('app_version', 'unknown')

  assert username

  logging.info(f'/upload {username}')

  path = flask.request.values.get('path', '')
  m = re.match(r'^[a-zA-Z0-9_:.-]+(?:/[a-zA-Z0-9_:.-]+){,5}$', path)
  if not m:
    return 'path had weird characters in it or too much depth.', 400
  filename = pathlib.Path(path).name
  m = re.match(r'^[a-zA-Z0-9_:.-]*$', filename)
  if not m:
    return 'filename had weird characters in it.', 400
  md5sum = flask.request.values.get('md5', '')
  m = re.match(r'^[0-9a-f]{32}$', md5sum)
  if not m:
    return 'md5 was invalid (must be 32 lower case hex characters).', 400
  file_size = flask.request.values.get('file_size', '')
  m = re.match(r'^\d+$', file_size)
  if not m:
    return 'file_size was not a non-negative integer', 400
  tutorial_mode = flask.request.values.get('tutorial_mode', '').lower() in [
      '1',
      't',
      'true',
  ]

  tutorial_mode_prefix = ''
  if tutorial_mode:
    tutorial_mode_prefix = 'tutorial_'

  db = firestore.Client()
  db.document(
      f'collector/users/{username}/{tutorial_mode_prefix}data/file/{filename}'
  ).set(
      {
          'appVersion': app_version,
          'path': path,
          'md5': md5sum,
          'fileSize': int(file_size),
      }
  )

  upload_link = get_upload_link(f'upload/{username}/{path}')
  return flask.jsonify({'uploadLink': upload_link})


if IS_LOCAL_ENV:
  # TODO remove this anchor.
  # (used by upload_file.py so it doesn't need login_token)

  @app.route('/simple_upload', methods=['POST'])
  def simple_upload():
    """Upload an item."""
    username = 'testing'

    app_version = flask.request.values.get('app_version', 'unknown')
    filename = flask.request.values.get('filename', '')
    m = re.match(r'^[a-zA-Z0-9_:.-]*$', filename)
    if not m:
      return 'filename had weird characters in it.', 400
    md5sum = flask.request.values.get('md5', '')
    m = re.match(r'^[0-9a-f]{32}$', md5sum)
    if not m:
      return 'md5 was invalid (must be 32 lower case hex characters).', 400

    db = firestore.Client()
    db.document(f'collector/users/{username}/data/file/{filename}').set(
        {'appVersion': app_version, 'filename': filename, 'md5': md5sum}
    )

    upload_link = get_upload_link(f'upload/{username}/{filename}')
    return flask.jsonify({'uploadLink': upload_link})


@app.route('/verify', methods=['POST'])
def verify():
  """verify the md5 on an uploaded item."""
  login_token = flask.request.values.get('login_token', '')
  is_valid_login, username, _ = check_login_token(login_token)
  if not is_valid_login:
    return 'login_token invalid', 401

  assert username

  logging.info(f'/verify {username}')

  path = flask.request.values.get('path', '')
  m = re.match(r'^[a-zA-Z0-9_:.-]+(?:/[a-zA-Z0-9_:.-]+){,5}$', path)
  if not m:
    return 'path had weird characters in it or too much depth.', 400
  filename = pathlib.Path(path).name
  m = re.match(r'^[a-zA-Z0-9_:.-]*$', filename)
  if not m:
    return 'filename had weird characters in it.', 400
  md5sum = flask.request.values.get('md5', '')
  m = re.match(r'^[0-9a-f]{32}$', md5sum)
  if not m:
    return 'md5 was invalid (must be 32 lower case hex characters).', 400
  file_size = flask.request.values.get('file_size', '')
  m = re.match(r'^\d+$', file_size)
  if not m:
    return 'file_size was not a non-negative integer', 400
  tutorial_mode = flask.request.values.get('tutorial_mode', '').lower() in [
      '1',
      't',
      'true',
  ]
  tutorial_mode_prefix = ''
  if tutorial_mode:
    tutorial_mode_prefix = 'tutorial_'

  db = firestore.Client()
  doc_ref = db.document(
      f'collector/users/{username}/{tutorial_mode_prefix}data/file/{filename}'
  ).get()
  if not doc_ref.exists:
    return 'no file registered', 400
  doc_dict = doc_ref.to_dict()
  registered_md5 = doc_dict.get('md5')
  if not registered_md5:
    return 'file does not have an md5sum registered.', 400
  assert re.match(r'^[0-9a-f]{32}$', registered_md5)
  if md5sum != registered_md5:
    return 'Provided md5sum and the one registered do not match.', 400

  gcs = storage.Client()
  bucket = gcs.get_bucket(BUCKET_NAME)
  gcs_path = str(pathlib.Path('upload').joinpath(username, path))
  blob = bucket.blob(gcs_path)
  if not blob.exists():
    return flask.jsonify({'verified': False, 'fileNotFound': True}), 404
  blob.reload()
  file_md5 = (
      base64.b16encode(base64.b64decode(blob.md5_hash)).decode('utf-8').lower()
  )
  # print(blob.md5_hash)
  # print(file_md5)
  md5_matches = file_md5 == md5sum
  size_matches = blob.size == int(file_size)
  verified = md5_matches and size_matches
  return (
      flask.jsonify(
          {
              'verified': verified,
              'md5Matches': md5_matches,
              'sizeMatches': size_matches,
          }
      ),
      200 if verified else 400,
  )


@app.route('/create_login', methods=['GET'])
def create_login():
  return flask.render_template('create_login.html')


@app.route('/logout', methods=['GET', 'POST'])
@flask_login.login_required
def logout():
  flask_login.logout_user()
  return flask.redirect(flask.url_for('home_page'), code=303)


@app.route('/users', methods=['GET', 'POST'])
@flask_login.login_required
def users_page():
  if not flask_login.current_user.allow_webapp_access:
    return 'User does not have access to the webapp.', 403
  db = firestore.Client()
  doc_ref = db.document(f'collector/users')

  current_time = datetime.datetime.now(datetime.timezone.utc)

  print('/users')

  users = list()
  for c_ref in doc_ref.collections():
    users.append({'username': c_ref.id})
    doc_ref = c_ref.document('data/heartbeat/latest')
    doc_data = doc_ref.get()
    if doc_data.exists:
      doc_dict = doc_data.to_dict()
      timestamp = doc_dict.get('timestamp')
      if timestamp:
        users[-1]['heartbeat'] = timestamp
        t = datetime.datetime.fromisoformat(timestamp)
        users[-1]['heartbeatFromNow'] = (current_time - t) / datetime.timedelta(
            seconds=1
        )
    doc_ref = c_ref.document('data/heartbeat/max_prompt')
    doc_data = doc_ref.get()
    if doc_data.exists:
      doc_dict = doc_data.to_dict()
      users[-1]['maxPrompt'] = doc_dict.get('maxPrompt')

  users.sort(key=lambda x: x.get('username'))
  return flask.render_template('users.html', users=users)


@app.route('/user', methods=['GET', 'POST'])
@flask_login.login_required
def user_page():
  if not flask_login.current_user.allow_webapp_access:
    return 'User does not have access to the webapp.', 403
  db = firestore.Client()
  username = flask.request.values.get('username', '')
  if not username:
    return 'invalid username', 400

  c_ref = db.collection(f'collector/users/{username}/data/directive')
  directives = list()
  for doc in c_ref.stream():
    doc_dict = doc.to_dict()
    if doc_dict.get('completed'):
      continue
    if doc_dict.get('completedClientTimestamp'):
      continue
    if doc_dict.get('completed_client_timestamp'):
      continue
    if doc_dict.get('cancelled'):
      continue
    assert doc_dict.get('id')
    assert doc_dict.get('op')
    assert doc_dict.get('value')
    directives.append(doc_dict)
  directives.sort(
      key=lambda x: (
          int(x.get('id', '-1')),
          x.get('op', ''),
          x.get('value', ''),
      )
  )

  c_ref = db.collection(f'collector/users/{username}/data/file')
  files = list()
  for doc in c_ref.stream():
    doc_dict = doc.to_dict()
    path = doc_dict.get('path')
    if path:
      doc_dict['filename'] = pathlib.Path(path).name
    files.append(doc_dict)
  files.sort(key=lambda x: (x.get('path', ''), x.get('md5', '')))

  c_ref = db.collection(f'collector/users/{username}/tutorial_data/file')
  tutorial_files = list()
  for doc in c_ref.stream():
    doc_dict = doc.to_dict()
    path = doc_dict.get('path')
    if path:
      doc_dict['filename'] = pathlib.Path(path).name
    tutorial_files.append(doc_dict)
  tutorial_files.sort(key=lambda x: (x.get('path', ''), x.get('md5', '')))

  return flask.render_template(
      'user.html',
      username=username,
      directives=directives,
      files=files,
      tutorial_files=tutorial_files,
  )


def get_clip_bounds_in_video(clip_data):
  video_start = clip_data.get('videoStart')
  if not video_start:
    return (None, None)

  clip_start = clip_data.get('startTimestamp')
  if not clip_start:
    clip_start = clip_data.get('startButtonDownTimestamp')
  if not clip_start:
    clip_start = clip_data.get('startButtonUpTimestamp')
  if not clip_start:
    return (None, None)

  clip_end = clip_data.get('endTimestamp')
  if not clip_end:
    clip_end = clip_data.get('restartButtonDownTimestamp')
  if not clip_end:
    clip_end = clip_data.get('swipeForwardTimestamp')
  if not clip_end:
    clip_end = clip_data.get('swipeBackTimestamp')
  if not clip_end:
    return (None, None)

  video_start_time = datetime.datetime.fromisoformat(video_start)
  clip_start_time = datetime.datetime.fromisoformat(clip_start)
  clip_end_time = datetime.datetime.fromisoformat(clip_end)

  start_s = (clip_start_time - video_start_time).total_seconds()
  end_s = (clip_end_time - video_start_time).total_seconds()
  return (start_s, end_s)


@app.route('/video', methods=['GET', 'POST'])
@flask_login.login_required
def video_page():
  if not flask_login.current_user.allow_webapp_access:
    return 'User does not have access to the webapp.', 403
  filename = flask.request.values.get('filename', '')
  if not filename:
    return 'invalid filename', 401
  username = flask.request.values.get('username', '')
  if not username:
    return 'invalid username', 401
  tutorial_mode = flask.request.values.get('tutorial_mode', '').lower() in [
      '1',
      't',
      'true',
  ]

  tutorial_mode_prefix = ''
  if tutorial_mode:
    tutorial_mode_prefix = 'tutorial_'

  db = firestore.Client()
  c_ref = db.collection(
      f'collector/users/{username}/{tutorial_mode_prefix}data/save_clip'
  )
  num_skipped = 0
  total_clips = 0
  clip_data = list()
  for doc in c_ref.stream():
    if doc.id.startswith('clipData-'):
      doc_dict = doc.to_dict()
      data = doc_dict.get('data')
      total_clips = 0
      simple_clip = {
          'clipId': data.get('clipId'),
          'filename': data.get('filename'),
          'promptText': data.get('promptData').get('prompt'),
          'valid': data.get('valid'),
      }
      start_s, end_s = get_clip_bounds_in_video(data)
      if start_s:
        simple_clip['start_s'] = start_s
      if end_s:
        simple_clip['end_s'] = end_s
      if data.get('filename') == filename:
        # print(repr(simple_clip))
        clip_data.append(simple_clip)
      else:
        num_skipped += 1

  print(
      f'found {len(clip_data)} clips '
      f'(skipped {num_skipped} from other filenames).'
  )

  c_ref = db.collection(
      f'collector/users/{username}/{tutorial_mode_prefix}data/save_session'
  )
  session_data = None
  for doc in c_ref.stream():
    if doc.id.startswith('sessionData-'):
      doc_dict = doc.to_dict()
      data = doc_dict.get('data')
      if data.get('filename') == filename:
        session_data = data
        break
  clip_data.sort(key=lambda x: (x.get('clipId', ''),))

  download_link = get_download_link(f'upload/{username}/upload/{filename}')
  # print(f'download_link = {download_link}')

  return flask.render_template(
      'video.html',
      filename=filename,
      session_data=session_data,
      video_link=download_link,
      clip_data=clip_data,
  )


@app.route('/register_login', methods=['POST'])
def register_login():
  """Create an authentication token for login."""
  device_id = flask.request.values.get('device_id', '').strip()
  login_token = flask.request.values.get('login_token', '')
  admin_token = flask.request.values.get('admin_token', '')
  allow_webapp_access = flask.request.values.get(
      'allow_webapp_access', ''
  ).strip()
  must_have_prompts_file = flask.request.values.get(
      'must_have_prompts_file', ''
  ).strip()
  must_match_device_id = flask.request.values.get(
      'must_match_device_id', ''
  ).strip()

  admin_token_hash = get_secret('admin_token_hash')
  if admin_token_hash != token_maker.get_login_hash('admin', admin_token):
    return 'admin token invalid', 400

  m = re.match(r'^([a-z][a-z0-9_]{2,}):[0-9a-f]{64}$', login_token)
  if not m:
    return (
        (
            'login_token is malformed.  '
            'Note that username must be at least 3 characters, start with '
            'a lowercase letter and include only lower case letters, '
            'numbers, and underscores.'
        ),
        400,
    )
  username = m.group(1)
  if username in _BANNED_USERNAMES:
    return (
        (
            'username choice is banned.  '
            'Your username cannot be a banned keywoard.'
        ),
        400,
    )
  db = firestore.Client()

  if must_have_prompts_file:
    doc_ref = db.document(f'collector/users/{username}/data/prompts/active')
    doc_data = doc_ref.get()
    if not doc_data.exists:
      return (
          f'Refusing to set login token for {username!r} because '
          f'it does not have an associated prompts file.'
      ), 400

  if must_match_device_id:
    if not device_id:
      return (
          f'Refusing to set login token for {username!r} because '
          f'device id was not provided but must_match_device_id was true.'
      ), 400
    doc_ref = db.document(f'collector/users/{username}/login_hash')
    doc_data = doc_ref.get()
    if doc_data.exists:
      doc_dict = doc_data.to_dict()
      old_device_id = doc_dict.get('device_id')
      if old_device_id and device_id != old_device_id:
        # If this error message is changed, then the App must also
        # be changed to correctly display the override checkbox.
        return (
            f'Refusing to set login token for {username!r} because '
            f'device id does not match old device id ({old_device_id!r}).'
        ), 400

  data = {
      'login_hash': token_maker.get_login_hash(username, login_token),
      'allow_webapp_access': allow_webapp_access == 'true',
  }
  if device_id:
    data['device_id'] = device_id
  db.document(f'collector/users/{username}/login_hash').set(data)

  return flask.render_template(
      'success.html', message=f'User {username} successfully created.'
  )


@app.route('/save', methods=['POST'])
def save():
  """Save multiple key value pairs."""
  login_token = flask.request.values.get('login_token', '')
  is_valid_login, username, _ = check_login_token(login_token)
  if not is_valid_login:
    return 'login_token invalid', 401

  data_string = flask.request.values.get('data', '[]')
  app_version = flask.request.values.get('app_version', 'unknown')

  assert username

  logging.info(f'/save {username}')

  data = json.loads(data_string)

  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()

  db = firestore.Client()

  current_max_prompt_indexes = dict()
  for entry in data:
    key = entry.get('key')
    if not key:
      return 'json entries malformed', 400
    partition = entry.get('partition', 'default')
    m = re.match(r'^[a-zA-Z0-9_-]+$', partition)
    if not m:
      logging.error(f'partition is badly formed {partition!r}')
      return 'partition is badly formed', 400
    tutorial_mode = entry.get('tutorialMode', False)
    tutorial_mode_prefix = ''
    if tutorial_mode:
      tutorial_mode_prefix = 'tutorial_'
    doc_key = (
        f'collector/users/{username}/{tutorial_mode_prefix}data/'
        f'save_{partition}/{key}'
    )
    save = {
        'appVersion': app_version,
        'serverTimestamp': timestamp,
        'key': key,
    }
    if tutorial_mode:
      save['tutorialMode'] = tutorial_mode
    if entry.get('message'):
      save['message'] = entry.get('message')
    if entry.get('data'):
      save['data'] = entry.get('data')
    db.document(doc_key).set(save)

    if key.startswith('sessionData-') and not tutorial_mode:
      if 'data' in save:
        if 'finalPromptIndex' in save['data']:
          section_name = save['data']['sectionName']
          final_prompt_index = int(save['data']['finalPromptIndex'])
          current_max_prompt_indexes[section_name] = max(
              current_max_prompt_indexes.get(section_name, 0),
              final_prompt_index,
          )
  if current_max_prompt_indexes:

    doc_ref = db.document(
        f'collector/users/{username}/data/heartbeat/max_prompt'
    )
    doc_data = doc_ref.get()
    if not doc_data.exists:
      total = sum(current_max_prompt_indexes.values())
      current_max_prompt_indexes['maxPrompt'] = total
      doc_ref.set(current_max_prompt_indexes)
    else:
      doc_dict = doc_data.to_dict()
      doc_dict.pop('maxPrompt', None)
      updated_dict = dict()
      for key in set(doc_dict) | set(current_max_prompt_indexes):
        updated_dict[key] = max(
            doc_dict.get(key, 0), current_max_prompt_indexes.get(key, 0)
        )
      total = sum(updated_dict.values())
      updated_dict['maxPrompt'] = total
      doc_ref.update(
          updated_dict,
          option=db.write_option(last_update_time=doc_data.update_time),
      )

  return flask.render_template('success.html', message='Key values saved.')


@app.route('/save_state', methods=['POST'])
def save_state():
  """Save a key value pair."""
  login_token = flask.request.values.get('login_token', '')
  is_valid_login, username, _ = check_login_token(login_token)
  if not is_valid_login:
    return 'login_token invalid', 401

  logging.info(f'/save_state {username}')

  state = flask.request.values.get('state', '')
  app_version = flask.request.values.get('app_version', 'unknown')

  assert username

  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()

  db = firestore.Client()
  db.document(f'collector/users/{username}/data/state/{timestamp}').set(
      {
          'appVersion': app_version,
          'serverTimestamp': timestamp,
          'state': json.loads(state),
      }
  )

  return flask.render_template('success.html', message='Key value saved.')


@app.route('/directives', methods=['POST'])
def directives_page():
  """Save a key value pair."""
  login_token = flask.request.values.get('login_token', '')
  is_valid_login, username, _ = check_login_token(login_token)
  if not is_valid_login:
    return 'login_token invalid', 401

  assert username

  logging.info(f'/directives {username}')

  db = firestore.Client()

  c_ref = db.collection(f'collector/users/{username}/data/directive')
  directives = list()
  for doc in c_ref.stream():
    doc_dict = doc.to_dict()
    if doc_dict.get('completed'):
      continue
    if doc_dict.get('completedClientTimestamp'):
      continue
    if doc_dict.get('completed_client_timestamp'):
      continue
    if doc_dict.get('cancelled'):
      continue
    assert doc_dict.get('id')
    assert doc_dict.get('op')
    assert doc_dict.get('value')
    directives.append(doc_dict)
  directives.sort(
      key=lambda x: (
          int(x.get('id', '-1')),
          x.get('op', ''),
          x.get('value', ''),
      )
  )
  if directives:
    logging.info(
        f'responding to /directives for {username} with ids='
        + ','.join([str(x.get('id', '-1')) for x in directives])
    )

  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
  app_version = flask.request.values.get('app_version', 'unknown')

  doc_ref = db.document(f'collector/users/{username}/data/heartbeat/latest')
  doc_ref.set(
      {
          'appVersion': app_version,
          'timestamp': timestamp,
      }
  )

  return flask.jsonify({'directives': directives}), 200


@app.route('/directive_completed', methods=['POST'])
def directive_completed():
  """Save a key value pair."""
  login_token = flask.request.values.get('login_token', '')
  directive_id = flask.request.values.get('id', '')
  timestamp = flask.request.values.get('timestamp', '')
  app_version = flask.request.values.get('app_version', 'unknown')

  is_valid_login, username, _ = check_login_token(login_token)
  if not is_valid_login:
    return 'login_token invalid', 401
  if not directive_id:
    return 'no directive id provided', 400
  if not timestamp:
    return 'timestamp must be provided', 400

  assert username

  logging.info(f'/directive_completed {username}')

  db = firestore.Client()
  doc_ref = db.document(
      f'collector/users/{username}/data/directive/{directive_id}'
  )
  doc_data = doc_ref.get()
  if not doc_data.exists:
    return 'directive not found.', 404
  doc_dict = doc_data.to_dict()
  server_timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
  doc_dict['completedClientTimestamp'] = timestamp
  doc_dict['completedServerTimestamp'] = server_timestamp
  doc_dict['appVersion'] = app_version
  doc_ref.update(
      doc_dict, option=db.write_option(last_update_time=doc_data.update_time)
  )
  return flask.jsonify({'updated:': True}), 200


if not IS_PROD_ENV:

  @app.route('/env', methods=['GET', 'POST'])
  def env_page():
    """The env page (only available in dev mode."""
    output = []
    output.append('')
    for k, v in os.environ.items():
      output.append(f'{k}={v}')
    return '\n'.join(output), 200, {'Content-Type': 'text/plain; charset=utf-8'}


initialize_app()

if __name__ == '__main__':
  # This is used when running locally only. When deploying to Google App
  # Engine, a webserver process such as Gunicorn will serve the app. This
  # can be configured by adding an `entrypoint` to app.yaml.
  app.run(host='127.0.0.1', port=8080, debug=True)

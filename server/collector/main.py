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
import mimetypes
import os
import pathlib
import re
import urllib.request

import flask

import google.auth
import google.auth.iam

from google.cloud import firestore
from google.cloud import secretmanager
from google.cloud import storage

import config
import generate_signed_url
import token_maker

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'
IS_PROD_ENV = (PROJECT_ID == config.PROD_PROJECT)
IS_LOCAL_ENV = os.environ.get('GAE_ENV', 'localdev') == 'localdev'
APP_VERSIONS = {
    'collector': 1,
}

# globals, including Flask environment.
app = flask.Flask(__name__)


class Error(Exception):
  """Module errors."""


def initialize_app():
  app.config['APPLICATION_ROOT'] = '/'
  app.config['SESSION_COOKIE_NAME'] = 'session'
  app.config['SESSION_COOKIE_PATH'] = '/'

  app.secret_key = get_secret('app_secret_key')
  mimetypes.add_type('image/webp', '.webp', strict=False)
  print('initialized.')


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
  return dict(dev_header=f"""
<div style="font-size:30px;color:blue;font-weight:bold;">
  {server_title}
  <a href="{config.DEV_URL}">(dev)</a>
  <a href="{config.PROD_URL}">(prod)</a>
  (GCP <a href="{config.DEV_DASHBOARD}">dev</a>,
  <a href="{config.PROD_DASHBOARD}">prod</a>)
</div>""")


@app.context_processor
def inject_favicon():
  """Make the path to the favicon avaialble in the Jinja templates."""
  return dict(favicon=favicon_path())


@app.context_processor
def inject_app_version():
  """Make the app version available in the Jinja templates."""
  return dict(app_version=APP_VERSIONS)


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


def is_valid_user(login_token):
  m = re.match(r'^([a-z][a-z0-9_]{2,}):[0-9a-f]{64}$', login_token)
  if not m:
    return False
  username = m.group(1)

  db = firestore.Client()
  doc_ref = db.document(f'collector/users/{username}/login_hash').get()
  if not doc_ref.exists:
    return False
  login_hash = doc_ref.to_dict().get('login_hash')
  return login_hash == token_maker.get_login_hash(username, login_token)


def get_username(login_token):
  m = re.match(r'^([a-z][a-z0-9_]{2,}):[0-9a-f]{64}$', login_token)
  if not m:
    return None
  return m.group(1)


def get_download_link(object_name):
  """Get a signed url for downloading an object."""
  gcs_path = str(object_name)

  auth_request = google.auth.transport.requests.Request()
  credentials, unused_project = google.auth.default()
  signer = google.auth.iam.Signer(
      auth_request, credentials, SERVICE_ACCOUNT_EMAIL)

  return generate_signed_url.generate_signed_url(
      signer=signer,
      service_account_email=SERVICE_ACCOUNT_EMAIL,
      bucket_name=BUCKET_NAME,
      object_name=gcs_path,
      expiration=datetime.timedelta(minutes=120),
  )


def get_upload_link(object_name):
  """Get a signed url for uploading an object."""
  gcs_path = str(pathlib.Path('upload').joinpath(object_name))

  auth_request = google.auth.transport.requests.Request()
  credentials, unused_project = google.auth.default()
  signer = google.auth.iam.Signer(
      auth_request, credentials, SERVICE_ACCOUNT_EMAIL)

  return generate_signed_url.generate_signed_url(
      signer=signer,
      service_account_email=SERVICE_ACCOUNT_EMAIL,
      bucket_name=BUCKET_NAME,
      object_name=gcs_path,
      expiration=datetime.timedelta(minutes=120),
      http_method='POST',
      headers={'X-Goog-Resumable': 'start'},
  )


@app.route('/', methods=['GET'])
def home_page():
  """The homepage."""
  return flask.render_template('index.html')


@app.route('/download', methods=['GET', 'POST'])
def download_page():
  """Download an item."""
  login_token = flask.request.values.get('login_token', '')
  if not is_valid_user(login_token):
    return 'login_token invalid', 400

  username = get_username(login_token)
  assert username

  path = flask.request.values.get('path', '')
  m = re.match(r'^[a-zA-Z0-9_:.-]+(?:/[a-zA-Z0-9_:.-]+){,5}$', path)
  if not m:
    return 'path had weird characters in it or too much depth.', 400
  download_link = get_download_link(f'{username}/{path}')

  return flask.jsonify({'downloadLink': download_link})


@app.route('/prompts', methods=['POST'])
def prompts_page():
  """Download a prompts."""
  print('download prompts')
  login_token = flask.request.values.get('login_token', '')
  if not is_valid_user(login_token):
    return 'login_token invalid', 400

  username = get_username(login_token)
  assert username

  db = firestore.Client()
  doc_ref = db.document(f'collector/users/{username}/data/prompts/active')
  doc_data = doc_ref.get()
  if not doc_data.exists:
    return f'no prompts found for user {username}', 404
  doc_dict = doc_data.to_dict()
  path = doc_dict.get('path')
  if not path:
    return f'prompt file not found for user {username}', 404

  download_link = get_download_link(path)

  return flask.redirect(download_link, code=303)


@app.route('/apk', methods=['GET'])
def apk_page():
  """Download the latest apk."""
  db = firestore.Client()
  doc_ref = db.document(f'collector/apk')
  doc_data = doc_ref.get()
  if not doc_data.exists:
    return 'apk version info not found.', 404
  apk_data = doc_data.to_dict()
  apk_filename = apk_data.get('filename')
  assert apk_filename

  download_link = get_download_link(f'apk/{apk_filename}')

  return flask.redirect(download_link, code=307)


@app.route('/upload', methods=['POST'])
def upload():
  """Upload an item."""
  login_token = flask.request.values.get('login_token', '')
  if not is_valid_user(login_token):
    return 'login_token invalid', 400
  app_version = flask.request.values.get('app_version', 'unknown')

  username = get_username(login_token)
  assert username

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
  tutorial_mode = (
      flask.request.values.get('tutorial_mode', '').lower() in
      ['1', 't', 'true'])

  tutorial_mode_prefix = ''
  if tutorial_mode:
    tutorial_mode_prefix = 'tutorial_'

  db = firestore.Client()
  db.document(f'collector/users/{username}/{tutorial_mode_prefix}data/'
              f'file/{filename}').set({
      'appVersion': app_version,
      'path': path,
      'md5': md5sum,
      'fileSize': int(file_size)
  })

  upload_link = get_upload_link(f'{username}/{path}')
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
    db.document(f'collector/users/{username}/data/file/{filename}'
               ).set({
                   'appVersion': app_version,
                   'filename': filename,
                   'md5': md5sum
               })

    upload_link = get_upload_link(f'{username}/{filename}')
    return flask.jsonify({'uploadLink': upload_link})


@app.route('/verify', methods=['POST'])
def verify():
  """verify the md5 on an uploaded item."""
  login_token = flask.request.values.get('login_token', '')
  if not is_valid_user(login_token):
    return 'login_token invalid', 400

  username = get_username(login_token)
  assert username

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
  tutorial_mode = (
      flask.request.values.get('tutorial_mode', '').lower() in
      ['1', 't', 'true'])
  tutorial_mode_prefix = ''
  if tutorial_mode:
    tutorial_mode_prefix = 'tutorial_'

  db = firestore.Client()
  doc_ref = db.document(
      f'collector/users/{username}/{tutorial_mode_prefix}data/'
      f'file/{filename}').get()
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
  file_md5 = base64.b16encode(
      base64.b64decode(blob.md5_hash)).decode('utf-8').lower()
  # print(blob.md5_hash)
  # print(file_md5)
  md5_matches = file_md5 == md5sum
  size_matches = blob.size == int(file_size)
  verified = md5_matches and size_matches
  return flask.jsonify({
      'verified': verified,
      'md5Matches': md5_matches,
      'sizeMatches': size_matches}), 200 if verified else 400


@app.route('/register_login', methods=['POST'])
def register_login():
  """Create an authentication token for login."""
  login_token = flask.request.values.get('login_token', '')
  admin_token = flask.request.values.get('admin_token', '')

  admin_token_hash = get_secret('admin_token_hash')
  if admin_token_hash != token_maker.get_login_hash('admin', admin_token):
    return 'admin token invalid', 400

  m = re.match(r'^([a-z][a-z0-9_]{2,}):[0-9a-f]{64}$', login_token)
  if not m:
    return (
        ('login_token is malformed.  '
         'Note that username must be at least 3 characters, start with '
         'a lowercase letter and include only lower case letters, '
         'numbers, and underscores.'),
        400)
  username = m.group(1)
  db = firestore.Client()
  db.document(f'collector/users/{username}/login_hash').set(
      {'login_hash': token_maker.get_login_hash(username, login_token)})

  return flask.render_template(
      'success.html',
      message=f'User {username} successfully created.')


@app.route('/save', methods=['POST'])
def save():
  """Save multiple key value pairs."""
  login_token = flask.request.values.get('login_token', '')
  if not is_valid_user(login_token):
    return 'login_token invalid', 400

  data_string = flask.request.values.get('data', '[]')
  app_version = flask.request.values.get('app_version', 'unknown')

  username = get_username(login_token)
  assert username

  data = json.loads(data_string)
  # print(json.dumps(data, indent=2))

  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()

  db = firestore.Client()

  for entry in data:
    key = entry.get('key')
    tutorial_mode = entry.get('tutorialMode', False)
    if tutorial_mode:
      doc_key = f'collector/users/{username}/tutorial_data/save/{key}'
    else:
      doc_key = f'collector/users/{username}/data/save/{key}'
    if not key:
      return 'json entries malformed', 400
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

  return flask.render_template(
      'success.html',
      message='Key values saved.')


@app.route('/save_state', methods=['POST'])
def save_state():
  """Save a key value pair."""
  login_token = flask.request.values.get('login_token', '')
  if not is_valid_user(login_token):
    return 'login_token invalid', 400

  state = flask.request.values.get('state', '')
  app_version = flask.request.values.get('app_version', 'unknown')

  username = get_username(login_token)
  assert username

  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()

  db = firestore.Client()
  db.document(f'collector/users/{username}/data/state/{timestamp}').set(
      {'appVersion': app_version,
       'serverTimestamp': timestamp,
       'state': json.loads(state)})

  return flask.render_template(
      'success.html',
      message='Key value saved.')


@app.route('/directives', methods=['POST'])
def directives_page():
  """Save a key value pair."""
  login_token = flask.request.values.get('login_token', '')
  if not is_valid_user(login_token):
    return 'login_token invalid', 400

  username = get_username(login_token)
  assert username

  db = firestore.Client()

  doc_ref = db.document(f'collector/apk')
  doc_data = doc_ref.get()
  if doc_data.exists:
    apk_data = doc_data.to_dict()
  else:
    apk_data = dict()

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
      key=lambda x: (x.get('id', ''), x.get('op', ''), x.get('value', '')))
  return flask.jsonify({'directives': directives, 'apk': apk_data}), 200


@app.route('/directive_completed', methods=['POST'])
def directive_completed():
  """Save a key value pair."""
  login_token = flask.request.values.get('login_token', '')
  directive_id = flask.request.values.get('id', '')
  timestamp = flask.request.values.get('timestamp', '')
  app_version = flask.request.values.get('app_version', 'unknown')
  if not is_valid_user(login_token):
    return 'login_token invalid', 400
  if not directive_id:
    return 'no directive id provided', 400
  if not timestamp:
    return 'timestamp must be provided', 400

  username = get_username(login_token)
  assert username

  db = firestore.Client()
  doc_ref = db.document(
      f'collector/users/{username}/data/directive/{directive_id}')
  doc_data = doc_ref.get()
  if not doc_data.exists:
    return 'directive not found.', 404
  doc_dict = doc_data.to_dict()
  server_timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
  doc_dict['completedClientTimestamp'] = timestamp
  doc_dict['completedServerTimestamp'] = server_timestamp
  doc_dict['appVersion'] = app_version
  doc_ref.update(
      doc_dict, option=db.write_option(last_update_time=doc_data.update_time))
  return flask.jsonify({'updated:': True}), 200


if not IS_PROD_ENV:
  @app.route('/env', methods=['GET', 'POST'])
  def env_page():
    """The env page (only available in dev mode."""
    output = []
    output.append('')
    for k, v in os.environ.items():
      output.append(f'{k}={v}')
    return '\n'.join(output), 200, {
        'Content-Type': 'text/plain; charset=utf-8'}


initialize_app()

if __name__ == '__main__':
  # This is used when running locally only. When deploying to Google App
  # Engine, a webserver process such as Gunicorn will serve the app. This
  # can be configured by adding an `entrypoint` to app.yaml.
  app.run(host='127.0.0.1', port=8080, debug=True)

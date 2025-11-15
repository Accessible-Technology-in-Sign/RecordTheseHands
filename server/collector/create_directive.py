#!/usr/bin/env python3

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
"""Script to create a directive for a user.."""

import datetime
import json
import os
import pathlib
import secrets
import sys

from google.cloud import firestore
import token_maker

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'


def delete_user(username):
  """delete the given user."""
  db = firestore.Client()
  c_ref = db.collection(f'collector/users/{username}')
  delete_collection_recursive(c_ref)


def delete_document_recursive(doc_ref):
  for c_ref in doc_ref.collections():
    delete_collection_recursive(c_ref)
  doc_ref.delete()


def delete_collection_recursive(c_ref):
  for doc_ref in c_ref.list_documents():
    delete_document_recursive(doc_ref)


def print_directives(username):
  """Print all the directives for the given user."""
  db = firestore.Client()
  c_ref = db.collection(f'collector/users/{username}/data/directive')
  max_sequence_number = -1
  directives = list()
  for doc in c_ref.stream():
    doc_dict = doc.to_dict()
    directives.append(doc_dict)
  print(json.dumps(directives, indent=2))


def create_directive(username, op, value):
  """Create a directive for the given user."""
  db = firestore.Client()
  c_ref = db.collection(f'collector/users/{username}/data/directive')
  max_sequence_number = -1
  directives = list()
  for doc in c_ref.stream():
    doc_dict = doc.to_dict()
    directives.append(doc_dict)
    max_sequence_number = max(max_sequence_number, int(doc_dict.get('id')))
  directive_id = str(max_sequence_number + 1)

  directives.sort(
      key=lambda x: (
          int(x.get('id', '-1')),
          x.get('op', ''),
          x.get('value', ''),
      )
  )

  print(json.dumps(directives, indent=2))

  directive = dict()
  directive['id'] = directive_id
  directive['op'] = op
  directive['value'] = value
  directive['creationTimestamp'] = datetime.datetime.now(
      datetime.timezone.utc
  ).isoformat()

  print('Adding directive:')
  print(json.dumps(directive, indent=2))
  doc_ref = db.document(
      f'collector/users/{username}/data/directive/{directive_id}'
  )
  doc_ref.set(directive)


def cancel_directive(username, directive_id):
  db = firestore.Client()
  directive_id = int(directive_id)
  doc_ref = db.document(
      f'collector/users/{username}/data/directive/{directive_id}'
  )
  doc_data = doc_ref.get()
  if not doc_data.exists:
    print(
        f'Unable to find directive for user {username} with id {directive_id}'
    )
    return False
  doc_dict = doc_data.to_dict()
  assert int(doc_dict['id']) == directive_id
  doc_dict['cancelled'] = datetime.datetime.now(
      datetime.timezone.utc
  ).isoformat()
  doc_ref.update(
      doc_dict, option=db.write_option(last_update_time=doc_data.update_time)
  )
  print(f'Cancelled directive for user {username} with id {directive_id}')
  return True


def main():
  """The main function."""
  if len(sys.argv) < 3:
    print(
        'USAGE: USERNAME OPERATION [ARGS...]',
        file=sys.stderr,
    )
    print(
        '  operations:',
        '    noop',
        '    listUsers',
        '    printDirectives',
        '    setPassword [PASSWORD]',
        '    setVersionRange MIN_APP_VERSION MAX_APP_VERSION',
        '    changeUser NEW_USERNAME NEW_PASSWORD',
        '    setPrompts PROMPT_FILE_PATH',
        '    setPromptsNoUpload PROMPT_FILE_PATH',
        '    deleteFile FILE_PATH',
        '    uploadState',
        '    unregisterLostFiles',
        '    setTutorialMode [true|false]',
        '    cancel DIRECTIVE_ID',
        '    deleteUser',
        sep='\n      ',
        file=sys.stderr,
    )
    sys.exit(1)
  username = sys.argv[1]
  print(f'Using username: {username}')
  if sys.argv[2] == 'noop':
    create_directive(sys.argv[1], sys.argv[2], '{}')

  elif sys.argv[2] == 'printDirectives':
    print_directives(sys.argv[1])
  elif sys.argv[2] == 'listUsers':
    db = firestore.Client()
    doc_ref = db.document(f'collector/users')
    for c_ref in doc_ref.collections():
      print(c_ref.id)
  elif sys.argv[2] == 'setPassword':
    if (len(sys.argv) >= 5):
      password = sys.argv[4]
    else:
      password = secrets.token_hex(16)
      print(f'Randomly generated password is: {password!r}')
    login_token, login_hash = token_maker.make_token(username, password)
    output = {'loginToken': login_token}
    print(json.dumps(output, indent=2))
    db = firestore.Client()
    doc_ref = db.document(f'collector/users/{username}/login_hash')
    doc_ref.set({'login_hash': login_hash})
  elif sys.argv[2] == 'changeUser':
    new_username = sys.argv[3]
    if (len(sys.argv) >= 5):
      password = sys.argv[4]
    else:
      password = secrets.token_hex(16)
      print(f'Randomly generated password is: {password!r}')
    login_token, login_hash = token_maker.make_token(new_username, password)
    output = {'loginToken': login_token}
    create_directive(sys.argv[1], sys.argv[2], json.dumps(output))
    db = firestore.Client()
    doc_ref = db.document(f'collector/users/{new_username}/login_hash')
    doc_ref.set({'login_hash': login_hash})
  elif sys.argv[2] == 'setPrompts' or sys.argv[2] == 'setPromptsNoUpload':
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    path = pathlib.Path(sys.argv[3])
    uploaded_relative_path = str(pathlib.Path('prompts', path.name))
    if sys.argv[2] == 'setPrompts':
      if not path.exists():
        raise ValueError(f'Prompt file not found: {path}')
      print(
          f'Upload the prompt file using\n'
          f'  gsutil cp {path} gs://{BUCKET_NAME}/prompts/'
      )
      input('Press enter when file is uploaded> ')
    if sys.argv[2] == 'setPromptsNoUpload':
      if uploaded_relative_path != sys.argv[3]:
        print(
            f'Ignoring directory and setting path to {uploaded_relative_path}'
        )
    prompts_data = {
        'path': uploaded_relative_path,
        'creationTimestamp': timestamp,
    }
    db = firestore.Client()
    doc_ref = db.document(f'collector/users/{username}/data/prompts/active')
    doc_ref.set(prompts_data)
    doc_ref = db.document(
        f'collector/users/{username}/data/prompts/all/all/{timestamp}'
    )
    doc_ref.set(prompts_data)
    print('THIS WILL NOT RELOAD THE PROMPTS, USE reloadPrompts FOR THAT.')
  elif sys.argv[2] == 'setVersionRange':
    min_version = sys.argv[3]
    max_version = sys.argv[4]
    if min_version.lower() == 'none':
      min_version = None
    if max_version.lower() == 'none':
      max_version = None

    version_constraints = dict()

    if min_version:
      version_constraints['min_version'] = min_version
    if max_version:
      version_constraints['max_version'] = max_version

    db = firestore.Client()
    doc_ref = db.document(
        f'collector/users/{username}/data/prompts/version_constraints')
    doc_ref.set(version_constraints)
  elif sys.argv[2] == 'reloadPrompts':
    create_directive(sys.argv[1], sys.argv[2], '{}')
  elif sys.argv[2] == 'deleteFile':
    output = {'filepath': sys.argv[3]}
    create_directive(sys.argv[1], sys.argv[2], json.dumps(output))
  elif sys.argv[2] == 'deleteFile':
    output = {'filepath': sys.argv[3]}
    create_directive(sys.argv[1], sys.argv[2], json.dumps(output))
  elif sys.argv[2] == 'uploadState':
    create_directive(sys.argv[1], sys.argv[2], '{}')
  elif sys.argv[2] == 'setTutorialMode':
    output = {'tutorialMode': sys.argv[3].lower() in ['1', 't', 'true']}
    create_directive(sys.argv[1], sys.argv[2], json.dumps(output))
  elif sys.argv[2] == 'unregisterLostFiles':
    create_directive(sys.argv[1], sys.argv[2], '{}')
  elif sys.argv[2] == 'cancel':
    directive_id = int(sys.argv[3])
    cancel_directive(sys.argv[1], directive_id)
  elif sys.argv[2] == 'deleteUser':
    delete_user(sys.argv[1])
  else:
    raise AssertionError(
        f'Unknown Operation {sys.argv[2]}. Full command line: '
        + ' '.join(sys.argv)
    )


if __name__ == '__main__':
  main()

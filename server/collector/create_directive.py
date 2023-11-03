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
import sys

from google.cloud import firestore

import token_maker

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'


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
      key=lambda x: (int(x.get('id','-1')), x.get('op',''), x.get('value','')))

  print(json.dumps(directives, indent=2))

  directive = dict()
  directive['id'] = directive_id
  directive['op'] = op
  directive['value'] = value
  directive['creationTimestamp'] = datetime.datetime.now(
      datetime.timezone.utc).isoformat()

  print('Adding directive:')
  print(json.dumps(directive, indent=2))
  doc_ref = db.document(
      f'collector/users/{username}/data/directive/{directive_id}')
  doc_ref.set(directive)


def cancel_directive(username, directive_id):
  db = firestore.Client()
  directive_id = int(directive_id)
  doc_ref = db.document(
      f'collector/users/{username}/data/directive/{directive_id}')
  doc_data = doc_ref.get()
  if not doc_data.exists:
    print(
        f'Unable to find directive for user {username} with id {directive_id}')
    return False
  doc_dict = doc_data.to_dict()
  assert int(doc_dict['id']) == directive_id
  doc_dict['cancelled'] = datetime.datetime.now(
      datetime.timezone.utc).isoformat()
  doc_ref.update(
      doc_dict, option=db.write_option(last_update_time=doc_data.update_time))
  print(f'Cancelled directive for user {username} with id {directive_id}')
  return True


if __name__ == '__main__':
  username = sys.argv[1]
  print(f'Using username: {username}')
  if sys.argv[2] == 'noop':
    create_directive(sys.argv[1], sys.argv[2], '{}')
  elif sys.argv[2] == 'changeUser':
    new_username = sys.argv[3]
    login_token, login_hash = token_maker.make_token(sys.argv[3], sys.argv[4])
    output = {
      'loginToken': login_token
    }
    create_directive(sys.argv[1], sys.argv[2], json.dumps(output))
    db = firestore.Client()
    doc_ref = db.document(f'collector/users/{new_username}/login_hash')
    doc_ref.set({'login_hash': login_hash})
  elif sys.argv[2] == 'updateApk':
    create_directive(sys.argv[1], sys.argv[2], '{}')
  elif sys.argv[2] == 'downloadPrompts':
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    prompts_data = {
      'path': sys.argv[3],
      'creationTimestamp': timestamp,
    }
    db = firestore.Client()
    doc_ref = db.document(
        f'collector/users/{username}/data/prompts/active')
    doc_ref.set(prompts_data)
    doc_ref = db.document(
        f'collector/users/{username}/data/prompts/all/all/{timestamp}')
    doc_ref.set(prompts_data)
    create_directive(sys.argv[1], sys.argv[2], '{}')
  elif sys.argv[2] == 'deleteFile':
    output = {
      'filepath': sys.argv[3]
    }
    create_directive(sys.argv[1], sys.argv[2], json.dumps(output))
  elif sys.argv[2] == 'uploadState':
    create_directive(sys.argv[1], sys.argv[2], '{}')
  elif sys.argv[2] == 'setTutorialMode':
    output = {
      'tutorialMode': sys.argv[3].lower() in ['1', 't', 'true']
    }
    create_directive(sys.argv[1], sys.argv[2], json.dumps(output))
  elif sys.argv[2] == 'cancel':
    directive_id = int(sys.argv[3])
    cancel_directive(sys.argv[1], directive_id)
  elif sys.argv[2] == 'getState':
    print_state(sys.argv[1])
  else:
    raise AssertionError("Did not understand arguments: " + " ".join(sys.argv))



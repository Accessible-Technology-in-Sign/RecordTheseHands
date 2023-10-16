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
import subprocess
import sys

from google.cloud import firestore

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'


if __name__ == '__main__':
  local_filepath = sys.argv[1]
  print(f'phrases: {local_filepath}')
  db = firestore.Client()
  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
  phrases = list()
  with open(local_filepath, 'r') as f:
    for line in f:
      line = line.strip()
      phrases.append({'key': line, 'prompt': line})

  path = f'phrases/phrases-{timestamp}.json'
  data = {
      'phrases': phrases,
      'path': path,
      'timestamp': timestamp,
  }
  doc_ref = db.document(f'collector/phrases/all/{timestamp}')
  doc_ref.set(data)
  doc_ref = db.document('collector/phrases')
  doc_ref.set(data)
  print(json.dumps(data, indent=2))
  local_tempfile = pathlib.Path(f'/tmp/{path}')
  local_tempfile.parent.mkdir(parents=True, exist_ok=True)
  with local_tempfile.open('w') as f:
    f.write(json.dumps(data, indent=2))
    f.write('\n')
    
  command = ['gsutil', 'cp', str(local_tempfile),
             f'gs://{BUCKET_NAME}/{path}']
  print(' '.join(command))
  p = subprocess.Popen(command)
  p.communicate()
  assert p.returncode == 0, (
      'upload of phrases FAILED, do not send update operation to phones!')
  print('Upload succeeded, send phrases to phone with')
  print(f'create_directive.py <username> downloadPhrases {path}')



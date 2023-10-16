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
import hashlib
import json
import os
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
  print(f'apk: {local_filepath}')
  db = firestore.Client()
  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
  with open(local_filepath, 'rb') as f:
    digest = hashlib.file_digest(f, 'md5')
  md5 = digest.hexdigest()

  filename = f'{md5}.apk'
  apk_data = {
      'md5': md5,
      'filename': filename,
      'timestamp': timestamp,
  }
  doc_ref = db.document(f'collector/apk/all/{md5}')
  doc_ref.set(apk_data)
  doc_ref = db.document('collector/apk')
  doc_ref.set(apk_data)
  print(json.dumps(apk_data, indent=2))
  command = ['gsutil', 'cp', local_filepath,
             f'gs://{BUCKET_NAME}/apk/{filename}']
  print(' '.join(command))
  p = subprocess.Popen(command)
  p.communicate()
  assert p.returncode == 0, (
      'upload of apk FAILED, do not send update operation to phones!')



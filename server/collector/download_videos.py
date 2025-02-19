#!/usr/bin/env python3

# Copyright 2025 Google LLC
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
"""Script to download videos from the Google Cloud Storage Bucket"""

import csv
import datetime
import json
import os
import re

import google.api_core.exceptions
#from google.cloud.storage import Client, transfer_manager
from google.cloud import firestore

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'

# Match these accounts (with a prefix of test)
_MATCH_USERS = re.compile(r'^test\d{3}$')
# stem name of the output files (json and csv).
_DUMP_ID = 'hash_dump'

# mismatch between firestore + bucket?
# compare md5 hashes

def get_md_hash(username):
  """Obtain the clip and session data from firestore."""
  db = firestore.Client()
  c_ref = db.collection(f'collector/users/{username}/data/file')
  hashes = list()
  for doc_data in c_ref.stream():
    if doc_data.id.startswith(username):
      doc_dict = doc_data.to_dict()
      hash = doc_dict.get('md5')
      assert hash
      hashes.append(hash)

  return (hashes)

def main():
  db = firestore.Client()
  doc_ref = db.document('collector/users')
  all_hashes = list()
  for c_ref in doc_ref.collections():
    m = _MATCH_USERS.match(c_ref.id)
    if not m:
      continue
    retry = True
    print(f"Getting data for user: {c_ref.id}")
    while retry:
      retry = False
      try:
        hashes = get_md_hash(c_ref.id)
        print(f'{c_ref.id} {len(hashes)}')
        all_hashes.extend(hashes)
      except google.api_core.exceptions.RetryError:
        print('timed out, retrying')
        retry = True
  with open(f'{_DUMP_ID}.json', 'w') as f:
    f.write(json.dumps({
        'hashes': all_hashes}, indent=2))
    f.write('\n')
  # Create a csv file for import into Google internal Clipping system.
  csv_rows = list()
  for hash in all_hashes:
    
    row = [
        hash
    ]
    csv_rows.append(row)
  if csv_rows:
    csv_path = f'{_DUMP_ID}.csv'
    print(f'Writing csv to {csv_path}')
    with open(csv_path, 'w', newline='') as csvfile:
      writer = csv.writer(csvfile)
      writer.writerows(csv_rows)

if __name__ == '__main__':
  main()

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

import warnings
warnings.filterwarnings("ignore", category=UserWarning, message=".*transfer_manager.*")

import os
import re

import google.api_core.exceptions
from google.cloud.storage import Client, transfer_manager
from google.cloud import firestore
from utils import compute_md5

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'

# Match these accounts (with a prefix of test)
_MATCH_USERS = re.compile(r'^test\d{3}$')
# stem name of the output video files
_DUMP_ID = 'video_dump'

def get_video_metadata(db, username):
  """Obtain the video hash and path metadata from firestore."""
  c_ref = db.collection(f'collector/users/{username}/data/file')
  hashes = []
  paths = []
  for doc_data in c_ref.stream():
    if doc_data.id.startswith(username):
      doc_dict = doc_data.to_dict()
      hash = doc_dict.get('md5')
      path = doc_dict.get('path')
      
      if not hash or not path:
        print(f"Skipping invalid data under {doc_data.id}")
        continue

      path = f"upload/{username}/{path}"

      if os.path.exists(f'{_DUMP_ID}/{path}'):
        print(f"Skipping already downloaded file: {_DUMP_ID}/{path}")
        continue
      
      hashes.append(hash)
      paths.append(path)
  
  return hashes, paths

def download_all_videos(bucket_name, blob_names, destination_directory="", workers=8):
    """Download blobs in a list by name, concurrently in a process pool."""
    storage_client = Client()
    bucket = storage_client.bucket(bucket_name)

    results = transfer_manager.download_many_to_path(
        bucket, blob_names, destination_directory=destination_directory, max_workers=workers
    )

    for name, result in zip(blob_names, results):
        if isinstance(result, Exception):
            print("Failed to download {} due to exception: {}".format(name, result))
        else:
            print(f"Downloaded {name} to {destination_directory + name}.")

def main():
  print("Getting metadata from firestore")
  db = firestore.Client()
  doc_ref = db.document('collector/users')
  all_hashes = []
  all_paths = []
  for c_ref in doc_ref.collections():
    m = _MATCH_USERS.match(c_ref.id)
    if not m:
      continue
    retry = True
    print(f"Getting data for user: {c_ref.id}")
    while retry:
      retry = False
      try:
        hashes, paths = get_video_metadata(db, c_ref.id)
        print(f'{c_ref.id} {len(hashes)}')
        all_hashes.extend(hashes)
        all_paths.extend(paths)
      except google.api_core.exceptions.RetryError:
        print('timed out, retrying')
        retry = True

  
  print(f"\nStarting download for {len(all_paths)} videos")
  download_all_videos(BUCKET_NAME, all_paths, f'{_DUMP_ID}/')
  print('Done downloading videos')
  
  print('\nValidating videos')
  for (hash, path) in zip(all_hashes, all_paths): # Should we parallelize this?
    file_path = f'{_DUMP_ID}/{path}'
    if compute_md5(file_path) != hash:
      print(f'File {file_path} failed validation')
      os.remove(file_path)
      print(f'Deleted {file_path}')
    else:
      print(f'File {file_path} passed validation')

if __name__ == '__main__':
  main()

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
"""Script to download videos from the Google Cloud Storage Bucket."""

import json
import os
import sys
import warnings

import constants
from google.cloud import storage
from google.cloud.storage import transfer_manager
import utils

warnings.filterwarnings(
    'ignore', category=UserWarning, message='.*transfer_manager.*'
)

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'


def get_video_metadata_from_json(username, data_doc):
  """Obtain video metadata from user JSON."""
  file_collection = utils.get_in_document(data_doc, 'file')
  hashes = []
  paths = []
  if not file_collection:
    return hashes, paths

  for doc in file_collection:
    doc_id = doc.get('id', '')
    if doc_id.startswith(username):
      data = doc.get('data', {})
      file_hash = data.get('md5')
      path = data.get('path')

      if not file_hash or not path:
        print(f'Skipping invalid data under {doc_id}')
        continue

      path = f'upload/{username}/{path}'

      if os.path.exists(f'{constants.VIDEO_DUMP_ID}/{path}'):
        print(
            f'Skipping already downloaded file: '
            f'{constants.VIDEO_DUMP_ID}/{path}'
        )
        continue

      hashes.append(file_hash)
      paths.append(path)

  return hashes, paths


def download_all_videos(
    bucket_name, blob_names, destination_directory='', workers=8
):
  """Download blobs in a list by name, concurrently in a process pool."""
  storage_client = storage.Client()
  bucket = storage_client.bucket(bucket_name)

  results = transfer_manager.download_many_to_path(
      bucket,
      blob_names,
      destination_directory=destination_directory,
      max_workers=workers,
  )

  for name, result in zip(blob_names, results):
    if isinstance(result, Exception):
      print('Failed to download {} due to exception: {}'.format(name, result))
    else:
      print(f'Downloaded {name} to {destination_directory + name}.')


def main(db_dump_path):
  if not db_dump_path:
    raise ValueError('A database dump JSON path must be provided.')

  all_hashes = []
  all_paths = []

  with open(db_dump_path, 'r') as f:
    db_data = json.load(f)

  users_doc = utils.get_in_document(db_data, 'collector/users')

  if not users_doc or 'collection' not in users_doc:
    print('No users found in database dump.')
    return

  for username, user_collection in users_doc['collection'].items():
    if not constants.MATCH_USERS.match(username):
      continue
    print(f'Getting data for user: {username}')

    data_doc = utils.get_in_collection(user_collection, 'data')

    if not data_doc:
      print(f'No data document found for user {username}')
      continue

    hashes, paths = get_video_metadata_from_json(username, data_doc)
    print(f'{username} {len(hashes)}')
    all_hashes.extend(hashes)
    all_paths.extend(paths)

  print(f'\nStarting download for {len(all_paths)} videos')
  download_all_videos(BUCKET_NAME, all_paths, f'{constants.VIDEO_DUMP_ID}/')
  print('Done downloading videos')

  print('\nValidating videos')
  for file_hash, path in zip(all_hashes, all_paths):
    file_path = f'{constants.VIDEO_DUMP_ID}/{path}'
    if utils.compute_md5(file_path) != file_hash:
      print(f'File {file_path} failed validation')
      os.remove(file_path)
      print(f'Deleted {file_path}')
    else:
      print(f'File {file_path} passed validation')


if __name__ == '__main__':
  if len(sys.argv) < 2:
    print('Usage: python download_videos.py DB_DUMP_PATH')
    sys.exit(1)
  main(sys.argv[1])

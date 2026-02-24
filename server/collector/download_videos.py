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

import argparse
import json
import os
import pathlib
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


def get_video_metadata_from_json(username, data_doc, video_dump_dir):
  """Obtain video metadata from user JSON.

  Args:
    username: The username to filter by.
    data_doc: The data document containing file collection.
    video_dump_dir: The directory where videos are stored locally.

  Returns:
    A tuple containing lists of (hashes, blob_names, exists).
  """
  file_collection = utils.get_in_document(data_doc, 'file')
  hashes = []
  blob_names = []
  exists = []
  if not file_collection:
    return hashes, blob_names, exists

  for doc in file_collection:
    doc_id = doc.get('id', '')
    if doc_id.startswith(username):
      data = doc.get('data', {})
      file_hash = data.get('md5')
      path = data.get('path')

      if not file_hash or not path:
        print(f'Skipping invalid data under {doc_id}')
        continue

      blob_name = pathlib.Path('upload') / username / path
      path = pathlib.Path(video_dump_dir) / blob_name

      hashes.append(file_hash)
      blob_names.append(str(blob_name))
      exists.append(path.exists())

  return hashes, blob_names, exists


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


def main(db_dump_path, video_dump_dir):
  """Main entry point for downloading videos.

  Args:
    db_dump_path: Path to the database dump JSON file.
    video_dump_dir: Directory where videos will be downloaded.

  Raises:
    FileNotFoundError: If db_dump_path does not exist.
  """
  db_dump_path = pathlib.Path(db_dump_path)
  video_dump_dir = pathlib.Path(video_dump_dir)

  if not db_dump_path.exists():
    raise FileNotFoundError(
        f'Database dump JSON path not found: {db_dump_path}'
    )

  all_hashes = []
  all_blob_names = []
  all_exists = []

  # Load database dump
  with db_dump_path.open('r') as f:
    db_data = json.load(f)

  users_doc = utils.get_in_document(db_data, 'collector/users')

  if not users_doc or 'collection' not in users_doc:
    print('No users found in database dump.')
    return

  # Iterate through users and collect video metadata
  for username, user_collection in users_doc['collection'].items():
    if not constants.MATCH_USERS.match(username):
      continue
    print(f'Getting data for user: {username}')

    data_doc = utils.get_in_collection(user_collection, 'data')

    if not data_doc:
      print(f'No data document found for user {username}')
      continue

    hashes, blob_names, exists = get_video_metadata_from_json(
        username, data_doc, video_dump_dir
    )
    print(f'{username} {len(hashes)}')
    all_hashes.extend(hashes)
    all_blob_names.extend(blob_names)
    all_exists.extend(exists)

  # Download missing videos
  print(f'\nFound data for {len(all_blob_names)} videos')
  download_blob_names = [x for x, y in zip(all_blob_names, all_exists) if not y]
  print(f'\nStarting download for {len(download_blob_names)} videos')
  download_all_videos(BUCKET_NAME, download_blob_names, f'{video_dump_dir}/')
  print('Done downloading videos')

  # Validate downloaded videos against MD5 hashes
  print('\nValidating videos')
  validated = 0
  for file_hash, blob_name in zip(all_hashes, all_blob_names):
    file_path = video_dump_dir / blob_name
    if utils.compute_md5(str(file_path)) != file_hash:
      print(f'File {file_path} failed validation')
      file_path.unlink(missing_ok=True)
      print(f'Deleted {file_path}')
    else:
      validated += 1
      print(f'File {file_path} passed validation')
  print(
      f'\nValidated {validated} '
      f'videos ({validated - len(all_blob_names)} failed validation).'
  )


if __name__ == '__main__':
  parser = argparse.ArgumentParser(
      description='Download videos from Google Cloud Storage.'
  )
  parser.add_argument(
      'db_dump_path', help='Path to the database dump JSON file.'
  )
  parser.add_argument(
      '--video-dump-dir',
      default=constants.VIDEO_DUMP_ID,
      help='Directory to download videos to.',
  )
  args = parser.parse_args()

  main(args.db_dump_path, args.video_dump_dir)

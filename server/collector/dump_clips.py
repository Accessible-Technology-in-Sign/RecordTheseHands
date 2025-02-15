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

import csv
import datetime
import json
import os
import re

import google.api_core.exceptions
from google.cloud import firestore

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'

# Match these accounts.
_MATCH_USERS = re.compile(r'^test\d{3}$')
# stem name of the output files (json and csv).
_DUMP_ID = 'dump'


def get_data(username):
  """Obtain the clip and session data from firestore."""
  db = firestore.Client()
  c_ref = db.collection(f'collector/users/{username}/data/save_clip')
  clips = list()
  sessions = list()
  for doc_data in c_ref.stream():
    if doc_data.id.startswith('clipData-'):
      doc_dict = doc_data.to_dict()
      data = doc_dict.get('data')
      clip_id = data.get('clipId')
      assert clip_id
      m = re.match(r'[^-]+-s(\d{3})-(\d{3})', clip_id)
      assert m, clip_id
      session_index = int(m.group(1))
      clip_index = int(m.group(2))
      filename = data.get('filename')
      assert filename
      m = re.match(r'^(tutorial-)?(.+[^-]+)-[^-]+-s(\d{3})-.+\.mp4$', filename) # keep tutorial prefixing -- make sure group indices are correct later
      assert m, filename
      tutorial_prefix = m.group(1)
      user_id = m.group(2)
      assert session_index == int(m.group(3)), (clip_id, filename)

      simple_clip = {
          'userId': user_id,
          'sessionIndex': session_index,
          'clipIndex': clip_index,
          'filename': data.get('filename'),
          'promptText': data.get('promptData').get('prompt'),
          'valid': data.get('valid'),
      }
      if tutorial_prefix:
        simple_clip['tutorial'] = True
      start_s, end_s = get_clip_bounds_in_video(data)
      if start_s:
        simple_clip['start_s'] = start_s
      if end_s:
        simple_clip['end_s'] = end_s
      clips.append({'summary': simple_clip, 'full': doc_dict})
    elif doc_data.id.startswith('sessionData-'):
      doc_dict = doc_data.to_dict()
      data = doc_dict.get('data')
      sessions.append(data)
  # clips.sort(key=lambda x: (x.get('filename', ''), x.get('clipId', '')))
  # sessions.sort(key=lambda x: (x.get('filename', ''),))
  return (clips, sessions)


def get_clip_bounds_in_video(clip_data):
  """Determine the clip start and end time based on button pushes and swipes."""
  video_start = clip_data.get('videoStart')
  if not video_start:
    return (None, None)

  clip_start = clip_data.get('startButtonDownTimestamp')
  if not clip_start:
    clip_start = clip_data.get('startButtonUpTimestamp')
  if not clip_start:
    return (None, None)

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


def main():
  db = firestore.Client()
  doc_ref = db.document('collector/users')
  all_clips = list()
  all_sessions = list()
  for c_ref in doc_ref.collections():
    m = _MATCH_USERS.match(c_ref.id)
    if not m:
      continue
    retry = True
    while retry:
      retry = False
      try:
        clips, sessions = get_data(c_ref.id)
        print(f'{c_ref.id} {len(clips)} {len(sessions)}')
        all_clips.extend(clips)
        all_sessions.extend(sessions)
      except google.api_core.exceptions.RetryError:
        print('timed out, retrying')
        retry = True
  all_clips.sort(key=lambda x: (x.get('filename', ''), x.get('clipId', '')))
  all_sessions.sort(key=lambda x: (x.get('filename', ''),))
  with open(f'{_DUMP_ID}.json', 'w') as f:
    f.write(json.dumps({
        'clips': all_clips,
        'sessions': all_sessions}, indent=2))
    f.write('\n')
  # Create a csv file for import into Google internal Clipping system.
  csv_rows = list()
  for clip in all_clips:
    if ('start_s' not in clip['summary'] or
        'end_s' not in clip['summary'] or
        'promptText' not in clip['summary']):
      print('csv conversion failed for:')
      print(json.dumps(clip, indent=2))
      continue
    if not clip['summary']['valid']:
      continue
    row = [
        '',  # unused
        clip['summary']['filename'][:-4],  # Filename without .mp4
        '',  # unused
        '',  # unused
        clip['summary']['start_s'],  # Clip start in s.
        clip['summary']['end_s'],  # Clip end in s.
        clip['summary']['promptText'],  # Phrase
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

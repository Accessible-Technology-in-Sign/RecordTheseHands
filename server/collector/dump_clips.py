#!/usr/bin/env python3

# Copyright 2026 Google LLC
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

"""Script to dump clip metadata from a database dump JSON."""

import argparse
import datetime
import json

import constants
import data_access


def get_data_from_json(data_doc, username=None, username_prefix=''):
  """Obtain clip and session data from a user's JSON data."""
  clips = []
  sessions = []

  # Try multiple possible collection paths for clips and sessions
  save_collection = data_access.get_in_document(data_doc, 'save')
  clip_collection = data_access.get_in_document(data_doc, 'save_clip')
  session_collection = data_access.get_in_document(data_doc, 'save_session')

  raw_entries = []
  if save_collection:
    raw_entries.extend(save_collection)
  if clip_collection:
    raw_entries.extend(clip_collection)
  if session_collection:
    raw_entries.extend(session_collection)

  for doc in raw_entries:
    doc_id = doc.get('id', '')
    if doc_id.startswith('clipData-'):
      data = doc.get('data', {}).get('data')
      if not data:
        continue
      clip_id = data.get('clipId')
      if not clip_id:
        continue

      filename = data.get('filename')
      if not filename:
        continue

      user_id = username or data.get('username') or doc_id.split('-')[0]
      simple_clip = {
          'userId': username_prefix + user_id,
          'clipId': clip_id,
          'sessionId': data.get('sessionId'),
          'filename': filename,
          'promptText': data.get('promptData', {}).get('prompt'),
          'promptId': data.get('promptData', {}).get('promptId'),
          'sectionName': data.get('sectionName'),
          'valid': data.get('valid'),
      }
      if data.get('isSkipExplanation'):
        simple_clip['isSkipExplanation'] = True

      start_s, end_s = get_clip_bounds_in_video(data)
      if start_s is not None:
        simple_clip['startS'] = start_s
      if end_s is not None:
        simple_clip['endS'] = end_s

      clips.append({'summary': simple_clip, 'full': doc})
    elif doc_id.startswith('sessionData-'):
      data = doc.get('data')
      if data:
        sessions.append(data)

  return clips, sessions


def get_clip_bounds_in_video(clip_data):
  """Determine the clip start and end time based on button pushes and swipes."""
  video_start = clip_data.get('videoStart')
  if not video_start:
    return (None, None)

  clip_start = (
      clip_data.get('startTimestamp')
      or clip_data.get('startButtonDownTimestamp')
      or clip_data.get('startButtonUpTimestamp')
  )
  if not clip_start:
    return (None, None)

  clip_end = (
      clip_data.get('endTimestamp')
      or clip_data.get('restartButtonDownTimestamp')
      or clip_data.get('swipeForwardTimestamp')
      or clip_data.get('swipeBackTimestamp')
  )
  if not clip_end:
    return (None, None)

  video_start_time = datetime.datetime.fromisoformat(video_start)
  clip_start_time = datetime.datetime.fromisoformat(clip_start)
  clip_end_time = datetime.datetime.fromisoformat(clip_end)

  start_s = (clip_start_time - video_start_time).total_seconds()
  end_s = (clip_end_time - video_start_time).total_seconds()
  return (start_s, end_s)


def main(db_dump_path, username_prefix=''):
  if not db_dump_path:
    raise ValueError('A database dump JSON path must be provided.')

  all_clips = []
  all_sessions = []

  with open(db_dump_path, 'r') as f:
    db_data = json.load(f)

  # Navigate to the users collection in the dump
  users_doc = data_access.get_in_document(db_data, 'collector/users')

  if not users_doc or 'collection' not in users_doc:
    print('No users found in database dump.')
    return

  for username, user_collection in users_doc['collection'].items():
    if not constants.MATCH_USERS.match(username):
      continue

    print(f'Getting data for user: {username}')
    data_doc = data_access.get_in_collection(user_collection, 'data')

    if not data_doc:
      print(f'No data document found for user {username}')
      continue

    clips, sessions = get_data_from_json(
        data_doc, username=username, username_prefix=username_prefix
    )
    print(f'{username} {len(clips)} {len(sessions)}')
    all_clips.extend(clips)
    all_sessions.extend(sessions)

  all_clips.sort(
      key=lambda x: (
          x['summary'].get('filename', ''),
          x['summary'].get('clipId', ''),
      )
  )

  # Write JSON
  with open(f'{constants.METADATA_DUMP_ID}.json', 'w') as f:
    json.dump({'clips': all_clips, 'sessions': all_sessions}, f, indent=2)
    f.write('\n')


if __name__ == '__main__':
  parser = argparse.ArgumentParser(description='Dump clip metadata.')
  parser.add_argument(
      'db_dump_path', help='Path to the database dump JSON file.'
  )
  parser.add_argument(
      '--username_prefix', default='', help='Prefix for the userId.'
  )
  args = parser.parse_args()
  main(args.db_dump_path, args.username_prefix)

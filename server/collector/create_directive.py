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

import collections
import concurrent.futures
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

USE_FUTURES_DEPTH = 8

db = firestore.Client()


def delete_user(username):
  """delete the given user."""
  c_ref = db.collection(f'collector/users/{username}')
  futures = list()
  with concurrent.futures.ThreadPoolExecutor(max_workers=200) as executor:
    print('Sending all deletion requests.')
    delete_collection_recursive(c_ref, executor, futures)
    print('Waiting for all requests to complete.')
    for future in futures:
      future.result()
    print('All requests completed.')


def delete_document_recursive(doc_ref, executor, futures):
  for c_ref in doc_ref.collections():
    delete_collection_recursive(c_ref, executor, futures)
  futures.append(executor.submit(doc_ref.delete))


def delete_collection_recursive(c_ref, executor, futures):
  for doc_ref in c_ref.list_documents():
    delete_document_recursive(doc_ref, executor, futures)


def save_user_data(username, output_filename):
  """Save the given user to json."""
  c_ref = db.collection(f'collector/users/{username}')
  with concurrent.futures.ThreadPoolExecutor(max_workers=20) as tree_executor:
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as leaf_executor:
      c_id, c_data = save_collection_recursive(
          c_ref,
          print_depth=3,
          tree_executor=tree_executor,
          leaf_executor=leaf_executor,
      )
      extract_futures_collection_recursive(c_data)
  with open(output_filename, 'w') as f:
    f.write(json.dumps({c_id: c_data}, indent=2))
    f.write('\n')


def save_database(output_filename, print_depth=10):
  """Save the entire database to json."""
  with concurrent.futures.ThreadPoolExecutor(max_workers=20) as tree_executor:
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=200
    ) as leaf_executor:
      data = save_document_recursive(
          db,
          print_depth=print_depth,
          tree_executor=tree_executor,
          leaf_executor=leaf_executor,
      )
      extract_futures_document_recursive(data)
  with open(output_filename, 'w') as f:
    f.write(json.dumps(data, indent=2))
    f.write('\n')


def restore_user(username, database_filename):
  """Restore a user from a database save."""
  with open(database_filename, 'r') as f:
    data = json.loads(f.read())
  user = None
  for entry in data['collection']['collector']:
    if entry['id'] == 'users':
      user = entry['collection'][username]
  if user:
    write_collection_to_firestore(f'collector/users/{username}', user)


def write_collection_to_firestore(prefix, data):
  print(f'Writing collection {prefix}')
  for entry in data:
    doc_id = f'{prefix}/{entry['id']}'
    if 'data' in entry:
      doc_ref = db.document(doc_id)
      doc_ref.set(entry['data'])
    if 'collection' in entry:
      for collection_id, collection in entry['collection'].items():
        write_collection_to_firestore(f'{doc_id}/{collection_id}', collection)


def get_document_in_collection(collection, doc_id):
  for entry in collection:
    if entry['id'] == 'doc_id':
      return entry
  return None


def get_in_collection(collection, path):
  """Use a path to get something in a collection.

  If the path has an odd number of parts it is a document,
  if even then it is a collection.
  """
  path_parts = path.split('/')
  current = collection
  for i in range(len(path_parts)):
    ident = path_parts[i]
    if i % 2 == 0:
      found = False
      for entry in current:
        if entry['id'] == ident:
          current = entry
          found = True
      if not found:
        return None
    else:
      if 'collection' not in current:
        return None
      current = current['collection'].get(ident)
      if current is None:
        return None
  return current


def get_in_document(document, path):
  """Use a path to get something in a document.

  If the path has an even number of parts it is a document,
  if odd then it is a collection.
  """
  if not path:
    return None
  path_parts = path.split('/')
  if 'collection' not in document:
    return None
  current = document['collection'].get(path_parts[0])
  if current is None:
    return None
  if len(path_parts) == 1:
    return current
  return get_in_collection(current, '/'.join(path_parts[1:]))


def statistics(username, filename, output_filename=None):
  """compute statistics based on database or user dump."""
  with open(filename, 'r') as f:
    data = json.loads(f.read())
  user = None
  if data.get('id') == '/' and 'collection' in data:
    # We have a database dump.
    user = get_in_document(data, f'collector/users/{username}')
  else:
    # We have a user dump.
    user = data.get(username)

  if not user:
    return None

  statistics = dict()
  files = get_in_collection(user, 'data/file')
  for doc in files:
    assert 'data' in doc
    path = doc['data'].get('path')
    if 'path' not in statistics:
      statistics['path'] = list()
    statistics['path'].append(path)
  heartbeat = get_in_collection(user, 'data/heartbeat/latest')
  if heartbeat:
    statistics['heartbeat'] = heartbeat['data']['timestamp']
  max_prompt = get_in_collection(user, 'data/heartbeat/max_prompt')
  if max_prompt:
    if 'maxPrompt' in max_prompt['data']:
      max_prompt['data']['all'] = max_prompt['data']['maxPrompt']
      max_prompt['data'].pop('maxPrompt')
    statistics['progress'] = max_prompt['data']
  prompts = get_in_collection(user, 'data/prompts/active')
  if prompts:
    statistics['promptsPath'] = prompts['data'].get('path')
  version_constraints = get_in_collection(
      user, 'data/prompts/version_constraints'
  )
  if version_constraints:
    statistics['versionConstraints'] = version_constraints['data']

  def fill_clip_stats(clips, clip_stats):
    if not clips:
      return
    for clip in clips:
      assert clip.get('id').startswith('clipData-')
      data = clip['data']['data']
      start = datetime.datetime.fromisoformat(data['startTimestamp'])
      end = datetime.datetime.fromisoformat(data['endTimestamp'])
      duration = (end - start).total_seconds()
      section_name = data['sectionName']
      # TODO(mgeorg): track bad prompts, track duplicate prompts, and track
      # total prompts answered.
      valid_str = 'valid' if data.get('valid') else 'invalid'
      if data.get('isSkipExplanation', False) == True:
        clip_stats['all'][f'num_{valid_str}_skip_explanation'] += 1
        clip_stats['all'][f'duration_{valid_str}_skip_explanation'] += duration
        clip_stats[section_name][f'num_{valid_str}_skip_explanation'] += 1
        clip_stats[section_name][
            f'duration_{valid_str}_skip_explanation'
        ] += duration
      else:
        clip_stats['all'][f'num_{valid_str}_clips'] += 1
        clip_stats['all'][f'duration_{valid_str}_clips'] += duration
        clip_stats[section_name][f'num_{valid_str}_clips'] += 1
        clip_stats[section_name][f'duration_{valid_str}_clips'] += duration

    for section_name, stats in clip_stats.items():
      num_valid_clips = stats.get('num_valid_clips')
      valid_duration = stats.get('duration_valid_clips')
      if num_valid_clips is not None and valid_duration is not None:
        stats['average_valid_clip_duration'] = valid_duration / num_valid_clips
      num_valid_clips = stats.get('num_valid_skip_explanation')
      valid_duration = stats.get('duration_valid_skip_explanation')
      if num_valid_clips is not None and valid_duration is not None:
        stats['average_skip_explanation_duration'] = (
            valid_duration / num_valid_clips
        )

  clips = get_in_collection(user, 'data/save_clip')
  clip_stats = collections.defaultdict(lambda: collections.defaultdict(int))
  fill_clip_stats(clips, clip_stats)

  tutorial_clips = get_in_collection(user, 'tutorial_data/save_clip')
  tutorial_clip_stats = collections.defaultdict(
      lambda: collections.defaultdict(int)
  )
  fill_clip_stats(tutorial_clips, tutorial_clip_stats)

  # TODO(mgeorg): count 'HomeScreenActivity.onCreate' log messages
  # (remember they might be in tutorial mode or regular mode).

  total_duration_in_video = 0.0

  def fill_session_stats(sessions, session_stats):
    if not sessions:
      return
    for session in sessions:
      data = session['data']['data']
      start = datetime.datetime.fromisoformat(data['startTimestamp'])
      end = datetime.datetime.fromisoformat(data['endTimestamp'])
      duration = (end - start).total_seconds()
      section_name = data['sectionName']

      session_stats['all']['num_sessions'] += 1
      session_stats['all']['duration_sessions'] += duration
      session_stats[section_name]['num_sessions'] += 1
      session_stats[section_name]['duration_sessions'] += duration

  sessions = get_in_collection(user, 'data/save_session')
  session_stats = collections.defaultdict(lambda: collections.defaultdict(int))
  fill_session_stats(sessions, session_stats)
  if 'progress' in statistics:
    for section_name, stats in session_stats.items():
      progress = statistics['progress'].get(section_name)
      if progress:
        stats['progress'] = progress

  tutorial_sessions = get_in_collection(user, 'tutorial_data/save_session')
  tutorial_session_stats = collections.defaultdict(
      lambda: collections.defaultdict(int)
  )
  fill_session_stats(tutorial_sessions, tutorial_session_stats)

  statistics['tutorial_clip_stats'] = dict(sorted(tutorial_clip_stats.items()))
  statistics['tutorial_session_stats'] = dict(
      sorted(tutorial_session_stats.items())
  )
  statistics['clip_stats'] = dict(sorted(clip_stats.items()))
  statistics['session_stats'] = dict(sorted(session_stats.items()))

  quick_summary = dict()
  quick_summary['username'] = username
  try:
    quick_summary['num_valid_clips'] = statistics['clip_stats']['all'][
        'num_valid_clips'
    ]
  except KeyError:
    pass
  try:
    quick_summary['num_invalid_clips'] = statistics['clip_stats']['all'][
        'num_invalid_clips'
    ]
  except KeyError:
    pass
  try:
    quick_summary['num_bad_prompts'] = statistics['clip_stats']['all'][
        'num_valid_skip_explanation'
    ]
  except KeyError:
    pass
  try:
    quick_summary['num_sessions'] = statistics['session_stats']['all'][
        'num_sessions'
    ]
  except KeyError:
    pass
  try:
    quick_summary['duration_sessions'] = (
        f'{round(statistics['session_stats']['all']['duration_sessions'] / 60 / 60, 2)} hours'
    )
  except KeyError:
    pass
  try:
    quick_summary['duration_clips'] = (
        f'{round(statistics['clip_stats']['all']['duration_valid_clips'] / 60 / 60, 2)} hours'
    )
  except KeyError:
    pass
  try:
    quick_summary['tutorial_duration_sessions'] = (
        f'{round(statistics['tutorial_session_stats']['all']['duration_sessions'] / 60 / 60, 2)} hours'
    )
  except KeyError:
    pass
  try:
    quick_summary['tutorial_duration_clips'] = (
        f'{round(statistics['tutorial_clip_stats']['all']['duration_valid_clips'] / 60 / 60, 2)} hours'
    )
  except KeyError:
    pass

  statistics['summary'] = quick_summary

  print(json.dumps(statistics, indent=2))
  if output_filename and output_filename != filename:
    with open(output_filename, 'w') as f:
      f.write(json.dumps(statistics, indent=2))
      f.write('\n')


def get_document_data(doc_ref):
  doc_data = doc_ref.get()
  if doc_data.exists:
    return doc_data.to_dict()


def extract_futures_document_recursive(data):
  if 'data' in data:
    data['data'] = data['data'].result()
  if 'collection' in data:
    for _, c_data in data['collection'].items():
      extract_futures_collection_recursive(c_data)


def extract_futures_collection_recursive(data):
  for entry in data:
    extract_futures_document_recursive(entry)


def save_document_recursive(
    doc_ref,
    print_depth=0,
    print_prefix=None,
    tree_executor=None,
    leaf_executor=None,
):
  """Recursively saves a document and its subcollections."""
  data = dict()
  if isinstance(doc_ref, firestore.Client):
    doc_id = '/'
  else:
    doc_id = doc_ref.id

  data['id'] = doc_id
  if print_prefix is not None:
    print_prefix = f'{print_prefix}/{doc_id}'
  else:
    print_prefix = doc_id

  if isinstance(doc_ref, firestore.Client):
    print_prefix = ''
    if print_depth:
      print('/')
  else:
    if print_depth:
      print(f'{print_prefix}')
    data['data'] = leaf_executor.submit(get_document_data, doc_ref)

  if USE_FUTURES_DEPTH == print_depth:
    # Only use the tree_executor on 1 depth, otherwise deadlock will occur.
    futures = list()
    for c_ref in doc_ref.collections():
      if 'collection' not in data:
        data['collection'] = dict()
      futures.append(
          tree_executor.submit(
              save_collection_recursive,
              c_ref,
              print_depth=max(0, print_depth - 1),
              print_prefix=print_prefix,
              tree_executor=tree_executor,
              leaf_executor=leaf_executor,
          )
      )
    for future in futures:
      print('Waiting for result')
      c_id, c_data = future.result()
      print(f'got result for {c_id}')
      data['collection'][c_id] = c_data
  else:
    for c_ref in doc_ref.collections():
      if 'collection' not in data:
        data['collection'] = dict()
      c_id, c_data = save_collection_recursive(
          c_ref,
          print_depth=max(0, print_depth - 1),
          print_prefix=print_prefix,
          tree_executor=tree_executor,
          leaf_executor=leaf_executor,
      )
      data['collection'][c_id] = c_data

  return data


def save_collection_recursive(
    c_ref,
    print_depth=0,
    print_prefix=None,
    tree_executor=None,
    leaf_executor=None,
):
  """Recursively saves a collection and its documents."""
  data = list()
  if print_prefix is not None:
    print_prefix = f'{print_prefix}/{c_ref.id}'
  else:
    print_prefix = c_ref.id
  if print_depth:
    print(f'{print_prefix}')
  for doc_ref in c_ref.list_documents():
    data.append(
        save_document_recursive(
            doc_ref,
            print_depth=max(0, print_depth - 1),
            print_prefix=print_prefix,
            tree_executor=tree_executor,
            leaf_executor=leaf_executor,
        )
    )
  return (c_ref.id, data)


def print_directives(username):
  """Print all the directives for the given user."""
  c_ref = db.collection(f'collector/users/{username}/data/directive')
  directives = list()
  for doc in c_ref.stream():
    doc_dict = doc.to_dict()
    directives.append(doc_dict)
  print(json.dumps(directives, indent=2))


def create_directive(username, op, value):
  """Create a directive for the given user."""
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
  """Cancels a directive for the given user and directive ID."""
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
        '    userData OUTPUT_FILENAME',
        '    saveDatabase OUTPUT_FILENAME',
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
    doc_ref = db.document('collector/users')
    for c_ref in doc_ref.collections():
      print(c_ref.id)
  elif sys.argv[2] == 'setPassword':
    if len(sys.argv) >= 5:
      password = sys.argv[4]
    else:
      password = secrets.token_hex(16)
      print(f'Randomly generated password is: {password!r}')
    login_token, login_hash = token_maker.make_token(username, password)
    output = {'loginToken': login_token}
    print(json.dumps(output, indent=2))
    doc_ref = db.document(f'collector/users/{username}/login_hash')
    doc_ref.set({'login_hash': login_hash})
  elif sys.argv[2] == 'changeUser':
    new_username = sys.argv[3]
    if len(sys.argv) >= 5:
      password = sys.argv[4]
    else:
      password = secrets.token_hex(16)
      print(f'Randomly generated password is: {password!r}')
    login_token, login_hash = token_maker.make_token(new_username, password)
    output = {'loginToken': login_token}
    create_directive(sys.argv[1], sys.argv[2], json.dumps(output))
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

    doc_ref = db.document(
        f'collector/users/{username}/data/prompts/version_constraints'
    )
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
  elif sys.argv[2] == 'userData':
    save_user_data(sys.argv[1], sys.argv[3])
  elif sys.argv[2] == 'saveDatabase':
    save_database(sys.argv[3])
  elif sys.argv[2] == 'restoreUser':
    restore_user(sys.argv[1], sys.argv[3])
  elif sys.argv[2] == 'stats':
    if len(sys.argv) >= 5:
      statistics(sys.argv[1], sys.argv[3], sys.argv[4])
    else:
      statistics(sys.argv[1], sys.argv[3])
  else:
    raise AssertionError(
        f'Unknown Operation {sys.argv[2]}. Full command line: '
        + ' '.join(sys.argv)
    )


if __name__ == '__main__':
  main()

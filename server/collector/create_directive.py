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

import data_access
import token_maker


def delete_user(username):
  """delete the given user."""
  c_ref = data_access.db.collection(f'collector/users/{username}')
  futures = list()
  with concurrent.futures.ThreadPoolExecutor(max_workers=200) as executor:
    print('Sending all deletion requests.')
    data_access.delete_collection_recursive(c_ref, executor, futures)
    print('Waiting for all requests to complete.')
    for future in futures:
      future.result()
    print('All requests completed.')


def save_user_data(username, output_filename):
  """Save the given user to json."""
  c_ref = data_access.db.collection(f'collector/users/{username}')
  c_id, c_data = data_access.save_collection_recursive_parallel(
      c_ref, print_depth=3
  )
  with open(output_filename, 'w') as f:
    f.write(json.dumps({c_id: c_data}, indent=2))
    f.write('\n')


def restore_user(username, database_filename):
  """Restore a user from a database save."""
  with open(database_filename, 'r') as f:
    data_dict = json.loads(f.read())
  user = None
  for entry in data_dict['collection']['collector']:
    if entry['id'] == 'users':
      user = entry['collection'][username]
  if user:
    data_access.write_collection_to_firestore(
        f'collector/users/{username}', user
    )


def list_users():
  """List all usernames in the database."""
  doc_ref = data_access.db.document('collector/users')
  return [c_ref.id for c_ref in doc_ref.collections()]


def set_login_hash(username, login_hash):
  """Set the login hash for a user."""
  doc_ref = data_access.db.document(f'collector/users/{username}/login_hash')
  doc_ref.set({'login_hash': login_hash})


def set_prompts_active(username, prompts_data, timestamp):
  """Set the active prompts and record in history for a user."""
  doc_ref = data_access.db.document(
      f'collector/users/{username}/data/prompts/active'
  )
  doc_ref.set(prompts_data)
  doc_ref = data_access.db.document(
      f'collector/users/{username}/data/prompts/all/all/{timestamp}'
  )
  doc_ref.set(prompts_data)


def set_version_constraints(username, version_constraints):
  """Set the version constraints for a user."""
  doc_ref = data_access.db.document(
      f'collector/users/{username}/data/prompts/version_constraints'
  )
  doc_ref.set(version_constraints)


def statistics(username, filename, output_filename=None):
  """compute statistics based on database or user dump.

  Args:
    username: The username to compute statistics for.
    filename: The filename of the dump.
    output_filename: The filename to write the statistics to.

  Returns:
    The statistics dictionary or None if user not found.
  """
  with open(filename, 'r') as f:
    data_dict = json.loads(f.read())
  user = None
  if data_dict.get('id') == '/' and 'collection' in data_dict:
    # We have a database dump.
    user = data_access.get_in_document(data_dict, f'collector/users/{username}')
  else:
    # We have a user dump.
    user = data_dict.get(username)

  if not user:
    return None

  user_stats = dict()
  files = data_access.get_in_collection(user, 'data/file')
  for doc in files:
    assert 'data' in doc
    path = doc['data'].get('path')
    if 'path' not in user_stats:
      user_stats['path'] = list()
    user_stats['path'].append(path)
  heartbeat = data_access.get_in_collection(user, 'data/heartbeat/latest')
  if heartbeat:
    user_stats['heartbeat'] = heartbeat['data']['timestamp']
  max_prompt = data_access.get_in_collection(user, 'data/heartbeat/max_prompt')
  if max_prompt:
    if 'maxPrompt' in max_prompt['data']:
      max_prompt['data']['all'] = max_prompt['data']['maxPrompt']
      max_prompt['data'].pop('maxPrompt')
    user_stats['progress'] = max_prompt['data']
  prompts = data_access.get_in_collection(user, 'data/prompts/active')
  if prompts:
    user_stats['promptsPath'] = prompts['data'].get('path')
  version_constraints = data_access.get_in_collection(
      user, 'data/prompts/version_constraints'
  )
  if version_constraints:
    user_stats['versionConstraints'] = version_constraints['data']

  def fill_clip_stats(clips, clip_stats, duplicate_prompt_ids=None):
    if not clips:
      return
    seen_prompts = set()
    for clip in clips:
      assert clip.get('id').startswith('clipData-')
      data_entry = clip['data']['data']
      start = datetime.datetime.fromisoformat(data_entry['startTimestamp'])
      end = datetime.datetime.fromisoformat(data_entry['endTimestamp'])
      duration = (end - start).total_seconds()
      section_name = data_entry['sectionName']

      prompt_data = data_entry.get('promptData')
      if data_entry.get('valid') and prompt_data:
        prompt_id = prompt_data.get('promptId')
        if prompt_id is not None:
          if prompt_id in seen_prompts:
            if duplicate_prompt_ids is not None:
              duplicate_prompt_ids.add(prompt_id)
            clip_stats['all']['num_valid_duplicates'] += 1
            clip_stats[section_name]['num_valid_duplicates'] += 1
          else:
            seen_prompts.add(prompt_id)

      valid_str = 'valid' if data_entry.get('valid') else 'invalid'
      if data_entry.get('isSkipExplanation', False):
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

  duplicate_prompt_ids = set()
  clips = data_access.get_in_collection(user, 'data/save_clip')
  clip_stats = collections.defaultdict(lambda: collections.defaultdict(int))
  fill_clip_stats(clips, clip_stats, duplicate_prompt_ids)
  user_stats['duplicate_prompt_ids'] = sorted(duplicate_prompt_ids)

  tutorial_clips = data_access.get_in_collection(
      user, 'tutorial_data/save_clip'
  )
  tutorial_clip_stats = collections.defaultdict(
      lambda: collections.defaultdict(int)
  )
  fill_clip_stats(tutorial_clips, tutorial_clip_stats)

  # TODO(mgeorg): count 'HomeScreenActivity.onCreate' log messages
  # (remember they might be in tutorial mode or regular mode).

  def fill_session_stats(sessions, session_stats):
    if not sessions:
      return
    for session in sessions:
      data_entry = session['data']['data']
      start = datetime.datetime.fromisoformat(data_entry['startTimestamp'])
      end = datetime.datetime.fromisoformat(data_entry['endTimestamp'])
      duration = (end - start).total_seconds()
      section_name = data_entry['sectionName']

      session_stats['all']['num_sessions'] += 1
      session_stats['all']['duration_sessions'] += duration
      session_stats[section_name]['num_sessions'] += 1
      session_stats[section_name]['duration_sessions'] += duration

  sessions = data_access.get_in_collection(user, 'data/save_session')
  session_stats = collections.defaultdict(lambda: collections.defaultdict(int))
  fill_session_stats(sessions, session_stats)
  if 'progress' in user_stats:
    for section_name, stats in session_stats.items():
      progress = user_stats['progress'].get(section_name)
      if progress:
        stats['progress'] = progress

  tutorial_sessions = data_access.get_in_collection(
      user, 'tutorial_data/save_session'
  )
  tutorial_session_stats = collections.defaultdict(
      lambda: collections.defaultdict(int)
  )
  fill_session_stats(tutorial_sessions, tutorial_session_stats)

  user_stats['tutorial_clip_stats'] = dict(sorted(tutorial_clip_stats.items()))
  user_stats['tutorial_session_stats'] = dict(
      sorted(tutorial_session_stats.items())
  )
  user_stats['clip_stats'] = dict(sorted(clip_stats.items()))
  user_stats['session_stats'] = dict(sorted(session_stats.items()))

  quick_summary = dict()
  quick_summary['username'] = username
  try:
    quick_summary['num_valid_clips'] = user_stats['clip_stats']['all'][
        'num_valid_clips'
    ]
  except KeyError:
    pass
  try:
    quick_summary['num_invalid_clips'] = user_stats['clip_stats']['all'][
        'num_invalid_clips'
    ]
  except KeyError:
    pass
  try:
    quick_summary['num_bad_prompts'] = user_stats['clip_stats']['all'][
        'num_valid_skip_explanation'
    ]
  except KeyError:
    pass
  try:
    quick_summary['num_valid_duplicates'] = user_stats['clip_stats']['all'][
        'num_valid_duplicates'
    ]
  except KeyError:
    pass
  try:
    quick_summary['num_sessions'] = user_stats['session_stats']['all'][
        'num_sessions'
    ]
  except KeyError:
    pass
  try:
    num = round(
        user_stats['session_stats']['all']['duration_sessions'] / 60 / 60, 2
    )
    quick_summary['duration_sessions'] = f'{num} hours'
  except KeyError:
    pass
  try:
    num = round(
        user_stats['clip_stats']['all']['duration_valid_clips'] / 60 / 60, 2
    )
    quick_summary['duration_clips'] = f'{num} hours'
  except KeyError:
    pass
  try:
    num = round(
        user_stats['tutorial_session_stats']['all']['duration_sessions']
        / 60
        / 60,
        2,
    )
    quick_summary['tutorial_duration_sessions'] = f'{num} hours'
  except KeyError:
    pass
  try:
    num = round(
        user_stats['tutorial_clip_stats']['all']['duration_valid_clips']
        / 60
        / 60,
        2,
    )
    quick_summary['tutorial_duration_clips'] = f'{num} hours'
  except KeyError:
    pass

  user_stats['summary'] = quick_summary

  print(json.dumps(user_stats, indent=2))
  if output_filename and output_filename != filename:
    with open(output_filename, 'w') as f:
      f.write(json.dumps(user_stats, indent=2))
      f.write('\n')


def print_directives(username):
  """Print all the directives for the given user."""
  c_ref = data_access.db.collection(
      f'collector/users/{username}/data/directive'
  )
  directives = list()
  for doc in c_ref.stream():
    doc_dict = doc.to_dict()
    directives.append(doc_dict)
  print(json.dumps(directives, indent=2))


def create_directive(username, op, value):
  """Create a directive for the given user."""
  c_ref = data_access.db.collection(
      f'collector/users/{username}/data/directive'
  )
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
  doc_ref = data_access.db.document(
      f'collector/users/{username}/data/directive/{directive_id}'
  )
  doc_ref.set(directive)


def cancel_directive(username, directive_id):
  """Cancels a directive for the given user and directive ID."""
  directive_id = int(directive_id)
  doc_ref = data_access.db.document(
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
      doc_dict,
      option=data_access.db.write_option(last_update_time=doc_data.update_time),
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
        '    reloadPrompts',
        '    deleteFile FILE_PATH',
        '    uploadState',
        '    unregisterLostFiles',
        '    setTutorialMode [true|false]',
        '    cancel DIRECTIVE_ID',
        '    deleteUser',
        '    userData OUTPUT_FILENAME',
        '    saveDatabase OUTPUT_FILENAME',
        '    restoreUser DATABASE_FILENAME',
        '    stats DUMP_FILENAME [OUTPUT_FILENAME]',
        sep='\n      ',
        file=sys.stderr,
    )
    sys.exit(1)
  username = sys.argv[1]
  op = sys.argv[2]
  op_args = sys.argv[3:]
  del sys.argv

  print(f'Using username: {username}')
  if op == 'noop':
    create_directive(username, op, '{}')

  elif op == 'printDirectives':
    print_directives(username)
  elif op == 'listUsers':
    for username in list_users():
      print(username)
  elif op == 'setPassword':
    if len(op_args) >= 1:
      password = op_args[0]
    else:
      password = secrets.token_hex(16)
      print(f'Randomly generated password is: {password!r}')
    login_token, login_hash = token_maker.make_token(username, password)
    output = {'loginToken': login_token}
    print(json.dumps(output, indent=2))
    set_login_hash(username, login_hash)
  elif op == 'changeUser':
    new_username = op_args[0]
    if len(op_args) >= 2:
      password = op_args[1]
    else:
      password = secrets.token_hex(16)
      print(f'Randomly generated password is: {password!r}')
    login_token, login_hash = token_maker.make_token(new_username, password)
    output = {'loginToken': login_token}
    create_directive(username, op, json.dumps(output))
    set_login_hash(new_username, login_hash)
  elif op == 'setPrompts' or op == 'setPromptsNoUpload':
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    path = pathlib.Path(op_args[0])
    uploaded_relative_path = str(pathlib.Path('prompts', path.name))
    if op == 'setPrompts':
      if not path.exists():
        raise ValueError(f'Prompt file not found: {path}')
      print(
          f'Upload the prompt file using\n'
          f'  gsutil cp {path} gs://{data_access.BUCKET_NAME}/prompts/'
      )
      input('Press enter when file is uploaded> ')
    if op == 'setPromptsNoUpload':
      if uploaded_relative_path != op_args[0]:
        print(
            f'Ignoring directory and setting path to {uploaded_relative_path}'
        )
    prompts_data = {
        'path': uploaded_relative_path,
        'creationTimestamp': timestamp,
    }
    set_prompts_active(username, prompts_data, timestamp)
    print('THIS WILL NOT RELOAD THE PROMPTS, USE reloadPrompts FOR THAT.')
  elif op == 'setVersionRange':
    min_version = op_args[0]
    max_version = op_args[1]
    if min_version.lower() == 'none':
      min_version = None
    if max_version.lower() == 'none':
      max_version = None

    version_constraints = dict()

    if min_version:
      version_constraints['min_version'] = min_version
    if max_version:
      version_constraints['max_version'] = max_version

    set_version_constraints(username, version_constraints)
  elif op == 'reloadPrompts':
    create_directive(username, op, '{}')
  elif op == 'deleteFile':
    output = {'filepath': op_args[0]}
    create_directive(username, op, json.dumps(output))
  elif op == 'uploadState':
    create_directive(username, op, '{}')
  elif op == 'setTutorialMode':
    output = {'tutorialMode': op_args[0].lower() in ['1', 't', 'true']}
    create_directive(username, op, json.dumps(output))
  elif op == 'unregisterLostFiles':
    create_directive(username, op, '{}')
  elif op == 'cancel':
    directive_id = int(op_args[0])
    cancel_directive(username, directive_id)
  elif op == 'deleteUser':
    delete_user(username)
  elif op == 'userData':
    save_user_data(username, op_args[0])
  elif op == 'saveDatabase':
    data_access.save_database(op_args[0])
  elif op == 'restoreUser':
    restore_user(username, op_args[0])
  elif op == 'stats':
    if len(op_args) >= 2:
      statistics(username, op_args[0], op_args[1])
    else:
      statistics(username, op_args[0])
  else:
    raise AssertionError(f'Unknown Operation {op!r}.')


if __name__ == '__main__':
  main()

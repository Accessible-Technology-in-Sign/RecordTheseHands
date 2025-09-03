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
"""Create prompt files (and upload) of up to 2 prompt datasets.

This can be used to mix copycat prompts with dqp prompts.  The copycat
prompts must be included in each prompt file, but the dqp prompts are
only sampled (with the entire set being output into exactly one of the
output prompt files).

Either input file can be "" which will ignore that file.
"""

import datetime
import json
import os
import pathlib
import random
import subprocess
import sys

from google.cloud import firestore

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'


def upload_prompts(prompts, prompts_ident, timestamp, useSummaryPage=True):
  path = f'prompts/prompts_{prompts_ident}_{timestamp}.json'
  data = {
      'path': path,
      'timestamp': timestamp,
      'useSummaryPage': useSummaryPage,
      'prompts': prompts,
  }
  doc_ref = db.document(f'collector/prompts/all/{prompts_ident}_{timestamp}')
  doc_ref.set(data)
  # print(json.dumps(data, indent=2))
  local_tempfile = pathlib.Path(os.environ.get('HOME')).joinpath(
      f'prompts/split_{timestamp}/{path}'
  )
  local_tempfile.parent.mkdir(parents=True, exist_ok=True)
  with local_tempfile.open('w') as f:
    f.write(json.dumps(data, indent=2))
    f.write('\n')
  print(local_tempfile)
  return

  command = ['gsutil', 'cp', str(local_tempfile), f'gs://{BUCKET_NAME}/{path}']
  print(' '.join(command))
  p = subprocess.Popen(command)
  p.communicate()
  assert (
      p.returncode == 0
  ), 'upload of prompts FAILED, do not send update operation to phones!'
  print('Upload succeeded, send prompts to phone with')
  print(f'create_directive.py <username> downloadPrompts {path}')


if __name__ == '__main__':
  dataset_name = sys.argv[1]
  local_filepath1 = sys.argv[2]
  local_filepath2 = sys.argv[3]
  num_samples2 = int(sys.argv[4])
  print(f'prompts take every time: {local_filepath1}')
  print(f'prompts {num_samples2}: {local_filepath2}')
  db = firestore.Client()
  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()

  prompts1 = list()
  prompts2 = list()
  useSummaryPage = False
  if local_filepath1:
    with open(local_filepath1, 'r') as f:
      data = json.loads(f.read())
      prompts1 = data.get('prompts')
      if data.get('useSummaryPage'):
        useSummaryPage = True
  if local_filepath2:
    with open(local_filepath2, 'r') as f:
      data = json.loads(f.read())
      prompts2 = data.get('prompts')
      if data.get('useSummaryPage'):
        useSummaryPage = True

  random.shuffle(prompts1)
  random.shuffle(prompts2)
  print(json.dumps(prompts1, indent=2))

  i = 0
  prompt_file_index = 1
  while True:
    prompts = prompts1 + prompts2[i : i + num_samples2]
    random.shuffle(prompts)

    upload_prompts(
        prompts, f'{dataset_name}_{prompt_file_index:02d}', timestamp
    )
    i += num_samples2
    prompt_file_index += 1
    if i >= len(prompts2):
      break

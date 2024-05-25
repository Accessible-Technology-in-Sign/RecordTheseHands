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
"""Create a prompt file (locally)."""

import datetime
import json
import os
import pathlib
import re
import subprocess
import sys

from google.cloud import firestore

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'


if __name__ == '__main__':
  key_prefix = sys.argv[1]
  local_filepath = sys.argv[2]
  print(f'prompts: {local_filepath}')
  db = firestore.Client()
  timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
  prompts = list()
  with open(local_filepath, 'r') as f:
    lines = [x.strip() for x in f.read().splitlines() if x.strip()]
    num_zeros = len(str(len(lines)))
    for i, line in enumerate(lines):
      m = re.match('^(TEXT|IMAGE|VIDEO)\s+(.*)$', line)
      resource_path = None
      prompt_text = None
      if m:
        prompt_type = m.group(1)
        if prompt_type == 'TEXT':
          prompt_text = m.group(2)
        elif prompt_type == 'IMAGE' or prompt_type == 'VIDEO':
          remainder = m.group(2)
          m = re.match('^(\S+)(?:\s+(.*))?$', m.group(2))
          assert m, (line, remainder)
          resource_path = m.group(1)
          prompt_text = m.group(2)
        else:
          raise ValueError(
              f'prompt_type {prompt_type!r} not recognized for line {line!r}')
      else:
        prompt_type = 'TEXT'
        prompt_text = line
      prompt_data = {
          'key': f'{key_prefix}{i:0{num_zeros}d}',
          'type': prompt_type,
      }
      if prompt_text:
        prompt_data['prompt'] = prompt_text
      if resource_path:
        prompt_data['resourcePath'] = resource_path
      prompts.append(prompt_data)

  path = f'prompts/prompts-{key_prefix}-{timestamp}.json'
  data = {
      'path': path,
      'timestamp': timestamp,
      'useSummaryPage': False,
      'prompts': prompts,
  }
  local_file = pathlib.Path(os.environ.get('HOME')).joinpath(path)
  local_file.parent.mkdir(parents=True, exist_ok=True)
  with local_file.open('w') as f:
    f.write(json.dumps(data, indent=2))
    f.write('\n')
  print(f'prompts written to {local_file}')


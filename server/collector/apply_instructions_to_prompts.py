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
"""Applies instructions from one json file to another."""

import json
import pathlib
import sys


def main() -> int:
  if len(sys.argv) < 4:
    print(
        'USAGE: instructions_json base_json output_json',
        file=sys.stderr,
    )
    return 1
  instructions_json_filename = sys.argv[1]
  base_json_filename = sys.argv[2]
  output_filename = sys.argv[3]

  output_path = pathlib.Path(output_filename)
  if output_path.exists():
    print(f'Refusing to overwrite {output_path}')
    return 1

  with open(instructions_json_filename, 'r') as f:
    instructions = json.loads(f.read())

  with open(base_json_filename, 'r') as f:
    base = json.loads(f.read())

  # Merge global instructions.
  instructions_metadata = instructions.get('metadata')
  if instructions_metadata:
    global_instructions = instructions_metadata.get('instructions')
    if global_instructions:
      if 'metadata' not in base:
        base['metadata'] = {}
      base['metadata']['instructions'] = global_instructions

  # Merge section-specific instructions.
  for section_name, section in base['data'].items():
    instructions_section = instructions['data'].get(section_name)
    if not instructions_section:
      continue
    instructions_metadata = instructions_section.get('metadata')
    if instructions_metadata:
      global_instructions = instructions_metadata.get('instructions')
      if global_instructions:
        if 'metadata' not in section:
          section['metadata'] = {}
        section['metadata']['instructions'] = global_instructions

  with open(output_filename, 'w') as f:
    f.write(json.dumps(base, indent=2))
    f.write('\n')

  return 0


if __name__ == '__main__':
  sys.exit(main())

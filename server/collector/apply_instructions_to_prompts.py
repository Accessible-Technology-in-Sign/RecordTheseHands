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

import argparse
import json
import logging
import pathlib
import sys

logging.basicConfig(
    level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s'
)


def main() -> int:
  parser = argparse.ArgumentParser(
      description='Applies instructions from one json file to another.'
  )
  parser.add_argument(
      'instructions_json',
      type=pathlib.Path,
      help='Path to instructions JSON file',
  )
  parser.add_argument(
      'tutorial_json',
      type=pathlib.Path,
      help='Path to tutorial JSON file',
  )
  parser.add_argument(
      'base_json', type=pathlib.Path, help='Path to base prompts JSON file'
  )
  parser.add_argument(
      'output_json', type=pathlib.Path, help='Path to output JSON file'
  )
  parser.add_argument(
      '--force',
      action='store_true',
      help='Overwrite output file if it exists',
  )

  args = parser.parse_args()

  if args.output_json.exists() and not args.force:
    logging.error(
        f'Refusing to overwrite {args.output_json}. Use --force to overwrite.'
    )
    return 1

  try:
    with open(args.instructions_json, 'r') as f:
      instructions = json.load(f)
    with open(args.tutorial_json, 'r') as f:
      tutorial = json.load(f)
    with open(args.base_json, 'r') as f:
      base = json.load(f)
  except (json.JSONDecodeError, IOError) as e:
    logging.error(f'Error reading input files: {e}')
    return 1

  # Merge overview instructions.
  instructions_metadata = instructions.get('metadata')
  if instructions_metadata:
    overview_instructions = instructions_metadata.get('instructions')
    if overview_instructions:
      if 'metadata' not in base:
        base['metadata'] = {}
      base['metadata']['instructions'] = overview_instructions
      logging.info('Applied overview instructions.')

  # Merge section-specific instructions.
  if 'data' in instructions:
    for section_name, section in base.get('data', {}).items():
      instructions_section = instructions['data'].get(section_name)
      if not instructions_section:
        continue
      instructions_metadata = instructions_section.get('metadata')
      if instructions_metadata:
        overview_instructions = instructions_metadata.get('instructions')
        if overview_instructions:
          if 'metadata' not in section:
            section['metadata'] = {}
          section['metadata']['instructions'] = overview_instructions
          logging.info(f'Applied instructions for section: {section_name}')

  # Merge tutorial prompts.
  for section_name, section in base.get('data', {}).items():
    tutorial_section = tutorial.get(section_name, {}).get('tutorial')
    if not tutorial_section:
      continue
    section['tutorial'] = tutorial_section
    logging.info(f'Applied tutorial prompts for section: {section_name}')

  try:
    with open(args.output_json, 'w') as f:
      json.dump(base, f, indent=2)
      f.write('\n')
    logging.info(f'Successfully wrote to {args.output_json}')
  except IOError as e:
    logging.error(f'Error writing output file: {e}')
    return 1

  return 0


if __name__ == '__main__':
  sys.exit(main())

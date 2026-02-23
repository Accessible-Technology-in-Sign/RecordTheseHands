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

"""A local video processing pipeline for downloading, clipping, and dumping video metadata."""

import argparse
import clip_video
import download_videos
import dump_clips

if __name__ == '__main__':
  parser = argparse.ArgumentParser()
  parser.add_argument(
      '--buffers', type=str, default=None, help='Config file for user buffers'
  )
  parser.add_argument(
      '--db_dump',
      type=str,
      required=True,
      help='JSON file containing database dump',
  )
  args = parser.parse_args()

  print('\n\nSTARTING VIDEO DOWNLOAD')
  print('---------------------------------------')
  download_videos.main(args.db_dump)

  print('\n\nSTARTING CLIP METADATA DOWNLOAD')
  print('---------------------------------------')
  dump_clips.main(args.db_dump)

  print('\n\nSTARTING VIDEO CLIPPING')
  print('---------------------------------------')
  clip_video.main(args.buffers)

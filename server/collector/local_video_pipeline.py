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

import argparse
import download_videos
import dump_clips
import clip_video

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--clean', action='store_true', default=False, help='Clean all existing files')
    parser.add_argument('--buffers', type=str, default=None, help='Config file for user buffers')
    args = parser.parse_args()

    if args.clean:
        print("CLEANING EXISTING FILES")
        print("---------------------------------------")
        download_videos.clean()
        dump_clips.clean()
        clip_video.clean()

    print("\n\nSTARTING VIDEO DOWNLOAD")
    print("---------------------------------------")
    download_videos.main()

    print("\n\nSTARTING CLIP METADATA DOWNLOAD")
    print("---------------------------------------")
    dump_clips.main()

    print("\n\nSTARTING VIDEO CLIPPING")
    print("---------------------------------------")
    clip_video.main(args.buffers)

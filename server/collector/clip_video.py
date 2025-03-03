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
"""Functions to create a frame accurate clip from a video.

Functions for making frame accurate clips.

Creating all the video clips is a long process.  As such, there is a Google
internal script which keeps track of progress and simultaneously uploads the
completed clips to the cloud.  There is currently no equivalent script
in this project.
"""

from collections import defaultdict
import datetime
import json
import pathlib
import re
import csv
import os

import utils

def ffprobe_packet_info(video):
  """Probe the video and get all the packet info."""

  cmd = [
      '-v', 'error', '-select_streams', 'v', '-show_packets',
      '-show_data_hash', 'md5', '-show_entries', 'packet=pts_time,duration_time,flags,data_hash',
      '-of', 'compact', video
  ]

  """
  ffmpeg -v error -y -ss 17.023567 -to 20.022301 -i video_dump/upload/test006/upload/test006-3e3a42ff-s007-2025-01-14T21:10:58.575099Z.mp4 -an -c:v copy PosixPath('clip_dump/test006-3e3a42ff-s007-2025-01-14T21:10:58.575099Z_clip_17.549616_19.310036.mp4')
  """

  result = utils.run_ffprobe(cmd, capture_output=True, check=True, text=True)
  probe_data = result.stdout

  packet_info = list()
  for line in probe_data.splitlines():
    m = re.match(
        r'packet\|pts_time=([\d\.-]+)\|duration_time=([\d\.]+)\|'
        r'flags=(...)\|data_hash=MD5:([0-9a-f]{32})$',
        line)

    #approximate duration time as difference between PTS
    assert m, line
    pts_time_s = float(m.group(1))
    duration = float(m.group(2))
    flags = m.group(3)
    md5sum = m.group(4)
    packet_info.append({
        'ptsTimeS': pts_time_s,
        'flags': flags,
        'md5': md5sum,
        'duration': duration
    })
  return packet_info


def make_clip(video, packet_info,
              annotation_start_s, annotation_end_s, output_filename, verbose=False):
  """Make a frame perfect clip at a keyframe boundary using codec copy.

  The clip is cut using a copy codec.  The clip is guaranteed to start
  at pts_time 0, on a keyframe that is not marked as being dropped and
  is the first keyframe before annotation_start_s in the original video.
  And it is guaranteed to end on the next keyframe in the original after
  annotation_end_s (or the last packet of the file).  These properties
  are confirmed using md5 hashes of the packet data.  Notice, that due
  to B frames, keyframes are the only reliable places to cut a video
  with a copy codec.

  The clipping should be done in a single call to ffmpeg, however, due
  to lots of rounding going on in various places, if the initial guess
  of start time fails, we use a bisection search on the start time to
  generate a packet accurate clip.

  Args:
    video: A video filename to clip from.
    packet_info: A pre-computed packet_info dump of the video.
    annotation_start_s: The clip start time in Seconds (assuming the video
                        starts at zero, despite pts_time values to the
                        contrary).
    annotation_end_s: The clip end time in Seconds (assuming the video
                      starts at zero, despite pts_time values to the contrary).
    output_filename: The name of the file to write to (overwriting anything
                     that was there).
  Returns:
    A clip_spec describing the created video.

    This is a dict with the following entries:
    "clipFilename", "fullVideoFilename", "fullVideoFirstPtsTimeS",
    "annotationStartTimeS", "annotationEndTimeS",
    "clipStartTimeS", "clipEndTimeS",
    "clipStartPacketMd5", "clipEndPacketMd5", "clipFileMd5".

    All of the start and end times are relative to the beginning of the
    video, assuming that the first packet is time zero (even if pts_time
    disagrees).  this is also how ffmpeg handles -ss and -to timestamps.
    If you want a time comparable to the pts_time values in the original
    video, then you must add fullVideoFirstPtsTimeS to the times.

    It should not be possible for annotationEndTimeS to be smaller than
    clipEndTimeS (this statement has not been rigorously verified).
  """
  # The input clip start and end are assumed to be from 0 sec as the first
  # frame of the video.  So we must add the first packet's pts_time in
  # order to search for the correct clipping time.
  pts_to_video_time = -packet_info[0]['ptsTimeS']
  if verbose:
    print(f'pts_to_video_time {pts_to_video_time}')

  # ffmpeg automatically adds the first pts_time to -ss and -to values.
  # So, to compensate, since we've precisely picked the frame we want
  # we need to subtract the first pts_time.  Adding this adjustment will
  # turn a pts time into a video (from zero) based time.
  #
  # Technically, this should probably be the first positive pts time,
  # although ffmpeg makes the same simplifying assumption and uses the
  # first pts_time regardless of if it is dropped.  We are assuming the
  # video does not have any packets with drop flags at the beginning.
  # Just another reason to ensure our clips start with the first packet
  # precisely at zero.

  search_pts_time_start = float(annotation_start_s) - pts_to_video_time
  search_pts_time_end = float(annotation_end_s) - pts_to_video_time

  last_keyframe = None
  seek_packet = None
  for seek_packet, info in enumerate(packet_info):
    # print(json.dumps(info, indent=2))
    if search_pts_time_start < info['ptsTimeS']:
      break
    if info['flags'][0] == 'K':
      last_keyframe = info
  assert last_keyframe, (last_keyframe, search_pts_time_start)
  assert seek_packet is not None
  if seek_packet == len(packet_info):
    raise ValueError('Annotation is after end of video.')
  start_packet_in_orig = last_keyframe

  look_for_keyframe = False
  end_packet_in_orig = None
  for seek_packet, info in enumerate(packet_info):
    if search_pts_time_end <= info['ptsTimeS']:
      look_for_keyframe = True
    if look_for_keyframe and info['flags'][0] == 'K':
      end_packet_in_orig = info
      break
  if not end_packet_in_orig:
    end_packet_in_orig = packet_info[-1]
  if verbose:
    print(start_packet_in_orig)
    print(end_packet_in_orig)

  start_time = start_packet_in_orig['ptsTimeS'] + pts_to_video_time
  start_time = int((start_time * 1000000) + 0.5) / 1000000.0
  minimum_start_time = start_time - 2 * start_packet_in_orig['duration']
  maximum_start_time = start_time + start_packet_in_orig['duration']
  end_time = end_packet_in_orig['ptsTimeS'] + pts_to_video_time + .000001
  minimum_end_time = end_time - 0.5 * end_packet_in_orig['duration']
  maximum_end_time = end_time + 0.5 * end_packet_in_orig['duration']
  end_time = int((end_time * 1000000) + 0.5) / 1000000.0
  max_tries = 40
  i = 0
  while True:
    i += 1
    if i == max_tries:
      break
    if verbose:
      print('###')
      print(f'### Cutting {start_time}')
      print('###')
    
    utils.trim_video(video, str(start_time), str(end_time), output_filename)

    clip_data = ffprobe_packet_info(output_filename)
    if verbose:
      print(clip_data[0])
      print(clip_data[-1])
    if clip_data[0]['flags'][1] == 'D':
      if clip_data[0]['md5'] == start_packet_in_orig['md5']:
        # Timestamp too late, deleted first packet (but it was the correct
        # packet).
        if verbose:
          print('First keyframe marked deleted.')
        maximum_start_time = start_time
      else:
        # Timestamp too early, went to previous keyframe.
        if verbose:
          print('Included one keyframe too many.')
      minimum_start_time = start_time
      start_time = (minimum_start_time + maximum_start_time) / 2
      start_time = int((start_time * 1000000) + 0.5) / 1000000.0
      continue
    # Start time is now set correctly.
    if clip_data[-1]['md5'] != end_packet_in_orig['md5']:
      if (clip_data[-1]['ptsTimeS'] >
          end_packet_in_orig['ptsTimeS'] - start_packet_in_orig['ptsTimeS']):
        print('Last packet is after the keyframe we wanted.')
        maximum_end_time = end_time
      else:
        print('Last packet is before the keyframe we wanted.')
        minimum_end_time = end_time
      end_time = (minimum_end_time + maximum_end_time) / 2
      end_time = int((end_time * 1000000) + 0.5) / 1000000.0
      continue
    break
  assert i != max_tries
  assert clip_data[0]['md5'] == start_packet_in_orig['md5']
  assert clip_data[0]['flags'][0] == 'K'
  assert clip_data[0]['ptsTimeS'] == 0
  assert clip_data[-1]['md5'] == end_packet_in_orig['md5']
  output_stat = pathlib.Path(output_filename).lstat()
  clip_c_time = datetime.datetime.fromtimestamp(
      output_stat.st_ctime, tz=datetime.timezone.utc)
  clip_file_size = output_stat.st_size
  return {
      'clipFilename': str(output_filename),
      'fullVideoFilename': str(video),
      'fullVideoFirstPtsTimeS': packet_info[0]['ptsTimeS'],
      'annotationStartTimeS': annotation_start_s,
      'annotationEndTimeS': annotation_end_s,
      'clipStartTimeS': start_time,
      'clipEndTimeS': end_time,
      'clipStartPacketMd5': clip_data[0]['md5'],
      'clipEndPacketMd5': clip_data[-1]['md5'],
      'clipFileSize': clip_file_size,
      'clipFileMd5': utils.compute_md5(output_filename),
      'clipCreationTime': clip_c_time.isoformat(),
  }

def make_clips(video_directory="video_dump/upload", dump_csv="dump.csv", output_dir="clip_dump"):
  """Make clips from all the videos in the video directory."""
  video_directory = pathlib.Path(video_directory)
  dump_csv = pathlib.Path(dump_csv)

  os.makedirs(output_dir, exist_ok=True)
  
  clip_data = defaultdict(list)
  clips = []

  with dump_csv.open('r', encoding='utf-8') as f:
    csv_reader = csv.reader(f)
    
    for row in csv_reader:
      user_id, video, start_time, end_time = row[0], row[1], row[4], row[5]
      video = pathlib.Path(user_id) / "upload" / (video + ".mp4")
      clip_data[(user_id, video)].append((start_time, end_time))

  for (user_id, video), clip_times in clip_data.items():
    video = video_directory.joinpath(video)
    packet_info = ffprobe_packet_info(str(video))
    for start_time, end_time in clip_times:
      output_dir = pathlib.Path(output_dir)
      output_filename = output_dir.joinpath(
          video.stem + f'_clip_{start_time}_{end_time}.mp4')

      clip_spec = make_clip(str(video), packet_info, str(start_time), str(end_time), str(output_filename))
      clips.append(clip_spec)

  with output_dir.joinpath('clips.json').open('w', encoding='utf-8') as f:
    f.write(json.dumps(clips, indent=2))

def main():
  make_clips()

if __name__ == '__main__':
  main()

"""Utility functions for video processing and file handling."""

import hashlib
import subprocess


def run_ffmpeg(command_args, capture_output=True, check=True, text=True):
  """Runs an ffmpeg command with the specified arguments."""
  cmd = ['ffmpeg'] + command_args
  try:
    result = subprocess.run(
        cmd, capture_output=capture_output, check=check, text=text
    )
    if result.returncode != 0:
      print(f'ffmpeg failed with error: {result.stderr}')
    return result
  except subprocess.CalledProcessError as e:
    print(f'ffmpeg process error: {e}')
  except Exception as e:  # pylint: disable=broad-exception-caught
    print(f'Unexpected error running ffmpeg: {e}')


def run_ffprobe(command_args, capture_output=True, check=True, text=True):
  """Runs an ffprobe command with the specified arguments."""
  cmd = ['ffprobe'] + command_args
  try:
    result = subprocess.run(
        cmd, capture_output=capture_output, check=check, text=text
    )
    if result.returncode != 0:
      print(f'ffprobe failed with error: {result.stderr}')
    return result
  except subprocess.CalledProcessError as e:
    print(f'ffprobe process error: {e}')
  except Exception as e:  # pylint: disable=broad-exception-caught
    print(f'Unexpected error running ffprobe: {e}')


def trim_video(video, start_time, end_time, output_filename):
  """Trim the video using ffmpeg."""
  command_args = [
      '-v',
      'error',
      '-y',
      '-ss',
      start_time,
      '-to',
      end_time,
      '-i',
      video,
      '-an',
      '-c:v',
      'copy',
      output_filename,
  ]

  result = run_ffmpeg(command_args)
  if result and result.returncode == 0:
    print(f'Video trimmed successfully: {output_filename}')
  else:
    print(
        'Failed to trim video:'
        f' {result.stderr if result else "No result returned"}'
    )


def compute_md5(file_path, chunk_size=8192):
  """Compute the MD5 hash of a file."""
  md5 = hashlib.md5()
  with open(file_path, 'rb') as f:
    for chunk in iter(lambda: f.read(chunk_size), b''):
      md5.update(chunk)

  return md5.hexdigest()


def get_in_collection(collection, path):
  """Use a path to get something in a collection.

  If the path has an odd number of parts it is a document,
  if even then it is a collection.

  Args:
    collection: The collection object.
    path: The path to retrieve.

  Returns:
    The object at the path, or None.
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

  Args:
    document: The document object.
    path: The path to retrieve.

  Returns:
    The object at the path, or None.
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

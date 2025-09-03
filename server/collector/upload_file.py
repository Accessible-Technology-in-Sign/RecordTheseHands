#!/usr/bin/python3

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
"""Script to upload a file using signed urls from a server."""

import base64
import copy
import datetime
import functools
import hashlib
import io
import json
import mimetypes
import os
import pathlib
import re
import urllib.request

_UPLOAD_PAGE = 'http://localhost:8040/simple_upload'


class Error(Exception):
  pass


class MockFile(io.IOBase):

  def __init__(self, content_length, raise_at=None):
    self.content_length = content_length
    self.bytes_written = 0
    self.sequence = (
        (b'0123456789' * 7 + b'\n') * 3
        + b'\n'
        + (b'0123456789' * 7 + b'\n') * 1
        + b'\n'
        + (b'0123456789' * 7 + b'\n') * 4
        + b'\n'
        + (b'0123456789' * 7 + b'\n') * 1
        + b'\n'
        + (b'0123456789' * 7 + b'\n') * 5
        + b'\n'
        + b'#####'
        + b'\n\n\n'
    )
    self.sequence_length = len(self.sequence)
    self.raise_at = raise_at

  def read(self, num_bytes):
    if self.raise_at is not None and self.bytes_written > self.raise_at:
      raise Error('Error triggered.')
    if self.bytes_written + num_bytes > self.content_length:
      bytes_produced = self.content_length - self.bytes_written
    else:
      bytes_produced = num_bytes
    start = self.bytes_written % self.sequence_length

    self.bytes_written += bytes_produced
    output = self.sequence[start:]
    if len(output) >= bytes_produced:
      return output[:bytes_produced]
    while len(output) < bytes_produced:
      output += self.sequence
      if len(output) >= bytes_produced:
        return output[:bytes_produced]


def print_errors(e):
  content = e.fp.read()
  print(e.code)
  print(e.reason)
  print(e.headers)
  print(content)


def upload_file(path):
  """Upload an item."""
  # data_length = 100*1024*1024
  data_length = 1 * 1024 * 1024  # 1 MiB
  data = MockFile(data_length, data_length / 2)
  data_for_md5sum = MockFile(data_length)
  # md5sum_base64 = '5yWJ+YCa8xSj+O699IKtEw=='
  md5object = hashlib.md5(data_for_md5sum.read(data_length))
  md5sum_base64 = base64.b64encode(md5object.digest()).decode('utf-8')
  md5sum = md5object.hexdigest()
  print(md5sum)
  print(md5sum_base64)

  quoted_path = urllib.parse.quote(str(path), safe='')
  upload_link = _UPLOAD_PAGE + f'?filename={quoted_path}&md5={md5sum}'

  print('Getting signed url.')
  request = urllib.request.Request(upload_link, data=b'', method='POST')
  request.add_header('Content-Length', '0')
  try:
    response = urllib.request.urlopen(request)
    response_code = response.code
    headers = response.headers
    content = json.loads(response.read())
    signed_url = content.get('uploadLink')
    assert signed_url, content
    print(
        f'Response code: {response_code}\nHeaders: \n  '
        + '\n  '.join([f'{k}: {v}' for k, v in headers.items()])
        + f'\n\nContent:\n{content}\n\n'
    )
  except urllib.error.HTTPError as e:
    print_errors(e)
    raise

  print('Starting upload session.')
  request = urllib.request.Request(signed_url, data=b'', method='POST')
  request.add_header('Content-Length', '0')
  request.add_header('Content-Type', 'text/plain')
  request.add_header('Content-MD5', md5sum_base64)
  request.add_header('X-Goog-Resumable', 'start')
  try:
    response = urllib.request.urlopen(request)
    response_code = response.code
    headers = response.headers
    session_link = headers.get('Location')
    assert session_link, response
    content = response.read()
    print(
        f'Response code: {response_code}\nHeaders: \n  '
        + '\n  '.join([f'{k}: {v}' for k, v in headers.items()])
        + f'\n\nContent:\n{content}\n\n'
    )
  except urllib.error.HTTPError as e:
    print_errors(e)
    raise

  print('Uploading.')
  request = urllib.request.Request(session_link, data=data, method='PUT')
  request.add_header('Content-Length', f'{data_length}')
  try:
    response = urllib.request.urlopen(request)
    response_code = response.code
    headers = response.headers
    content = response.read()
    print(
        f'Response code: {response_code}\nHeaders: \n  '
        + '\n  '.join([f'{k}: {v}' for k, v in headers.items()])
        + f'\n\nContent:\n{content}\n\n'
    )

  except urllib.error.HTTPError as e:
    print_errors(e)
    raise
  except Error as e:
    print(e)

  print('Checking status of the upload.')
  request = urllib.request.Request(session_link, data=b'', method='PUT')
  request.add_header('Content-Length', '0')
  request.add_header('Content-Range', f'bytes */{data_length}')
  try:
    response = urllib.request.urlopen(request)
    response_code = response.code
    headers = response.headers
    content = response.read()
  except urllib.error.HTTPError as e:
    if e.code != 308:
      print_errors(e)
      raise
    response_code = e.code
    headers = e.headers
    content = e.read()
  print(
      f'Response code: {response_code}\nHeaders: \n  '
      + '\n  '.join([f'{k}: {v}' for k, v in headers.items()])
      + f'\n\nContent:\n{content}\n\n'
  )

  if response_code >= 200 and response_code < 300:
    print('file uploaded successfully.')
    return

  print('File upload incomplete.')
  range_header = headers.get('Range')
  if not range_header:
    start_from = 0
  else:
    m = re.match(r'^bytes=(\d+)-(\d+)$', range_header)
    assert m, range_header
    assert m.group(1) == '0', range_header
    start_from = int(m.group(2)) + 1

  data = MockFile(data_length)
  data.bytes_written = start_from
  print('Uploading.')
  request = urllib.request.Request(session_link, data=data, method='PUT')
  request.add_header('Content-Length', f'{data_length-data.bytes_written}')
  request.add_header(
      'Content-Range',
      f'bytes {data.bytes_written}-{data_length-1}/{data_length}',
  )
  try:
    response = urllib.request.urlopen(request)
    response_code = response.code
    headers = response.headers
    content = response.read()
    print(
        f'Response code: {response_code}\nHeaders: \n  '
        + '\n  '.join([f'{k}: {v}' for k, v in headers.items()])
        + f'\n\nContent:\n{content}\n\n'
    )

  except urllib.error.HTTPError as e:
    print_errors(e)
    raise

  print('File uploaded, checking final status.')
  # print(md5sum)
  request = urllib.request.Request(session_link, data=b'', method='PUT')
  request.add_header('Content-Length', '0')
  request.add_header('Content-Range', f'bytes */{data_length}')
  try:
    response = urllib.request.urlopen(request)
    response_code = response.code
    headers = response.headers
    content = response.read()
  except urllib.error.HTTPError as e:
    if e.code != 308:
      print_errors(e)
      raise
    response_code = e.code
    headers = e.headers
    content = e.read()
  print(
      f'Response code: {response_code}\nHeaders: \n  '
      + '\n  '.join([f'{k}: {v}' for k, v in headers.items()])
      + f'\n\nContent:\n{content}\n\n'
  )

  if response_code >= 200 and response_code < 300:
    print('file uploaded successfully.')
    return


if __name__ == '__main__':
  upload_file('a_file.txt')

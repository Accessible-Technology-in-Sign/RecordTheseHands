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
"""Utility functions for creating and verifying login tokens.

Run the file directly in order to create a login token.
"""

import hashlib


def get_login_hash(username, login_token):
  """Get the login hash.

  The "login hash" is the username a colon and then sha256 hash of the
  "login token" in hex.  The login token is itself the username a colon
  and a sha256 hash.  The login token is generated iteratively from
  hashing the username a colon and the password/hash 1000 times which
  is performed on the client side.  This means the server never sees
  the user's password, nevertheless, this login token is not stored,
  but one more iteration is performed, creating the "login hash" (1001
  iterations total) and that value is stored.

  In short, the following code produces the login_hash from the password.
  val = f'{username}:{password}'
  for i in range(1001):
    val = get_login_hash(username, val)

  Args:
    username: string username of the annotator.
    login_token: string login token in the format username, colon, sha256 hash.

  Returns:
    The login hash, a string of username, colon, and a sha256 hash of the
    login token.
  """
  login_hash = (
      username + ':' + hashlib.sha256(login_token.encode('utf-8')).hexdigest()
  )
  return login_hash


def make_token(username, password):
  login_token = f'{username}:{password}'
  for _ in range(1000):
    login_token = get_login_hash(username, login_token)
  login_hash = get_login_hash(username, login_token)
  return (login_token, login_hash)


def main():
  username = input('username: ')
  if not username:
    username = 'admin'
  password = getpass.getpass()
  assert password == password.strip(), 'password has surrounding white space.'

  login_token, login_hash = make_token(username, password)

  print(
      f"""login_token (sent over network):
{login_token}
login_hash (saved by server):
{login_hash}

If you are generating the admin token then make sure to use the
username "admin" and save the "login_hash" value with
key "admin_token_hash" in the secret manager.
Make sure to include the "admin:" portion of the login_hash.
"""
  )


if __name__ == '__main__':
  import getpass  # pylint: disable=g-import-not-at-top

  main()

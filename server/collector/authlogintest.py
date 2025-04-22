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
"""Main flask based server to facilitate sign language data collection."""

import datetime
import jwt
from functools import wraps

import flask
from flask import request
import flask_login
from google.cloud import firestore
import token_maker



##############
#
#
# The following variables are used in the user authentication system.
#
#
##############
ACCESS_TOKEN_EXP = 15  # minutes
REFRESH_TOKEN_EXP = 7 * 24 * 60  # minutes (7 days)

# globals, including Flask environment.
app = flask.Flask(__name__)
login_manager = flask_login.LoginManager()

##############
#
#
# The following variables are used in the user authentication system.
#
#
##############


def generate_tokens(username):
    now = datetime.datetime.utcnow()

    access_token = jwt.encode({
        'sub': username,
        'exp': now + datetime.timedelta(minutes=ACCESS_TOKEN_EXP)
    }, app.config['SECRET_KEY'], algorithm='HS256')

    refresh_token = jwt.encode({
        'sub': username,
        'exp': now + datetime.timedelta(minutes=REFRESH_TOKEN_EXP),
        'type': 'refresh'
    }, app.config['SECRET_KEY'], algorithm='HS256')

    return access_token, refresh_token


##############
#
#
# The following variables are used in the user authentication system.
#
#
##############


def token_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        token = None
        if 'Authorization' in request.headers:
            token = request.headers['Authorization'].split(" ")[1]

        if not token:
            return ('Missing token'), 401

        try:
            payload = jwt.decode(token, app.config['SECRET_KEY'], algorithms=['HS256'])
            username = payload['sub']
        except jwt.ExpiredSignatureError:
            return ('Token expired'), 401
        except jwt.InvalidTokenError:
            return ('Invalid token'), 401

        return f(username, *args, **kwargs)

    return decorated

@app.route('/login', methods=['POST'])
def login():
    data = request.json
    username = data.get('username')
    password = data.get('password')

    if not username or not password:
        return ({'msg': 'Username and password required'}), 400

    db = firestore.Client()
    doc_ref = db.document(f'collector/users/{username}/login_hash')
    doc = doc_ref.get()

    if not doc.exists:
        return ({'msg': 'User not found'}), 404

    doc_data = doc.to_dict()

    if not doc_data.get('allow_webapp_access'):
        return ({'msg': 'Access denied'}), 403

    expected_hash = doc_data.get('login_hash')
    actual_hash = token_maker.get_login_hash(username, password)

    if expected_hash != actual_hash:
        return ({'msg': 'Invalid credentials'}), 401

    access_token, refresh_token = generate_tokens(username)
    return {'access_token': access_token, 'refresh_token': refresh_token}

@app.route('/authrefresh', methods=['POST'])
def refresh():
    data = request.json
    refresh_token = data.get('refresh_token')

    if not refresh_token:
        return ({'msg': 'Missing refresh token'}), 400

    try:
        payload = jwt.decode(refresh_token, app.config['SECRET_KEY'], algorithms=['HS256'])

        if payload.get('type') != 'refresh':
            return ({'msg': 'Invalid token type'}), 401

        username = payload['sub']
        new_access_token, _ = generate_tokens(username)
        return {'access_token': new_access_token}

    except jwt.ExpiredSignatureError:
        return ({'msg': 'Refresh token expired'}), 401
    except jwt.InvalidTokenError:
        return ({'msg': 'Invalid refresh token'}), 401

@app.route('/protected', methods=['GET'])
@token_required
def protected(current_user):
    return f'Hello {current_user}, you’re authenticated.'



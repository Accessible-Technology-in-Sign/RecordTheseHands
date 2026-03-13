#!/bin/bash

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

# Must be sourced directly to activate the python3 environment.
#
# Usage:
#   source setup_local_env.sh

APP_ENV_DIR=~/.asl_app_env

if ! dpkg -s google-cloud-cli || ! dpkg -s google-cloud-cli-anthoscli ; then
  sudo apt install -y google-cloud-cli google-cloud-cli-anthoscli
fi
if ! dpkg -s python3-venv ; then
  sudo apt install -y python3-venv
fi

if ! [[ -d "${APP_ENV_DIR}" ]] ; then
  python3 -m venv "${APP_ENV_DIR}"
fi

source "${APP_ENV_DIR}"/bin/activate

pip install -r requirements.txt
pip install gunicorn pyink

cat << EndOfMessage
Local environment is setup and sourced.

########################################
# Remember to authenticate Google Cloud Platform.
########################################

gcloud auth login
gcloud auth application-default login

########################################
# Commands for starting the server.
########################################

You will need credentials in order to create an https server or proxy.

self-signed credentials with no password on key file:
openssl req -x509 -newkey rsa:4096 -keyout /home/$USER/.ssh/https/key.pem -out /home/$USER/.ssh/https/cert.pem -sha256 -nodes -days 365

########################################
# Option 1 for https server:
########################################

Setup https using gunicorn:
export GOOGLE_CLOUD_PROJECT=$(cat config.py | grep 'DEV_PROJECT *=' | sed 's/DEV_PROJECT *= *['\''"]\([^'\''"]*\)['\''"].*/\1/')
gunicorn --certfile=/home/$USER/.ssh/https/cert.pem --keyfile=/home/$USER/.ssh/https/key.pem -b :8050 main:app

########################################
# Option 2 for https (and http) server:
########################################

Start an http server using gunicorn:
export GOOGLE_CLOUD_PROJECT=$(cat config.py | grep 'DEV_PROJECT *=' | sed 's/DEV_PROJECT *= *['\''"]\([^'\''"]*\)['\''"].*/\1/')
gunicorn -b :8040 main:app

Setup https using nginx proxying to the http server
(process exits, but errors print on terminal):
sudo nginx -c nginx.conf

In order to successfully run that command you will need to have created
the nginx.conf which should look something like this.
/usr/share/nginx/nginx.conf:

events {}

http {
  server {
    listen  8050 ssl;

    ssl_certificate        /home/$USER/.ssh/https/cert.pem;
    ssl_certificate_key    /home/$USER/.ssh/https/key.pem;

    location / {
      proxy_pass http://localhost:8040;
    }
  }
}

########################################
# Port forwarding from device.
########################################

Setup port forwarding for the https port from Android device to computer:
/home/$USER/Android/Sdk/platform-tools/adb reverse tcp:8050 tcp:8050

########################################
# Check the server.
########################################

Check the http server (if it exists):
curl http://localhost:8040/

Check the https server or proxy:
openssl s_client -debug -connect localhost:8050
curl https://localhost:8050/

########################################
# Export line again
########################################

# And again, before you do anything else you probably want to
# set the environ variable GOOGLE_CLOUD_PROJECT .
export GOOGLE_CLOUD_PROJECT=$(cat config.py | grep 'DEV_PROJECT *=' | sed 's/DEV_PROJECT *= *['\''"]\([^'\''"]*\)['\''"].*/\1/')

EndOfMessage


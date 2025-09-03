# DPAN Server Set Up and Usage

## About

The DPAN Server is a Flask-based web server that communicates with the
RecordTheseHands app. Users register accounts and download instructions /
prompts from the server. Once users are done collecting data, users can upload
their recorded videos to the server, in which the data/metadata is stored in a
GCP bucket and firestore database. Server admins can issue instructions to user
accounts via *directives*, which handles account management, which prompts users
must complete, and app updates. The scripts under `/collector` are responsible
for how the user interacts with the DPAN server.

## Set Up

To set up the DPAN Server, please refer to [SETUP.md](SETUP.md).

## Usage

To create prompts, register users, and issue directives to user devices, please
refer to [USAGE.md](USAGE.md).

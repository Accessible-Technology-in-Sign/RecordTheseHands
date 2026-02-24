#!/usr/bin/env python3

# Copyright 2026 Google LLC
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
"""Data access layer for Firestore and data structure navigation."""

import concurrent.futures
import json
import os
from google.cloud import firestore

# Static globals.
PROJECT_ID = os.environ.get('GOOGLE_CLOUD_PROJECT')
assert PROJECT_ID, 'must specify the environment variable GOOGLE_CLOUD_PROJECT'
BUCKET_NAME = f'{PROJECT_ID}.appspot.com'
SERVICE_ACCOUNT_EMAIL = f'{PROJECT_ID}@appspot.gserviceaccount.com'

USE_FUTURES_DEPTH = 8

db = firestore.Client()


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


def delete_document_recursive(doc_ref, executor, futures):
  """Recursively deletes a document and its subcollections."""
  for c_ref in doc_ref.collections():
    delete_collection_recursive(c_ref, executor, futures)
  futures.append(executor.submit(doc_ref.delete))


def delete_collection_recursive(c_ref, executor, futures):
  """Recursively deletes a collection and its documents."""
  for doc_ref in c_ref.list_documents():
    delete_document_recursive(doc_ref, executor, futures)


def save_document_recursive_parallel(doc_ref, print_depth=10):
  """Recursively saves a document and its subcollections in parallel."""
  with concurrent.futures.ThreadPoolExecutor(max_workers=20) as tree_executor:
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=200
    ) as leaf_executor:
      data = save_document_recursive(
          doc_ref,
          print_depth=print_depth,
          tree_executor=tree_executor,
          leaf_executor=leaf_executor,
      )
      extract_futures_document_recursive(data)
  return data


def save_database(output_filename, print_depth=10):
  """Save the entire database to json."""
  data = save_document_recursive_parallel(db, print_depth=print_depth)
  with open(output_filename, 'w') as f:
    f.write(json.dumps(data, indent=2))
    f.write('\n')


def save_collection_recursive_parallel(c_ref, print_depth=3):
  """Recursively saves a collection and its documents in parallel."""
  with concurrent.futures.ThreadPoolExecutor(max_workers=20) as tree_executor:
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as leaf_executor:
      c_id, c_data = save_collection_recursive(
          c_ref,
          print_depth=print_depth,
          tree_executor=tree_executor,
          leaf_executor=leaf_executor,
      )
      extract_futures_collection_recursive(c_data)
  return c_id, c_data


def write_collection_to_firestore(prefix, data):
  """Writes a collection structure back to Firestore."""
  print(f'Writing collection {prefix}')
  for entry in data:
    doc_id = f'{prefix}/{entry["id"]}'
    if 'data' in entry:
      doc_ref = db.document(doc_id)
      doc_ref.set(entry['data'])
    if 'collection' in entry:
      for collection_id, collection in entry['collection'].items():
        write_collection_to_firestore(f'{doc_id}/{collection_id}', collection)


def get_document_data(doc_ref):
  """Retrieves data for a single document."""
  doc_data = doc_ref.get()
  if doc_data.exists:
    return doc_data.to_dict()


def extract_futures_document_recursive(data):
  """Recursively extracts results from futures in a document structure."""
  if 'data' in data:
    data['data'] = data['data'].result()
  if 'collection' in data:
    for _, c_data in data['collection'].items():
      extract_futures_collection_recursive(c_data)


def extract_futures_collection_recursive(data):
  """Recursively extracts results from futures in a collection structure."""
  for entry in data:
    extract_futures_document_recursive(entry)


def save_document_recursive(
    doc_ref,
    print_depth=0,
    print_prefix=None,
    tree_executor=None,
    leaf_executor=None,
):
  """Recursively saves a document and its subcollections."""
  data = dict()
  if isinstance(doc_ref, firestore.Client):
    doc_id = '/'
  else:
    doc_id = doc_ref.id

  data['id'] = doc_id
  if print_prefix is not None:
    print_prefix = f'{print_prefix}/{doc_id}'
  else:
    print_prefix = doc_id

  if isinstance(doc_ref, firestore.Client):
    print_prefix = ''
    if print_depth:
      print('/')
  else:
    if print_depth:
      print(f'{print_prefix}')
    data['data'] = leaf_executor.submit(get_document_data, doc_ref)

  if USE_FUTURES_DEPTH == print_depth:
    # Only use the tree_executor on a single depth,
    # otherwise deadlock will occur.
    futures = list()
    for c_ref in doc_ref.collections():
      if 'collection' not in data:
        data['collection'] = dict()
      futures.append(
          tree_executor.submit(
              save_collection_recursive,
              c_ref,
              print_depth=max(0, print_depth - 1),
              print_prefix=print_prefix,
              tree_executor=tree_executor,
              leaf_executor=leaf_executor,
          )
      )
    for future in futures:
      print('Waiting for result')
      c_id, c_data = future.result()
      print(f'got result for {c_id}')
      data['collection'][c_id] = c_data
  else:
    for c_ref in doc_ref.collections():
      if 'collection' not in data:
        data['collection'] = dict()
      c_id, c_data = save_collection_recursive(
          c_ref,
          print_depth=max(0, print_depth - 1),
          print_prefix=print_prefix,
          tree_executor=tree_executor,
          leaf_executor=leaf_executor,
      )
      data['collection'][c_id] = c_data

  return data


def save_collection_recursive(
    c_ref,
    print_depth=0,
    print_prefix=None,
    tree_executor=None,
    leaf_executor=None,
):
  """Recursively saves a collection and its documents."""
  data = list()
  if print_prefix is not None:
    print_prefix = f'{print_prefix}/{c_ref.id}'
  else:
    print_prefix = c_ref.id
  if print_depth:
    print(f'{print_prefix}')
  if USE_FUTURES_DEPTH == print_depth:
    # Only use the tree_executor on a single depth,
    # otherwise deadlock will occur.
    futures = list()
    for doc_ref in c_ref.list_documents():
      futures.append(
          tree_executor.submit(
              save_document_recursive,
              doc_ref,
              print_depth=max(0, print_depth - 1),
              print_prefix=print_prefix,
              tree_executor=tree_executor,
              leaf_executor=leaf_executor,
          )
      )
    for future in futures:
      print('Waiting for result')
      data.append(future.result())
  else:
    for doc_ref in c_ref.list_documents():
      data.append(
          save_document_recursive(
              doc_ref,
              print_depth=max(0, print_depth - 1),
              print_prefix=print_prefix,
              tree_executor=tree_executor,
              leaf_executor=leaf_executor,
          )
      )

  return (c_ref.id, data)

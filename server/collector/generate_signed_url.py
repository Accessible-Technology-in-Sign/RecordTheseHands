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

"""Generate a signed URL."""

import binascii
import collections
import datetime
import hashlib
import urllib.parse

import google.auth.exceptions


def generate_signed_url(
    signer,
    service_account_email,
    bucket_name,
    object_name,
    subresource=None,
    expiration=None,
    http_method="GET",
    query_parameters=None,
    headers=None,
    payload_sha256="UNSIGNED-PAYLOAD",
):
    """Generate a signed URL for a data object."""
    if expiration is None:
        expiration = datetime.timedelta(minutes=5)

    escaped_object_name = urllib.parse.quote(object_name.encode("UTF-8"), safe=b"/~")
    canonical_uri = "/{}".format(escaped_object_name)

    datetime_now = datetime.datetime.utcnow()
    request_timestamp = datetime_now.strftime("%Y%m%dT%H%M%SZ")
    datestamp = datetime_now.strftime("%Y%m%d")

    credential_scope = "{}/auto/storage/goog4_request".format(datestamp)
    credential = "{}/{}".format(service_account_email, credential_scope)

    if headers is None:
        headers = dict()
    host = "{}.storage.googleapis.com".format(bucket_name)
    headers["host"] = host

    canonical_headers = ""
    ordered_headers = collections.OrderedDict(
        sorted([(str(k).lower(), str(v).strip()) for k, v in headers.items()])
    )
    for lower_k, strip_v in ordered_headers.items():
        canonical_headers += "{}:{}\n".format(lower_k, strip_v)

    signed_headers = ""
    for lower_k, _ in ordered_headers.items():
        signed_headers += "{};".format(lower_k)
    signed_headers = signed_headers[:-1]  # remove trailing ';'

    if query_parameters is None:
        query_parameters = dict()
    query_parameters["X-Goog-Algorithm"] = "GOOG4-RSA-SHA256"
    query_parameters["X-Goog-Credential"] = credential
    query_parameters["X-Goog-Date"] = request_timestamp
    query_parameters["X-Goog-Expires"] = int(expiration.total_seconds() + 0.5)
    query_parameters["X-Goog-SignedHeaders"] = signed_headers
    if subresource:
        query_parameters[subresource] = ""

    canonical_query_string = ""
    ordered_query_parameters = collections.OrderedDict(sorted(query_parameters.items()))
    for k, v in ordered_query_parameters.items():
        encoded_k = urllib.parse.quote(str(k), safe="")
        encoded_v = urllib.parse.quote(str(v), safe="")
        canonical_query_string += "{}={}&".format(encoded_k, encoded_v)
    canonical_query_string = canonical_query_string[:-1]  # remove trailing '&'

    canonical_request = "\n".join(
        [
            http_method,
            canonical_uri,
            canonical_query_string,
            canonical_headers,
            signed_headers,
            payload_sha256,
        ]
    )

    canonical_request_hash = hashlib.sha256(canonical_request.encode()).hexdigest()

    string_to_sign = "\n".join(
        [
            "GOOG4-RSA-SHA256",
            request_timestamp,
            credential_scope,
            canonical_request_hash,
        ]
    )

    try:
        signature = binascii.hexlify(signer.sign(string_to_sign)).decode()
    except google.auth.exceptions.TransportError:
        print(
            f'TransportError while signing string\n"""{string_to_sign}"""\nfor canonical request\n"""{canonical_request}"""\n'
        )
        raise

    scheme_and_host = "{}://{}".format("https", host)
    signed_url = "{}{}?{}&x-goog-signature={}".format(
        scheme_and_host, canonical_uri, canonical_query_string, signature
    )

    return signed_url

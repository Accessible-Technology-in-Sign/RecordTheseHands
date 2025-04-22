# test_auth.py

import pytest
from authlogintest import app  # assuming your Flask app is in auth.py
import json

from unittest.mock import patch, MagicMock

@patch('auth.firestore.Client')  # <-- patch where it's used
def test_get_user_doc(mock_client):
    mock_doc = MagicMock()
    mock_doc.exists = True
    mock_doc.to_dict.return_value = {
        'allow_webapp_access': True,
        'login_hash': 'ethan:superhashed'
    }

    # Make the firestore.Client() call return a mock with our fake .document().get()
    mock_db_instance = mock_client.return_value
    mock_db_instance.document.return_value.get.return_value = mock_doc

    from authlogintest import get_user_doc
    doc = get_user_doc('ethan')

    assert doc.exists
    assert doc.to_dict()['login_hash'] == 'ethan:superhashed'


@pytest.fixture
def client():
    app.config['TESTING'] = True
    with app.test_client() as client:
        yield client

def test_login_success(client):
    response = client.post('/authlogin', json={
        'username': 'testuser',
        'password': 'testpass'
    })
    data = response.get_json()
    assert response.status_code == 200
    assert 'access_token' in data
    assert 'refresh_token' in data

def test_login_invalid_user(client):
    response = client.post('/authlogin', json={
        'username': 'wronguser',
        'password': 'any'
    })
    assert response.status_code == 404

def test_protected_requires_token(client):
    response = client.get('/protected')
    assert response.status_code == 401

def test_protected_with_token(client):
    # First, get tokens
    login_res = client.post('/authlogin', json={
        'username': 'testuser',
        'password': 'testpass'
    })
    access_token = login_res.get_json()['access_token']

    # Use token in Authorization header
    protected_res = client.get('/protected', headers={
        'Authorization': f'Bearer {access_token}'
    })
    assert protected_res.status_code == 200
    assert 'Hello testuser' in protected_res.get_json()['msg']

def test_refresh_token(client):
    login_res = client.post('/authlogin', json={
        'username': 'testuser',
        'password': 'testpass'
    })
    refresh_token = login_res.get_json()['refresh_token']

    refresh_res = client.post('/authrefresh', json={
        'refresh_token': refresh_token
    })
    assert refresh_res.status_code == 200
    assert 'access_token' in refresh_res.get_json()


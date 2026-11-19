"""One-time local consent for the application's Gmail OAuth client.

The owner runs this interactively with private paths outside the repository.
No token or message content is printed. No mailbox read occurs here.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen
import webbrowser

SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
ROOT = Path(__file__).resolve().parents[2]


def private_path(name, must_exist):
    path = Path(name).expanduser().resolve()
    if path == ROOT or ROOT in path.parents or path.exists() != must_exist:
        raise ValueError("Use an appropriate private path outside the repository")
    return path


def authorize(client_file, output_file, modify=False):
    scope = "https://www.googleapis.com/auth/gmail.modify" if modify else SCOPE
    client_path = private_path(client_file, True)
    output_path = private_path(output_file, False)
    if client_path.stat().st_size > 16_384:
        raise ValueError("OAuth client file exceeds limit")
    client = json.loads(client_path.read_text())["installed"]
    client_id, client_secret = client["client_id"], client["client_secret"]
    if not client_id or not client_secret:
        raise ValueError("Desktop OAuth client is incomplete")
    state = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    result = {}

    class Callback(BaseHTTPRequestHandler):
        def do_GET(self):
            parameters = parse_qs(urlsplit(self.path).query)
            if parameters.get("state") != [state] or len(parameters.get("code", [])) != 1:
                result["error"] = "Authorization response was rejected"
                self.send_response(400)
            else:
                result["code"] = parameters["code"][0]
                self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"You can return to the terminal. No mailbox data was read.")

        def log_message(self, _format, *_args):
            pass  # Callback paths contain authorization codes.

    with HTTPServer(("127.0.0.1", 0), Callback) as server:
        server.timeout = 180
        redirect = f"http://127.0.0.1:{server.server_port}/callback"
        authorization = "https://accounts.google.com/o/oauth2/v2/auth?" + urlencode({
            "client_id": client_id, "redirect_uri": redirect, "response_type": "code",
            "scope": scope, "access_type": "offline", "prompt": "consent", "state": state,
            "code_challenge": challenge, "code_challenge_method": "S256"})
        if not webbrowser.open(authorization):
            raise RuntimeError("Browser authorization could not be opened")
        server.handle_request()
    if "code" not in result:
        raise RuntimeError("Authorization was not completed")
    request = Request("https://oauth2.googleapis.com/token", data=urlencode({
        "code": result["code"], "client_id": client_id, "client_secret": client_secret,
        "redirect_uri": redirect, "grant_type": "authorization_code",
        "code_verifier": verifier}).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST")
    with urlopen(request, timeout=20) as response:
        token = json.loads(response.read(16_385))
    if set(token.get("scope", "").split()) != {scope}:
        raise RuntimeError("Issued Gmail scope did not match the requested authorization")
    refresh = token.get("refresh_token")
    if not isinstance(refresh, str) or not refresh:
        raise RuntimeError("No refresh token was issued; review consent configuration")
    payload = json.dumps({"client_id": client_id, "client_secret": client_secret,
                          "refresh_token": refresh}).encode()
    descriptor = os.open(output_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(payload)
    print("Private Gmail OAuth material saved outside the repository.")


if __name__ == "__main__":
    arguments = argparse.ArgumentParser(description="Authorize Gmail intake once (read-only by default)")
    arguments.add_argument("--client-file", required=True)
    arguments.add_argument("--output-file", required=True)
    arguments.add_argument("--cutover-modify", action="store_true", help="Request separate cutover consent")
    options = arguments.parse_args()
    authorize(options.client_file, options.output_file, options.cutover_modify)

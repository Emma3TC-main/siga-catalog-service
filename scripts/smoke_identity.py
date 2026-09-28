"""Real Identity/JWKS -> Catalog HTTP verification; writes only to dedicated test DB.
Uses existing demo MFA enrollment, never creates users or keys, never prints tokens.
"""
import base64, hashlib, hmac, json, os, struct, subprocess, time, uuid
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parent.parent
IDENTITY = ROOT.parent / 'siga-identity-service'

def call(base, method, path, data=None, token=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = Request(base + path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
    try:
        with urlopen(request, timeout=5) as response:
            status, body = response.status, response.read()
    except HTTPError as error:
        status, body = error.code, error.read()
    assert status == expected, f'{method} {path}: expected {expected}, got {status}'
    print(f'PASS {method} {path}: {status}', flush=True)
    return json.loads(body) if body else None

def main():
    config = json.loads((ROOT / '.local/catalog.json').read_text(encoding='utf-8-sig'))
    identity = dict(line.split('=', 1) for line in (IDENTITY / '.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
    # Fail before login if historical MFA enrollment is unavailable.
    secret = json.loads((IDENTITY / '.local/demo-mfa.json').read_text())['secret']
    jar = (ROOT / '.local/catalog.jar').read_text(encoding='utf-8-sig').strip()
    env = os.environ.copy()
    # Isolated application process, same implementation/JAR, real Identity JWKS.
    env.update(CATALOG_DB_URL=f"jdbc:postgresql://127.0.0.1:{config['dbPort']}/siga_catalog_local_test",
               CATALOG_DB_USER='siga_catalog_test', CATALOG_DB_PASSWORD=config['testPassword'],
               CATALOG_PORT='18082', DEBUG='false')
    import socket
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 18082))
    with (ROOT / '.local/smoke-catalog.log').open('w') as log:
        process = subprocess.Popen(['java', '-jar', jar], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            base = 'http://127.0.0.1:18082'
            deadline = time.time() + 45
            while True:
                if process.poll() is not None:
                    raise RuntimeError('Isolated Catalog exited; inspect .local/smoke-catalog.log')
                try:
                    with urlopen(base + '/actuator/health/readiness', timeout=2) as response:
                        if json.load(response)['status'] == 'UP':
                            break
                except (URLError, OSError):
                    pass
                if time.time() > deadline:
                    raise RuntimeError('Readiness timeout')
                time.sleep(.5)
            iam = 'http://127.0.0.1:8081'
            call(iam, 'GET', '/.well-known/jwks.json')
            challenge = call(iam, 'POST', '/api/v1/auth/login', {'username': 'admin.demo', 'password': identity['IAM_BOOTSTRAP_PASSWORD']})['challengeId']
            digest = hmac.new(base64.b32decode(secret), struct.pack('>Q', int(time.time()) // 30), hashlib.sha1).digest()
            offset = digest[-1] & 15
            otp = f'{(int.from_bytes(digest[offset:offset+4], "big") & 0x7fffffff) % 1000000:06}'
            tokens = call(iam, 'POST', '/api/v1/auth/mfa/verify', {'challengeId': challenge, 'otp': otp})
            token = tokens['accessToken']
            categories = call(base, 'GET', '/api/v1/categories', token=token)
            units = call(base, 'GET', '/api/v1/units', token=token)
            assert isinstance(categories, list) and all(categories[index]['code'] <= categories[index + 1]['code'] for index in range(len(categories) - 1))
            assert isinstance(units, list) and all(units[index]['code'] <= units[index + 1]['code'] for index in range(len(units) - 1))
            unit = {'code': 'SMOKE' + uuid.uuid4().hex[:12], 'name': 'HTTP Identity smoke', 'symbol': 'sm', 'dimension': 'TEST'}
            call(base, 'POST', '/api/v1/units', unit, expected=401)
            call(base, 'POST', '/api/v1/units', unit, token, 201)
            call(base, 'POST', '/api/v1/units', unit, token, 409)
            call(base, 'POST', '/api/v1/units', {}, token, 400)
            print('PASS real Identity RS256/JWKS and PRODUCT_WRITE; data only in siga_catalog_local_test')
        finally:
            process.terminate()
            process.wait(timeout=15)

if __name__ == '__main__':
    main()

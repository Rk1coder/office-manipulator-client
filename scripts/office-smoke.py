#!/usr/bin/env python3
"""Local protocol smoke test. Synthetic DOCX files only; never calls a paid AI API.
Leaves two demonstration documents in the isolated trial database.
"""
import base64, hashlib, hmac, io, json, pathlib, urllib.request, urllib.error, uuid, zipfile
from xml.sax.saxutils import escape

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = 'http://localhost:8090'
DOCS = 'http://localhost:8082'
secret = next(line.split('=', 1)[1] for line in (ROOT / '.office.local.env').read_text().splitlines() if line.startswith('OFFICE_JWT_SECRET='))

def token(data):
    encode = lambda data: base64.urlsafe_b64encode(data).rstrip(b'=')
    unsigned = encode(b'{"alg":"HS256","typ":"JWT"}') + b'.' + encode(json.dumps(data, separators=(',', ':')).encode())
    return (unsigned + b'.' + encode(hmac.new(secret.encode(), unsigned, hashlib.sha256).digest())).decode()

def request(url, data=None, headers=None):
    headers = dict(headers or {})
    if isinstance(data, dict):
        data = json.dumps(data).encode(); headers['Content-Type'] = 'application/json'
    response = urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=90)
    return response.read(), dict(response.headers)

def api(path, data=None, headers=None):
    body, _ = request(APP + path, data, headers)
    return json.loads(body)

def word(text):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr('_rels/.rels', '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        z.writestr('word/document.xml', '<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>' + escape(text) + '</w:t></w:r></w:p><w:sectPr/></w:body></w:document>')
    return out.getvalue()

def upload(name, text):
    boundary = 'office-smoke-' + uuid.uuid4().hex
    body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + name + '"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n').encode() + word(text) + ('\r\n--' + boundary + '--\r\n').encode()
    result, _ = request(APP + '/api/office/import', body, {'Content-Type': 'multipart/form-data; boundary=' + boundary})
    return json.loads(result)['jobId']

def doc_text(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        return z.read('word/document.xml').decode()

before_budget = api('/api/budget')
original = upload('ONLYOFFICE-Deneme.docx', 'Başlangıç belgesi. Bu paragrafı seçip Belge Asistanı ile düzenleyebilirsiniz.')
modified = upload('ONLYOFFICE-Kaydetme-Testi.docx', 'Kaydedilen Türkçe sürüm: ğüşıöç ĞÜŞİÖÇ. Belge düzenleme bağlantısı doğrulandı.')
opened = api('/api/office/jobs/' + original + '/session', {})
source = api('/api/office/jobs/' + modified + '/session', {})
session = opened['sessionId']; key = opened['config']['document']['key']
original_bytes, _ = request(APP + '/api/files/' + original + '/download')
# Real Docs downloads from the signed internal app URL and converts into its cache.
conversion = {'async': False, 'filetype': 'docx', 'outputtype': 'docx', 'key': 'smoke-' + uuid.uuid4().hex, 'title': 'saved.docx', 'url': source['config']['document']['url']}
conversion['token'] = token(conversion)
converted_body, _ = request(DOCS + '/converter', conversion, {'Accept': 'application/json'})
converted = json.loads(converted_body)
assert converted.get('endConvert') is True and converted.get('fileUrl'), 'ONLYOFFICE conversion did not finish'
# Replay a signed close/save notification against the real generated Docs cache file.
callback = {'key': key, 'status': 2, 'url': converted['fileUrl']}
try:
    api('/api/office/sessions/' + session + '/callback', callback)
    raise AssertionError('Unsigned callback accepted')
except urllib.error.HTTPError as error:
    assert error.code == 403
headers = {'Authorization': 'Bearer ' + token({'payload': callback})}
assert api('/api/office/sessions/' + session + '/callback', callback, headers) == {'error': 0}
status = api('/api/office/sessions/' + session)
assert status['revision'] == 1 and status['savedJobId'] != original
saved_bytes, _ = request(APP + '/api/files/' + status['savedJobId'] + '/download')
assert 'Kaydedilen Türkçe sürüm' in doc_text(saved_bytes)
assert request(APP + '/api/files/' + original + '/download')[0] == original_bytes
api('/api/office/sessions/' + session + '/callback', callback, headers)
assert api('/api/office/sessions/' + session)['revision'] == 1
reopened = api('/api/office/jobs/' + original + '/session', {})
assert api('/api/office/sessions/' + reopened['sessionId'])['savedJobId'] == status['savedJobId']
assert api('/api/budget') == before_budget, 'Unexpected budget change'
evidence = {'result': 'PASS', 'checks': ['DOCX import', 'signed internal document download by real ONLYOFFICE', 'real DOCX conversion', 'unsigned callback rejected', 'signed callback persists new version', 'original bytes preserved', 'duplicate callback idempotent', 'reopen uses saved version', 'no API budget change'], 'demoJobId': original, 'savedJobId': status['savedJobId'], 'manualBrowserAcceptance': 'Not executed: no browser available', 'callbackNote': 'Save notification was replayed by test against real Docs conversion output; no browser typing simulated.'}
path = ROOT / 'target/verification/office-smoke.json'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(evidence, ensure_ascii=False, indent=2))

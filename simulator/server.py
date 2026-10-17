"""Deterministic provider simulator. State persists across container restarts."""
import json, os, sqlite3, time, hmac
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
DB=os.environ.get('SIMULATOR_DB','/data/provider.db')
os.makedirs(os.path.dirname(DB),exist_ok=True)
with sqlite3.connect(DB) as db:
 db.execute('CREATE TABLE IF NOT EXISTS payments(id TEXT PRIMARY KEY,payload TEXT NOT NULL)')
class Handler(BaseHTTPRequestHandler):
 def reply(self,status,value):
  self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers()
  try:self.wfile.write(json.dumps(value).encode())
  except BrokenPipeError:pass
 def authorized(self):
  if not hmac.compare_digest(self.headers.get('X-Provider-Secret',''),os.environ.get('PROVIDER_SECRET','local-provider-secret')):
   self.reply(401,{'error':'unauthorized'});return False
  return True
 def do_GET(self):
  if not self.authorized():return
  if self.path=='/report':
   with sqlite3.connect(DB) as db: rows=db.execute('SELECT payload FROM payments ORDER BY rowid DESC LIMIT 1000').fetchall()
   self.reply(200,[json.loads(row[0]) for row in rows]);return
  with sqlite3.connect(DB) as db:row=db.execute('SELECT payload FROM payments WHERE id=?',(self.path.split('/')[-1],)).fetchone()
  self.reply(200 if row else 404,json.loads(row[0]) if row else {'error':'not found'})
 def do_POST(self):
  if not self.authorized():return
  try:
   if self.headers.get('Transfer-Encoding','').lower()=='chunked':
    chunks=[];total=0
    while True:
     size=int(self.rfile.readline().split(b';')[0].strip(),16)
     if size==0:
      while self.rfile.readline().strip():pass
      break
     total+=size
     if total>65536:raise ValueError('body too large')
     chunks.append(self.rfile.read(size));self.rfile.read(2)
    raw=b''.join(chunks)
   else:
    size=int(self.headers.get('Content-Length','0'))
    if size>65536:raise ValueError('body too large')
    raw=self.rfile.read(size)
   value=json.loads(raw)
   result={**value,'status':'FAILED' if value['amount']==1300 else 'SUCCEEDED'}
   with sqlite3.connect(DB) as db:
    db.execute('INSERT OR IGNORE INTO payments VALUES (?,?)',(value['paymentId'],json.dumps(result)))
    result=json.loads(db.execute('SELECT payload FROM payments WHERE id=?',(value['paymentId'],)).fetchone()[0])
   if value['amount']==9900:time.sleep(4) # Accepted externally, response lost locally.
   self.reply(200,result)
  except (ValueError,KeyError):self.reply(400,{'error':'invalid request'})
ThreadingHTTPServer(('0.0.0.0',8081),Handler).serve_forever()
